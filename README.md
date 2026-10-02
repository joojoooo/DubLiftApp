# DubLift Android app

This project packages the [DubLift](https://github.com/joojoooo/DubLift) Go server, FFmpeg, and ffprobe in separate ARM64 (64-bit) and ARMv7 (32-bit) APKs. DubLift is included as a pinned Git submodule and built from source. Opening the app starts a local HTTP server and displays its dashboard at `http://127.0.0.1:7000/` in a WebView. Stremio/Nuvio on the same phone can use `http://127.0.0.1:7000/manifest.json`. Other devices on the same trusted Wi-Fi can use `http://PHONE-LAN-IP:7000/manifest.json`.

The dashboard fills the screen. A small floating dock has equal-size Restart, Battery and Stop controls plus a server-status dot; it hides when scrolling down and returns when scrolling up. The server runs in an Android foreground service with an ongoing notification, `START_STICKY` restart, a process watchdog, boot restart after the app has been opened, and CPU and Wi-Fi locks. **Stop** in the app or its notification stops the server and disables boot restart. On first launch, the app requests a battery-optimization exemption; **Battery** opens that request again. Android and phone makers can still stop apps, especially after a user force-stop; no APK can guarantee permanent execution. Keeping the locks active consumes battery.

## Install

Download `DubLift-arm64.apk` for an ARM64 (64-bit) device or `DubLift-armv7.apk` for an ARMv7 (32-bit) device from this repository's GitHub Releases page. Both require Android 7.1 (API 25) or newer. Release APKs use a persistent signing key so updates can be installed over previous releases. Open DubLift once after installation and allow notifications and the battery exemption when prompted. The app needs no Termux installation. On Android 7.1, update Chrome or Android System WebView if the dashboard does not load.

The dashboard's copied manifest URL uses the address by which the dashboard was opened (`127.0.0.1` inside the app). To use another device on the LAN, substitute the phone's LAN IP or set DubLift's Public URL in its dashboard settings. DubLift has no LAN authentication, so use it on trusted networks.

## Rebuild

Requirements: JDK 17+, Android SDK platform 35 and build tools 35.0.1, Android NDK r27c, and the Go version specified by [DubLift's go.mod](https://github.com/joojoooo/DubLift/blob/main/go.mod). Set `ANDROID_HOME` to the SDK directory and `ANDROID_NDK_HOME` if the NDK is elsewhere. Clone with submodules or initialize them after cloning:

```sh
git clone --recurse-submodules YOUR-APP-REPOSITORY-URL
# Or, from an existing clone:
git submodule update --init --recursive
```

The build refuses to use a sibling checkout if the submodule is missing.

```sh
./gradlew assembleDebug
```

To fetch the latest commit from DubLift's `main` branch and build both debug APKs in one step, run:

```sh
./scripts/update-dublift-and-build-debug.sh
```

The script initializes the submodule if needed and stops if it contains local changes. It verifies both APKs exist and prints their paths when the build succeeds. The updated submodule pointer remains in your working tree; commit it if you want future builds or releases to use that DubLift revision.

To build from the current DubLift checkout, including uncommitted source changes, without fetching or changing the submodule, run:

```sh
./scripts/update-dublift-and-build-debug.sh --no-update
```

The `gradlew` shell script downloads Gradle 8.13 and verifies its SHA-256 before running it. The repository contains no Gradle wrapper JAR, native executables, or APK. Local debug APKs are generated at `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` and `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` and are ignored by Git.

After changing `DubLift/internal/dublift/web/icon.svg`, run `python3 scripts/generate-icons.py` to refresh the launcher, themed, and notification icons. The generator needs ImageMagick's `convert` command and Python's Pillow package; generated Android resources are checked into the app repository.

The `prepareNative` build step compiles the submodule's Go source for Android ARM64 and ARMv7 (`GOARCH=arm`, `GOARM=7`) with the NDK C toolchain so DNS uses Android's network resolver. It also downloads checksum-verified FFmpeg 7.1.2 source when needed and builds `ffmpeg` and `ffprobe` with the Android NDK in separate per-ABI build directories. Native executables are generated only under `app/build/` and packaged under `lib/arm64-v8a/` or `lib/armeabi-v7a/` in the matching APK, with legacy extraction enabled so they can be executed from Android's native library directory. FFmpeg is built with NEON, internal codecs and LGPL options; it needs no Termux libraries. MediaCodec is omitted because DubLift copies video and uses FFmpeg's native AAC encoder; its current FFmpeg commands would not select MediaCodec decoding. To force fresh FFmpeg builds for both ABIs, run `REBUILD_FFMPEG=1 ./scripts/prepare-native.sh`.

DubLift's private configuration, alignment records, key, and log are stored under the app's private files directory. Uninstalling the app removes them. Local debug APKs use each developer's debug signing key and cannot update a release APK.

## Publish a release

The [release workflow](.github/workflows/release.yml) checks out the pinned DubLift submodule, rebuilds all three native executables and an APK for each ABI on GitHub Actions, and signs both APKs. It uploads only `DubLift-arm64.apk` and `DubLift-armv7.apk` to GitHub Releases; GitHub displays their SHA-256 checksums. Release notes combine the [download, installation, and usage guide](.github/release-notes.md) with GitHub's automatically generated changelog. Rerunning the workflow refreshes the APKs and release notes. No generated binary or APK belongs in Git.

Before the first release, create a long-lived Android signing keystore and save these four values as repository Actions secrets: `ANDROID_KEYSTORE_BASE64` (base64 of the keystore file), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Keep a private backup of the keystore and passwords; future updates need the same signing key. For example, create the keystore with `keytool -genkeypair -keystore dublift-release.jks -alias dublift -keyalg RSA -keysize 4096 -validity 10000`, then base64-encode that file into the first secret.

Push a tag such as `v1.0.0` to trigger the workflow. Tags must use `vMAJOR.MINOR.PATCH`; the workflow derives Android's increasing `versionCode` from those numbers. Updating DubLift requires advancing and committing the submodule pointer before tagging.

## Verification

The APK build verifies Java compilation and native packaging. To check an APK on a device, open the app and confirm a green status dot in the floating dock, **FFmpeg ready** in the dashboard, and a successful `http://127.0.0.1:7000/healthz` response. Then test a real stream through Stremio/Nuvio, background the app, lock the screen, and verify playback continues.

See [third-party notices](THIRD_PARTY.md) for FFmpeg source and licensing.
