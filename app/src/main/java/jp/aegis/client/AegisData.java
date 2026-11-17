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
        Location(String name, double lat, double lon, String prefCode, String areaName) {
            this.name = name; this.lat = lat; this.lon = lon; this.prefCode = prefCode; this.areaName = areaName;
        }
    }

    public static final Location TAKASAKI = new Location("高崎", 36.3219, 139.0023, "100000", "南部");
    public static final Location OMIYA = new Location("大宮", 35.9064, 139.6239, "110000", "南部");
    public static final Location[] LOCATIONS = {TAKASAKI, OMIYA};

    // ----- 1.1 HTTP -----
    public static String fetch(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "AEGIS-Android/1.0");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
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
    public static String earthquakesText(int limit) throws Exception {
        JSONArray data = new JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=551&limit=" + limit));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            JSONObject eq = item.optJSONObject("earthquake");
            if (eq == null) continue;
            JSONObject hypo = eq.optJSONObject("hypocenter");
            if (hypo == null) hypo = new JSONObject();
            int maxScale = eq.optInt("maxScale", -1);
            double mag = hypo.optDouble("magnitude", 0);
            String sev = severityOf(maxScale, mag);
            sb.append(severityEmoji(sev)).append(" [").append(sev).append("] 地震 最大震度")
              .append(scaleToStr(maxScale)).append(" ").append(hypo.optString("name", "不明")).append("\n")
              .append(eq.optString("time")).append("発生 / M").append(mag)
              .append(" / 深さ約").append(hypo.optString("depth", "?")).append("km")
              .append("\n").append("-".repeat(48)).append("\n");
        }
        if (sb.length() == 0) sb.append("最近の地震情報はありません。\n");
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

    public static String weatherText(Location loc) throws Exception {
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
                sb.append("☀ 天気予報（").append(loc.name).append("）\n今日: ").append(weathers.optString(0));
                if (weathers.length() > 1) sb.append("\n明日: ").append(weathers.optString(1));
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("（気象庁予報の取得に失敗: ").append(e.getMessage()).append("）\n");
        }
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
            sb.append("（現在の気象の取得に失敗: ").append(e.getMessage()).append("）\n");
        }
        // 警報・注意報 (発表中の名称だけ簡易表示)
        try {
            JSONObject warn = new JSONObject(fetch("https://www.jma.go.jp/bosai/warning/data/warning/" + loc.prefCode + ".json"));
            String headline = warn.optString("headlineText");
            if (!headline.isEmpty()) sb.append("⚠ ").append(headline).append("\n");
        } catch (Exception ignored) {}
        return sb.toString();
    }

    // ===== 5. ブリーフィング =====
    public static String briefingDirect() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("🛡️ AEGIS ブリーフィング\n").append("=".repeat(48)).append("\n");
        for (Location loc : LOCATIONS) {
            sb.append(weatherText(loc)).append("-".repeat(48)).append("\n");
        }
        try { sb.append(tsunamiText()).append("-".repeat(48)).append("\n"); }
        catch (Exception e) { sb.append("（津波情報の取得に失敗: ").append(e.getMessage()).append("）\n"); }
        sb.append("最新の地震（3件）\n");
        try { sb.append(earthquakesText(3)); }
        catch (Exception e) { sb.append("（地震情報の取得に失敗: ").append(e.getMessage()).append("）\n"); }
        return sb.toString();
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
