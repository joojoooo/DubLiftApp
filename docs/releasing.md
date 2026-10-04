# Releasing

[The release workflow](../.github/workflows/release.yml) is the source of
truth for CI versions, tag validation, signing, and published asset names.
It runs when a `v*` tag is pushed and checks out the DubLift revision pinned
in that app commit.

## Signing setup

Create a long-lived Android signing keystore before publishing the first
release. For example:

```sh
keytool -genkeypair -keystore dublift-release.jks -alias dublift \
  -keyalg RSA -keysize 4096 -validity 10000
```

Store these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing-key alias, e.g. `dublift` |
| `ANDROID_KEY_PASSWORD` | Signing-key password |

Keep a private backup of the keystore and passwords. Future updates require
the same signing key. Never commit signing material or secrets; the workflow
decodes the keystore into the runner's temporary directory.

## Version and tag rules

Tags must be `vMAJOR.MINOR.PATCH` with numeric components and no suffix.
The workflow removes `v` for `versionName` and calculates:

```text
versionCode = MAJOR * 1000000 + MINOR * 1000 + PATCH
```

Minor and patch must each be below 1000, and the calculated code must be
between 1 and 2100000000. Choose a code greater than the previous release
so Android accepts an update. `v0.0.0` is rejected.

Gradle reads `releaseVersionName` and `releaseVersionCode` properties; without
them, it defaults to `1.0.0` and `1`. Check release packaging locally with
the [build prerequisites](building.md):

```sh
./gradlew --no-daemon assembleRelease \
  -PreleaseVersionName=1.0.0 -PreleaseVersionCode=1000000
```

This produces `app-arm64-v8a-release-unsigned.apk` and
`app-armeabi-v7a-release-unsigned.apk` under
`app/build/outputs/apk/release/`. Gradle does not configure release signing;
CI aligns and signs these APKs separately.

## Publish

1. Review and commit the Android changes and any intended DubLift submodule
   update. Core commits must be available in the submodule's remote before
   CI can check them out. Regenerate icons if the core icon changed.
2. Complete [validation](validation.md) and check the short
   [release-notes template](../.github/release-notes.md) against installation
   requirements and asset names.
3. Push the app commit and a new compliant tag. For a `1.0.0` release:

   ```sh
   git push origin HEAD
   git tag v1.0.0
   git push origin v1.0.0
   ```

CI builds both ABIs from source, aligns the APKs with `zipalign`, signs them
with `apksigner`, and verifies both signatures before publishing:

- `DubLift-arm64.apk`
- `DubLift-armv7.apk`

The workflow uses its GitHub token with `contents: write` permission.
Release notes combine the template with GitHub's generated changelog.
Rerunning the workflow replaces the two assets and refreshes the notes on
an existing release. No generated binaries or APKs belong in Git.

## Checksums and final checks

GitHub exposes a SHA-256 digest for each release asset. The workflow uploads
the two APKs only; it does not create a separate checksum file. After
downloading them, calculate local hashes and compare with the release asset
digests:

```sh
sha256sum DubLift-arm64.apk DubLift-armv7.apk
```

With `ANDROID_HOME` set to the SDK directory, verify downloaded signatures:

```sh
for apk in DubLift-arm64.apk DubLift-armv7.apk; do
  "$ANDROID_HOME/build-tools/35.0.1/apksigner" verify --verbose --print-certs "$apk"
done
```

Compare signing certificates with a previous release and test an in-place
update on a device to confirm settings survive. Check both architectures
using the device and playback checks in [validation](validation.md).
