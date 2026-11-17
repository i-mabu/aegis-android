#!/usr/bin/env bash
# AEGIS Android - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + kotlinc + d8 でAPKを作る)
# 必要: JDK 17+ と Android SDK (platforms;android-34, build-tools;34.0.0) と Kotlin コンパイラ
# 使い方: ANDROID_SDK_ROOT=~/android-sdk ./build-apk.sh
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
[ -f "$AJAR" ] || { echo "android.jar が見つかりません: $AJAR" >&2; exit 1; }
[ -x "$BT/aapt2" ] || { echo "aapt2 が見つかりません: $BT" >&2; exit 1; }

# JAVA_HOME 未設定なら VM 内の JDK を使う (kotlinc の起動にも java が必要)
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/workspace/tools/jdk-21" ]; then
  JAVA_HOME="$HOME/workspace/tools/jdk-21"
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVAC="${JAVA_BIN}javac"; KEYTOOL="${JAVA_BIN}keytool"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool
[ -n "$JAVA_BIN" ] && export PATH="${JAVA_BIN}:$PATH"

# Kotlin コンパイラ (KOTLINC 環境変数 → VM 内の導入済みコンパイラ → PATH の順に探す)
KOTLINC="${KOTLINC:-}"
if [ -z "$KOTLINC" ] && [ -x "$HOME/workspace/tools/kotlin/bin/kotlinc" ]; then
  KOTLINC="$HOME/workspace/tools/kotlin/bin/kotlinc"
fi
if [ -z "$KOTLINC" ]; then KOTLINC="$(command -v kotlinc || true)"; fi
{ [ -n "$KOTLINC" ] && [ -x "$KOTLINC" ]; } || { echo "kotlinc が見つかりません" >&2; exit 1; }
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar が見つかりません: $STDLIB" >&2; exit 1; }

BUILD=build/manual
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/compiled.zip"

echo "[2/6] aapt2 link"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーに注入して使う
# (ソース側の manifest は AGP の namespace 方式に合わせて package 属性なしのまま)
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.aegis.client"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "$BUILD/compiled.zip" \
  --java "$BUILD/gen" --min-sdk-version 26 --target-sdk-version 34

echo "[3/6] kotlinc (+ javac for R.java)"
# kotlinc は aapt2 生成の R.java をシンボル解決のためだけに読み、クラスは出力しないため、
# R.java のクラスは従来どおり javac で別途コンパイルする
"$KOTLINC" -cp "$AJAR" -d "$BUILD/classes" \
  $(find app/src/main/java -name '*.kt') $(find "$BUILD/gen" -name '*.java')
"$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" $(find "$BUILD/gen" -name '*.java')

echo "[4/6] d8 (kotlin-stdlib を同梱)"
"$BT/d8" --min-api 26 --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class') "$STDLIB"

echo "[5/6] package + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes.dex )
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

echo "[6/6] sign (debug keystore)"
KS="debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=AEGIS,C=JP" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/aegis-client.apk" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$BUILD/aegis-client.apk" | head -3
mkdir -p dist
cp "$BUILD/aegis-client.apk" dist/aegis-client.apk
echo "OK: dist/aegis-client.apk"
