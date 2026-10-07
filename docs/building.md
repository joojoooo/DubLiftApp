# Building and contributing

Run commands from the app repository root unless shown otherwise.

## Prerequisites

The native script expects the NDK's `linux-x86_64` toolchain. Release CI runs
on Ubuntu 24.04. Install:

- JDK 17 or newer; CI uses JDK 21.
- Go matching the requirement in [DubLift/go.mod](../DubLift/go.mod).
- Android SDK platform 35, build tools 35.0.1, and NDK r27c
  (`27.2.12479018`).
- Git, Bash, CMake, Make, pkg-config, curl, tar, unzip, and `sha256sum`.

Set `ANDROID_HOME` to your SDK directory. The native script also accepts
`ANDROID_SDK_ROOT` and otherwise defaults to
`$HOME/.local/share/dublift-android-sdk`. Gradle can locate the SDK through
an untracked `local.properties` containing `sdk.dir=/path/to/android-sdk`.
Use the same SDK for both. Set `ANDROID_NDK_HOME` if the NDK is outside
`$ANDROID_HOME/ndk/27.2.12479018`.

For the script's default SDK location:

```sh
export ANDROID_HOME="$HOME/.local/share/dublift-android-sdk"
```

Initial builds download Gradle, Go modules, and native dependency sources.
`gradlew` downloads Gradle 8.13 into `build/toolchains/` and verifies its
SHA-256 before extraction; it is a shell bootstrap, without a wrapper JAR.
The Android Gradle plugin version is defined in [build.gradle](../build.gradle).

## Checkout and debug build

```sh
git clone --recurse-submodules https://github.com/joojoooo/DubLiftApp.git
cd DubLiftApp
./gradlew assembleDebug
```

For an existing clone with an uninitialized submodule:

```sh
git submodule update --init --recursive
```

`DubLift/` is a pinned Git submodule. The native script requires it and does
not fall back to a sibling repository. Local builds compile its current
checkout, including uncommitted source changes; CI checks out the pin recorded
in the tagged app commit.

Gradle produces separate APKs, with no universal APK:

| ABI | Debug output |
| --- | --- |
| `arm64-v8a` | `app/build/outputs/apk/debug/DubLift-0.0.0-arm64-v8a-debug.apk` |
| `armeabi-v7a` | `app/build/outputs/apk/debug/DubLift-0.0.0-armeabi-v7a-debug.apk` |

The filename includes the build's version name and Android ABI. Builds default
to version `0.0.0`; set `releaseVersionName` to use another version.

Debug APKs use the developer's debug signing key and cannot update release
APKs signed with the release key. Uninstalling to switch signing keys removes
the app's private settings and data. See [validation](validation.md) before
submitting a contribution and [releasing](releasing.md) for release builds.

## Native preparation

Gradle's `preBuild` depends on `prepareNative`, which invokes
[scripts/prepare-native.sh](../scripts/prepare-native.sh) for both ABIs:

```sh
./gradlew prepareNative
```

The script compiles DubLift with `CGO_ENABLED=1`, `GOOS=android`, and the NDK
C compiler so DNS uses Android's resolver. ARM64 uses `GOARCH=arm64`; ARMv7
uses `GOARCH=arm`, `GOARM=7`, and NEON-enabled FFmpeg.

All three executables use 16 KB ELF alignment, including the RELRO end, via
the NDK r27 linker flags `-z max-page-size=16384` and
`-z common-page-size=16384`. Native preparation checks the resulting segments,
including cached FFmpeg outputs. The same binaries support 4 KB devices;
the minimum Android version remains API 25.

It downloads checksum-verified sources and builds FFmpeg/ffprobe with static
FreeType, HarfBuzz, and OpenH264 dependencies. `lavfi` and `drawtext` render
playback failure screens using Android system fonts; OpenH264 encodes those
screens. No font files are bundled. Normal playback copies source video and
uses FFmpeg's native AAC encoder; this build does not enable MediaCodec, GPL,
or nonfree FFmpeg options. Source versions, checksums, and licenses are
documented in [THIRD_PARTY.md](../THIRD_PARTY.md); the script defines build
options.

Downloads and native work directories live under `build/native/`. The three
executables are emitted as `libdublift.so`, `libffmpeg.so`, and `libffprobe.so`
under `app/build/generated/jniLibs/<abi>/` and packaged in the matching APK's
`lib/<abi>/` directory. Legacy extraction and preserved debug symbols let
the service execute them from Android's native library directory.

DubLift is rebuilt on each native preparation. FFmpeg/ffprobe are reused
when the API and script-recipe stamps match. To force their rebuild:

```sh
REBUILD_FFMPEG=1 ./scripts/prepare-native.sh
```

`FFMPEG_JOBS` controls native build parallelism (default: `4`). Native
executables, APKs, downloads, and caches are ignored by Git.

At runtime, the service keeps configuration, alignment records, the playback
key, and `server.log` in `files/dublift/` inside the app's private data
directory. It supplies the bundled executable paths and fixes the listen
address to `0.0.0.0:7000` on startup, overriding those dashboard settings.
Uninstalling removes this data.

## Updating DubLift and icons

To fetch DubLift's latest `main` commit, check it out detached, and build both
debug APKs:

```sh
./scripts/update-dublift-and-build-debug.sh
```

The script initializes a missing submodule, refuses to update one with local
changes, and verifies both APKs exist. The new submodule revision remains in
the app working tree; review and commit the pointer to use it in future
checkouts and releases.

To build and verify APK presence from the existing core checkout without
fetching or changing it:

```sh
./scripts/update-dublift-and-build-debug.sh --no-update
```

If the core icon changes, regenerate the tracked Android resources:

```sh
python3 scripts/generate-icons.py
```

This requires Python 3.10 or newer, Pillow, and ImageMagick's `convert` command.
It reads `DubLift/internal/dublift/web/icon.svg` and writes launcher, themed,
and notification PNGs under `app/src/main/res/`. Include those resources with
the reviewed submodule update. Keep core server changes and their documentation
in [DubLift](https://github.com/joojoooo/DubLift).
