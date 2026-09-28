# Third-party notices

The APK includes FFmpeg 7.1.2 `ffmpeg` and `ffprobe` executables, built from the official source archive at https://ffmpeg.org/releases/ffmpeg-7.1.2.tar.xz (SHA-256 `089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304`). Build commands and options are in [scripts/prepare-native.sh](scripts/prepare-native.sh). No GPL or nonfree FFmpeg options or optional third-party codec libraries are enabled. The executables use Android's system C, math, and zlib libraries. FFmpeg is licensed under LGPL 2.1 or later; its source archive includes the full license and copyright notices. The statically linked FFmpeg code can be rebuilt or modified using the script and that source archive.

DubLift's Go binary is built from the pinned [DubLift](https://github.com/joojoooo/DubLift) submodule and embeds its Go module dependencies. Their versions and checksums are in the submodule's `go.mod` and `go.sum`.
