#!/bin/bash
#
# Rebuild FFmpeg 4.4.3 + libvpx 1.10.0 for arm64-v8a using NDK 26.
#
# Required env:
#   ANDROID_NDK    — path to NDK 26.3.11579264 (or compatible r26+)
#   LEEMEN_FFMPEG_SRC — off-tree dir containing ffmpeg/ and libvpx/ source clones:
#                       git clone --depth 1 --branch n4.4.3   https://github.com/FFmpeg/FFmpeg.git  $LEEMEN_FFMPEG_SRC/ffmpeg
#                       git clone --depth 1 --branch v1.10.0  https://chromium.googlesource.com/webm/libvpx $LEEMEN_FFMPEG_SRC/libvpx
#
# Patch required before running:
#   In $LEEMEN_FFMPEG_SRC/ffmpeg/libavutil/aarch64/asm.S, replace the generic CONFIG_PIC branch of `movrel`
#   with GOT-based addressing (see comments in this repo's commit history).
#
# Output: static .a written to $LEEMEN_FFMPEG_SRC/out/arm64-v8a/lib/
#         (libavcodec.a libavformat.a libavutil.a libavresample.a libswresample.a libswscale.a libvpx.a)
#         Copy these into TMessagesProj/jni/ffmpeg/arm64-v8a/ to use.
#
# NOTE: FFmpeg asm is currently disabled for arm64 — FFmpeg 4.4.3's aarch64 asm has multiple
# shared-library-incompat issues (GOT alignment, out-of-range conditional branches, movrel).
# Building C-only avoids those for now. h264/h265 decode is slower; tolerable for dev builds.

set -e

: "${ANDROID_NDK:?Set ANDROID_NDK to NDK 26 path}"
: "${LEEMEN_FFMPEG_SRC:?Set LEEMEN_FFMPEG_SRC to dir with ffmpeg/ and libvpx/ source}"

API=21
TOOLCHAIN=$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64
ROOT=$LEEMEN_FFMPEG_SRC
PREFIX=$ROOT/out/arm64-v8a
CORES=$(sysctl -n hw.physicalcpu 2>/dev/null || nproc)

CC=$TOOLCHAIN/bin/aarch64-linux-android${API}-clang
CXX=$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++
AR=$TOOLCHAIN/bin/llvm-ar
NM=$TOOLCHAIN/bin/llvm-nm
STRIP=$TOOLCHAIN/bin/llvm-strip
RANLIB=$TOOLCHAIN/bin/llvm-ranlib

rm -rf $PREFIX
mkdir -p $PREFIX

echo "===== Building libvpx (arm64-v8a) ====="
cd $ROOT/libvpx
make distclean >/dev/null 2>&1 || true
export CC CXX AR NM STRIP RANLIB
export LD=$CC
./configure \
  --target=arm64-android-gcc \
  --prefix=$PREFIX \
  --disable-runtime-cpu-detect \
  --enable-pic \
  --enable-libyuv \
  --enable-static --disable-shared \
  --enable-small --enable-optimizations \
  --enable-better-hw-compatibility \
  --enable-realtime-only \
  --enable-vp8 --enable-vp9 \
  --disable-webm-io --disable-examples --disable-tools \
  --disable-debug --disable-neon-asm \
  --disable-unit-tests --disable-docs \
  --extra-cflags="-fPIC -DPIC -DANDROID -O3 -march=armv8-a" \
  --extra-cxxflags="-fPIC -DPIC"
make -j$CORES install

echo "===== Building FFmpeg (arm64-v8a, C-only) ====="
cd $ROOT/ffmpeg
make distclean >/dev/null 2>&1 || true
./configure \
  --cc=$CC --cxx=$CXX --ar=$AR --nm=$NM --strip=$STRIP --ranlib=$RANLIB \
  --arch=aarch64 --cpu=armv8-a \
  --target-os=android \
  --enable-cross-compile \
  --enable-pic --enable-stripping \
  --disable-asm --disable-inline-asm --disable-neon \
  --enable-version3 --enable-gpl \
  --enable-static --disable-shared \
  --disable-doc --disable-everything --disable-network --disable-zlib \
  --disable-avfilter --disable-avdevice --disable-postproc \
  --disable-debug --disable-programs \
  --enable-libvpx \
  --enable-decoder=libvpx_vp9 --enable-encoder=libvpx_vp9 \
  --enable-muxer=matroska \
  --enable-bsf=vp9_superframe --enable-bsf=vp9_raw_reorder \
  --enable-runtime-cpudetect --enable-pthreads --enable-avresample --enable-swscale \
  --enable-protocol=file \
  --enable-decoder=h264 --enable-decoder=h265 --enable-decoder=mpeg4 \
  --enable-decoder=mjpeg --enable-decoder=gif --enable-decoder=alac \
  --enable-decoder=opus --enable-decoder=mp3 --enable-decoder=aac \
  --enable-demuxer=mov --enable-demuxer=gif --enable-demuxer=ogg \
  --enable-demuxer=matroska --enable-demuxer=mp3 --enable-demuxer=aac \
  --enable-hwaccels \
  --prefix=$PREFIX \
  --extra-cflags="-fPIC -DPIC -isystem $TOOLCHAIN/sysroot/usr/include -I$PREFIX/include -Os" \
  --extra-ldflags="-L$PREFIX/lib"
make -j$CORES install

echo
echo "Done. Output: $PREFIX/lib"
ls -lh $PREFIX/lib/*.a
echo
echo "Next: copy these into TMessagesProj/jni/ffmpeg/arm64-v8a/"
