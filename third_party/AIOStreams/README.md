# AIOStreams (ported code)

Source: https://github.com/Viren070/AIOStreams (`packages/android/exoplayer`)
License: GNU Affero General Public License v3.0 (see `LICENSE` in this folder)

Files ported from AIOStreams, used when "Use libmpv's libass for ASS/SSA subtitles" is enabled:

| Nuvio path | AIOStreams path |
| --- | --- |
| `androidApp/src/main/cpp/subtitles/ass_surface.c` | `exoplayer/src/main/cpp/subtitles/ass_surface.c` |
| `androidApp/src/main/cpp/subtitles/masks.c`, `masks.h` | `exoplayer/src/main/cpp/subtitles/masks.c`, `masks.h` |
| `androidApp/src/main/cpp/subtitles/blend.c`, `blend.h` | `exoplayer/src/main/cpp/subtitles/blend.c`, `blend.h` |
| `androidApp/src/main/cpp/subtitles/gl.c`, `gl.h` | `exoplayer/src/main/cpp/subtitles/gl.c`, `gl.h` |
| `androidApp/src/main/cpp/subtitles/gl_masks.c`, `gl_masks.h` | `exoplayer/src/main/cpp/subtitles/gl_masks.c`, `gl_masks.h` |
| `androidApp/src/main/cpp/ass/ass.h`, `ass_types.h` | `exoplayer/src/main/cpp/ass/` (libass public headers, ISC) |
| `androidApp/src/main/cpp/CMakeLists.txt` | `exoplayer/src/main/cpp/CMakeLists.txt` |
| `composeApp/.../player/aiostreams/AssSurface.kt` | `exoplayer/.../AssSurface.kt` |
| `composeApp/.../player/aiostreams/AssNative.kt` | `exoplayer/.../subtitles/ass/AssNative.kt` |
| `composeApp/.../player/aiostreams/AttachedFonts.kt` | `exoplayer/.../AttachedFonts.kt` |
| `composeApp/.../player/aiostreams/FontNames.kt` | `exoplayer/.../FontNames.kt` |
| `composeApp/.../player/aiostreams/Matroska.kt` | `exoplayer/.../Matroska.kt` |
| `composeApp/.../player/aiostreams/SampleDependencies.kt` | `exoplayer/.../SampleDependencies.kt` |
| `composeApp/.../player/aiostreams/Subtitles.kt` | `exoplayer/.../Subtitles.kt` |

Modifications:

- Package changed to `com.nuvio.app.features.player.aiostreams`, and the JNI symbol prefix in `ass_surface.c` changed to match.
- `CMakeLists.txt` builds only the `aiostreams_ass` library.
- `Subtitles.kt`: `Silent` mutes only ASS/SSA; other subtitle formats are parsed by Media3's default parsers.
- The `subtitles/` native code (GPU blending, from AIOStreams' "blend subtitles on the GPU" and the follow-up
  "upload a picture's new subtitle masks as one block") loads libass from libmpv rather than a separate `libass.so`.
  libmpv's libass is stock 0.17.4, whose `ASS_Image` has no `cache_id`, so `masks.c` treats every image as having
  none (each mask is its picture's own) and `ass/ass.h` stays the stock header.
- `ass_surface.c` starts each new libass renderer (font clear/reload) on a fresh picture.
- `AssSurface.kt` keeps Nuvio's single libass thread: each video frame's moment is queued, taken and drawn at once,
  rather than rendered ahead from AIOStreams' `VideoFrames`. With GL the buffer carries the frame's presentation time.
  `setGpu` follows the "Draw libmpv ASS subtitles on the GPU" setting (on by default).
- `AiostreamsAssEngine.kt` is new Nuvio glue replacing the parts of AIOStreams' `ExoEngine.kt` and `Extractors.kt` that drive these files.
