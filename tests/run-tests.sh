#!/usr/bin/env bash
# AEGIS Android - 単体テスト (AegisData の純粋関数を kotlinc でコンパイルして JVM で実行する)
# TestMain は AegisData のみを使うため、本体は AegisData.kt だけをコンパイルする。
# org.json は android.jar のスタブでコンパイルのみ行い、実行時に JSON 関数には触れない。
set -euo pipefail
cd "$(dirname "$0")/.."
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"

# JAVA_HOME 未設定なら VM 内の JDK を使う
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/workspace/tools/jdk-21" ]; then
  JAVA_HOME="$HOME/workspace/tools/jdk-21"
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVA="${JAVA_BIN}java"
command -v "$JAVA" >/dev/null 2>&1 || JAVA=java
[ -n "$JAVA_BIN" ] && export PATH="${JAVA_BIN}:$PATH"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/workspace/tools/android/sdk}}"
AJAR="$SDK/platforms/android-34/android.jar"
[ -f "$AJAR" ] || { echo "android.jar が見つかりません: $AJAR" >&2; exit 1; }

# Kotlin コンパイラ (KOTLINC 環境変数 → VM 内の導入済みコンパイラ → PATH の順に探す)
KOTLINC="${KOTLINC:-}"
if [ -z "$KOTLINC" ] && [ -x "$HOME/workspace/tools/kotlin/bin/kotlinc" ]; then
  KOTLINC="$HOME/workspace/tools/kotlin/bin/kotlinc"
fi
if [ -z "$KOTLINC" ]; then KOTLINC="$(command -v kotlinc || true)"; fi
{ [ -n "$KOTLINC" ] && [ -x "$KOTLINC" ]; } || { echo "kotlinc が見つかりません" >&2; exit 1; }
STDLIB="$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "kotlin-stdlib.jar が見つかりません: $STDLIB" >&2; exit 1; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -nowarn -cp "$AJAR" -d "$OUT" \
  app/src/main/java/jp/aegis/client/AegisData.kt tests/TestMain.kt
"$JAVA" -cp "$OUT:$AJAR:$STDLIB" TestMain
