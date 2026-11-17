# 🛡️ AEGIS Android クライアント

地震・津波・気象 自律アシスタント **AEGIS** の Android アプリ（クライアント）です。
Java で実装し、外部ライブラリ依存ゼロ（Android標準の `org.json` / `HttpURLConnection` のみ）。

- パッケージ: `jp.aegis.client` / アプリ名: AEGIS
- 対応: Android 8.0 (API 26) 以上、target API 34
- 地点: 高崎（群馬）・大宮（埼玉）— PC版 AEGIS と同じ初期設定

## 2つの動作モード

### 1. 直接取得モード（既定・推奨）
スマホ自体が公式ソースから直接取得します。PCサーバー不要で、どこでも使えます。
- 地震・津波: P2P地震情報 API（気象庁データ）
- 天気予報・警報: 気象庁 bosai
- 現在の気象・降水確率: Open-Meteo

### 2. AEGISサーバーモード（クライアントとして接続）
PC等で動いている AEGIS の `serve`（Python版 / Java版どちらでも可）の `/api/briefing` に接続して表示します。
設定タブでモードを切り替え、サーバーURLを入れて保存します。

- 同じWi-FiのPC: `http://192.168.1.10:8765`（PCのLANアドレス）
- AndroidエミュレータからPC: `http://10.0.2.2:8765`
- ※ サーバーモード用に平文HTTPを許可しています（`usesCleartextTraffic`）。自宅LAN内での利用を想定しています

## 画面
- **ブリーフィング**: 高崎・大宮の天気/現在の気象/警報 + 津波情報 + 最新地震3件（サーバーモードではサーバーのブリーフィング）
- **地震情報**: 最新15件の地震（震度・マグニチュード・深さ、重要度つき）+ 津波情報
- **設定**: モード切替とサーバーURL（SharedPreferencesに保存）

## インストール
配布APK `aegis-client.apk` をスマホで開いてインストールします（デバッグ署名のため、初回は「提供元不明アプリ」の許可が必要な場合があります）。

## リポジトリ
https://github.com/i-mabu/aegis-android （Private）

## ビルド方法

### 方法A: オフライン手動ビルド（検証済み）
Gradle/AGPを使わず、Android SDK のツールだけでAPKを作ります。
```bash
# 必要: JDK 17+ と Android SDK (platforms;android-34 / build-tools;34.0.0)
ANDROID_SDK_ROOT=/path/to/android-sdk ./build-apk.sh
# -> dist/aegis-client.apk (デバッグ署名)
```
手順は aapt2 compile/link → javac (--release 17) → d8 → zipalign → apksigner です。

### 方法B: Gradle / Android Studio
Android Studio でこのフォルダを開くか、コマンドラインで:
```bash
./gradlew assembleDebug   # 初回は Gradle 8.9 と AGP 8.7.3 のダウンロードが必要
```
- AGP 8.7.3 / compileSdk 34 / minSdk 26 / Java 17
- `local.properties` に `sdk.dir=/path/to/android-sdk` を書いてください（git管理外）

## 検証結果（2026-11-17）
- ✅ 方法AでAPKビルド成功: `aegis-client.apk`（25KB）
- ✅ `aapt2 dump badging`: package `jp.aegis.client`, versionCode 1 / versionName 1.0, minSdk 26, targetSdk 34, INTERNET権限, 起動Activity = MainActivity
- ✅ `apksigner verify`: v2 / v3 スキームで検証OK
- ⚠️ 実機での動作確認は未実施です（この環境には実機/エミュレータがありません）。スマホにインストールして、直接取得モードで最新情報が表示されるか確認してください。表示がおかしい場合は、スクリーンショットをもらえれば直します。

## 構成
```
aegis-android/
  README.md / build-apk.sh / build.gradle / settings.gradle / gradle.properties
  app/
    build.gradle
    src/main/
      AndroidManifest.xml
      java/jp/aegis/client/
        MainActivity.java   画面・タブ・設定保存
        AegisData.java      取得と整形（P2P/気象庁/Open-Meteo/AEGISサーバー）
      res/                  レイアウト・アイコン・文字列
  dist/aegis-client.apk     ビルド済みAPK（デバッグ署名）
```

## v1.1 の変更点（2026-11-17）
- 警報・注意報の詳細表示: 見出し文だけでなく、発表中の名称一覧（コード表から復元）を表示
- 自動更新: アプリ表示中は5分ごとに自動で再取得（設定タブ表示中は除く）
- 接続テスト: 設定タブにボタン追加。入力中のサーバーURLへ /api/history で疎通確認
- 取得リトライ: HTTP取得が一時失敗/5xx のとき最大3回まで自動リトライ
- 署名の安定化: デバッグキーストアをリポジトリ直下に固定し、ビルドごとに署名が変わらないようにした
  - ⚠ v1.0 のAPKとは署名が異なるため、v1.1 の初回インストール時は v1.0 をアンインストールしてください（v1.1 以降は上書き更新できます）

## v1.2 の変更点（2026-11-17、実機スクリーンショットを受けて）
- デザイン刷新: テキスト表示をやめ、カードUIに変更（セクションごとのカード＋重要度カラーのドット）
- 地震情報は震度バッジ付きカードで表示（震度で色分け）。[INFO] 等のタグ表記と区切り線を廃止
- エラー表示を日本語化: 「Read timed out」等の生メッセージを出さず「通信がタイムアウトしました」等に変換
- 天気文の整形: 全角スペース区切りを詰めて表示。警報は発表中のみ表示（解除見出しのノイズを除去）
- 取得の並列化: 2地点の天気を並列取得し、表示までの時間を短縮。読み取りタイムアウトを 15秒→25秒に延長
- v1.1 と署名が同じため、v1.1 からは上書きインストールできます

## v1.3 の変更点（2026-11-17、取得の軽量化）
- 全並列化: 天気の3要素（予報・現在・警報）を地点内でも並列取得に変更（従来は地点間並列＋地点内は直列）
- gzip対応: 応答を圧縮転送し、気象庁JSONなどの転送量を削減
- キャッシュ表示: 前回表示した内容を保存し、起動時はまずキャッシュを即表示→裏で最新に更新
- リトライをUI向けに調整: 最大2回にし、タイムアウトも短めに（待ち時間の暴走防止）
