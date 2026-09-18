#!/usr/bin/env bash
#
# Builds HAKiosk.apk without Gradle.
#
# This box is aarch64 and Google ships aapt2 as an x86_64-only binary, so the
# normal Android toolchain can't run here. Instead:
#   - aapt (v1) and zipalign come from Ubuntu's arm64 packages, unpacked into
#     tools/sysroot without root (apt-get download + dpkg -x)
#   - d8 and apksigner are pure-Java jars pulled out of the x86 build-tools zip
#   - javac comes from the system JRE, which happens to include jdk.compiler
#
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$PWD"
SYSROOT="$ROOT/tools/sysroot"

AAPT="$SYSROOT/usr/bin/aapt"
ZIPALIGN="$SYSROOT/usr/bin/zipalign"
ANDROID_JAR="$ROOT/tools/android.jar"
D8_JAR="$ROOT/tools/d8.jar"
APKSIGNER_JAR="$ROOT/tools/apksigner.jar"

KEYSTORE="$ROOT/keystore/hakiosk.jks"
KS_PASS="hakiosk"

MIN_API=19
OUT="$ROOT/dist/HAKiosk.apk"

# aapt/zipalign are linked against AOSP libs that live in a non-standard dir.
export LD_LIBRARY_PATH="$SYSROOT/usr/lib/aarch64-linux-gnu/android:$SYSROOT/usr/lib/aarch64-linux-gnu:$SYSROOT/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# The system JRE ships jdk.compiler, so we get a javac without installing a JDK.
javac_cmd() { java -m jdk.compiler/com.sun.tools.javac.Main "$@"; }

for f in "$AAPT" "$ZIPALIGN" "$ANDROID_JAR" "$D8_JAR" "$APKSIGNER_JAR"; do
  [ -e "$f" ] || { echo "missing: $f  (run tools/fetch-tools.sh)" >&2; exit 1; }
done

rm -rf build && mkdir -p build/classes build/gen dist

echo "==> aapt package (resources + R.java)"
# Resources are packaged first because the code needs R.java to compile: the
# settings screen is a layout, and the icon and colours are resources too.
"$AAPT" package -f -m \
  -J build/gen \
  -M app/AndroidManifest.xml \
  -S app/res \
  -I "$ANDROID_JAR" \
  -F build/app.apk

echo "==> javac"
# -source/-target 8 (not --release 8): the system JRE ships jdk.compiler but no
# lib/ct.sym, so --release has no API signatures to compile against.
# d8 8.2.2 cannot read anonymous classes emitted by javac 21, so app/src is
# written without any -- see the note in MainActivity.java.
javac_cmd -source 8 -target 8 -nowarn \
  -bootclasspath "$ANDROID_JAR" \
  -d build/classes \
  $(find app/src build/gen -name '*.java')

echo "==> d8"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api "$MIN_API" \
  --lib "$ANDROID_JAR" \
  --output build \
  $(find build/classes -name '*.class')

echo "==> add classes.dex"
# aapt stores the path as given, so classes.dex must be added from its own dir.
( cd build && "$AAPT" add -f app.apk classes.dex >/dev/null )

echo "==> zipalign"
"$ZIPALIGN" -f 4 build/app.apk build/app-aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  echo "==> generating signing key (keep keystore/ — a different key blocks upgrades)"
  mkdir -p keystore
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias hakiosk -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=HA Panel, OU=HAKiosk, O=HAKiosk" >/dev/null
fi

echo "==> apksigner"
# v1 (JAR) signing stays on: older Android and some vendor installers reject
# v2-only APKs, and we don't know what this panel runs.
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" build/app-aligned.apk

echo "==> verify"
java -jar "$APKSIGNER_JAR" verify --verbose "$OUT" | sed 's/^/    /'
"$AAPT" dump badging "$OUT" | grep -E "^(package|sdkVersion|targetSdkVersion|launchable-activity)" | sed 's/^/    /'

echo
echo "built: $OUT ($(stat -c%s "$OUT") bytes)"
