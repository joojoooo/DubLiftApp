# Validation

Run commands from the app repository root with the
[build prerequisites](building.md) installed and `ANDROID_HOME` set.

## Build and static checks

```sh
./gradlew assembleDebug lint test
./gradlew assembleRelease
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
bash -n gradlew scripts/prepare-native.sh scripts/update-dublift-and-build-debug.sh
git diff --check
```

The builds compile Java, prepare native executables, and package both ABI
splits. `assembleRelease` produces unsigned APKs; release version properties
and signing are covered in [releasing](releasing.md). Native preparation
reuses FFmpeg when its stamps match; see [building](building.md) to force a
fresh native rebuild after toolchain changes.

Lint reports appear under `app/build/reports/`. Review warnings as well as
errors. Gradle's JVM unit tests cover version comparison, release/changelog
parsing, and bounded/redacted dashboard diagnostics; the Python tests cover the
release-note commit range, first releases, and reruns. There are no Android
instrumentation tests, so the popup lifecycle
and browser handoff still require the device checks below.
Core server checks belong to [DubLift's validation guide](../DubLift/docs/validation.md).

## APK inspection

For debug APKs:

```sh
ndk="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
for abi in arm64-v8a armeabi-v7a; do
  apk="app/build/outputs/apk/debug/app-$abi-debug.apk"
  test -s "$apk" || exit 1
  unzip -t "$apk" >/dev/null || exit 1
  "$ANDROID_HOME/build-tools/35.0.1/aapt" dump badging "$apk" || exit 1
  "$ANDROID_HOME/build-tools/35.0.1/apksigner" verify --verbose "$apk" || exit 1
  "$ANDROID_HOME/build-tools/35.0.1/zipalign" -c -P 16 4 "$apk" || exit 1
  unzip -l "$apk" "lib/$abi/*" || exit 1
  unzip -oq "$apk" "lib/$abi/*" -d build/apk-inspection || exit 1
  for binary in build/apk-inspection/lib/"$abi"/*.so; do
    "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -lW "$binary" || exit 1
  done
done
```

Confirm package `org.dublift.app`, minimum SDK `25`, target SDK `35`, and only
the matching `native-code` ABI in each APK. Its `lib/<abi>/` directory must
contain `libdublift.so`, `libffmpeg.so`, and `libffprobe.so`. There is no
universal or x86 APK. Inspect final signed release APKs in the same way;
unsigned Gradle release outputs cannot pass signature verification.

For every packaged executable, confirm each `LOAD` segment has alignment
`0x4000` (16 KB) or greater and its file offset and virtual address are
congruent modulo `0x4000`. For each `GNU_RELRO` segment,
`(VirtAddr + MemSiz) % 0x4000` must be zero. Native preparation enforces these
checks before packaging. ZIP alignment alone cannot validate ELF segments;
the APK uses compressed native entries that Android extracts before execution.
See [Android's page-size guide](https://developer.android.com/guide/practices/page-sizes)
for the requirements.

## Device smoke test

Use a device whose Android ABI matches the APK. An x86-only emulator cannot
execute the bundled ARM programs. With Android platform-tools installed:

```sh
adb devices
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell getconf PAGE_SIZE
```

Install the appropriate debug APK, for example on ARM64:

```sh
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

A debug key cannot update an installed release signed with another key.
Use a test device/profile or account for data loss before uninstalling.

1. Open DubLift and respond to notification and battery-exemption prompts.
   Confirm the dashboard loads, the dock's status dot turns green, and the
   notification shows the server address.
2. Check the health endpoint through an ADB forward from the development host:

   ```sh
   adb forward tcp:17000 tcp:7000
   curl -fsS http://127.0.0.1:17000/healthz
   adb forward --remove tcp:17000
   ```

   Expect HTTP 200 with `{"status":"ok"}`. This checks server health, not
   FFmpeg execution or playback.
3. Follow guided setup and confirm settings survive an app reopen. Verify
   **Restart** reloads the dashboard, **Stop** stops the server, and **Start**
   resumes it. Confirm the notification's **Stop** action also stops it.
4. Check boot behavior while enabled and after using **Stop**: the receiver
   attempts to restore an enabled server, while a stopped server stays off
   until started again. Record any Android/OEM restrictions.
5. With a build whose `releaseVersionName` is lower than the latest GitHub
   release, confirm startup shows a scrollable update popup after the permission
   screens. Check the installed/available versions and changelog. **Open release
   page** must open a browser on that release's page without downloading an APK.
   **Later**, Back, or tapping outside dismisses it for the current launch. Rotate
   with the popup open and after dismissing it; the pending popup should survive
   rotation, and a dismissed popup should stay dismissed. Repeat offline and with
   an equal or newer installed version; the dashboard must still load without an
   update popup. Debug builds default to `1.0.0`; supply version properties as
   described in [releasing](releasing.md) when testing against a `0.x` release.
6. On a test device without WebView support or with no enabled WebView provider,
   confirm a native text screen explains why the dashboard cannot be displayed,
   suggests Chrome or Android System WebView, and shows a selectable dashboard URL
   using the device's Wi-Fi/Ethernet IPv4 address when available. Open that URL from
   another device on the same LAN. Check **Restart**, **Stop**, **Start**, Back,
   rotation, and app reopen; the fallback must stay usable without a WebView. Tap
   **Open dashboard in external browser** with and without a browser installed; it must
   open `http://127.0.0.1:7000/` or show a helpful message. The button must use this
   loopback URL regardless of whether a LAN address is available.
   Disconnect/reconnect the LAN and change networks; the address should refresh,
   and no LAN address should show connection guidance rather than a loopback or
   cellular address. Install or enable a compatible WebView provider and reopen
   the app; the embedded dashboard should return. Repeat on API 25 when available.
7. On the fallback screen, press **Copy** beside the LAN address and paste it into
   a text field; it must match the displayed LAN URL. With no LAN address, that
   copy button must be hidden. Press **Copy debug info** and confirm the pasted
   report contains the device/Android/app versions, selected WebView provider,
   Chrome/System WebView versions and enabled state, server health, and the
   original failure reason or exception stack with its causes. Rotate and copy
   again; the original failure must remain in the report. On a working or blank
   embedded dashboard, tap the dock's status dot to copy the same report. Use a
   controlled main-frame HTTP/load error and renderer exit to check the fallback
   and recorded status/error details; JavaScript warnings/errors should appear
   in the recent events. Reports must omit private configuration, server-log
   contents, URL credentials/query strings, and playback paths or keys.

## Playback, LAN, and background checks

- Follow the [README connection instructions](../README.md) for same-phone
  playback. Play a real stream in Stremio/Nuvio; verify video, Italian audio,
  synchronization, forward/backward seeks, and audio timing adjustments.
- From another device on the same trusted LAN, load the phone's dashboard
  and `/healthz`, install its manifest, and play a stream. Confirm the
  Public base URL and generated playback URLs reach the phone.
- Background DubLift, switch to the player, and lock the screen during
  playback. Check playback continuity, notification state, and recovery
  after returning to the app. Repeat with the battery exemption enabled.
  Android/OEM process termination remains a limitation.
- Using a controlled failing source, confirm a preparation failure displays
  a readable error video. This exercises Android system-font selection,
  `drawtext`, and OpenH264, which a health response cannot verify.
- Repeat installation and playback on ARM64 and ARMv7 hardware, including
  Android 7.1 where available. A successful cross-build does not establish
  runtime compatibility on those devices.
- Repeat server startup, playback, and error-video generation on a 16 KB
  ARM64 device (`getconf PAGE_SIZE` returns `16384`) with page-size compatibility
  mode disabled. Also check a 4 KB device (`4096`) for backward compatibility.

Record which device, Android version, ABI, and app/core revisions were
checked. Report unavailable hardware, skipped checks, and provider failures
separately from build results. Keep private configs, logs, and provider
credentials out of tracked files.
