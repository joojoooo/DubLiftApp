# Coding-agent guidance

## Project orientation

- This is a single-module Android application (`:app`) written in Java 17.
  `MainActivity` hosts the core dashboard in a WebView; `DubLiftService` runs
  bundled executables; `BootReceiver` restores an enabled service.
- `app/build.gradle` defines SDK levels, ABI splits, native packaging, and
  release version properties. The root build file selects the Android Gradle
  plugin; `gradlew` is a checksum-verifying Gradle bootstrap shell script,
  without a wrapper JAR.
- `DubLift/` is a pinned Git submodule, not a sibling checkout or vendored copy.
  Builds use its current working tree; release CI uses the revision recorded
  by the app commit. Preserve existing submodule changes. If editing core
  source, follow its own `AGENTS.md` when present and commit the core change
  before recording its revision here.

## Build and runtime constraints

- `preBuild` depends on `prepareNative`, which runs
  `scripts/prepare-native.sh` for both `arm64-v8a` and `armeabi-v7a`.
  There is no universal or x86 APK. Native builds expect Linux x86_64 and the
  NDK layout described in [building](docs/building.md).
- Keep `libdublift.so`, `libffmpeg.so`, and `libffprobe.so` names consistent
  between the native script, Gradle packaging, and `DubLiftService`. They are
  executable programs packaged as native libraries. Preserve legacy
  extraction and `keepDebugSymbols` so Android can execute them.
- Preserve CGO with the NDK compiler for Android DNS resolution, ARMv7/NEON
  support, and Android error-video dependencies (`lavfi`, `drawtext`,
  FreeType, HarfBuzz, OpenH264, and system fonts). FFmpeg builds do not enable
  GPL or nonfree options.
- Preserve API 25 behavior through `AndroidCompat` and SDK guards. Changes
  to service lifecycle must agree with manifest permissions, foreground
  service type, notification actions, and boot enable/disable state. Preserve
  `START_STICKY`, the process watchdog, and lock release when stopping.
- The service owns the executable paths and listen address (`0.0.0.0:7000`)
  in private configuration. Keep dashboard, health-check, and notification
  addresses aligned if changing the port. Do not track private config,
  credentials, logs, or signing material.

## Scripts and coordinated updates

- `scripts/update-dublift-and-build-debug.sh` advances the core checkout to
  `origin/main` and builds both debug APKs. Use `--no-update` to validate the
  existing checkout without fetching or changing its revision. Do not advance
  the pin as an incidental validation step.
- Native outputs, downloads, and build caches live in ignored `build/` and
  `app/build/`; release outputs live in ignored `release/`. Do not commit
  binaries or APKs. Generated Android icon resources are tracked.
- When the core `internal/dublift/web/icon.svg` changes, run
  `python3 scripts/generate-icons.py` and include the regenerated launcher,
  themed, and notification icons with the submodule update.
- Keep SDK/NDK versions and ABIs aligned across `app/build.gradle`, the native
  script, release workflow, and affected docs. Dependency/source/checksum
  changes must agree with `THIRD_PARTY.md` and bundled license notices.
- Release tags, version properties, ABI asset names, and signing steps must
  agree with `.github/workflows/release.yml`. Preserve signing-key continuity
  and never commit secrets. See [releasing](docs/releasing.md) for the rules.

## Validation and documentation

- Run `./gradlew assembleDebug lint test` for Android changes; use
  `./gradlew assembleRelease` for release packaging changes. Follow
  [validation](docs/validation.md) for APK inspection and device checks.
  Gradle JVM tests cover release-update parsing and version comparison; there
  are no Android instrumentation tests. Report device checks that could not run.
- Keep `README.md` concise and focused on Android users. Maintain one canonical
  guide for each of building, releasing, and validation; link instead of
  duplicating procedures. Keep `.github/release-notes.md` a short download and
  installation entry point linked to the README. Core server documentation
  belongs in DubLift. Store only durable project guidance here.
