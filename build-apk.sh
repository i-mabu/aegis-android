#!/usr/bin/env bash
# AEGIS Android - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + kotlinc + d8 でAPKを作る)
# 必要: JDK 17+ と Android SDK (platforms;android-34, build-tools;34.0.0) と Kotlin コンパイラ
# 使い方:
#   ./build-apk.sh           単体テスト → ビルド → デバッグ署名 (dist/aegis-client.apk)
#   ./build-apk.sh release   単体テスト → ビルド → リリース署名 (dist/aegis-client-release.apk)
#     リリース署名は環境変数で指定する (スクリプト内に秘密情報は書かない):
#       RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS
#       RELEASE_KEY_PASS (省略時は RELEASE_KEYSTORE_PASS と同じ)
#   SDK の場所は ANDROID_SDK_ROOT / ANDROID_HOME で上書き可能
set -euo pipefail
cd "$(dirname "$0")"
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"

# ビルドモード (引数なし=debug)
MODE="${1:-debug}"
case "$MODE" in
  debug|release) ;;
  *) echo "使い方: $0 [debug|release]" >&2; exit 1 ;;
esac
# release の場合は署名情報を最初に検証する (ビルドが終わってから気づかないように)
if [ "$MODE" = "release" ]; then
  if [ -z "${RELEASE_KEYSTORE:-}" ] || [ -z "${RELEASE_KEYSTORE_PASS:-}" ] || [ -z "${RELEASE_KEY_ALIAS:-}" ]; then
    echo "release 署名には環境変数 RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS が必要です (RELEASE_KEY_PASS は省略時はストアパスワードと同じ)" >&2
    exit 1
  fi
  [ -f "$RELEASE_KEYSTORE" ] || { echo "キーストアが見つかりません: $RELEASE_KEYSTORE" >&2; exit 1; }
fi

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

echo "[1/7] unit tests"
./tests/run-tests.sh

echo "[2/7] aapt2 compile"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/compiled.zip"

echo "[3/7] aapt2 link"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーに注入して使う
# (ソース側の manifest は AGP の namespace 方式に合わせて package 属性なしのまま)
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.aegis.client"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "$BUILD/compiled.zip" \
  --java "$BUILD/gen" --min-sdk-version 26 --target-sdk-version 34

echo "[4/7] kotlinc (+ javac for R.java)"
# kotlinc は aapt2 生成の R.java をシンボル解決のためだけに読み、クラスは出力しないため、
# R.java のクラスは従来どおり javac で別途コンパイルする
"$KOTLINC" -cp "$AJAR" -d "$BUILD/classes" \
  $(find app/src/main/java -name '*.kt') $(find "$BUILD/gen" -name '*.java')
"$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" $(find "$BUILD/gen" -name '*.java')

echo "[5/7] d8 (kotlin-stdlib を同梱)"
"$BT/d8" --min-api 26 --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class') "$STDLIB"

echo "[6/7] package + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes.dex )
"$BT/zipalign" -f -p 4 "$BUILD/app-with-dex.apk" "$BUILD/app-aligned.apk"

echo "[7/7] sign ($MODE)"
if [ "$MODE" = "release" ]; then
  KS="${RELEASE_KEYSTORE:-}"
  KS_PASS="${RELEASE_KEYSTORE_PASS:-}"
  KEY_ALIAS="${RELEASE_KEY_ALIAS:-}"
  KEY_PASS="${RELEASE_KEY_PASS:-$KS_PASS}"
  if [ -z "$KS" ] || [ -z "$KS_PASS" ] || [ -z "$KEY_ALIAS" ]; then
    echo "release 署名には環境変数 RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS が必要です (RELEASE_KEY_PASS は省略時はストアパスワードと同じ)" >&2
    exit 1
  fi
  [ -f "$KS" ] || { echo "キーストアが見つかりません: $KS" >&2; exit 1; }
  OUT="aegis-client-release.apk"
else
  KS="debug.keystore"
  KS_PASS="android"; KEY_ALIAS="androiddebugkey"; KEY_PASS="android"
  if [ ! -f "$KS" ]; then
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=AEGIS,C=JP" >/dev/null 2>&1
  fi
  OUT="aegis-client.apk"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" --out "$BUILD/$OUT" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$BUILD/$OUT" | head -3
mkdir -p dist
cp "$BUILD/$OUT" "dist/$OUT"
echo "=== build complete ($MODE) ==="
echo "APK: dist/$OUT"
echo "size: $(stat -c%s "dist/$OUT") bytes"
echo "sha256: $(sha256sum "dist/$OUT" | awk '{print $1}')"
