#!/usr/bin/env bash
# AEGIS Android - オフライン手動ビルド (Gradle/AGPを使わず aapt2 + kotlinc + R8/D8 でAPKを作る)
# v1.8.0 から UI は Jetpack Compose。Compose 対応のため通常のパイプラインと異なる点:
#   * ライブラリ AAR の res をすべて aapt2 compile し、link は --auto-add-overlay で行う
#   * R.txt を持つ AAR は --extra-packages で各ライブラリの R を生成し、アプリの R と一緒に javac する
#   * kotlinc には Compose コンパイラプラグイン (Maven 版 kotlin-compose-compiler-plugin) を -Xplugin で渡す
#     (kotlinc 同梱の compose-compiler.jar は embeddable コンパイラ用で plain kotlinc では使えない)
#   * デキサは build-tools 34.0.0 の d8 ではなく単体 R8 (tools/r8-8.7.18.jar) を使う
#     (同梱 d8 は lifecycle の匿名クラスで NPE クラッシュするため)
#   * Compose のメソッド数は 64k を超えるため classes*.dex をすべて同梱する (マルチデックス)
#   * AAR 内の .so (androidx.graphics.path) は lib/<abi>/ に入れて zipalign -p でページ揃えする
#   * ライブラリのマニフェストはマージしない (AGP の役割)。androidx.startup 等の
#     ライブラリ側コンポーネントは無効のままになるが、Compose UI の動作には影響しない
# 依存ライブラリ: libs/artifacts.txt (tools/resolve.py で解決。バージョンはそちらの roots/pins)
#   libs/ とツール jar (tools/*.jar) は .gitignore 対象のため、無い場合は冒頭で自動取得する
# kotlin-stdlib は kotlinc 配布物 (2.0.21) を使い、libs/ 内のものは使わない
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
JAVAC="${JAVA_BIN}javac"; KEYTOOL="${JAVA_BIN}keytool"; JAVA="${JAVA_BIN}java"
command -v "$JAVAC" >/dev/null 2>&1 || JAVAC=javac
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool
command -v "$JAVA" >/dev/null 2>&1 || JAVA=java
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

# ---- 依存とツール jar の自動取得 ------------------------------------------------
# libs/ と tools/*.jar は .gitignore 対象で、CI の新規チェックアウトには存在しない。
# 無ければここで取得する (初回のみネット必須)。手元に揃っている場合は何もしない。
if [ ! -f libs/artifacts.txt ]; then
  echo "[0/7] 依存を解決しています (tools/resolve.py)"
  python3 tools/resolve.py
fi
if [ ! -f tools/kotlin-compose-compiler-plugin-2.0.21.jar ]; then
  echo "[0/7] Compose コンパイラプラグインを取得しています"
  curl -fsSL -o tools/kotlin-compose-compiler-plugin-2.0.21.jar \
    "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin/2.0.21/kotlin-compose-compiler-plugin-2.0.21.jar"
fi
if [ ! -f tools/r8-8.7.18.jar ]; then
  echo "[0/7] R8 (D8) を取得しています"
  curl -fsSL -o tools/r8-8.7.18.jar \
    "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar"
fi

BUILD=build/manual
AARS=build/aars
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/libres" "$AARS"

echo "[1/7] unit tests"
./tests/run-tests.sh

# ---- ライブラリ jar の収集 ---------------------------------------------------
CP_JARS=()    # コンパイル用クラスパス (クラスを含む jar)
DEX_JARS=()   # dex 入力
AAR_DIRS=()   # 展開済み AAR (artifacts.txt の順。他バージョンの残骸を拾わないよう glob しない)
while IFS= read -r f; do
  case "$f" in kotlin-stdlib*) continue;; esac   # stdlib は kotlinc 配布物を使う
  path="libs/$f"
  [ -f "$path" ] || { echo "ライブラリがありません: $path (tools/resolve.py を実行してください)" >&2; exit 1; }
  if [[ "$f" == *.aar ]]; then
    d="$AARS/${f%.aar}"
    AAR_DIRS+=("$d")
    if [ ! -d "$d" ]; then mkdir -p "$d"; unzip -qo "$path" -d "$d"; fi
    if [ -f "$d/classes.jar" ]; then
      CP_JARS+=("$d/classes.jar"); DEX_JARS+=("$d/classes.jar")
    fi
  else
    # クラスが0個の KMP ルート jar は除外する
    if [ "$(unzip -l "$path" | grep -c '\.class' || true)" -gt 0 ]; then
      CP_JARS+=("$path"); DEX_JARS+=("$path")
    fi
  fi
