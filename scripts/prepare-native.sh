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
# Keep LOAD segments and the end of RELRO compatible with both 4 KB and 16 KB
# kernels. NDK r27 needs both flags; max-page-size alone leaves 4 KB RELRO.
native_ldflags='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
archive="$root/build/native/ffmpeg-$version.tar.xz"
expected=089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304
source="$root/build/native/ffmpeg-$version"
freetype_version=2.13.3
harfbuzz_version=10.4.0
openh264_version=2.5.0
recipe_id=$(sha256sum "$0" | cut -d ' ' -f 1)

check_native_alignment() {
  local binary type offset vaddr paddr filesz memsz flags alignment loads
  for binary in "$@"; do
    loads=0
    while read -r type offset vaddr paddr filesz memsz flags; do
      case "$type" in
        LOAD)
          alignment=${flags##* }
          if (( alignment < 16384 || (vaddr - offset) % 16384 != 0 )); then
            echo "Native LOAD segment is not 16 KB aligned: $binary" >&2
            exit 1
          fi
          loads=$((loads + 1))
          ;;
        GNU_RELRO)
          if (( (vaddr + memsz) % 16384 != 0 )); then
            echo "Native RELRO end is not 16 KB aligned: $binary" >&2
            exit 1
          fi
          ;;
      esac
    done < <("$toolchain/bin/llvm-readelf" -lW "$binary")
    if (( loads == 0 )); then
      echo "No native LOAD segments found: $binary" >&2
      exit 1
    fi
    echo "Verified 16 KB native alignment: $binary"
  done
}

fetch_source() {
  local archive="$1" expected="$2" url="$3" source="$4"
  shift 4
  local urls=("$url" "$@")

  if [[ -f "$archive" ]] && ! echo "$expected  $archive" | sha256sum -c --status -; then
    echo "Checksum mismatch for existing $archive; re-downloading..." >&2
    rm -f "$archive"
  fi

  if [[ ! -f "$archive" ]]; then
    local downloaded=0
    for u in "${urls[@]}"; do
      echo "Downloading $(basename "$archive") from $u..."
      rm -f "$archive.tmp"
      if curl -fL --retry 3 --connect-timeout 15 "$u" -o "$archive.tmp"; then
        if echo "$expected  $archive.tmp" | sha256sum -c --status -; then
          mv "$archive.tmp" "$archive"
          downloaded=1
          break
        else
          echo "Checksum mismatch for $(basename "$archive") downloaded from $u" >&2
          rm -f "$archive.tmp"
        fi
      else
        echo "Failed to download from $u" >&2
        rm -f "$archive.tmp"
      fi
    done

    if (( downloaded == 0 )); then
      echo "Failed to download $archive from any available source." >&2
      return 1
    fi
  fi

  echo "$expected  $archive" | sha256sum -c -
  if [[ ! -d "$source" ]]; then
    tar -xf "$archive" -C "$root/build/native"
  fi
}

