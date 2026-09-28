#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
output="$root/app/build/generated/jniLibs/arm64-v8a"
mkdir -p "$output" "$root/build/native"

source_root="$root/DubLift"
if [[ ! -f "$source_root/go.mod" ]]; then
  echo 'DubLift submodule is missing. Run: git submodule update --init --recursive' >&2
  exit 1
fi

echo 'Building DubLift for Android ARM64...'
(cd "$source_root" && CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go build -trimpath -ldflags='-s -w' -o "$output/libdublift.so" ./cmd/dublift)

if [[ -s "$output/libffmpeg.so" && -s "$output/libffprobe.so" && ${REBUILD_FFMPEG:-0} != 1 ]]; then
  echo 'Using previously built FFmpeg and ffprobe.'
  exit 0
fi

sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"$HOME/.local/share/dublift-android-sdk"}}
ndk_root=${ANDROID_NDK_HOME:-"$sdk_root/ndk/27.2.12479018"}
toolchain="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -x "$toolchain/bin/aarch64-linux-android26-clang" ]]; then
  echo "Android NDK r27c not found at $ndk_root (set ANDROID_NDK_HOME)." >&2
  exit 1
fi

version=7.1.2
archive="$root/build/native/ffmpeg-$version.tar.xz"
expected=089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304
if [[ ! -f "$archive" ]]; then
  cached="$sdk_root/downloads/ffmpeg-$version.tar.xz"
  if [[ -f "$cached" ]]; then
    cp "$cached" "$archive"
  else
    curl -fL --retry 3 "https://ffmpeg.org/releases/ffmpeg-$version.tar.xz" -o "$archive"
  fi
fi
echo "$expected  $archive" | sha256sum -c -

source="$root/build/native/ffmpeg-$version"
build="$root/build/native/ffmpeg-build"
if [[ ! -d "$source" ]]; then
  tar -xf "$archive" -C "$root/build/native"
fi
mkdir -p "$build"
cd "$build"

echo 'Configuring LGPL FFmpeg and ffprobe for Android ARM64...'
"$source/configure" \
  --target-os=android --arch=aarch64 --enable-cross-compile \
  --cc="$toolchain/bin/aarch64-linux-android26-clang" \
  --ld="$toolchain/bin/aarch64-linux-android26-clang" \
  --ar="$toolchain/bin/llvm-ar" --nm="$toolchain/bin/llvm-nm" \
  --ranlib="$toolchain/bin/llvm-ranlib" --strip="$toolchain/bin/llvm-strip" \
  --sysroot="$toolchain/sysroot" \
  --extra-ldflags='-Wl,-z,max-page-size=16384' \
  --disable-doc --disable-debug --disable-ffplay --disable-avdevice \
  --disable-postproc --disable-shared --enable-static --disable-symver \
  --enable-small --enable-neon

# DubLift copies video packets and encodes AAC with FFmpeg's native encoder.
# MediaCodec is not selected by those commands, so the Termux MediaCodec/JNI
# stack would add dependencies without accelerating this workload.
grep -q '^#define HAVE_NEON 1$' config.h

make -j "${FFMPEG_JOBS:-4}" ffmpeg ffprobe
cp ffmpeg "$output/libffmpeg.so"
cp ffprobe "$output/libffprobe.so"
"$toolchain/bin/llvm-strip" "$output/libffmpeg.so" "$output/libffprobe.so"
echo 'Android native executables are ready.'