done < libs/artifacts.txt
CP="$(IFS=:; echo "${CP_JARS[*]}")"
echo "libraries: ${#CP_JARS[@]} jars with classes"

echo "[2/7] aapt2 compile (アプリ + ライブラリのリソース)"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/libres/app.zip"
RES_ZIPS=("$BUILD/libres/app.zip")
EXTRA_PKGS=()
for d in "${AAR_DIRS[@]}"; do
  if [ -d "$d/res" ] && [ -n "$(find "$d/res" -type f -print -quit)" ]; then
    out="$BUILD/libres/$(basename "$d").zip"
    "$BT/aapt2" compile --dir "$d/res" -o "$out"
    RES_ZIPS+=("$out")
  fi
  if [ -f "$d/R.txt" ]; then
    pkg="$(grep -o 'package="[^"]*"' "$d/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
    [ -n "$pkg" ] && EXTRA_PKGS+=("$pkg")
  fi
done
echo "  resource sets: ${#RES_ZIPS[@]}, R packages: ${#EXTRA_PKGS[@]}"

echo "[3/7] aapt2 link (--auto-add-overlay / --extra-packages)"
# 手動ビルドでは manifest に package 属性が要るため、一時コピーに注入して使う
# (ソース側の manifest は AGP の namespace 方式に合わせて package 属性なしのまま)
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="jp.aegis.client"|' \
  app/src/main/AndroidManifest.xml > "$BUILD/AndroidManifest.xml"
EXTRA_ARG=()
if [ "${#EXTRA_PKGS[@]}" -gt 0 ]; then
  EXTRA_ARG=(--extra-packages "$(IFS=:; echo "${EXTRA_PKGS[*]}")")
fi
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" -I "$AJAR" \
  --manifest "$BUILD/AndroidManifest.xml" "${RES_ZIPS[@]}" \
  --java "$BUILD/gen" --auto-add-overlay "${EXTRA_ARG[@]}" \
  --min-sdk-version 26 --target-sdk-version 34

echo "[4/7] kotlinc (+ Compose コンパイラプラグイン) / javac R.java"
COMPOSE_PLUGIN="tools/kotlin-compose-compiler-plugin-2.0.21.jar"
[ -f "$COMPOSE_PLUGIN" ] || { echo "Compose プラグインがありません: $COMPOSE_PLUGIN" >&2; exit 1; }
# kotlinc は aapt2 生成の R.java をシンボル解決のためだけに読み、クラスは出力しないため、
# R.java のクラスは従来どおり javac で別途コンパイルする
"$KOTLINC" -cp "$AJAR:$CP" -d "$BUILD/classes" \
  -Xplugin="$COMPOSE_PLUGIN" \
  $(find app/src/main/java -name '*.kt') $(find "$BUILD/gen" -name '*.java')
"$JAVAC" --release 17 -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" \
  $(find "$BUILD/gen" -name '*.java')

echo "[5/7] d8 (単体 R8 8.7.18 / マルチデックス)"
R8_JAR="tools/r8-8.7.18.jar"
[ -f "$R8_JAR" ] || { echo "R8 がありません: $R8_JAR" >&2; exit 1; }
"$JAVA" -Xmx3g -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 26 --lib "$AJAR" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class') "${DEX_JARS[@]}" "$STDLIB"
ls "$BUILD/dex"

echo "[6/7] package (dex + ネイティブライブラリ) + zipalign"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
( cd "$BUILD/dex" && zip -q -uj ../app-with-dex.apk classes*.dex )
# AAR 内の .so は lib/<abi>/ に未圧縮で入れ、zipalign -p でページ揃えする
while IFS= read -r so; do
  rel="${so#*jni/}"   # <abi>/<lib>.so
  mkdir -p "$BUILD/nativelib/lib/$(dirname "$rel")"
  cp "$so" "$BUILD/nativelib/lib/$rel"
done < <(find "${AAR_DIRS[@]}" -path '*/jni/*' -name '*.so')
if [ -d "$BUILD/nativelib" ]; then
  ( cd "$BUILD/nativelib" && zip -q -0 -r ../app-with-dex.apk lib )
fi
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
