package jp.aegis.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// ===== 1. AegisData (取得・整形: P2P地震情報 / 気象庁 / Open-Meteo / AEGISサーバー) =====
public final class AegisData {
    private AegisData() {}

    public static final class Location {
        public final String name;
        public final double lat, lon;
        public final String prefCode, areaName;
        public Location(String name, double lat, double lon, String prefCode, String areaName) {
            this.name = name; this.lat = lat; this.lon = lon; this.prefCode = prefCode; this.areaName = areaName;
        }
    }

    public static final Location TAKASAKI = new Location("高崎", 36.3219, 139.0023, "100000", "南部");
    public static final Location OMIYA = new Location("大宮", 35.9064, 139.6239, "110000", "南部");
    public static final Location[] LOCATIONS = {TAKASAKI, OMIYA};

    // ユーザーが追加した任意地点 (設定画面から MainActivity が読み込んで設定する)
    public static volatile java.util.List<Location> customLocations = new java.util.ArrayList<>();

    public static Location[] effectiveLocations() {
        java.util.List<Location> all = new java.util.ArrayList<>(java.util.Arrays.asList(LOCATIONS));
        all.addAll(customLocations);
        return all.toArray(new Location[0]);
    }

    // 並列取得用の共有プール (天気の3要素や複数地点を同時に取る)
    static final java.util.concurrent.ExecutorService FETCH_POOL = java.util.concurrent.Executors.newCachedThreadPool();

