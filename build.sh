#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_TOOLS="$(cd "$PROJECT/../../work/toolchain" && pwd)"
YETI_JAVA_HOME="${YETI_JAVA_HOME:-$DEFAULT_TOOLS/jdk-17.0.21+9/Contents/Home}"
YETI_SDK="${YETI_SDK:-$DEFAULT_TOOLS/sdk}"
BT="${YETI_BUILD_TOOLS:-$YETI_SDK/android-16}"
export JAVA_HOME="$YETI_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
BUILD="$PROJECT/build"
DEFAULT_GRADLE="$DEFAULT_TOOLS/gradle-8.13/bin/gradle"
if [[ ! -x "$DEFAULT_GRADLE" ]]; then DEFAULT_GRADLE=gradle; fi
GRADLE="${YETI_GRADLE:-$DEFAULT_GRADLE}"
VARIANT="${YETI_BUILD_VARIANT:-release}"
if [[ "$VARIANT" != "release" && "$VARIANT" != "debug" ]]; then
    printf '%s\n' 'YETI_BUILD_VARIANT может быть только release или debug.' >&2
    exit 1
fi
if ! command -v "$GRADLE" >/dev/null; then
    printf '%s\n' 'Установи Gradle 8.13 или задай YETI_GRADLE путём к его исполняемому файлу.' >&2
    exit 1
fi
if [[ -d "$YETI_SDK/platforms/android-36" ]]; then
    export ANDROID_HOME="$YETI_SDK"
    BT="${YETI_BUILD_TOOLS:-$YETI_SDK/build-tools/36.0.0}"
else
    export ANDROID_HOME="$BUILD/gradle-sdk"
    mkdir -p "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"
    ln -sfn "$(cd "$YETI_SDK/android-36" && pwd)" "$ANDROID_HOME/platforms/android-36"
    ln -sfn "$(cd "$BT" && pwd)" "$ANDROID_HOME/build-tools/36.0.0"
    if [[ -d "$YETI_SDK/platform-tools" ]]; then
        ln -sfn "$(cd "$YETI_SDK/platform-tools" && pwd)" "$ANDROID_HOME/platform-tools"
    fi
fi
if [[ "$VARIANT" == "release" ]]; then TASK="Release"; else TASK="Debug"; fi
"$GRADLE" --no-daemon -p "$PROJECT" ":app:assemble$TASK"
if [[ "$VARIANT" == "release" ]]; then
    UNSIGNED="$BUILD/android/outputs/apk/release/app-release-unsigned.apk"
else
    UNSIGNED="$BUILD/android/outputs/apk/debug/app-debug.apk"
fi
"$BT/zipalign" -f 4 "$UNSIGNED" "$BUILD/aligned.apk"
KEYDIR="$DEFAULT_TOOLS/signing"
mkdir -p "$KEYDIR"
if [ ! -f "$KEYDIR/yeti-local.p12" ]; then
    keytool -genkeypair -keystore "$KEYDIR/yeti-local.p12" -storetype PKCS12 -storepass android -keypass android -alias yeti-local -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Yeti Local Development'
    chmod 600 "$KEYDIR/yeti-local.p12"
fi
VERSION="$("$BT/aapt" dump badging "$UNSIGNED" | sed -n "1s/.*versionName='\([^']*\)'.*/\1/p")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
APK="${YETI_APK_OUT:-$BUILD/yeti-diagnostics-$VERSION.apk}"
"$BT/apksigner" sign --ks "$KEYDIR/yeti-local.p12" --ks-key-alias yeti-local --ks-pass pass:android --out "$APK" "$BUILD/aligned.apk"
"$BT/apksigner" verify --verbose "$APK"
printf 'Built: %s\n' "$APK"