build_error_screen_deps() {
  local abi="$1" prefix="$2" android_arch="$3"
  local freetype="$root/build/native/freetype-$freetype_version"
  local harfbuzz="$root/build/native/harfbuzz-$harfbuzz_version"
  local openh264="$root/build/native/openh264-$openh264_version"
  fetch_source "$root/build/native/freetype-$freetype_version.tar.xz" \
    0550350666d427c74daeb85d5ac7bb353acba5f76956395995311a9c6f063289 \
    "https://download.savannah.gnu.org/releases/freetype/freetype-$freetype_version.tar.xz" "$freetype" \
    "https://downloads.sourceforge.net/project/freetype/freetype2/$freetype_version/freetype-$freetype_version.tar.xz"
  fetch_source "$root/build/native/harfbuzz-$harfbuzz_version.tar.xz" \
    480b6d25014169300669aa1fc39fb356c142d5028324ea52b3a27648b9beaad8 \
    "https://github.com/harfbuzz/harfbuzz/releases/download/$harfbuzz_version/harfbuzz-$harfbuzz_version.tar.xz" "$harfbuzz"
  fetch_source "$root/build/native/openh264-v$openh264_version.tar.gz" \
    94c8ca364db990047ec4ec3481b04ce0d791e62561ef5601443011bdc00825e3 \
    "https://github.com/cisco/openh264/archive/refs/tags/v$openh264_version.tar.gz" "$openh264"

  # drawtext needs both FreeType and HarfBuzz. Avoid host libraries and
  # optional fontconfig: Android passes an explicit /system/fonts path.
  local cmake_args=(-DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$min_api"
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix"
    -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF)
  cmake -S "$freetype" -B "$root/build/native/freetype-build-$abi" "${cmake_args[@]}" \
    -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON \
    -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
  cmake --build "$root/build/native/freetype-build-$abi" --parallel "${FFMPEG_JOBS:-4}"
  cmake --install "$root/build/native/freetype-build-$abi"
  cmake -S "$harfbuzz" -B "$root/build/native/harfbuzz-build-$abi" "${cmake_args[@]}" \
    -DCMAKE_PREFIX_PATH="$prefix" -DHB_HAVE_FREETYPE=ON \
    -DFREETYPE_LIBRARY="$prefix/lib/libfreetype.a" \
    -DFREETYPE_INCLUDE_DIR_ft2build="$prefix/include/freetype2" \
    -DFREETYPE_INCLUDE_DIR_freetype2="$prefix/include/freetype2" \
    -DHB_BUILD_UTILS=OFF -DHB_BUILD_SUBSET=OFF \
    -DHB_HAVE_GLIB=OFF -DHB_HAVE_ICU=OFF
  cmake --build "$root/build/native/harfbuzz-build-$abi" --parallel "${FFMPEG_JOBS:-4}"
  cmake --install "$root/build/native/harfbuzz-build-$abi"

  # OpenH264 is BSD licensed and supplies AVC without enabling GPL FFmpeg.
  # The static archive is shared across FFmpeg's two executables at link time.
  # The Android clean target also tries to clean OpenH264's demo APKs.
  make -s -C "$openh264" clean OS=linux ARCH="$android_arch" USE_ASM=No
  rm -f "$openh264/codec/common/src/cpu-features.o" \
    "$openh264/codec/common/src/cpu-features.d"
  make -s -C "$openh264" -j "${FFMPEG_JOBS:-4}" install-static \
    OS=android ARCH="$android_arch" NDKROOT="$ndk_root" TARGET="android-$min_api" \
    USE_ASM=No STATIC_LDFLAGS= PREFIX="$prefix"
}

