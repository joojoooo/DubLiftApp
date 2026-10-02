#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$root/build/native"

source_root="$root/DubLift"
if [[ ! -f "$source_root/go.mod" ]]; then
  echo 'DubLift submodule is missing. Run: git submodule update --init --recursive' >&2
  exit 1
fi

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/.local/share/dublift-android-sdk"}}
ndk_root=${ANDROID_NDK_HOME:-"$sdk_root/ndk/27.2.12479018"}
toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64"

version=7.1.2
min_api=25
archive="$root/build/native/ffmpeg-$version.tar.xz"
expected=089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304
source="$root/build/native/ffmpeg-$version"

build_native() {
  local abi="$1" goarch compiler ffmpeg_arch
  local output="$root/app/build/generated/jniLibs/$abi"
  local build="$root/build/native/ffmpeg-build-$abi"
  local ffmpeg_flags=()
  case "$abi" in
    arm64-v8a)
      goarch=arm64
      compiler=aarch64-linux-android${min_api}-clang
      ffmpeg_arch=aarch64
      ;;
    armeabi-v7a)
      goarch=arm
      compiler=armv7a-linux-androideabi${min_api}-clang
      ffmpeg_arch=arm
      ffmpeg_flags=(--cpu=armv7-a --extra-cflags='-mfpu=neon -mfloat-abi=softfp')
      ;;
  esac
  local android_cc="$toolchain/bin/$compiler"
  local api_stamp="$output/.ffmpeg-api"
  if [[ ! -x "$android_cc" ]]; then
    echo "Android NDK r27c compiler not found at $android_cc (set ANDROID_NDK_HOME)." >&2
    exit 1
  fi
  mkdir -p "$output"

  echo "Building DubLift for Android $abi..."
  (cd "$source_root" && CGO_ENABLED=1 GOOS=android GOARCH="$goarch" GOARM=7 CC="$android_cc" \
    go build -trimpath -ldflags='-s -w' -o "$output/libdublift.so" ./cmd/dublift)

  if [[ -s "$output/libffmpeg.so" && -s "$output/libffprobe.so" &&
        -f "$api_stamp" && ${REBUILD_FFMPEG:-0} != 1 ]] &&
      [[ "$(cat "$api_stamp")" == "$min_api" ]]; then
    echo "Using previously built API $min_api FFmpeg and ffprobe for $abi."
    return
  fi

  # Discard old API 26 binaries and objects before rebuilding.
  rm -f "$api_stamp" "$output/libffmpeg.so" "$output/libffprobe.so"
  rm -rf "$build"

  if [[ ! -f "$archive" ]]; then
    local cached="$sdk_root/downloads/ffmpeg-$version.tar.xz"
    if [[ -f "$cached" ]]; then
      cp "$cached" "$archive"
    else
      curl -fL --retry 3 "https://ffmpeg.org/releases/ffmpeg-$version.tar.xz" -o "$archive"
    fi
  fi
  echo "$expected  $archive" | sha256sum -c -
  if [[ ! -d "$source" ]]; then
    tar -xf "$archive" -C "$root/build/native"
  fi
  mkdir -p "$build"
  (
    cd "$build"
    echo "Configuring LGPL FFmpeg and ffprobe for Android $abi..."
    "$source/configure" \
      --target-os=android --arch="$ffmpeg_arch" --enable-cross-compile \
      --cc="$android_cc" --ld="$android_cc" \
      --ar="$toolchain/bin/llvm-ar" --nm="$toolchain/bin/llvm-nm" \
      --ranlib="$toolchain/bin/llvm-ranlib" --strip="$toolchain/bin/llvm-strip" \
      --sysroot="$toolchain/sysroot" \
      --extra-ldflags='-Wl,-z,max-page-size=16384' \
      --disable-doc --disable-debug --disable-ffplay --disable-avdevice \
      --disable-postproc --disable-shared --enable-static --disable-symver \
      --enable-small --enable-neon "${ffmpeg_flags[@]}"

    # DubLift copies video packets and encodes AAC with FFmpeg's native encoder.
    # MediaCodec is not selected by those commands, so the Termux MediaCodec/JNI
    # stack would add dependencies without accelerating this workload.
    grep -q '^#define HAVE_NEON 1$' config.h

    make -j "${FFMPEG_JOBS:-4}" ffmpeg ffprobe
    cp ffmpeg "$output/libffmpeg.so"
    cp ffprobe "$output/libffprobe.so"
    "$toolchain/bin/llvm-strip" "$output/libffmpeg.so" "$output/libffprobe.so"
  )
  printf '%s\n' "$min_api" > "$api_stamp"
}

for abi in arm64-v8a armeabi-v7a; do
  build_native "$abi"
done
echo 'Android native executables are ready.'
