#!/bin/bash
# Builds TripRecorderNG.apk with the plain Android SDK tools (no Gradle).
# Needs: JDK 21, Android SDK with a platform (android-34 or newer) and build-tools 35 or newer.
# Set JAVA_HOME / ANDROID_SDK_ROOT if yours are not in the Homebrew locations below.
#
# Version: the version code is always 1 on purpose (Android refuses to install a lower version code over a
# higher one, and switching from the dev to the stable channel must work). The version name identifies the
# build: CI sets VERSION_NAME to "<channel>-<run number>" ("dev-9"), which is also the release tag; a build made
# on a computer is "0.1" unless you set VERSION_NAME.
#
# Signing: the APK is signed with triprec.keystore (alias "triprec"). If that file does not exist a new key
# is created with a random password, kept in triprec.keystore.pass. Both files are git-ignored. Keep them
# (and a backup): an update only installs over an earlier build when it is signed with the same key.
# To use an existing keystore set KEYSTORE=path and KS_PASS=password.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"  # needs 35+ (older d8 crashes on JDK 21 output)
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"
VERSION_NAME="${VERSION_NAME:-0.1}"

[ -f "$PLATFORM" ] || { echo "missing $PLATFORM (install a platform with sdkmanager)"; exit 1; }
[ -x "$BT/aapt2" ] || { echo "missing build-tools (install with sdkmanager)"; exit 1; }

rm -rf build && mkdir -p build/classes build/dex

echo "[1/6] javac"
javac --release 8 -g:none -Xlint:-options -classpath "$PLATFORM" -d build/classes $(find src -name '*.java')

echo "[2/6] d8"
"$BT/d8" --lib "$PLATFORM" --min-api 29 --output build/dex $(find build/classes -name '*.class')

echo "[3/6] aapt2 compile + link"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$PLATFORM" build/res.zip -A assets \
    --min-sdk-version 29 --target-sdk-version 29 --version-code 1 --version-name "$VERSION_NAME" --replace-version

echo "[4/6] add classes.dex"
( cd build/dex && zip -q -u ../base.apk classes.dex )

echo "[5/6] zipalign"
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

echo "[6/6] sign"
KS="${KEYSTORE:-triprec.keystore}"
if [ ! -f "$KS" ]; then
    KS_PASS="${KS_PASS:-$(openssl rand -hex 16)}"
    export KS_PASS
    keytool -genkeypair -keystore "$KS" -storepass:env KS_PASS -keypass:env KS_PASS \
        -alias triprec -keyalg RSA -keysize 2048 -validity 36500 \
        -dname "CN=Trip Recorder NG, O=Personal, C=US" >/dev/null 2>&1
    ( umask 077; printf '%s' "$KS_PASS" > "$KS.pass" )
    echo "created a new signing key: $KS (password in $KS.pass) - keep both"
fi
if [ -z "${KS_PASS:-}" ] && [ -f "$KS.pass" ]; then KS_PASS="$(cat "$KS.pass")"; fi
[ -n "${KS_PASS:-}" ] || { echo "no password for $KS: set KS_PASS or put it in $KS.pass"; exit 1; }
export KS_PASS
"$BT/apksigner" sign --ks "$KS" --ks-pass env:KS_PASS --key-pass env:KS_PASS \
    --out TripRecorderNG.apk build/aligned.apk
"$BT/apksigner" verify TripRecorderNG.apk && echo "built: $(pwd)/TripRecorderNG.apk ($(du -h TripRecorderNG.apk | cut -f1))"