    // ----- 1.1 HTTP -----
    public static String fetch(String urlStr) throws Exception {
        // UI表示用: リトライは最大2回まで (待ち時間の暴走を防ぐ)。gzip で転送量も削減する
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            try {
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "AEGIS-Android/1.5");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Accept-Encoding", "gzip");
                int code = conn.getResponseCode();
                if (code == 200) {
                    java.io.InputStream in = conn.getInputStream();
                    if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                        in = new java.util.zip.GZIPInputStream(in);
                    }
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    return sb.toString();
                }
                if (code < 500) throw new IllegalStateException("HTTP " + code);
                last = new IllegalStateException("HTTP " + code);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                last = e;
            } finally {
                conn.disconnect();
            }
            if (attempt < 1) Thread.sleep(1000L);
        }
        throw last != null ? last : new IllegalStateException("fetch failed");
    }

    // 例外をユーザー向けの短い日本語に変換する (生の英語メッセージは出さない)
    public static String friendlyError(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.SocketTimeoutException || m.contains("timed out")) return "通信がタイムアウトしました";
        if (m.startsWith("HTTP ")) return "サーバーエラー（" + m + "）";
        if (e instanceof java.net.UnknownHostException) return "サーバーに接続できませんでした";
        if (m.contains("abort") || m.contains("reset") || m.contains("ECONN")) return "通信が切断されました";
        return "通信エラーが発生しました";
    }

    // ===== 1.2 構造化データ (カードUI用) =====
    public static final class Section {
        public final String title, body, level; // level: info | ok | warn | danger
        public Section(String title, String body, String level) {
            this.title = title; this.body = body; this.level = level;
        }
    }

    public static final class Quake {
        public final String place, detail, sev;
        public final int maxScale;
        public final double lat, lon; // 震源座標 (0,0 は不明)
        public final long originMillis; // 発生時刻 (epoch ミリ秒, 0 は不明)
        public Quake(String place, String detail, String sev, int maxScale) {
            this(place, detail, sev, maxScale, 0, 0, 0);
        }
        public Quake(String place, String detail, String sev, int maxScale, double lat, double lon) {
            this(place, detail, sev, maxScale, lat, lon, 0);
        }
        public Quake(String place, String detail, String sev, int maxScale, double lat, double lon, long originMillis) {
            this.place = place; this.detail = detail; this.sev = sev; this.maxScale = maxScale;
            this.lat = lat; this.lon = lon; this.originMillis = originMillis;
        }
    }

    // ===== 2. 震度・重要度 =====
    public static String scaleToStr(int scale) {
        if (scale <= 0) return "不明"; // P2P の -1 は「震度不明」
        switch (scale) {
            case 10: return "1";
            case 20: return "2";
            case 30: return "3";
            case 40: return "4";
            case 45: return "5弱";
            case 50: return "5強";
            case 55: return "6弱";
            case 60: return "6強";
            case 70: return "7";
            default: return String.valueOf(scale);
        }
    }

    public static String severityEmoji(String sev) {
        switch (sev == null ? "" : sev) {
            case "CRITICAL": return "🔴";
            case "HIGH": return "🟠";
            case "MEDIUM": return "🟡";
            case "LOW": return "🟢";
            default: return "ℹ️";
        }
    }

    private static String severityOf(int scale, double mag) {
        if (scale >= 45 || mag >= 6.0) return "CRITICAL";
        if (scale >= 40 || mag >= 5.0) return "HIGH";
        if (scale >= 30 || mag >= 4.5) return "MEDIUM";
        if (scale >= 20) return "LOW";
        return "INFO";
    }

    // ===== 3. 地震・津波 (P2P) =====
    public static java.util.List<Quake> quakeItems(int limit) throws Exception {
        JSONArray data = new JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=551&limit=" + limit));
        java.util.List<Quake> out = new java.util.ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            JSONObject eq = item.optJSONObject("earthquake");
            if (eq == null) continue;
            JSONObject hypo = eq.optJSONObject("hypocenter");
            if (hypo == null) hypo = new JSONObject();
            int maxScale = eq.optInt("maxScale", -1);
            double mag = hypo.optDouble("magnitude", -1);
            String time = eq.optString("time");
            // "2026/11/17 10:40:00" -> "11/17 10:40"
            String shortTime = time;
            if (time.length() >= 16) shortTime = time.substring(5, 10) + " " + time.substring(11, 16);
            // P2P のセンチネル値 (-1) は「不明」として扱う (深さ -1 は気象庁の「ごく浅い」)
            String magStr = mag < 0 ? "M不明" : "M" + mag;
            int depthVal = hypo.optInt("depth", -999);
            String depthStr = depthVal == -1 ? "深さごく浅い" : (depthVal <= -999 ? "深さ不明" : "深さ約" + depthVal + "km");
            String detail = shortTime + "発生 / " + magStr + " / " + depthStr;
            // 津波の見通しを日本語で明示
            String tsunami = eq.optString("domesticTsunami", "None");
            switch (tsunami) {
                case "Forecast": detail += " / 若干の海面変動の可能性あり"; break;
                case "Advisory": detail += " / 津波注意報レベル"; break;
                case "Warning": detail += " / 津波警報レベル"; break;
                case "Unknown": detail += " / 津波の有無は確認中"; break;
                default: detail += " / 津波の心配なし";
            }
            // 最大震度を観測した地点と、地元（群馬・埼玉）の震度を points から拾う
            JSONArray points = item.optJSONArray("points");
            if (points != null) {
                String topAddr = "";
                int topCount = 0;
                java.util.Map<String, Integer> localMax = new java.util.LinkedHashMap<>();
                for (int j = 0; j < points.length(); j++) {
                    JSONObject p = points.getJSONObject(j);
                    int sc = p.optInt("scale", 0);
                    if (sc == maxScale) {
                        topCount++;
                        if (topAddr.isEmpty()) topAddr = p.optString("addr", p.optString("pref", ""));
                    }
                    String pref = p.optString("pref", "");
                    if (pref.equals("群馬県") || pref.equals("埼玉県")) {
                        Integer cur = localMax.get(pref);
                        if (cur == null || sc > cur) localMax.put(pref, sc);
                    }
                }
                if (!topAddr.isEmpty()) {
                    detail += " / 最大震度観測: " + topAddr + (topCount > 1 ? " ほか" + (topCount - 1) + "地点" : "");
                }
                if (!localMax.isEmpty()) {
                    StringBuilder lsb = new StringBuilder(" / 地元震度:");
                    for (java.util.Map.Entry<String, Integer> e : localMax.entrySet()) {
                        lsb.append(" ").append(e.getKey()).append(" 最大").append(scaleToStr(e.getValue()));
                    }
                    detail += lsb.toString();
                }
            }
            double lat = hypo.optDouble("latitude", 0);
            double lon = hypo.optDouble("longitude", 0);
            out.add(new Quake(hypo.optString("name", "不明"), detail, severityOf(maxScale, mag), maxScale, lat, lon, parseP2pTime(time)));
        }
        return out;
    }

    public static String earthquakesText(int limit) throws Exception {
        java.util.List<Quake> items = quakeItems(limit);
        if (items.isEmpty()) return "最近の地震情報はありません。\n";
        StringBuilder sb = new StringBuilder();
        for (Quake q : items) {
            sb.append(severityEmoji(q.sev)).append(" 震度").append(scaleToStr(q.maxScale)).append(" ").append(q.place)
              .append("\n").append(q.detail).append("\n");
        }
        return sb.toString();
    }

    public static String tsunamiText() throws Exception {
        JSONArray data = new JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=552&limit=5"));
        if (data.length() == 0) {
            return "🌊 津波情報\n現在、発表されている津波警報・注意報はありません。\n強い地震を感じたら、すぐ高台へ避難してください。\n";
        }
        StringBuilder sb = new StringBuilder("🌊 津波情報（最新）\n");
        JSONObject item = data.getJSONObject(0);
        boolean cancelled = item.optBoolean("cancelled");
        JSONArray areas = item.optJSONArray("areas");
        // 警戒レベルは「全エリア中の最大 grade」で判定する (先頭エリアだけで判断すると過小評価になる)
        String grade = "";
        int best = -1;
        StringBuilder names = new StringBuilder();
        if (areas != null) {
            for (int i = 0; i < areas.length(); i++) {
                JSONObject a = areas.getJSONObject(i);
                String g = a.optString("grade");
                int rank = "MajorWarning".equals(g) ? 3 : "Warning".equals(g) ? 2 : "Watch".equals(g) ? 1 : 0;
                if (rank > best) { best = rank; grade = g; }
                if (names.length() > 0) names.append("、");
                names.append(a.optString("name"));
                // 予想高さ・第1波到達予想があれば併記する
                JSONObject mh = a.optJSONObject("maxHeight");
                if (mh != null && !mh.optString("description").isEmpty()) {
                    names.append("（予想 ").append(mh.optString("description")).append("）");
                }
                JSONObject fh = a.optJSONObject("firstHeight");
                if (fh != null && !fh.optString("arrivalTime").isEmpty()) {
                    String at = fh.optString("arrivalTime");
                    names.append("（第1波到達予想 ").append(at.length() >= 16 ? at.substring(11, 16) : at).append("）");
                }
            }
        }
        if (cancelled) sb.append("津波警報・注意報は解除されました。対象: ").append(names);
        else if ("MajorWarning".equals(grade)) sb.append("🔴 大津波警報: ").append(names).append("\nただちに高台・津波避難ビルへ避難してください。");
        else if ("Warning".equals(grade)) sb.append("🔴 津波警報: ").append(names).append("\nただちに高台・津波避難ビルへ避難してください。");
        else if ("Watch".equals(grade)) sb.append("🟠 津波注意報: ").append(names).append("\n海から上がり、海岸から離れてください。");
        else sb.append("津波予報/情報: ").append(names);
        sb.append("\n発表: ").append(item.optString("time")).append("\n");
        return sb.toString();
    }

    // 緊急地震速報 (P2P 554): 実データのある最新報が1時間以内なら表示用テキストを返す。それ以外は null
    public static String eewText() {
        try {
            JSONArray data = new JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=554&limit=3"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                JSONObject eq = item.optJSONObject("earthquake");
                JSONArray areas = item.optJSONArray("areas");
                if (eq == null && areas == null) continue; // メタデータのみのレコードは読み飛ばす
                String time = item.optString("time");
                if (time.length() < 19) continue;
                java.util.Date t = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).parse(time.substring(0, 19));
                if (t == null || System.currentTimeMillis() - t.getTime() > 60 * 60 * 1000L) return null; // 古い EEW は出さない
                if (item.optBoolean("isCancel")) return "緊急地震速報は取り消されました（" + time.substring(5, 16) + "）";
                StringBuilder sb = new StringBuilder();
                if (item.optBoolean("isFinal")) sb.append("（最終報）");
                if (eq != null) {
                    JSONObject hypo = eq.optJSONObject("hypocenter");
                    if (hypo != null) sb.append("震源 ").append(hypo.optString("name", "不明"));
                    int est = eq.optInt("maxScale", -1);
                    if (est > 0) sb.append(" / 推定最大震度 ").append(scaleToStr(est));
                }
                if (areas != null && areas.length() > 0) {
                    java.util.LinkedHashSet<String> prefs = new java.util.LinkedHashSet<>();
                    for (int j = 0; j < areas.length() && prefs.size() < 6; j++) {
                        String p = areas.getJSONObject(j).optString("pref");
                        if (!p.isEmpty()) prefs.add(p);
                    }
                    if (!prefs.isEmpty()) sb.append("\n対象: ").append(String.join("、", prefs));
                }
                sb.append("\n強い揺れに備えてください。");
                return sb.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // P2P の時刻文字列 "2026/11/17 10:40:00" を epoch ミリ秒にする (失敗時 0)
    public static long parseP2pTime(String time) {
        if (time == null || time.length() < 19) return 0;
        try {
            java.util.Date d = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).parse(time.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // 2点間の球面距離 (km)
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    // ===== 4. 気象 (気象庁 + Open-Meteo) =====
    private static final Map<String, String> WMO = new HashMap<>();
    static {
        WMO.put("0", "快晴"); WMO.put("1", "晴れ"); WMO.put("2", "一部曇り"); WMO.put("3", "曇り");
        WMO.put("45", "霧"); WMO.put("48", "霧"); WMO.put("51", "霧雨"); WMO.put("53", "霧雨"); WMO.put("55", "霧雨");
        WMO.put("61", "雨"); WMO.put("63", "雨"); WMO.put("65", "大雨"); WMO.put("71", "雪"); WMO.put("73", "雪"); WMO.put("75", "大雪");
        WMO.put("80", "にわか雨"); WMO.put("81", "にわか雨"); WMO.put("82", "激しい雨"); WMO.put("95", "雷雨");
        WMO.put("56", "着氷性の霧雨"); WMO.put("57", "着氷性の霧雨"); WMO.put("66", "着氷性の雨"); WMO.put("67", "着氷性の雨");
        WMO.put("77", "雪あられ"); WMO.put("85", "雪"); WMO.put("86", "雪"); WMO.put("96", "雹を伴う雷雨"); WMO.put("99", "雹を伴う雷雨");
    }

    public static String weatherText(Location loc) {
        // 気象庁予報を先に取得する: 予報テキストに加え、警報の地域判定に使う
        // 「一次細分区域コード」をこのJSONから得る (警報JSONの areas は名称を持たずコードのみのため)
        String[] fc = forecastPart(loc); // [0]=予報テキスト [1]=細分区域コード (失敗時 "")
        java.util.concurrent.Future<String> f2 = FETCH_POOL.submit(() -> currentPart(loc));
        java.util.concurrent.Future<String> f3 = FETCH_POOL.submit(() -> warningPart(loc, fc[1]));
        StringBuilder sb = new StringBuilder();
        sb.append(fc[0]);
        try { sb.append(f2.get(50, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (Exception e) { sb.append("（現在の気象を取得できませんでした）\n"); }
        try { sb.append(f3.get(50, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (Exception ignored) {}
        return sb.toString();
    }

    // 気象庁予報: [予報テキスト, 一次細分区域コード] を返す
    private static String[] forecastPart(Location loc) {
        StringBuilder sb = new StringBuilder();
        String areaCode = "";
        try {
            JSONArray arr = new JSONArray(fetch("https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.prefCode + ".json"));
            JSONObject first = arr.getJSONObject(0);
            JSONArray series = first.getJSONArray("timeSeries");
            JSONObject ts0 = series.getJSONObject(0);
            JSONArray areas = ts0.getJSONArray("areas");
            JSONObject chosen = null;
            boolean matched = false;
            for (int i = 0; i < areas.length(); i++) {
                JSONObject a = areas.getJSONObject(i);
                if (loc.areaName.equals(a.getJSONObject("area").optString("name"))) { chosen = a; matched = true; break; }
            }
            if (chosen == null && areas.length() > 0) chosen = areas.getJSONObject(0);
            if (chosen != null) {
                areaCode = chosen.getJSONObject("area").optString("code");
                JSONArray weathers = chosen.getJSONArray("weathers");
                sb.append("今日： ").append(cleanWeather(weathers.optString(0)));
                if (weathers.length() > 1) sb.append("\n明日： ").append(cleanWeather(weathers.optString(1)));
                if (weathers.length() > 2) sb.append("\n明後日： ").append(cleanWeather(weathers.optString(2)));
                sb.append("\n");
                if (!matched) sb.append("（※地域名が一致しないため、県内の先頭地域の予報を表示しています）\n");
            }
            // 降水確率 (timeSeries[1]: 6時間ごとの pops) — 細分区域コードで照合
            for (int s = 0; s < series.length(); s++) {
                JSONObject ts = series.getJSONObject(s);
                JSONArray tas = ts.optJSONArray("areas");
                if (tas == null || tas.length() == 0) continue;
                if (!tas.getJSONObject(0).has("pops")) continue;
                JSONObject pa = null;
                for (int i = 0; i < tas.length(); i++) {
                    JSONObject a = tas.getJSONObject(i);
                    if (areaCode.equals(a.getJSONObject("area").optString("code"))) { pa = a; break; }
                }
                if (pa == null) pa = tas.getJSONObject(0);
                JSONArray pops = pa.optJSONArray("pops");
                JSONArray defs = ts.optJSONArray("timeDefines");
                if (pops != null && defs != null) {
                    StringBuilder pb = new StringBuilder("降水確率： ");
                    int shown = 0;
                    String prevDate = "";
                    for (int i = 0; i < pops.length() && shown < 6; i++) {
                        String def = defs.optString(i); // 2026-11-17T12:00:00+09:00
                        if (def.length() < 13) continue;
                        int hour = Integer.parseInt(def.substring(11, 13));
                        String date = def.substring(5, 10).replace('-', '/'); // 11/17
                        if (shown > 0) pb.append(" / ");
                        if (!date.equals(prevDate)) { pb.append(date).append(" "); prevDate = date; }
                        pb.append(hour).append("〜").append((hour + 6) % 24).append("時 ").append(pops.optString(i)).append("%");
                        shown++;
                    }
                    sb.append(pb).append("\n");
                }
                break;
            }
            // 気温 (timeSeries[2]: temps は [今日の最低, 今日の最高, 明日の最低, 明日の最高])
            for (int s = 0; s < series.length(); s++) {
                JSONObject ts = series.getJSONObject(s);
                JSONArray tas = ts.optJSONArray("areas");
                if (tas == null || tas.length() == 0) continue;
                if (!tas.getJSONObject(0).has("temps")) continue;
                JSONObject ta = tas.getJSONObject(0);
                JSONArray temps = ta.optJSONArray("temps");
                if (temps != null && temps.length() >= 4) {
                    String city = ta.getJSONObject("area").optString("name");
                    sb.append("気温（").append(city).append("）： 今日 ")
                      .append(temps.optString(0)).append("〜").append(temps.optString(1)).append("℃ / 明日 ")
                      .append(temps.optString(2)).append("〜").append(temps.optString(3)).append("℃\n");
                }
                break;
            }
        } catch (Exception e) {
            sb.append("（天気予報を取得できませんでした：").append(friendlyError(e)).append("）\n");
        }
        return new String[]{sb.toString(), areaCode};
    }

    // WMO 天気コード → 絵文字 (時間別・週間予報のコンパクト表示用)
    public static String weatherEmoji(int code) {
        switch (code) {
            case 0: case 1: return "☀";
            case 2: return "⛅";
            case 3: return "☁";
            case 45: case 48: return "🌫";
            case 51: case 53: case 55: case 80: case 81: case 82: return "🌦";
            case 61: case 63: case 65: case 66: case 67: return "🌧";
            case 56: case 57: case 71: case 73: case 75: case 77: case 85: case 86: return "🌨";
            case 95: case 96: case 99: return "⛈";
            default: return "・";
        }
    }

    private static String currentPart(Location loc) {
        StringBuilder sb = new StringBuilder();
        // Open-Meteo: 現在・時間別・日別(7日) を1リクエストで統合取得する
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + loc.lat + "&longitude=" + loc.lon
                    + "&current=temperature_2m,weather_code,wind_speed_10m"
                    + "&hourly=temperature_2m,precipitation_probability,weather_code"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&forecast_days=7&timezone=Asia%2FTokyo";
            JSONObject root = new JSONObject(fetch(url));
            JSONObject cur = root.getJSONObject("current");
            JSONObject daily = root.getJSONObject("daily");
            String desc = WMO.getOrDefault(String.valueOf(cur.optInt("weather_code")), "不明");
            double pop = daily.getJSONArray("precipitation_probability_max").optDouble(0, -1);
            sb.append("現在：").append(desc).append(" ").append(cur.optDouble("temperature_2m")).append("℃ / 風 ")
              .append(cur.optDouble("wind_speed_10m")).append("km/h / 今日 ")
              .append(daily.getJSONArray("temperature_2m_min").optDouble(0)).append("〜")
              .append(daily.getJSONArray("temperature_2m_max").optDouble(0)).append("℃ / 降水確率 最大")
              .append(pop < 0 ? "?" : String.valueOf((long) pop)).append("%\n");
            if (pop >= 80) sb.append("▶ 降水確率が高め。大きめの傘が安心です\n");
            // 明日の気温・降水確率 (Open-Meteo daily[1])
            JSONArray dCodes = daily.optJSONArray("weather_code");
            if (dCodes != null && dCodes.length() > 1) {
                double pop2 = daily.getJSONArray("precipitation_probability_max").optDouble(1, -1);
                sb.append("明日（Open-Meteo）: ").append(WMO.getOrDefault(String.valueOf(dCodes.optInt(1)), "不明")).append(" ")
                  .append(daily.getJSONArray("temperature_2m_min").optDouble(1)).append("〜")
                  .append(daily.getJSONArray("temperature_2m_max").optDouble(1)).append("℃ / 降水確率 最大")
                  .append(pop2 < 0 ? "?" : String.valueOf((long) pop2)).append("%\n");
            }
            // 時間別 (今日の残り + 明日を3時間刻み)
            JSONObject hourly = root.optJSONObject("hourly");
            if (hourly != null) {
                String h = hourlyText(hourly);
                if (!h.isEmpty()) sb.append(h);
            }
            // 週間予報 (daily 7日を1〜2行に)
            String w = weeklyText(daily);
            if (!w.isEmpty()) sb.append(w);
        } catch (Exception e) {
            sb.append("（現在の気象を取得できませんでした：").append(friendlyError(e)).append("）\n");
        }
        return sb.toString();
    }

    // 時間別予報: 現在時刻以降、今日の残り+明日分を3時間刻みでコンパクトに整形する
    public static String hourlyText(JSONObject hourly) {
        try {
            JSONArray times = hourly.optJSONArray("time");
            JSONArray temps = hourly.optJSONArray("temperature_2m");
            JSONArray pops = hourly.optJSONArray("precipitation_probability");
            JSONArray codes = hourly.optJSONArray("weather_code");
            if (times == null || temps == null || codes == null) return "";
            String nowIso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH", java.util.Locale.JAPAN).format(new java.util.Date());
            StringBuilder sb = new StringBuilder("時間別： ");
            String prevDate = "";
            int shown = 0;
            for (int i = 0; i < times.length() && shown < 12; i++) {
                String t = times.optString(i); // 2026-11-17T15:00
                if (t.length() < 13) continue;
                if (t.substring(0, 13).compareTo(nowIso) < 0) continue; // 過去の時間帯は出さない
                int hour = Integer.parseInt(t.substring(11, 13));
                if (hour % 3 != 0) continue;
                String date = t.substring(5, 10).replace('-', '/'); // 11/17
                if (!date.equals(prevDate)) {
                    if (shown > 0) sb.append("\n　");
                    sb.append(date).append(" ");
                    prevDate = date;
                } else if (shown > 0) {
                    sb.append(" / ");
                }
                sb.append(hour).append("時 ").append(weatherEmoji(codes.optInt(i)))
                  .append(Math.round(temps.optDouble(i))).append("℃");
                double p = pops != null ? pops.optDouble(i, -1) : -1;
                if (p >= 0) sb.append(" 降水").append(Math.round(p)).append("%");
                shown++;
            }
            if (shown == 0) return "";
            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // 週間予報: daily (7日) を「週間: 11/18 ☀ 9〜21℃ / …」形式に整形する
    public static String weeklyText(JSONObject daily) {
        try {
            JSONArray times = daily.optJSONArray("time");
            JSONArray codes = daily.optJSONArray("weather_code");
            JSONArray maxs = daily.optJSONArray("temperature_2m_max");
            JSONArray mins = daily.optJSONArray("temperature_2m_min");
            if (times == null || codes == null || maxs == null || mins == null || times.length() == 0) return "";
            StringBuilder sb = new StringBuilder("週間： ");
            for (int i = 0; i < times.length(); i++) {
                String t = times.optString(i); // 2026-11-18
                if (t.length() < 10) continue;
                if (i > 0) sb.append(" / ");
                sb.append(t.substring(5, 10).replace('-', '/')).append(" ")
                  .append(weatherEmoji(codes.optInt(i))).append(" ")
                  .append(Math.round(mins.optDouble(i))).append("〜").append(Math.round(maxs.optDouble(i))).append("℃");
            }
            sb.append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // 警報・注意報: 警報JSONの areas は名称を持たず「一次細分区域コード」のみのため、
    // 予報JSONから得た areaCode で areaTypes[0] (一次細分区域) を照合する。
    // コードが得られなかった場合は県内全域を対象にし、その旨を注記する (無言で欠落させない)。
    private static String warningPart(Location loc, String areaCode) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject warn = new JSONObject(fetch("https://www.jma.go.jp/bosai/warning/data/warning/" + loc.prefCode + ".json"));
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            JSONArray areaTypes = warn.optJSONArray("areaTypes");
            boolean prefWide = areaCode == null || areaCode.isEmpty();
            if (areaTypes != null && areaTypes.length() > 0) {
                JSONArray areas = areaTypes.getJSONObject(0).optJSONArray("areas");
                if (areas != null) {
                    for (int j = 0; j < areas.length(); j++) {
                        JSONObject area = areas.getJSONObject(j);
                        if (!prefWide && !areaCode.equals(area.optString("code"))) continue;
                        JSONArray ws = area.optJSONArray("warnings");
                        if (ws == null) continue;
                        for (int k = 0; k < ws.length(); k++) {
                            JSONObject w = ws.getJSONObject(k);
                            if ("解除".equals(w.optString("status"))) continue;
                            String code = w.optString("code");
                            if (code.isEmpty()) continue;
                            String nm = WARNING_CODES.get(code);
                            // コード表にない警報も無言で落とさず、コード付きで表示する
                            names.add(nm != null ? nm : "その他の警報・注意報（コード" + code + "）");
                        }
                    }
                }
            }
            if (!names.isEmpty()) {
                sb.append("⚠ 発表中： ").append(String.join("、", names));
                String rd = warn.optString("reportDatetime"); // 2026-11-17T11:00:00+09:00
                if (rd.length() >= 16) sb.append("（").append(rd.substring(5, 10).replace('-', '/')).append(" ").append(rd.substring(11, 16)).append("発表）");
                sb.append("\n");
                if (prefWide) sb.append("（※地域を特定できなかったため、県内全域の警報・注意報です）\n");
                String headline = warn.optString("headlineText");
                if (!headline.isEmpty() && !headline.contains("解除")) {
                    sb.append("　").append(headline).append("\n");
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    // 気象庁の天気文は全角スペース区切りで間延びして見えるため整形する
    private static String cleanWeather(String s) {
        return s.replace('　', ' ').replaceAll(" +", " ").trim();
    }

    // 気象庁 警報・注意報コード表 (主要なもの)
    static final Map<String, String> WARNING_CODES = new HashMap<>();
    static {
        WARNING_CODES.put("02", "暴風雪警報"); WARNING_CODES.put("03", "大雨警報");
        WARNING_CODES.put("04", "洪水警報"); WARNING_CODES.put("05", "暴風警報");
        WARNING_CODES.put("06", "大雪警報"); WARNING_CODES.put("07", "波浪警報");
        WARNING_CODES.put("08", "高潮警報"); WARNING_CODES.put("10", "大雨注意報");
        WARNING_CODES.put("12", "大雪注意報"); WARNING_CODES.put("13", "風雪注意報");
        WARNING_CODES.put("14", "雷注意報"); WARNING_CODES.put("15", "強風注意報");
        WARNING_CODES.put("16", "波浪注意報"); WARNING_CODES.put("18", "洪水注意報");
        WARNING_CODES.put("19", "高潮注意報"); WARNING_CODES.put("20", "濃霧注意報");
        WARNING_CODES.put("21", "乾燥注意報"); WARNING_CODES.put("22", "なだれ注意報");
        WARNING_CODES.put("23", "低温注意報"); WARNING_CODES.put("24", "霜注意報");
        WARNING_CODES.put("25", "着氷注意報"); WARNING_CODES.put("26", "着雪注意報");
        WARNING_CODES.put("27", "融雪注意報"); WARNING_CODES.put("29", "氷結注意報");
        WARNING_CODES.put("32", "暴風雪特別警報"); WARNING_CODES.put("33", "大雨特別警報");
        WARNING_CODES.put("35", "暴風特別警報"); WARNING_CODES.put("36", "高潮特別警報");
        WARNING_CODES.put("37", "波浪特別警報"); WARNING_CODES.put("38", "大雪特別警報");
    }

    // ===== 5. ブリーフィング =====
    public static String briefingDirect() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Section s : briefingSections()) sb.append(s.title).append("\n").append(s.body).append("\n");
        return sb.toString();
    }

    // カードUI用: 天気・津波・地震をすべて並列取得し、表示順に組み立てる
    public static java.util.List<Section> briefingSections() {
        java.util.List<Section> out = new java.util.ArrayList<>();
        Location[] locs = effectiveLocations();
        java.util.concurrent.Future<String> tsunamiF = FETCH_POOL.submit(() -> tsunamiText());
        java.util.concurrent.Future<String> typhoonF = FETCH_POOL.submit(() -> typhoonText());
        java.util.concurrent.Future<String> eewF = FETCH_POOL.submit(() -> eewText());
        java.util.concurrent.Future<java.util.List<Quake>> quakesF = FETCH_POOL.submit(() -> quakeItems(3));
        java.util.List<Section> weatherSections = new java.util.ArrayList<>();
        {
            java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
            for (Location loc : locs) {
                final Location l = loc;
                futures.add(FETCH_POOL.submit(() -> weatherText(l)));
            }
            for (int i = 0; i < locs.length; i++) {
                String body;
                try { body = futures.get(i).get(90, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception e) { body = "（天気を取得できませんでした）"; }
                String level = body.contains("特別警報") ? "danger" : body.contains("発表中") ? "warn" : "info";
                weatherSections.add(new Section("☀ 天気予報（" + locs[i].name + "）", body.trim(), level));
            }
        }
        boolean tsunamiAlert = false;
        Section tsunamiSection;
        try {
            String t = tsunamiF.get(60, java.util.concurrent.TimeUnit.SECONDS);
            tsunamiAlert = t.contains("大津波警報") || t.contains("津波警報:") || t.contains("津波注意報");
            tsunamiSection = new Section("🌊 津波情報", stripFirstLine(t), tsunamiAlert ? "danger" : "ok");
        } catch (Exception e) {
            tsunamiSection = new Section("🌊 津波情報", "取得できませんでした：" + friendlyError(e), "warn");
        }
        out.addAll(weatherSections);
        out.add(tsunamiSection);
        try {
            String ty = typhoonF.get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (ty != null && !ty.isEmpty()) {
                out.add(new Section("🌀 台風情報", ty, "warn"));
            }
        } catch (Exception ignored) {}
        try {
            String eew = eewF.get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (eew != null) {
                out.add(new Section("🚨 緊急地震速報", eew, eew.contains("取り消し") ? "info" : "danger"));
            }
        } catch (Exception ignored) {}
        java.util.List<Quake> qsForSummary = new java.util.ArrayList<>();
        try {
            java.util.List<Quake> qs = quakesF.get(60, java.util.concurrent.TimeUnit.SECONDS);
            qsForSummary = qs;
            if (qs.isEmpty()) {
                out.add(new Section("🌏 最新の地震", "最近の地震情報はありません。", "ok"));
            } else {
                StringBuilder sb = new StringBuilder();
                for (Quake q : qs) {
                    sb.append(severityEmoji(q.sev)).append(" 震度").append(scaleToStr(q.maxScale)).append(" ").append(q.place)
                      .append("\n　").append(q.detail).append("\n");
                }
                out.add(new Section("🌏 最新の地震（3件）", sb.toString().trim(), "info"));
            }
        } catch (Exception e) {
            out.add(new Section("🌏 最新の地震", "取得できませんでした：" + friendlyError(e), "warn"));
        }
        out.add(0, summarize(weatherSections, tsunamiAlert, qsForSummary));
        return out;
    }

    // 一括で把握するための先頭まとめカード (警報・津波・最新地震・今日の天気を凝縮)
    public static Section summarize(java.util.List<Section> weatherSections, boolean tsunamiAlert, java.util.List<Quake> quakes) {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> warnNames = new java.util.LinkedHashSet<>();
        for (Section s : weatherSections) {
            for (String line : s.body.split("\n")) {
                if (line.startsWith("⚠ 発表中：")) {
                    for (String nm : line.substring("⚠ 発表中：".length()).split("、")) {
                        nm = nm.trim();
                        int cut = nm.indexOf('（');
                        if (cut > 0) nm = nm.substring(0, cut);
                        if (!nm.isEmpty() && !warnNames.contains(nm)) warnNames.add(nm);
                    }
                }
            }
        }
        sb.append(warnNames.isEmpty() ? "⚠ 警報・注意報：発表なし" : "⚠ 発表中：" + String.join("、", warnNames));
        sb.append("\n🌊 津波: ").append(tsunamiAlert ? "警報・注意報あり" : "なし");
        if (!quakes.isEmpty()) {
            Quake q = quakes.get(0);
            sb.append("\n🌏 最新の地震: 震度").append(scaleToStr(q.maxScale)).append(" ").append(q.place);
        }
        for (Section s : weatherSections) {
            for (String line : s.body.split("\n")) {
                if (line.startsWith("今日：")) {
                    String locName = s.title.replace("☀ 天気予報（", "").replace("）", "");
                    sb.append("\n☀ ").append(locName).append(": ").append(line.substring("今日：".length()).trim());
                    break;
                }
            }
        }
        boolean special = false;
        for (String n : warnNames) if (n.contains("特別警報")) special = true;
        String level = (tsunamiAlert || special) ? "danger" : (!warnNames.isEmpty() ? "warn" : "ok");
        return new Section("📌 現在のまとめ", sb.toString(), level);
    }

    private static String stripFirstLine(String s) {
        int idx = s.indexOf('\n');
        return idx >= 0 ? s.substring(idx + 1).trim() : s.trim();
    }

    // 地震タブ用: 津波セクション + EEWセクション
    public static java.util.List<Section> quakesSections() {
        return quakesSections(true);
    }

    // includeEew=false のときは EEW を含めない (モニターのカウントダウンカードで別表示するため)
    public static java.util.List<Section> quakesSections(boolean includeEew) {
        java.util.List<Section> out = new java.util.ArrayList<>();
        try {
            String t = tsunamiText();
            boolean alert = t.contains("大津波警報") || t.contains("津波警報:") || t.contains("津波注意報");
            out.add(new Section("🌊 津波情報", stripFirstLine(t), alert ? "danger" : "ok"));
        } catch (Exception e) {
            out.add(new Section("🌊 津波情報", "取得できませんでした：" + friendlyError(e), "warn"));
        }
        if (includeEew) {
            String eew = eewText();
            if (eew != null) {
                out.add(new Section("🚨 緊急地震速報", eew, eew.contains("取り消し") ? "info" : "danger"));
            }
        }
        return out;
    }

    // サーバーモード用: イベントをセクションに変換する
    public static java.util.List<Section> serverSections(String baseUrl) throws Exception {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        JSONObject root = new JSONObject(fetch(base + "/api/briefing"));
        JSONArray events = root.optJSONArray("events");
        java.util.List<Section> out = new java.util.ArrayList<>();
        if (events == null || events.length() == 0) {
            out.add(new Section("🛡️ AEGIS ブリーフィング", "サーバーからイベントが返りませんでした。", "warn"));
            return out;
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            String sev = ev.optString("severity", "INFO");
            String level = "CRITICAL".equals(sev) ? "danger" : ("HIGH".equals(sev) || "MEDIUM".equals(sev)) ? "warn" : "info";
            StringBuilder body = new StringBuilder(ev.optString("body"));
            String guide = ev.optString("action_guide");
            if (!guide.isEmpty()) body.append("\n▶ 行動ガイド： ").append(guide);
            out.add(new Section(severityEmoji(sev) + " " + ev.optString("title"), body.toString(), level));
        }
        return out;
    }

    // ===== 6. AEGISサーバーモード =====
    public static String briefingFromServer(String baseUrl) throws Exception {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        JSONObject root = new JSONObject(fetch(base + "/api/briefing"));
        JSONArray events = root.optJSONArray("events");
        StringBuilder sb = new StringBuilder();
        sb.append("🛡️ AEGIS ブリーフィング（サーバー: ").append(base).append("）\n").append("=".repeat(48)).append("\n");
        if (events == null || events.length() == 0) {
            sb.append("サーバーからイベントが返りませんでした。\n");
            return sb.toString();
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            String sev = ev.optString("severity", "INFO");
            sb.append(severityEmoji(sev)).append(" [").append(sev).append("] ").append(ev.optString("title")).append("\n")
              .append(ev.optString("body")).append("\n");
            String guide = ev.optString("action_guide");
            if (!guide.isEmpty()) sb.append("▶ 行動ガイド: ").append(guide).append("\n");
            sb.append("-".repeat(48)).append("\n");
        }
        return sb.toString();
    }

    // ===== 7. 都道府県テーブル (気象庁コード・正式名称・県庁所在地) =====
    public static final class Pref {
        public final String code, name;
        public final double capLat, capLon;
        public Pref(String code, String name, double capLat, double capLon) {
            this.code = code; this.name = name; this.capLat = capLat; this.capLon = capLon;
        }
    }

    public static final Pref[] PREFS = {
        new Pref("010000", "北海道", 43.0642, 141.3469), new Pref("020000", "青森県", 40.8244, 140.7400),
        new Pref("030000", "岩手県", 39.7019, 141.1545), new Pref("040000", "宮城県", 38.2682, 140.8694),
        new Pref("050000", "秋田県", 39.7186, 140.1024), new Pref("060000", "山形県", 38.2404, 140.3633),
        new Pref("070000", "福島県", 37.7503, 140.4676), new Pref("080000", "茨城県", 36.3418, 140.4468),
        new Pref("090000", "栃木県", 36.5657, 139.8836), new Pref("100000", "群馬県", 36.3895, 139.0634),
        new Pref("110000", "埼玉県", 35.8617, 139.6455), new Pref("120000", "千葉県", 35.6074, 140.1065),
        new Pref("130000", "東京都", 35.6895, 139.6917), new Pref("140000", "神奈川県", 35.4478, 139.6425),
        new Pref("150000", "新潟県", 37.9024, 139.0232), new Pref("160000", "富山県", 36.6953, 137.2113),
        new Pref("170000", "石川県", 36.5947, 136.6256), new Pref("180000", "福井県", 36.0652, 136.2216),
        new Pref("190000", "山梨県", 35.6642, 138.5684), new Pref("200000", "長野県", 36.6513, 138.1810),
        new Pref("210000", "岐阜県", 35.3912, 136.7223), new Pref("220000", "静岡県", 34.9769, 138.3831),
        new Pref("230000", "愛知県", 35.1815, 136.9066), new Pref("240000", "三重県", 34.7303, 136.5086),
        new Pref("250000", "滋賀県", 35.0045, 135.8686), new Pref("260000", "京都府", 35.0210, 135.7556),
        new Pref("270000", "大阪府", 34.6863, 135.5200), new Pref("280000", "兵庫県", 34.6913, 135.1830),
        new Pref("290000", "奈良県", 34.6851, 135.8048), new Pref("300000", "和歌山県", 34.2260, 135.1675),
        new Pref("310000", "鳥取県", 35.5039, 134.2377), new Pref("320000", "島根県", 35.4723, 133.0505),
        new Pref("330000", "岡山県", 34.6618, 133.9350), new Pref("340000", "広島県", 34.3966, 132.4596),
        new Pref("350000", "山口県", 34.1861, 131.4706), new Pref("360000", "徳島県", 34.0658, 134.5593),
        new Pref("370000", "香川県", 34.3401, 134.0434), new Pref("380000", "愛媛県", 33.8416, 132.7661),
        new Pref("390000", "高知県", 33.5597, 133.5311), new Pref("400000", "福岡県", 33.6064, 130.4183),
        new Pref("410000", "佐賀県", 33.2494, 130.2988), new Pref("420000", "長崎県", 32.7448, 129.8737),
        new Pref("430000", "熊本県", 32.7898, 130.7417), new Pref("440000", "大分県", 33.2382, 131.6126),
        new Pref("450000", "宮崎県", 31.9111, 131.4202), new Pref("460000", "鹿児島県", 31.5602, 130.5581),
        new Pref("470000", "沖縄県", 26.2124, 127.6809),
    };

    // 気象庁の都道府県コード → 正式名称 (EEW の対象地域照合などに使う)
    public static String prefNameForCode(String prefCode) {
        if (prefCode != null) {
            for (Pref p : PREFS) if (p.code.equals(prefCode)) return p.name;
        }
        return "";
    }

    // 県庁所在地が最も近い都道府県を推定する (GPS 地点登録用)
    public static Pref nearestPref(double lat, double lon) {
        Pref best = null;
        double bestKm = Double.MAX_VALUE;
        for (Pref p : PREFS) {
            double d = haversineKm(lat, lon, p.capLat, p.capLon);
            if (d < bestKm) { bestKm = d; best = p; }
        }
        return best;
    }

    // 予報JSONから一次細分区域名の一覧を返す (GPS 地点登録の地域選択用)
    public static java.util.List<String> forecastAreaNames(String prefCode) throws Exception {
        JSONArray arr = new JSONArray(fetch("https://www.jma.go.jp/bosai/forecast/data/forecast/" + prefCode + ".json"));
        JSONObject first = arr.getJSONObject(0);
        JSONArray areas = first.getJSONArray("timeSeries").getJSONObject(0).getJSONArray("areas");
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < areas.length(); i++) {
            String n = areas.getJSONObject(i).getJSONObject("area").optString("name");
            if (!n.isEmpty() && !names.contains(n)) names.add(n);
        }
        return names;
    }

    // ===== 8. 気象レーダー (RainViewer + 地理院タイル) =====
    public static final class RadarImage {
        public final android.graphics.Bitmap bitmap;
        public final String caption;
        public RadarImage(android.graphics.Bitmap bitmap, String caption) {
            this.bitmap = bitmap; this.caption = caption;
        }
    }

    // slippy map (XYZ) のタイル番号
    public static int tileX(double lon, int z) {
        int n = 1 << z;
        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        return Math.max(0, Math.min(n - 1, x));
    }

    public static int tileY(double lat, int z) {
        int n = 1 << z;
        double rad = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)));
        int y = (int) Math.floor((1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * n);
        return Math.max(0, Math.min(n - 1, y));
    }

    public static byte[] fetchBytes(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "AEGIS-Android/1.5");
            if (conn.getResponseCode() != 200) throw new IllegalStateException("HTTP " + conn.getResponseCode());
            java.io.InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new java.util.zip.GZIPInputStream(in);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static android.graphics.Bitmap fetchTile(String url) {
        try {
            byte[] bytes = fetchBytes(url);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    // 選択地点を中心に ズーム8・3×3タイル (地理院地図 + RainViewer レーダー) を合成する
    public static RadarImage radarImage(Location loc) throws Exception {
        JSONObject root = new JSONObject(fetch("https://api.rainviewer.com/public/weather-maps.json"));
        JSONArray past = root.getJSONObject("radar").getJSONArray("past");
        if (past.length() == 0) throw new IllegalStateException("レーダーフレームがありません");
        JSONObject last = past.getJSONObject(past.length() - 1);
        String path = last.getString("path");
        long epochSec = last.getLong("time");
        final int z = 8, tile = 256;
        final int cx = tileX(loc.lon, z), cy = tileY(loc.lat, z);
        // 18枚のタイルを並列取得する
        java.util.Map<String, java.util.concurrent.Future<android.graphics.Bitmap>> futures = new java.util.HashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                final int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) continue;
                futures.put("b" + dx + "," + dy, FETCH_POOL.submit(() -> fetchTile(
                        "https://cyberjapandata.gsi.go.jp/xyz/std/" + z + "/" + x + "/" + y + ".png")));
                futures.put("r" + dx + "," + dy, FETCH_POOL.submit(() -> fetchTile(
                        "https://tilecache.rainviewer.com" + path + "/256/" + z + "/" + x + "/" + y + "/2/1_1.png")));
            }
        }
        android.graphics.Bitmap canvas = android.graphics.Bitmap.createBitmap(tile * 3, tile * 3, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(canvas);
        cv.drawColor(android.graphics.Color.parseColor("#0B1220"));
        int radarOk = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int px = (dx + 1) * tile, py = (dy + 1) * tile;
                try {
                    java.util.concurrent.Future<android.graphics.Bitmap> fb = futures.get("b" + dx + "," + dy);
                    android.graphics.Bitmap b = fb == null ? null : fb.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (b != null) cv.drawBitmap(b, px, py, null);
                } catch (Exception ignored) {}
                try {
                    java.util.concurrent.Future<android.graphics.Bitmap> fr = futures.get("r" + dx + "," + dy);
                    android.graphics.Bitmap r = fr == null ? null : fr.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (r != null) { cv.drawBitmap(r, px, py, null); radarOk++; }
                } catch (Exception ignored) {}
            }
        }
        // 中心に地点マーカー (二重丸)
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
        paint.setColor(android.graphics.Color.WHITE);
        cv.drawCircle(tile * 1.5f, tile * 1.5f, 26f, paint);
        paint.setStrokeWidth(4f);
        paint.setColor(android.graphics.Color.parseColor("#FF5252"));
        cv.drawCircle(tile * 1.5f, tile * 1.5f, 17f, paint);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        cv.drawCircle(tile * 1.5f, tile * 1.5f, 5f, paint);
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("M月d日 H:mm", java.util.Locale.JAPAN);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        String caption = "レーダー観測時刻： " + fmt.format(new java.util.Date(epochSec * 1000L))
                + "\nレーダー: RainViewer（気象庁データ） / 地図: 国土地理院"
                + (radarOk == 0 ? "\n（レーダー画像を取得できませんでした）" : "");
        return new RadarImage(canvas, caption);
    }

    // ===== 9. 緊急地震速報 (構造化・主要動到達カウントダウン) =====
    public static final class EewArea {
        public final String pref, name, arrivalTime;
        public EewArea(String pref, String name, String arrivalTime) {
            this.pref = pref; this.name = name; this.arrivalTime = arrivalTime;
        }
    }

    public static final class EewInfo {
        public long originMillis;      // 発生時刻
        public long reportMillis;      // 報の時刻
        public boolean isCancel, isFinal;
        public String hypoName = "不明";
        public double lat, lon;        // 震源座標 (0 は不明)
        public int depth = -1;         // 深さ km (-1 は不明)
        public int estMaxScale = -1;   // 推定最大震度 (P2P形式)
        public final java.util.List<EewArea> areas = new java.util.ArrayList<>();
    }

    // 実データのある最新報が1時間以内なら構造化して返す。それ以外は null
    public static EewInfo eewInfo() {
        try {
            JSONArray data = new JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=554&limit=3"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                JSONObject eq = item.optJSONObject("earthquake");
                JSONArray areas = item.optJSONArray("areas");
                if (eq == null && areas == null) continue;
                String time = item.optString("time");
                long report = parseP2pTime(time);
                if (report == 0 || System.currentTimeMillis() - report > 60 * 60 * 1000L) return null;
                EewInfo info = new EewInfo();
                info.reportMillis = report;
                info.isCancel = item.optBoolean("isCancel");
                info.isFinal = item.optBoolean("isFinal");
                info.originMillis = report;
                if (eq != null) {
                    long origin = parseP2pTime(eq.optString("originTime"));
                    if (origin > 0) info.originMillis = origin;
                    JSONObject hypo = eq.optJSONObject("hypocenter");
                    if (hypo != null) {
                        info.hypoName = hypo.optString("name", "不明");
                        info.lat = hypo.optDouble("latitude", 0);
                        info.lon = hypo.optDouble("longitude", 0);
                        info.depth = hypo.optInt("depth", -1);
                    }
                    info.estMaxScale = eq.optInt("maxScale", -1);
                }
                if (areas != null) {
                    for (int j = 0; j < areas.length(); j++) {
                        JSONObject a = areas.getJSONObject(j);
                        info.areas.add(new EewArea(a.optString("pref"), a.optString("name"), a.optString("arrivalTime")));
                    }
                }
                return info;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // 地点への主要動到達予想 (epoch ミリ秒)。該当なしは -1。estimatedOut[0] に推定かどうかを返す
    // arrivalTime があればそれを使い、なければ震源距離と深さから S波 3.5km/s で推定する
    public static long arrivalMillis(EewInfo info, Location loc, boolean[] estimatedOut) {
        if (estimatedOut != null && estimatedOut.length > 0) estimatedOut[0] = false;
        String prefName = prefNameForCode(loc.prefCode);
        EewArea match = null;
        for (EewArea a : info.areas) {
            if (!prefName.isEmpty() && prefName.equals(a.pref)) { match = a; break; }
        }
        if (match == null) {
            for (EewArea a : info.areas) {
                if (!loc.areaName.isEmpty() && a.name.contains(loc.areaName)) { match = a; break; }
            }
        }
        if (match != null && match.arrivalTime != null && !match.arrivalTime.isEmpty()) {
            long t = parseP2pTime(match.arrivalTime);
            if (t > 0) return t;
        }
        if (info.lat != 0 && info.lon != 0 && info.depth >= 0 && info.originMillis > 0) {
            double epi = haversineKm(loc.lat, loc.lon, info.lat, info.lon);
            double hypo = Math.sqrt(epi * epi + info.depth * info.depth);
            if (estimatedOut != null && estimatedOut.length > 0) estimatedOut[0] = true;
            return info.originMillis + (long) (hypo / 3.5 * 1000.0);
        }
        return -1;
    }

    // カウントダウンがまだ意味を持つか (到達前、または到達後30秒以内)
    public static boolean countdownActive(EewInfo info, Location[] locs, long now) {
        if (info == null || info.isCancel) return false;
        boolean[] est = new boolean[1];
        for (Location loc : locs) {
            long arr = arrivalMillis(info, loc, est);
            if (arr > 0 && now < arr + 30 * 1000L) return true;
        }
        return false;
    }

    // EEW の静的本文 (eewText と同じ内容を構造化データから作る)
    public static String eewStaticBody(EewInfo info) {
        if (info.isCancel) {
            String t = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(new java.util.Date(info.reportMillis));
            return "緊急地震速報は取り消されました（" + t.substring(5, 16) + "）";
        }
        StringBuilder sb = new StringBuilder();
        if (info.isFinal) sb.append("（最終報）");
        sb.append("震源 ").append(info.hypoName);
        if (info.estMaxScale > 0) sb.append(" / 推定最大震度 ").append(scaleToStr(info.estMaxScale));
        java.util.LinkedHashSet<String> prefs = new java.util.LinkedHashSet<>();
        for (EewArea a : info.areas) {
            if (!a.pref.isEmpty() && prefs.size() < 6) prefs.add(a.pref);
        }
        if (!prefs.isEmpty()) sb.append("\n対象: ").append(String.join("、", prefs));
        sb.append("\n強い揺れに備えてください。");
        return sb.toString();
    }

    // カウントダウンカードの本文 (1秒ごとに再計算される)
    public static String eewCountdownBody(EewInfo info, Location[] locs, long now) {
        StringBuilder sb = new StringBuilder(eewStaticBody(info));
        boolean[] est = new boolean[1];
        for (Location loc : locs) {
            long arr = arrivalMillis(info, loc, est);
            if (arr <= 0) continue;
            if (now < arr) {
                long sec = (arr - now + 999) / 1000;
                sb.append("\n⏱ ").append(loc.name).append(": あと約").append(sec).append("秒");
                if (est[0]) sb.append("（推定）");
            } else if (now < arr + 30 * 1000L) {
                sb.append("\n⏱ ").append(loc.name).append(": 到達した可能性が高いです");
            }
        }
        return sb.toString();
    }

    // ===== 10. 台風 (targetTc.json) =====
    // 台風があるときだけ表示用テキストを返す。なければ null
    public static String typhoonText() {
        try {
            JSONArray arr = new JSONArray(fetch("https://www.jma.go.jp/bosai/typhoon/data/targetTc.json").trim());
            if (arr.length() == 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object el = arr.get(i);
                if (i > 0) sb.append("\n");
                if (el instanceof JSONObject) {
                    JSONObject o = (JSONObject) el;
                    // 名称・番号らしきフィールドを優先し、なければ文字列フィールドを連結する (防御的)
                    String[] keys = {"name", "tcName", "typhoonName", "title", "number", "tcNumber", "id", "href"};
                    java.util.List<String> parts = new java.util.ArrayList<>();
                    for (String k : keys) {
                        String v = o.optString(k);
                        if (!v.isEmpty() && !parts.contains(v)) parts.add(v);
                    }
                    if (parts.isEmpty()) {
                        java.util.Iterator<String> it = o.keys();
                        while (it.hasNext()) {
                            Object v = o.opt(it.next());
                            if (v instanceof String && !((String) v).isEmpty()) parts.add((String) v);
                        }
                    }
                    sb.append(String.join(" / ", parts));
                } else {
                    sb.append(String.valueOf(el));
                }
            }
            return sb.toString().trim().isEmpty() ? null : sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 11. ニュース要約 (NHK RSS) =====
    public static final class NewsItem {
        public final String title, timeText, summary, link;
        public NewsItem(String title, String timeText, String summary, String link) {
            this.title = title; this.timeText = timeText; this.summary = summary; this.link = link;
        }
    }

    static final String[] NEWS_KEYWORDS = {
        "地震", "津波", "警報", "特別警報", "台風", "大雨", "洪水", "土砂", "避難", "震度",
        "噴火", "熱中症", "暴風", "大雪", "竜巻", "線状降水帯"
    };

    // description の HTML を除去し、約80字に切り詰める (途中で切れたら「…」)
    public static String truncateSummary(String raw, int maxLen) {
        if (raw == null) return "";
        String s = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }

    private static java.util.List<NewsItem> parseRss(String xml) {
        java.util.List<NewsItem> out = new java.util.ArrayList<>();
        try {
            org.xmlpull.v1.XmlPullParserFactory factory = org.xmlpull.v1.XmlPullParserFactory.newInstance();
            org.xmlpull.v1.XmlPullParser parser = factory.newPullParser();
            parser.setInput(new java.io.StringReader(xml));
            java.text.SimpleDateFormat inFmt = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US);
            java.text.SimpleDateFormat outFmt = new java.text.SimpleDateFormat("H:mm", java.util.Locale.JAPAN);
            outFmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            boolean inItem = false;
            String tag = "", title = "", link = "", desc = "", pub = "";
            int ev = parser.getEventType();
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    tag = parser.getName();
                    if ("item".equals(tag)) { inItem = true; title = ""; link = ""; desc = ""; pub = ""; }
                } else if (ev == org.xmlpull.v1.XmlPullParser.TEXT && inItem) {
                    String text = parser.getText();
                    if (text != null) {
                        if ("title".equals(tag)) title += text;
                        else if ("link".equals(tag)) link += text;
                        else if ("description".equals(tag)) desc += text;
                        else if ("pubDate".equals(tag)) pub += text;
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    if ("item".equals(parser.getName()) && inItem) {
                        inItem = false;
                        String timeText = "";
                        try {
                            java.util.Date d = inFmt.parse(pub.trim());
                            if (d != null) timeText = outFmt.format(d);
                        } catch (Exception ignored) {}
                        out.add(new NewsItem(title.trim(), timeText, truncateSummary(desc, 80), link.trim()));
                    }
                    tag = "";
                }
                ev = parser.next();
            }
        } catch (Exception ignored) {}
        return out;
    }

    // NHK 主要(cat0) + 社会(cat1) をマージし、防災関連を優先して最大8件返す
    public static java.util.List<NewsItem> newsItems() throws Exception {
        java.util.concurrent.Future<String> f0 = FETCH_POOL.submit(() -> fetch("https://www3.nhk.or.jp/rss/news/cat0.xml"));
        java.util.concurrent.Future<String> f1 = FETCH_POOL.submit(() -> fetch("https://www3.nhk.or.jp/rss/news/cat1.xml"));
        java.util.List<NewsItem> merged = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (java.util.concurrent.Future<String> f : java.util.Arrays.asList(f0, f1)) {
            try {
                for (NewsItem n : parseRss(f.get(30, java.util.concurrent.TimeUnit.SECONDS))) {
                    if (!n.link.isEmpty() && seen.add(n.link)) merged.add(n);
                }
            } catch (Exception ignored) {}
        }
        if (merged.isEmpty()) throw new IllegalStateException("ニュースを取得できませんでした");
        java.util.List<NewsItem> disaster = new java.util.ArrayList<>();
        java.util.List<NewsItem> general = new java.util.ArrayList<>();
        for (NewsItem n : merged) {
            boolean hit = false;
            for (String kw : NEWS_KEYWORDS) {
                if (n.title.contains(kw) || n.summary.contains(kw)) { hit = true; break; }
            }
            (hit ? disaster : general).add(n);
        }
        java.util.List<NewsItem> out = new java.util.ArrayList<>(disaster);
        // 防災関連が少ない場合は主要ニュースも少し混ぜる (合計 最大8件)
        for (NewsItem n : general) {
            if (out.size() >= 8) break;
            if (disaster.size() >= 4 && out.size() >= 4) break;
            out.add(n);
        }
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    // ===== 12. 接続テスト (自己診断) =====
    // 結果1行の整形 (純粋関数)
    public static String diagLine(String name, boolean ok, long ms, String reason) {
        if (ok) return "○ " + name + "：接続OK（" + ms + "ms）";
        return "× " + name + "：失敗（" + (reason == null || reason.isEmpty() ? "不明なエラー" : reason) + "）";
    }

    private static java.util.concurrent.Callable<String> diagTask(String name, String url) {
        return () -> {
            long start = System.currentTimeMillis();
            try {
                fetch(url);
                return diagLine(name, true, System.currentTimeMillis() - start, "");
            } catch (Exception e) {
                return diagLine(name, false, System.currentTimeMillis() - start, friendlyError(e));
            }
        };
    }

    // 各データソースへ実際にリクエストし、ソースごとの結果行を返す
    public static java.util.List<String> diagnose(boolean serverMode, String serverUrl) {
        Location loc = effectiveLocations()[0];
        java.util.List<java.util.concurrent.Callable<String>> tasks = new java.util.ArrayList<>();
        tasks.add(diagTask("P2P 地震情報（551）", "https://api.p2pquake.net/v2/history?codes=551&limit=1"));
        tasks.add(diagTask("P2P 津波情報（552）", "https://api.p2pquake.net/v2/history?codes=552&limit=1"));
        tasks.add(diagTask("P2P 緊急地震速報（554）", "https://api.p2pquake.net/v2/history?codes=554&limit=1"));
        tasks.add(diagTask("気象庁 予報", "https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.prefCode + ".json"));
        tasks.add(diagTask("気象庁 警報", "https://www.jma.go.jp/bosai/warning/data/warning/" + loc.prefCode + ".json"));
        tasks.add(diagTask("気象庁 台風", "https://www.jma.go.jp/bosai/typhoon/data/targetTc.json"));
        tasks.add(diagTask("Open-Meteo", "https://api.open-meteo.com/v1/forecast?latitude=" + loc.lat + "&longitude=" + loc.lon + "&current=temperature_2m&timezone=Asia%2FTokyo"));
        tasks.add(diagTask("RainViewer レーダー", "https://api.rainviewer.com/public/weather-maps.json"));
        tasks.add(diagTask("NHK ニュース RSS", "https://www3.nhk.or.jp/rss/news/cat0.xml"));
        if (serverMode) {
            String base = serverUrl == null ? "" : serverUrl.trim();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            tasks.add(diagTask("AEGISサーバー", base + "/api/briefing"));
        }
        java.util.List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (java.util.concurrent.Callable<String> t : tasks) futures.add(FETCH_POOL.submit(t));
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<String> f : futures) {
            try { lines.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (Exception e) { lines.add(diagLine("（不明なソース）", false, 0, friendlyError(e))); }
        }
        return lines;
    }
}
