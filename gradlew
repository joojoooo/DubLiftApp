#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
version=8.13
expected=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
toolchains="$root/build/toolchains"
archive="$toolchains/gradle-$version-bin.zip"
gradle_bin="$toolchains/gradle-$version/bin/gradle"
ready="$toolchains/gradle-$version/.dublift-verified"
if [[ ! -f "$ready" || ! -x "$gradle_bin" ]]; then
  mkdir -p "$toolchains"
  curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$version-bin.zip" -o "$archive"
  echo "$expected  $archive" | sha256sum -c -
  unzip -qo "$archive" -d "$toolchains"
  touch "$ready"
fi
exec "$gradle_bin" "$@"
