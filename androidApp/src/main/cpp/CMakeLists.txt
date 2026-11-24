cmake_minimum_required(VERSION 3.22.1)
project(aiostreams_exoplayer C)

# ass/ holds libass's public headers, 0.17.4 as libmpv carries it; the library is loaded from libmpv.
add_library(aiostreams_ass SHARED
    subtitles/ass_surface.c subtitles/masks.c subtitles/blend.c subtitles/gl.c subtitles/gl_masks.c)
target_compile_options(aiostreams_ass PRIVATE -O3 -Wall -fvisibility=hidden)
target_link_libraries(aiostreams_ass PRIVATE android dl log EGL GLESv2)

# ffmpeg/ holds FFmpeg 8.0's public headers, as libmpv carries it; the libraries are loaded from libmpv's.
add_library(nuvio_swvideo SHARED sw_video.c)
target_include_directories(nuvio_swvideo PRIVATE ffmpeg)
target_compile_options(nuvio_swvideo PRIVATE -O3 -Wall)
target_link_libraries(nuvio_swvideo PRIVATE android dl log)
