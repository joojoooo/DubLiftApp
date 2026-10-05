# Third-party notices

The APK includes FFmpeg 7.1.2 `ffmpeg` and `ffprobe` executables, built from the official source archive at https://ffmpeg.org/releases/ffmpeg-7.1.2.tar.xz (SHA-256 `089bc60fb59d6aecc5d994ff530fd0dcb3ee39aa55867849a2bbc4e555f9c304`). Build commands and options are in [scripts/prepare-native.sh](scripts/prepare-native.sh). FFmpeg is licensed under LGPL 2.1 or later; its source archive includes the full license and copyright notices. The statically linked FFmpeg code can be rebuilt or modified using the script and that source archive. No GPL or nonfree FFmpeg options are enabled.

The FFmpeg executables statically link these libraries to render and encode the playback error screen:

| Library | Source archive SHA-256 | License |
| --- | --- | --- |
| [FreeType 2.13.3](https://download.savannah.gnu.org/releases/freetype/freetype-2.13.3.tar.xz) | `0550350666d427c74daeb85d5ac7bb353acba5f76956395995311a9c6f063289` | [FreeType License](licenses/FreeType-FTL.TXT) |
| [HarfBuzz 10.4.0](https://github.com/harfbuzz/harfbuzz/releases/download/10.4.0/harfbuzz-10.4.0.tar.xz) | `480b6d25014169300669aa1fc39fb356c142d5028324ea52b3a27648b9beaad8` | [Old MIT License](licenses/HarfBuzz-COPYING) |
| [OpenH264 2.5.0](https://github.com/cisco/openh264/archive/refs/tags/v2.5.0.tar.gz) | `94c8ca364db990047ec4ec3481b04ce0d791e62561ef5601443011bdc00825e3` | [BSD License](licenses/OpenH264-LICENSE) |

This software uses the FreeType Project's font engine. Android system fonts are used at runtime; no font files are bundled.

DubLift's Go binary is built from the pinned [DubLift](https://github.com/joojoooo/DubLift) submodule and embeds its Go module dependencies. Their versions and checksums are in the submodule's `go.mod` and `go.sum`.

JVM unit tests use [JUnit 4.13.2](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt)
(EPL-1.0), its [Hamcrest Core 1.3](https://github.com/hamcrest/JavaHamcrest/blob/hamcrest-java-1.3/LICENSE.txt)
dependency (BSD-3-Clause), and [JSON-java 20260814](https://github.com/stleary/JSON-java/blob/20260814/LICENSE)
(public domain). These are test-only dependencies and are not bundled in the APKs;
the app uses Android's built-in JSON implementation at runtime.
