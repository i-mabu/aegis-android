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
                conn.setRequestProperty("User-Agent", "AEGIS-Android/1.3");
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
        public Quake(String place, String detail, String sev, int maxScale) {
            this(place, detail, sev, maxScale, 0, 0);
        }
        public Quake(String place, String detail, String sev, int maxScale, double lat, double lon) {
            this.place = place; this.detail = detail; this.sev = sev; this.maxScale = maxScale;
            this.lat = lat; this.lon = lon;
        }
    }

    // ===== 2. 震度・重要度 =====
    public static String scaleToStr(int scale) {
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
            double mag = hypo.optDouble("magnitude", 0);
            String time = eq.optString("time");
            // "2026/11/17 10:40:00" -> "11/17 10:40"
            String shortTime = time;
            if (time.length() >= 16) shortTime = time.substring(5, 10) + " " + time.substring(11, 16);
            String detail = shortTime + "発生 / M" + mag + " / 深さ約" + hypo.optString("depth", "?") + "km";
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
            out.add(new Quake(hypo.optString("name", "不明"), detail, severityOf(maxScale, mag), maxScale, lat, lon));
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
        StringBuilder names = new StringBuilder();
        String grade = "";
        if (areas != null) {
            for (int i = 0; i < areas.length(); i++) {
                JSONObject a = areas.getJSONObject(i);
                if (names.length() > 0) names.append("、");
                names.append(a.optString("name"));
                if (grade.isEmpty()) grade = a.optString("grade");
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

    // ===== 4. 気象 (気象庁 + Open-Meteo) =====
    private static final Map<String, String> WMO = new HashMap<>();
    static {
        WMO.put("0", "快晴"); WMO.put("1", "晴れ"); WMO.put("2", "一部曇り"); WMO.put("3", "曇り");
        WMO.put("45", "霧"); WMO.put("48", "霧"); WMO.put("51", "霧雨"); WMO.put("53", "霧雨"); WMO.put("55", "霧雨");
        WMO.put("61", "雨"); WMO.put("63", "雨"); WMO.put("65", "大雨"); WMO.put("71", "雪"); WMO.put("73", "雪"); WMO.put("75", "大雪");
        WMO.put("80", "にわか雨"); WMO.put("81", "にわか雨"); WMO.put("82", "激しい雨"); WMO.put("95", "雷雨");
    }

    public static String weatherText(Location loc) {
        // 予報・現在・警報の3要素は独立しているので並列取得し、順序どおりに結合する
        java.util.concurrent.Future<String> f1 = FETCH_POOL.submit(() -> forecastPart(loc));
        java.util.concurrent.Future<String> f2 = FETCH_POOL.submit(() -> currentPart(loc));
        java.util.concurrent.Future<String> f3 = FETCH_POOL.submit(() -> warningPart(loc));
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(f1.get()).append(f2.get()).append(f3.get());
        } catch (Exception e) {
            sb.append("（天気を取得できませんでした）\n");
        }
        return sb.toString();
    }

    private static String forecastPart(Location loc) {
        StringBuilder sb = new StringBuilder();
        // 気象庁 予報
        try {
            JSONArray arr = new JSONArray(fetch("https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.prefCode + ".json"));
            JSONObject first = arr.getJSONObject(0);
            JSONObject ts0 = first.getJSONArray("timeSeries").getJSONObject(0);
            JSONArray areas = ts0.getJSONArray("areas");
            JSONObject chosen = null;
            for (int i = 0; i < areas.length(); i++) {
                JSONObject a = areas.getJSONObject(i);
                if (loc.areaName.equals(a.getJSONObject("area").optString("name"))) { chosen = a; break; }
            }
            if (chosen == null && areas.length() > 0) chosen = areas.getJSONObject(0);
            if (chosen != null) {
                JSONArray weathers = chosen.getJSONArray("weathers");
                sb.append("今日： ").append(cleanWeather(weathers.optString(0)));
                if (weathers.length() > 1) sb.append("\n明日： ").append(cleanWeather(weathers.optString(1)));
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("（天気予報を取得できませんでした：").append(friendlyError(e)).append("）\n");
        }
        return sb.toString();
    }

    private static String currentPart(Location loc) {
        StringBuilder sb = new StringBuilder();
        // Open-Meteo 現在
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + loc.lat + "&longitude=" + loc.lon
                    + "&current=temperature_2m,weather_code,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&forecast_days=1&timezone=Asia%2FTokyo";
            JSONObject root = new JSONObject(fetch(url));
            JSONObject cur = root.getJSONObject("current");
            JSONObject daily = root.getJSONObject("daily");
            String desc = WMO.getOrDefault(String.valueOf(cur.optInt("weather_code")), "");
            double pop = daily.getJSONArray("precipitation_probability_max").optDouble(0, -1);
            sb.append("現在: ").append(desc).append(" ").append(cur.optDouble("temperature_2m")).append("℃ / 風 ")
              .append(cur.optDouble("wind_speed_10m")).append("km/h / 今日 ")
              .append(daily.getJSONArray("temperature_2m_min").optDouble(0)).append("〜")
              .append(daily.getJSONArray("temperature_2m_max").optDouble(0)).append("℃ / 降水確率 最大")
              .append(pop < 0 ? "?" : String.valueOf((long) pop)).append("%\n");
            if (pop >= 80) sb.append("▶ 降水確率が高め。大きめの傘が安心です\n");
        } catch (Exception e) {
            sb.append("（現在の気象を取得できませんでした：").append(friendlyError(e)).append("）\n");
        }
        return sb.toString();
    }

    private static String warningPart(Location loc) {
        StringBuilder sb = new StringBuilder();
        // 警報・注意報 (発表中の名称をコード表から復元して表示)
        try {
            JSONObject warn = new JSONObject(fetch("https://www.jma.go.jp/bosai/warning/data/warning/" + loc.prefCode + ".json"));
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            JSONArray areaTypes = warn.optJSONArray("areaTypes");
            if (areaTypes != null) {
                for (int i = 0; i < areaTypes.length(); i++) {
                    JSONArray areas = areaTypes.getJSONObject(i).optJSONArray("areas");
                    if (areas == null) continue;
                    for (int j = 0; j < areas.length(); j++) {
                        JSONObject area = areas.getJSONObject(j);
                        if (!loc.areaName.equals(area.optString("name"))) continue;
                        JSONArray ws = area.optJSONArray("warnings");
                        if (ws == null) continue;
                        for (int k = 0; k < ws.length(); k++) {
                            JSONObject w = ws.getJSONObject(k);
                            if ("解除".equals(w.optString("status"))) continue;
                            String nm = WARNING_CODES.get(w.optString("code"));
                            if (nm != null) names.add(nm);
                        }
                    }
                }
            }
            if (!names.isEmpty()) {
                sb.append("⚠ 発表中： ").append(String.join("、", names)).append("\n");
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
        WARNING_CODES.put("35", "大雪特別警報"); WARNING_CODES.put("36", "暴風特別警報");
        WARNING_CODES.put("37", "波浪特別警報"); WARNING_CODES.put("38", "高潮特別警報");
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
                try { body = futures.get(i).get(); }
                catch (Exception e) { body = "（天気を取得できませんでした）"; }
                String level = body.contains("発表中") ? "warn" : "info";
                weatherSections.add(new Section("☀ 天気予報（" + locs[i].name + "）", body.trim(), level));
            }
        }
        boolean tsunamiAlert = false;
        Section tsunamiSection;
        try {
            String t = tsunamiF.get();
            tsunamiAlert = t.contains("大津波警報") || t.contains("津波警報:") || t.contains("津波注意報");
            tsunamiSection = new Section("🌊 津波情報", stripFirstLine(t), tsunamiAlert ? "danger" : "ok");
        } catch (Exception e) {
            tsunamiSection = new Section("🌊 津波情報", "取得できませんでした：" + friendlyError(e), "warn");
        }
        out.addAll(weatherSections);
        out.add(tsunamiSection);
        java.util.List<Quake> qsForSummary = new java.util.ArrayList<>();
        try {
            java.util.List<Quake> qs = quakesF.get();
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
                if (line.startsWith("⚠ 発表中：")) warnNames.add(line.substring("⚠ 発表中：".length()).trim());
            }
        }
        sb.append(warnNames.isEmpty() ? "⚠ 警報・注意報: 発表なし" : "⚠ 発表中: " + String.join("、", warnNames));
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
        String level = tsunamiAlert ? "danger" : (!warnNames.isEmpty() ? "warn" : "ok");
        return new Section("📌 現在のまとめ", sb.toString(), level);
    }

    private static String stripFirstLine(String s) {
        int idx = s.indexOf('\n');
        return idx >= 0 ? s.substring(idx + 1).trim() : s.trim();
    }

    // 地震タブ用: 津波セクション + 地震カード
    public static java.util.List<Section> quakesSections() {
        java.util.List<Section> out = new java.util.ArrayList<>();
        try {
            String t = tsunamiText();
            boolean alert = t.contains("大津波警報") || t.contains("津波警報:") || t.contains("津波注意報");
            out.add(new Section("🌊 津波情報", stripFirstLine(t), alert ? "danger" : "ok"));
        } catch (Exception e) {
            out.add(new Section("🌊 津波情報", "取得できませんでした：" + friendlyError(e), "warn"));
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
}
