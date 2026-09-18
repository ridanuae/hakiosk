#!/usr/bin/env bash
#
# Assembles an Android build toolchain on aarch64 without root and without
# Android Studio.
#
# The problem this solves: Google ships aapt2 as an x86_64-only Linux binary and
# this machine is aarch64 with no qemu/binfmt, so the standard SDK can't run.
# What does work:
#   * aapt (v1) and zipalign exist as native arm64 Ubuntu packages
#   * d8 and apksigner are pure-Java jars, so the x86 build-tools zip is fine
#     as a container to pull them out of
#   * javac comes from the system JRE, which includes the jdk.compiler module
#
# Nothing here needs sudo: apt-get download + dpkg -x into a local sysroot.
#
set -euo pipefail
cd "$(dirname "$0")"

PLATFORM_ZIP=platform-31_r01.zip
BUILDTOOLS_ZIP=build-tools_r34-linux.zip
BASE=https://dl.google.com/android/repository

# aapt/zipalign plus every shared library they link against, resolved with
# `apt-cache depends --recurse`. They live in a non-standard directory
# (/usr/lib/<triplet>/android), hence the LD_LIBRARY_PATH in build.sh.
DEBS="aapt zipalign
      android-libaapt android-libandroidfw android-libbacktrace android-libbase
      android-libcutils android-liblog android-libutils android-libziparchive
      libzopfli1 libprotobuf32t64"

echo "==> downloading Android platform + build-tools"
[ -f "$PLATFORM_ZIP" ]   || curl -sSL -o "$PLATFORM_ZIP"   "$BASE/$PLATFORM_ZIP"
[ -f "$BUILDTOOLS_ZIP" ] || curl -sSL -o "$BUILDTOOLS_ZIP" "$BASE/$BUILDTOOLS_ZIP"

echo "==> extracting android.jar, d8.jar, apksigner.jar"
unzip -o -j -q "$PLATFORM_ZIP"   'android-*/android.jar' -d .
unzip -o -j -q "$BUILDTOOLS_ZIP" '*/lib/d8.jar' '*/lib/apksigner.jar' -d .

echo "==> downloading arm64 debs"
mkdir -p debs sysroot
( cd debs && apt-get download $DEBS )

echo "==> unpacking into sysroot"
for d in debs/*.deb; do dpkg -x "$d" sysroot/; done

export LD_LIBRARY_PATH="$PWD/sysroot/usr/lib/aarch64-linux-gnu/android:$PWD/sysroot/usr/lib/aarch64-linux-gnu:$PWD/sysroot/usr/lib"
echo "==> check"
sysroot/usr/bin/aapt version
java -m jdk.compiler/com.sun.tools.javac.Main -version
echo "toolchain ready"
