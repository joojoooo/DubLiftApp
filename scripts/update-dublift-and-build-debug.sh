#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
submodule="$root/DubLift"
apk_dir="$root/app/build/outputs/apk/debug"

usage() {
  echo "Usage: $0 [--no-update]"
}

if (( $# > 1 )); then
  usage >&2
  exit 2
fi

case "${1:-}" in
  '') update=true ;;
  --no-update) update=false ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

if [[ "$update" == true ]]; then
  if [[ ! -e "$submodule/.git" ]]; then
    git -C "$root" submodule update --init --recursive -- DubLift
  fi

  if [[ -n "$(git -C "$submodule" status --porcelain --untracked-files=all)" ]]; then
    echo 'DubLift has local changes. Commit or stash them before updating.' >&2
    exit 1
  fi

  git -C "$submodule" fetch origin refs/heads/main
  latest_commit=$(git -C "$submodule" rev-parse FETCH_HEAD)
  git -C "$submodule" checkout --detach "$latest_commit"
  git -C "$submodule" submodule update --init --recursive
else
  if [[ ! -e "$submodule/.git" ]]; then
    echo 'DubLift submodule is not initialized. Run without --no-update first.' >&2
    exit 1
  fi
  latest_commit=$(git -C "$submodule" rev-parse HEAD)
fi

echo "Building ARM64 and ARMv7 debug APKs with DubLift $latest_commit..."
(cd "$root" && ./gradlew assembleDebug)

for abi in arm64-v8a armeabi-v7a; do
  apk="$apk_dir/app-$abi-debug.apk"
  if [[ ! -s "$apk" ]]; then
    echo "Build completed, but the $abi debug APK is missing: $apk" >&2
    exit 1
  fi
done

for abi in arm64-v8a armeabi-v7a; do
  echo "Debug APK ($abi): $apk_dir/app-$abi-debug.apk"
done