build_native() {
  local abi="$1" goarch compiler ffmpeg_arch android_arch triplet
  local output="$root/app/build/generated/jniLibs/$abi"
  local build="$root/build/native/ffmpeg-build-$abi"
  local ffmpeg_flags=()
  case "$abi" in
    arm64-v8a)
      goarch=arm64
      compiler=aarch64-linux-android${min_api}-clang
      ffmpeg_arch=aarch64
      android_arch=arm64
      triplet=aarch64-linux-android
      ;;
    armeabi-v7a)
      goarch=arm
      compiler=armv7a-linux-androideabi${min_api}-clang
      ffmpeg_arch=arm
      android_arch=arm
      triplet=arm-linux-androideabi
      ffmpeg_flags=(--cpu=armv7-a --extra-cflags='-mfpu=neon -mfloat-abi=softfp')
      ;;
  esac
  local android_cc="$toolchain/bin/$compiler"
  local api_stamp="$output/.ffmpeg-api"
  local build_stamp="$output/.ffmpeg-build-id"
  if [[ ! -x "$android_cc" ]]; then
    echo "Android NDK r27c compiler not found at $android_cc (set ANDROID_NDK_HOME)." >&2
    exit 1
  fi
  mkdir -p "$output"

  echo "Building DubLift for Android $abi..."
  (cd "$source_root" && CGO_ENABLED=1 GOOS=android GOARCH="$goarch" GOARM=7 CC="$android_cc" \
    go build -trimpath -ldflags="-s -w -linkmode=external -extldflags '$native_ldflags'" \
      -o "$output/libdublift.so" ./cmd/dublift)

  if [[ -s "$output/libffmpeg.so" && -s "$output/libffprobe.so" &&
        -f "$api_stamp" && -f "$build_stamp" && ${REBUILD_FFMPEG:-0} != 1 ]] &&
      [[ "$(cat "$api_stamp")" == "$min_api" && "$(cat "$build_stamp")" == "$recipe_id" ]]; then
    echo "Using previously built error-screen-capable FFmpeg and ffprobe for $abi."
    return
  fi

  rm -f "$api_stamp" "$build_stamp" "$output/libffmpeg.so" "$output/libffprobe.so"
  rm -rf "$build"

  if [[ ! -f "$archive" && -f "$sdk_root/downloads/ffmpeg-$version.tar.xz" ]]; then
    cp "$sdk_root/downloads/ffmpeg-$version.tar.xz" "$archive"
  fi
  fetch_source "$archive" "$expected" "https://ffmpeg.org/releases/ffmpeg-$version.tar.xz" "$source"
  local prefix="$root/build/native/error-screen-deps-$abi"
  rm -rf "$prefix"
  build_error_screen_deps "$abi" "$prefix" "$android_arch"
  mkdir -p "$build"
  (
    cd "$build"
    echo "Configuring LGPL FFmpeg and ffprobe for Android $abi..."
    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" "$source/configure" \
      --target-os=android --arch="$ffmpeg_arch" --enable-cross-compile \
      --cc="$android_cc" --ld="$android_cc" \
      --ar="$toolchain/bin/llvm-ar" --nm="$toolchain/bin/llvm-nm" \
      --ranlib="$toolchain/bin/llvm-ranlib" --strip="$toolchain/bin/llvm-strip" \
      --sysroot="$toolchain/sysroot" \
      --pkg-config-flags=--static \
      --extra-cflags="-I$prefix/include" \
      --extra-ldflags="-L$prefix/lib $native_ldflags" \
      --extra-libs="$toolchain/sysroot/usr/lib/$triplet/libc++_static.a $toolchain/sysroot/usr/lib/$triplet/libc++abi.a -ldl -lm" \
      --enable-libfreetype --enable-libharfbuzz --enable-libopenh264 --enable-zlib \
      --disable-autodetect \
      --disable-doc --disable-debug --disable-ffplay \
      --disable-indevs --disable-outdevs --enable-indev=lavfi \
      --disable-postproc --disable-shared --enable-static --disable-symver \
      --enable-small --enable-neon "${ffmpeg_flags[@]}"

    grep -q '^#define CONFIG_DRAWTEXT_FILTER 1$' config_components.h
    grep -q '^#define CONFIG_COLOR_FILTER 1$' config_components.h
    grep -q '^#define CONFIG_LIBOPENH264_ENCODER 1$' config_components.h
    grep -q '^#define CONFIG_LAVFI_INDEV 1$' config_components.h
    grep -q '^#define HAVE_NEON 1$' config.h

    make -j "${FFMPEG_JOBS:-4}" ffmpeg ffprobe
    cp ffmpeg "$output/libffmpeg.so"
    cp ffprobe "$output/libffprobe.so"
    "$toolchain/bin/llvm-strip" "$output/libffmpeg.so" "$output/libffprobe.so"
  )
  printf '%s\n' "$min_api" > "$api_stamp"
  printf '%s\n' "$recipe_id" > "$build_stamp"
}

for abi in arm64-v8a armeabi-v7a; do
  build_native "$abi"
  check_native_alignment "$root/app/build/generated/jniLibs/$abi/"*.so
done
echo 'Android native executables are ready.'
