package jp.aegis.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

// ===== 1. AegisData (取得・整形: P2P地震情報 / 気象庁 / Open-Meteo / AEGISサーバー) =====
object AegisData {
    class Location(
        val name: String,
        val lat: Double,
        val lon: Double,
        val prefCode: String,
        val areaName: String
    )

    val TAKASAKI = Location("高崎", 36.3219, 139.0023, "100000", "南部")
    val OMIYA = Location("大宮", 35.9064, 139.6239, "110000", "南部")
    val LOCATIONS: Array<Location> = arrayOf(TAKASAKI, OMIYA)

    // ユーザーが追加した任意地点 (設定画面から MainActivity が読み込んで設定する)
    @Volatile
    var customLocations: MutableList<Location> = ArrayList()

    fun effectiveLocations(): Array<Location> {
        val all = ArrayList(java.util.Arrays.asList(*LOCATIONS))
        all.addAll(customLocations)
        return all.toTypedArray()
    }

    // 並列取得用の共有プール (天気の3要素や複数地点を同時に取る)
    private val FETCH_POOL: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newCachedThreadPool()

    // ----- 1.1 HTTP -----
    fun fetch(urlStr: String): String {
        // 共通のテキスト取得 (画面表示・バックグラウンド同期・自己診断で共用): 試行は最大2回まで (待ち時間の暴走を防ぐ)。gzip で転送量も削減する
        var last: Exception? = null
        for (attempt in 0 until 2) {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "AEGIS-Android/1.8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                val code = conn.responseCode
                if (code == 200) {
                    var ins: java.io.InputStream = conn.inputStream
                    if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                        ins = java.util.zip.GZIPInputStream(ins)
                    }
                    val sb = StringBuilder()
                    BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).use { br ->
                        var line = br.readLine()
                        while (line != null) {
                            sb.append(line)
                            line = br.readLine()
                        }
                    }
                    return sb.toString()
                }
                if (code < 500) throw IllegalStateException("HTTP $code")
                last = IllegalStateException("HTTP $code")
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                last = e
            } finally {
                conn.disconnect()
            }
            if (attempt < 1) Thread.sleep(1000L)
        }
        throw last ?: IllegalStateException("fetch failed")
    }

    // 例外をユーザー向けの短い日本語に変換する (生の英語メッセージは出さない)
    fun friendlyError(e: Exception): String {
        val m = e.message.toString()
        if (e is java.net.SocketTimeoutException || m.contains("timed out")) return "通信がタイムアウトしました"
        if (m.startsWith("HTTP ")) return "サーバーエラー（" + m + "）"
        if (e is java.net.UnknownHostException) return "サーバーに接続できませんでした"
        if (m.contains("abort") || m.contains("reset") || m.contains("ECONN")) return "通信が切断されました"
        return "通信エラーが発生しました"
    }

    // ===== 1.2 構造化データ (カードUI用) =====
    class Section(
        val title: String,
        val body: String,
        val level: String // level: info | ok | warn | danger
    )

    class Quake(
        val place: String,
        val detail: String,
        val sev: String,
        val maxScale: Int,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val originMillis: Long = 0,
        id: String? = ""
    ) {
        val id: String = id ?: "" // P2P のレコード ID (通知の重複排除用、無い場合は "")
    }

    // ===== 2. 震度・重要度 =====
    fun scaleToStr(scale: Int): String {
        if (scale <= 0) return "不明" // P2P の -1 は「震度不明」
        return when (scale) {
            10 -> "1"
            20 -> "2"
            30 -> "3"
            40 -> "4"
            45 -> "5弱"
            50 -> "5強"
            55 -> "6弱"
            60 -> "6強"
            70 -> "7"
            else -> scale.toString()
        }
    }

    fun severityEmoji(sev: String?): String {
        return when (sev ?: "") {
            "CRITICAL" -> "🔴"
            "HIGH" -> "🟠"
            "MEDIUM" -> "🟡"
            "LOW" -> "🟢"
            else -> "ℹ️"
        }
    }

    private fun severityOf(scale: Int, mag: Double): String {
        if (scale >= 45 || mag >= 6.0) return "CRITICAL"
        if (scale >= 40 || mag >= 5.0) return "HIGH"
        if (scale >= 30 || mag >= 4.5) return "MEDIUM"
        if (scale >= 20) return "LOW"
        return "INFO"
    }

    // ===== 3. 地震・津波 (P2P) =====
    fun quakeItems(limit: Int): MutableList<Quake> {
        val data = JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=551&limit=" + limit))
        val out = ArrayList<Quake>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val eq = item.optJSONObject("earthquake") ?: continue
            var hypo = eq.optJSONObject("hypocenter")
            if (hypo == null) hypo = JSONObject()
            val maxScale = eq.optInt("maxScale", -1)
            val mag = hypo.optDouble("magnitude", -1.0)
            val time = eq.optString("time")
            // "2026/11/17 10:40:00" -> "11/17 10:40"
            var shortTime = time
            if (time.length >= 16) shortTime = time.substring(5, 10) + " " + time.substring(11, 16)
            // P2P のセンチネル値 (-1) は「不明」として扱う (深さ -1 は気象庁の「ごく浅い」)
            val magStr = if (mag < 0) "M不明" else "M" + mag
            val depthVal = hypo.optInt("depth", -999)
            val depthStr = if (depthVal == -1) "深さごく浅い" else (if (depthVal <= -999) "深さ不明" else "深さ約" + depthVal + "km")
            var detail = shortTime + "発生 / " + magStr + " / " + depthStr
            // 津波の見通しを日本語で明示
            val tsunami = eq.optString("domesticTsunami", "None")
            when (tsunami) {
                "Forecast" -> detail += " / 若干の海面変動の可能性あり"
                "Advisory" -> detail += " / 津波注意報レベル"
                "Warning" -> detail += " / 津波警報レベル"
                "Unknown" -> detail += " / 津波の有無は確認中"
                else -> detail += " / 津波の心配なし"
            }
            // 最大震度を観測した地点と、地元（群馬・埼玉）の震度を points から拾う
            val points = item.optJSONArray("points")
            if (points != null) {
                var topAddr = ""
                var topCount = 0
                val localMax = java.util.LinkedHashMap<String, Int>()
                for (j in 0 until points.length()) {
                    val p = points.getJSONObject(j)
                    val sc = p.optInt("scale", 0)
                    if (sc == maxScale) {
                        topCount++
                        if (topAddr.isEmpty()) topAddr = p.optString("addr", p.optString("pref", ""))
                    }
                    val pref = p.optString("pref", "")
                    if (pref == "群馬県" || pref == "埼玉県") {
                        val cur = localMax[pref]
                        if (cur == null || sc > cur) localMax[pref] = sc
                    }
                }
                if (topAddr.isNotEmpty()) {
                    detail += " / 最大震度観測: " + topAddr + (if (topCount > 1) " ほか" + (topCount - 1) + "地点" else "")
                }
                if (localMax.isNotEmpty()) {
                    val lsb = StringBuilder(" / 地元震度:")
                    for ((key, value) in localMax) {
                        lsb.append(" ").append(key).append(" 最大").append(scaleToStr(value))
                    }
                    detail += lsb.toString()
                }
            }
            val lat = hypo.optDouble("latitude", 0.0)
            val lon = hypo.optDouble("longitude", 0.0)
            out.add(Quake(hypo.optString("name", "不明"), detail, severityOf(maxScale, mag), maxScale, lat, lon, parseP2pTime(time), item.optString("id")))
        }
        return out
    }

    fun tsunamiText(): String {
        val data = JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=552&limit=5"))
        if (data.length() == 0) {
            return "🌊 津波情報\n現在、発表されている津波警報・注意報はありません。\n強い地震を感じたら、すぐ高台へ避難してください。\n"
        }
        val sb = StringBuilder("🌊 津波情報（最新）\n")
        val item = data.getJSONObject(0)
        val cancelled = item.optBoolean("cancelled")
        val areas = item.optJSONArray("areas")
        // 警戒レベルは「全エリア中の最大 grade」で判定する (先頭エリアだけで判断すると過小評価になる)
        var grade = ""
        var best = -1
        val names = StringBuilder()
        if (areas != null) {
            for (i in 0 until areas.length()) {
                val a = areas.getJSONObject(i)
                val g = a.optString("grade")
                val rank = if ("MajorWarning" == g) 3 else if ("Warning" == g) 2 else if ("Watch" == g) 1 else 0
                if (rank > best) { best = rank; grade = g }
                if (names.isNotEmpty()) names.append("、")
                names.append(a.optString("name"))
                // 予想高さ・第1波到達予想があれば併記する
                val mh = a.optJSONObject("maxHeight")
                if (mh != null && mh.optString("description").isNotEmpty()) {
                    names.append("（予想 ").append(mh.optString("description")).append("）")
                }
                val fh = a.optJSONObject("firstHeight")
                if (fh != null && fh.optString("arrivalTime").isNotEmpty()) {
                    val at = fh.optString("arrivalTime")
                    names.append("（第1波到達予想 ").append(if (at.length >= 16) at.substring(11, 16) else at).append("）")
                }
            }
        }
        if (cancelled) sb.append("津波警報・注意報は解除されました。対象: ").append(names)
        else if ("MajorWarning" == grade) sb.append("🔴 大津波警報: ").append(names).append("\nただちに高台・津波避難ビルへ避難してください。")
        else if ("Warning" == grade) sb.append("🔴 津波警報: ").append(names).append("\nただちに高台・津波避難ビルへ避難してください。")
        else if ("Watch" == grade) sb.append("🟠 津波注意報: ").append(names).append("\n海から上がり、海岸から離れてください。")
        else sb.append("津波予報/情報: ").append(names)
        sb.append("\n発表: ").append(item.optString("time")).append("\n")
        return sb.toString()
    }

    // 緊急地震速報 (P2P 554): 実データのある最新報が1時間以内なら表示用テキストを返す。それ以外は null
    fun eewText(): String? {
        try {
            val data = JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=554&limit=3"))
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val eq = item.optJSONObject("earthquake")
                val areas = item.optJSONArray("areas")
                if (eq == null && areas == null) continue // メタデータのみのレコードは読み飛ばす
                val time = item.optString("time")
                if (time.length < 19) continue
                val t = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).parse(time.substring(0, 19))
                if (t == null || System.currentTimeMillis() - t.time > 60 * 60 * 1000L) return null // 古い EEW は出さない
                if (item.optBoolean("isCancel")) return "緊急地震速報は取り消されました（" + time.substring(5, 16) + "）"
                val sb = StringBuilder()
                if (item.optBoolean("isFinal")) sb.append("（最終報）")
                if (eq != null) {
                    val hypo = eq.optJSONObject("hypocenter")
                    if (hypo != null) sb.append("震源 ").append(hypo.optString("name", "不明"))
                    val est = eq.optInt("maxScale", -1)
                    if (est > 0) sb.append(" / 推定最大震度 ").append(scaleToStr(est))
                }
                if (areas != null && areas.length() > 0) {
                    val prefs = java.util.LinkedHashSet<String>()
                    var j = 0
                    while (j < areas.length() && prefs.size < 6) {
                        val p = areas.getJSONObject(j).optString("pref")
                        if (p.isNotEmpty()) prefs.add(p)
                        j++
                    }
                    if (prefs.isNotEmpty()) sb.append("\n対象: ").append(prefs.joinToString("、"))
                }
                sb.append("\n強い揺れに備えてください。")
                return sb.toString()
            }
        } catch (e: Exception) {}
        return null
    }

    // P2P の時刻文字列 "2026/11/17 10:40:00" を epoch ミリ秒にする (失敗時 0)
    fun parseP2pTime(time: String?): Long {
        if (time == null || time.length < 19) return 0
        try {
            val d = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).parse(time.substring(0, 19))
            return d?.time ?: 0
        } catch (e: Exception) {
            return 0
        }
    }

    // 2点間の球面距離 (km)
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    // ===== 4. 気象 (気象庁 + Open-Meteo) =====
    private val WMO: Map<String, String> = hashMapOf(
        "0" to "快晴", "1" to "晴れ", "2" to "一部曇り", "3" to "曇り",
        "45" to "霧", "48" to "霧", "51" to "霧雨", "53" to "霧雨", "55" to "霧雨",
        "61" to "雨", "63" to "雨", "65" to "大雨", "71" to "雪", "73" to "雪", "75" to "大雪",
        "80" to "にわか雨", "81" to "にわか雨", "82" to "激しい雨", "95" to "雷雨",
        "56" to "着氷性の霧雨", "57" to "着氷性の霧雨", "66" to "着氷性の雨", "67" to "着氷性の雨",
        "77" to "雪あられ", "85" to "雪", "86" to "雪", "96" to "雹を伴う雷雨", "99" to "雹を伴う雷雨"
    )

    // ----- 表表示用の行データ (純粋ビルダーで組み立てる) -----
    // 時間別予報の1行
    class HourRow(
        val date: String,
        val weekday: String,
        val hour: Int,
        val emoji: String,
        val desc: String,
        val temp: Long,
        val pop: Int // pop: -1 は不明
    )

    // 週間予報の1行
    class DayRow(
        val date: String,
        val weekday: String,
        val emoji: String,
        val desc: String,
        val minTemp: Long,
        val maxTemp: Long,
        val pop: Int // 降水確率最大: -1 は不明
    )

    // 気象庁 降水確率 (6時間刻み) の1行
    class PopRow(
        val date: String,
        val weekday: String,
        val fromHour: Int,
        val toHour: Int,
        val pop: Int // pop: -1 は不明
    )

    // ISO日付 "2026-11-18" → 曜日1文字 ("日"〜"土")。解釈できなければ ""
    fun weekdayJa(isoDate: String): String {
        try {
            val p = isoDate.split("-")
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            cal.clear()
            cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
            val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) // 1=日 〜 7=土
            return "日月火水木金土".substring(dow - 1, dow)
        } catch (e: Exception) {
            return ""
        }
    }

    // 時間別予報の行データを組み立てる (現在時刻以降・3時間刻み・最大12件)
    fun buildHourRows(times: Array<String>?, temps: DoubleArray?, pops: DoubleArray?, codes: IntArray?,
                      nowMillis: Long, tz: java.util.TimeZone): MutableList<HourRow> {
        val out = ArrayList<HourRow>()
        if (times == null) return out
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH", java.util.Locale.JAPAN)
        fmt.timeZone = tz
        val nowIso = fmt.format(java.util.Date(nowMillis))
        var i = 0
        while (i < times.size && out.size < 12) {
            val t = times[i]
            if (t.length < 13) { i++; continue }
            if (t.substring(0, 13) < nowIso) { i++; continue } // 過去の時間帯は出さない
            val hour: Int
            try { hour = t.substring(11, 13).toInt() } catch (e: Exception) { i++; continue }
            if (hour % 3 != 0) { i++; continue }
            val pop = if (pops != null && i < pops.size) pops[i] else -1.0
            val code = if (codes != null && i < codes.size) codes[i] else -1
            val temp = if (temps != null && i < temps.size) temps[i] else 0.0
            val dateIso = t.substring(0, 10)
            out.add(HourRow(dateIso.substring(5).replace('-', '/'), weekdayJa(dateIso), hour,
                    forecastEmoji(code, pop, hour), WMO[code.toString()] ?: "不明",
                    Math.round(temp), if (pop < 0) -1 else Math.round(pop).toInt()))
            i++
        }
        return out
    }

    // 週間予報の行データを組み立てる
    fun buildDayRows(dates: Array<String>?, codes: IntArray?, maxs: DoubleArray?, mins: DoubleArray?, pops: DoubleArray?): MutableList<DayRow> {
        val out = ArrayList<DayRow>()
        if (dates == null) return out
        for (i in dates.indices) {
            val t = dates[i]
            if (t.length < 10) continue
            val code = if (codes != null && i < codes.size) codes[i] else -1
            val pop = if (pops != null && i < pops.size) pops[i] else -1.0
            val mx = if (maxs != null && i < maxs.size) maxs[i] else 0.0
            val mn = if (mins != null && i < mins.size) mins[i] else 0.0
            val dateIso = t.substring(0, 10)
            out.add(DayRow(dateIso.substring(5).replace('-', '/'), weekdayJa(dateIso),
                    forecastEmoji(code, pop, 12), WMO[code.toString()] ?: "不明",
                    Math.round(mn), Math.round(mx), if (pop < 0) -1 else Math.round(pop).toInt()))
        }
        return out
    }

    // 気象庁 降水確率 (6時間刻み) の行データを組み立てる (最大6件)
    fun buildPopRows(timeDefines: Array<String>?, pops: Array<String>?): MutableList<PopRow> {
        val out = ArrayList<PopRow>()
        if (timeDefines == null || pops == null) return out
        var i = 0
        while (i < timeDefines.size && i < pops.size && out.size < 6) {
            val def = timeDefines[i] // 2026-11-17T12:00:00+09:00
            if (def.length < 13) { i++; continue }
            val hour: Int
            try { hour = def.substring(11, 13).toInt() } catch (e: Exception) { i++; continue }
            var pop = -1
            try { pop = pops[i].trim().toInt() } catch (e: Exception) {}
            val dateIso = def.substring(0, 10)
            out.add(PopRow(dateIso.substring(5).replace('-', '/'), weekdayJa(dateIso), hour, (hour + 6) % 24, pop))
            i++
        }
        return out
    }

    // 「ラベル：値」形式のテキストを [ラベル, 値] の組に分解する (ラベル列を揃えた行表示用)
    // 区切りがない行・ラベルが長すぎる行は [行全体, ""] を返す
    fun summaryRows(body: String?): MutableList<Array<String>> {
        val out = ArrayList<Array<String>>()
        if (body == null) return out
        for (line in body.split("\n")) {
            val s = line.trim()
            if (s.isEmpty()) continue
            var idx = s.indexOf('：')
            if (idx < 0) idx = s.indexOf(": ")
            if (idx > 0 && idx <= 12) out.add(arrayOf(s.substring(0, idx), s.substring(idx + 1).trim()))
            else out.add(arrayOf(s, ""))
        }
        return out
    }

    // JSONArray → 配列変換の小ヘルパー群 (null の場合は空の配列を返す)
    private fun jsonStrings(a: JSONArray?): Array<String> {
        if (a == null) return arrayOf()
        return Array(a.length()) { i -> a.optString(i) }
    }

    private fun jsonDoubles(a: JSONArray?, def: Double): DoubleArray {
        if (a == null) return DoubleArray(0)
        return DoubleArray(a.length()) { i -> a.optDouble(i, def) }
    }

    private fun jsonInts(a: JSONArray?): IntArray {
        if (a == null) return IntArray(0)
        return IntArray(a.length()) { i -> a.optInt(i) }
    }

    // 天気カードの構造化データ (テキスト全文 + 表の行データ)
    class WeatherView(
        val loc: Location,
        val text: String,         // 従来のテキスト全文 (まとめ生成・キャッシュ用)
        val daysText: String,     // 今日/明日/明後日の天気文
        val tempsText: String,    // 気温行
        val currentText: String,  // 現在の気象 (+傘アドバイス)
        val tomorrowText: String, // 明日 (Open-Meteo)
        val warningText: String,  // 警報テキスト (なければ "")
        val popRows: List<PopRow>,
        val hourRows: List<HourRow>,
        val dayRows: List<DayRow>,
        val level: String         // info | warn | danger
    )

    fun weatherView(loc: Location): WeatherView {
        // 気象庁予報を先に取得する: 予報テキストに加え、警報の地域判定に使う
        // 「一次細分区域コード」をこのJSONから得る (警報JSONの areas は名称を持たずコードのみのため)
        val fp = forecastData(loc)
        val f2 = FETCH_POOL.submit(java.util.concurrent.Callable { currentData(loc) })
        val f3 = FETCH_POOL.submit(java.util.concurrent.Callable { warningPart(loc, fp.areaCode) })
        var cp: CurrentPart
        try { cp = f2.get(50, java.util.concurrent.TimeUnit.SECONDS) }
        catch (e: Exception) {
            cp = CurrentPart()
            cp.text = "（現在の気象を取得できませんでした）\n"
            cp.currentText = "（現在の気象を取得できませんでした）"
        }
        var warn = ""
        try { warn = f3.get(50, java.util.concurrent.TimeUnit.SECONDS) }
        catch (e: Exception) {}
        val text = fp.text + cp.text + warn
        val level = if (text.contains("特別警報")) "danger" else if (text.contains("発表中")) "warn" else "info"
        return WeatherView(loc, text, fp.daysText, fp.tempsText, cp.currentText, cp.tomorrowText,
                warn.trim(), fp.popRows, cp.hourRows, cp.dayRows, level)
    }

    // 気象庁予報の構造化データ
    private class ForecastPart {
        var text: String = ""
        var daysText: String = ""
        var tempsText: String = ""
        var areaCode: String = ""
        var popRows: List<PopRow> = ArrayList()
    }

    private fun forecastData(loc: Location): ForecastPart {
        val fp = ForecastPart()
        val sb = StringBuilder()
        val days = StringBuilder()
        try {
            val arr = JSONArray(fetch("https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.prefCode + ".json"))
            val first = arr.getJSONObject(0)
            val series = first.getJSONArray("timeSeries")
            val ts0 = series.getJSONObject(0)
            val areas = ts0.getJSONArray("areas")
            var chosen: JSONObject? = null
            var matched = false
            for (i in 0 until areas.length()) {
                val a = areas.getJSONObject(i)
                if (loc.areaName == a.getJSONObject("area").optString("name")) { chosen = a; matched = true; break }
            }
            if (chosen == null && areas.length() > 0) chosen = areas.getJSONObject(0)
            if (chosen != null) {
                fp.areaCode = chosen.getJSONObject("area").optString("code")
                val weathers = chosen.getJSONArray("weathers")
                days.append("今日： ").append(cleanWeather(weathers.optString(0)))
                if (weathers.length() > 1) days.append("\n明日： ").append(cleanWeather(weathers.optString(1)))
                if (weathers.length() > 2) days.append("\n明後日： ").append(cleanWeather(weathers.optString(2)))
                if (!matched) days.append("\n（※地域名が一致しないため、県内の先頭地域の予報を表示しています）")
                sb.append(days).append("\n")
            }
            // 降水確率 (timeSeries[1]: 6時間ごとの pops) — 細分区域コードで照合
            for (s in 0 until series.length()) {
                val ts = series.getJSONObject(s)
                val tas = ts.optJSONArray("areas")
                if (tas == null || tas.length() == 0) continue
                if (!tas.getJSONObject(0).has("pops")) continue
                var pa: JSONObject? = null
                for (i in 0 until tas.length()) {
                    val a = tas.getJSONObject(i)
                    if (fp.areaCode == a.getJSONObject("area").optString("code")) { pa = a; break }
                }
                if (pa == null) pa = tas.getJSONObject(0)
                val pops = pa.optJSONArray("pops")
                val defs = ts.optJSONArray("timeDefines")
                if (pops != null && defs != null) {
                    fp.popRows = buildPopRows(jsonStrings(defs), jsonStrings(pops))
                    if (fp.popRows.isNotEmpty()) {
                        val pb = StringBuilder("降水確率： ")
                        var prevDate = ""
                        for (i in fp.popRows.indices) {
                            val r = fp.popRows[i]
                            if (i > 0) pb.append(" / ")
                            if (r.date != prevDate) { pb.append(r.date).append(" "); prevDate = r.date }
                            pb.append(r.fromHour).append("〜").append(r.toHour).append("時 ")
                                .append(if (r.pop < 0) "-" else r.pop.toString()).append("%")
                        }
                        sb.append(pb).append("\n")
                    }
                }
                break
            }
            // 気温 (timeSeries[2]: temps は [今日の最低, 今日の最高, 明日の最低, 明日の最高])
            for (s in 0 until series.length()) {
                val ts = series.getJSONObject(s)
                val tas = ts.optJSONArray("areas")
                if (tas == null || tas.length() == 0) continue
                if (!tas.getJSONObject(0).has("temps")) continue
                val ta = tas.getJSONObject(0)
                val temps = ta.optJSONArray("temps")
                if (temps != null && temps.length() >= 4) {
                    val city = ta.getJSONObject("area").optString("name")
                    fp.tempsText = "気温（" + city + "）： 今日 " +
                            temps.optString(0) + "〜" + temps.optString(1) + "℃ / 明日 " +
                            temps.optString(2) + "〜" + temps.optString(3) + "℃"
                    sb.append(fp.tempsText).append("\n")
                }
                break
            }
        } catch (e: Exception) {
            sb.append("（天気予報を取得できませんでした：").append(friendlyError(e)).append("）\n")
        }
        fp.text = sb.toString()
        fp.daysText = if (days.isNotEmpty()) days.toString() else fp.text.trim()
        return fp
    }

    // 予報用絵文字: 天気コード + 降水確率 + 時刻から選ぶ (純粋関数)
    // - 降水確率 50% 以上なら、晴れ/くもり系コードでも雨系を優先する
    //   (「降水86%なのに ☀」という不整合の修正。雨・雪・雷などのコードはそのまま)
    //   晴れ系 (0/1/2) → 🌦、くもり (3) → ☔ (80% 以上は 🌧)
    // - 夜間 (21時〜翌4時台) の晴れは ☀ ではなく 🌙
    // pop が負数 (不明) のときは降水確率による上書きをしない
    fun forecastEmoji(code: Int, pop: Double, hour: Int): String {
        if (pop >= 50) {
            when (code) {
                0, 1, 2 -> return "🌦"
                3 -> return if (pop >= 80) "🌧" else "☔"
                else -> {} // 雨・雪・雷・霧などのコードは weatherEmoji のまま
            }
        }
        if ((code == 0 || code == 1) && (hour >= 21 || hour < 5)) return "🌙"
        return weatherEmoji(code)
    }

    // WMO 天気コード → 絵文字 (時間別・週間予報のコンパクト表示用)
    fun weatherEmoji(code: Int): String {
        return when (code) {
            0, 1 -> "☀"
            2 -> "⛅"
            3 -> "☁"
            45, 48 -> "🌫"
            51, 53, 55, 80, 81, 82 -> "🌦"
            61, 63, 65, 66, 67 -> "🌧"
            56, 57, 71, 73, 75, 77, 85, 86 -> "🌨"
            95, 96, 99 -> "⛈"
            else -> "・"
        }
    }

    // Open-Meteo の構造化データ
    private class CurrentPart {
        var text: String = ""
        var currentText: String = ""
        var tomorrowText: String = ""
        var hourRows: List<HourRow> = ArrayList()
        var dayRows: List<DayRow> = ArrayList()
    }

    private fun currentData(loc: Location): CurrentPart {
        val cp = CurrentPart()
        val sb = StringBuilder()
        // Open-Meteo: 現在・時間別・日別(7日) を1リクエストで統合取得する
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=" + loc.lat + "&longitude=" + loc.lon +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
                    "&hourly=temperature_2m,precipitation_probability,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&forecast_days=7&timezone=Asia%2FTokyo"
            val root = JSONObject(fetch(url))
            val cur = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")
            val desc = WMO[cur.optInt("weather_code").toString()] ?: "不明"
            val pop = daily.getJSONArray("precipitation_probability_max").optDouble(0, -1.0)
            val curText = StringBuilder()
            curText.append("現在：").append(desc).append(" ").append(cur.optDouble("temperature_2m")).append("℃ / 風 ")
                .append(cur.optDouble("wind_speed_10m")).append("km/h / 今日 ")
                .append(daily.getJSONArray("temperature_2m_min").optDouble(0)).append("〜")
                .append(daily.getJSONArray("temperature_2m_max").optDouble(0)).append("℃ / 降水確率 最大")
                .append(if (pop < 0) "?" else (pop.toLong()).toString()).append("%")
            if (pop >= 80) curText.append("\n▶ 降水確率が高め。大きめの傘が安心です")
            cp.currentText = curText.toString()
            sb.append(cp.currentText).append("\n")
            // 明日の気温・降水確率 (Open-Meteo daily[1])
            val dCodes = daily.optJSONArray("weather_code")
            if (dCodes != null && dCodes.length() > 1) {
                val pop2 = daily.getJSONArray("precipitation_probability_max").optDouble(1, -1.0)
                cp.tomorrowText = "明日（Open-Meteo）: " + (WMO[dCodes.optInt(1).toString()] ?: "不明") + " " +
                        daily.getJSONArray("temperature_2m_min").optDouble(1) + "〜" +
                        daily.getJSONArray("temperature_2m_max").optDouble(1) + "℃ / 降水確率 最大" +
                        (if (pop2 < 0) "?" else (pop2.toLong()).toString()) + "%"
                sb.append(cp.tomorrowText).append("\n")
            }
            // 時間別 (今日の残り + 明日を3時間刻み)
            val hourly = root.optJSONObject("hourly")
            if (hourly != null) {
                cp.hourRows = hourRowsFromJson(hourly)
                val h = hourlyText(hourly)
                if (h.isNotEmpty()) sb.append(h)
            }
            // 週間予報 (daily 7日)
            cp.dayRows = dayRowsFromJson(daily)
            val w = weeklyText(daily)
            if (w.isNotEmpty()) sb.append(w)
        } catch (e: Exception) {
            sb.append("（現在の気象を取得できませんでした：").append(friendlyError(e)).append("）\n")
            cp.currentText = sb.toString().trim()
        }
        cp.text = sb.toString()
        return cp
    }

    private fun hourRowsFromJson(hourly: JSONObject?): MutableList<HourRow> {
        if (hourly == null) return ArrayList()
        val times = hourly.optJSONArray("time")
        val temps = hourly.optJSONArray("temperature_2m")
        val codes = hourly.optJSONArray("weather_code")
        if (times == null || temps == null || codes == null) return ArrayList()
        return buildHourRows(jsonStrings(times), jsonDoubles(temps, 0.0),
                jsonDoubles(hourly.optJSONArray("precipitation_probability"), -1.0), jsonInts(codes),
                System.currentTimeMillis(), java.util.TimeZone.getTimeZone("Asia/Tokyo"))
    }

    private fun dayRowsFromJson(daily: JSONObject?): MutableList<DayRow> {
        if (daily == null) return ArrayList()
        val times = daily.optJSONArray("time")
        val codes = daily.optJSONArray("weather_code")
        val maxs = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        if (times == null || codes == null || maxs == null || mins == null) return ArrayList()
        return buildDayRows(jsonStrings(times), jsonInts(codes), jsonDoubles(maxs, 0.0), jsonDoubles(mins, 0.0),
                jsonDoubles(daily.optJSONArray("precipitation_probability_max"), -1.0))
    }

    // 時間別予報: 行データから従来のテキスト形式に整形する (テキスト表示・キャッシュ用)
    fun hourlyText(hourly: JSONObject): String {
        val rows = hourRowsFromJson(hourly)
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("時間別： ")
        var prevDate = ""
        for (i in rows.indices) {
            val r = rows[i]
            if (r.date != prevDate) {
                if (i > 0) sb.append("\n　")
                sb.append(r.date).append(" ")
                prevDate = r.date
            } else if (i > 0) {
                sb.append(" / ")
            }
            sb.append(r.hour).append("時 ").append(r.emoji).append(r.temp).append("℃")
            if (r.pop >= 0) sb.append(" 降水").append(r.pop).append("%")
        }
        sb.append("\n")
        return sb.toString()
    }

    // 週間予報: 行データから従来のテキスト形式に整形する (テキスト表示・キャッシュ用)
    fun weeklyText(daily: JSONObject): String {
        val rows = dayRowsFromJson(daily)
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("週間： ")
        for (i in rows.indices) {
            val r = rows[i]
            if (i > 0) sb.append(" / ")
            sb.append(r.date).append(" ").append(r.emoji).append(" ")
                .append(r.minTemp).append("〜").append(r.maxTemp).append("℃")
        }
        sb.append("\n")
        return sb.toString()
    }

    // 警報・注意報: 警報JSONの areas は名称を持たず「一次細分区域コード」のみのため、
    // 予報JSONから得た areaCode で areaTypes[0] (一次細分区域) を照合する。
    // コードが得られなかった場合は県内全域を対象にし、その旨を注記する (無言で欠落させない)。
    private fun warningPart(loc: Location, areaCode: String?): String {
        val sb = StringBuilder()
        try {
            val warn = JSONObject(fetch("https://www.jma.go.jp/bosai/warning/data/warning/" + loc.prefCode + ".json"))
            val names = java.util.LinkedHashSet<String>()
            val areaTypes = warn.optJSONArray("areaTypes")
            val code = areaCode ?: ""
            val prefWide = code.isEmpty()
            if (areaTypes != null && areaTypes.length() > 0) {
                val areas = areaTypes.getJSONObject(0).optJSONArray("areas")
                if (areas != null) {
                    for (j in 0 until areas.length()) {
                        val area = areas.getJSONObject(j)
                        if (!prefWide && code != area.optString("code")) continue
                        val ws = area.optJSONArray("warnings")
                        if (ws == null) continue
                        for (k in 0 until ws.length()) {
                            val w = ws.getJSONObject(k)
                            if ("解除" == w.optString("status")) continue
                            val wcode = w.optString("code")
                            if (wcode.isEmpty()) continue
                            val nm = WARNING_CODES[wcode]
                            // コード表にない警報も無言で落とさず、コード付きで表示する
                            names.add(nm ?: "その他の警報・注意報（コード" + wcode + "）")
                        }
                    }
                }
            }
            if (names.isNotEmpty()) {
                sb.append("⚠ 発表中： ").append(names.joinToString("、"))
                val rd = warn.optString("reportDatetime") // 2026-11-17T11:00:00+09:00
                if (rd.length >= 16) sb.append("（").append(rd.substring(5, 10).replace('-', '/')).append(" ").append(rd.substring(11, 16)).append("発表）")
                sb.append("\n")
                if (prefWide) sb.append("（※地域を特定できなかったため、県内全域の警報・注意報です）\n")
                val headline = warn.optString("headlineText")
                if (headline.isNotEmpty() && !headline.contains("解除")) {
                    sb.append("　").append(headline).append("\n")
                }
            }
        } catch (e: Exception) {}
        return sb.toString()
    }

    // 気象庁の天気文は全角スペース区切りで間延びして見えるため整形する
    private fun cleanWeather(s: String): String {
        return s.replace('　', ' ').replace(Regex(" +"), " ").trim()
    }

    // 気象庁 警報・注意報コード表 (主要なもの)
    private val WARNING_CODES: Map<String, String> = hashMapOf(
        "02" to "暴風雪警報", "03" to "大雨警報",
        "04" to "洪水警報", "05" to "暴風警報",
        "06" to "大雪警報", "07" to "波浪警報",
        "08" to "高潮警報", "10" to "大雨注意報",
        "12" to "大雪注意報", "13" to "風雪注意報",
        "14" to "雷注意報", "15" to "強風注意報",
        "16" to "波浪注意報", "18" to "洪水注意報",
        "19" to "高潮注意報", "20" to "濃霧注意報",
        "21" to "乾燥注意報", "22" to "なだれ注意報",
        "23" to "低温注意報", "24" to "霜注意報",
        "25" to "着氷注意報", "26" to "着雪注意報",
        "27" to "融雪注意報", "29" to "氷結注意報",
        "32" to "暴風雪特別警報", "33" to "大雨特別警報",
        "35" to "暴風特別警報", "36" to "高潮特別警報",
        "37" to "波浪特別警報", "38" to "大雪特別警報"
    )

    // ===== 5. ブリーフィング =====
    fun briefingDirect(): String {
        val sb = StringBuilder()
        for (s in briefingSections()) sb.append(s.title).append("\n").append(s.body).append("\n")
        return sb.toString()
    }

    // ブリーフィング一式: セクション (テキスト) + 天気の構造化データ (表表示用)
    // sections の並びは [まとめ, 天気×地点数, 津波, (台風), (EEW), 地震] で、weathers は天気分と同順
    class Briefing(
        val sections: List<Section>,
        val weathers: List<WeatherView>
    )

    fun briefingSections(): List<Section> {
        return briefing().sections
    }

    // カードUI用: 天気・津波・地震をすべて並列取得し、表示順に組み立てる
    fun briefing(): Briefing {
        val out = ArrayList<Section>()
        val locs = effectiveLocations()
        val tsunamiF = FETCH_POOL.submit(java.util.concurrent.Callable { tsunamiText() })
        val typhoonF = FETCH_POOL.submit(java.util.concurrent.Callable { typhoonText() })
        val eewF = FETCH_POOL.submit(java.util.concurrent.Callable { eewText() })
        val quakesF = FETCH_POOL.submit(java.util.concurrent.Callable { quakeItems(3) })
        val weatherSections = ArrayList<Section>()
        val weatherViews = ArrayList<WeatherView>()
        run {
            val futures = ArrayList<java.util.concurrent.Future<WeatherView>>()
            for (loc in locs) {
                futures.add(FETCH_POOL.submit(java.util.concurrent.Callable { weatherView(loc) }))
            }
            for (i in locs.indices) {
                var view: WeatherView
                try { view = futures[i].get(90, java.util.concurrent.TimeUnit.SECONDS) }
                catch (e: Exception) {
                    view = WeatherView(locs[i], "（天気を取得できませんでした）", "（天気を取得できませんでした）",
                            "", "", "", "", ArrayList(), ArrayList(), ArrayList(), "warn")
                }
                weatherViews.add(view)
                weatherSections.add(Section("☀ 天気予報（" + locs[i].name + "）", view.text.trim(), view.level))
            }
        }
        var tsunamiAlert = false
        var tsunamiSection: Section
        try {
            val t = tsunamiF.get(60, java.util.concurrent.TimeUnit.SECONDS)
            tsunamiAlert = t.contains("大津波警報") || t.contains("津波警報:") || t.contains("津波注意報")
            tsunamiSection = Section("🌊 津波情報", stripFirstLine(t), if (tsunamiAlert) "danger" else "ok")
        } catch (e: Exception) {
            tsunamiSection = Section("🌊 津波情報", "取得できませんでした：" + friendlyError(e), "warn")
        }
        out.addAll(weatherSections)
        out.add(tsunamiSection)
        try {
            val ty = typhoonF.get(30, java.util.concurrent.TimeUnit.SECONDS)
            if (ty != null && ty.isNotEmpty()) {
                out.add(Section("🌀 台風情報", ty, "warn"))
            }
        } catch (e: Exception) {}
        try {
            val eew = eewF.get(30, java.util.concurrent.TimeUnit.SECONDS)
            if (eew != null) {
                out.add(Section("🚨 緊急地震速報", eew, if (eew.contains("取り消し")) "info" else "danger"))
            }
        } catch (e: Exception) {}
        var qsForSummary: List<Quake> = ArrayList()
        try {
            val qs = quakesF.get(60, java.util.concurrent.TimeUnit.SECONDS)
            qsForSummary = qs
            if (qs.isEmpty()) {
                out.add(Section("🌏 最新の地震", "最近の地震情報はありません。", "ok"))
            } else {
                val sb = StringBuilder()
                for (q in qs) {
                    sb.append(severityEmoji(q.sev)).append(" 震度").append(scaleToStr(q.maxScale)).append(" ").append(q.place)
                        .append("\n　").append(q.detail).append("\n")
                }
                out.add(Section("🌏 最新の地震（3件）", sb.toString().trim(), "info"))
            }
        } catch (e: Exception) {
            out.add(Section("🌏 最新の地震", "取得できませんでした：" + friendlyError(e), "warn"))
        }
        out.add(0, summarize(weatherSections, tsunamiAlert, qsForSummary))
        return Briefing(out, weatherViews)
    }

    // 一括で把握するための先頭まとめカード (警報・津波・最新地震・今日の天気を凝縮)
    fun summarize(weatherSections: List<Section>, tsunamiAlert: Boolean, quakes: List<Quake>): Section {
        val sb = StringBuilder()
        val warnNames = java.util.LinkedHashSet<String>()
        for (s in weatherSections) {
            for (line in s.body.split("\n")) {
                if (line.startsWith("⚠ 発表中：")) {
                    for (nm0 in line.substring("⚠ 発表中：".length).split("、")) {
                        var nm = nm0.trim()
                        val cut = nm.indexOf('（')
                        if (cut > 0) nm = nm.substring(0, cut)
                        if (nm.isNotEmpty() && !warnNames.contains(nm)) warnNames.add(nm)
                    }
                }
            }
        }
        sb.append(if (warnNames.isEmpty()) "⚠ 警報・注意報：発表なし" else "⚠ 発表中：" + warnNames.joinToString("、"))
        sb.append("\n🌊 津波: ").append(if (tsunamiAlert) "警報・注意報あり" else "なし")
        if (quakes.isNotEmpty()) {
            val q = quakes[0]
            sb.append("\n🌏 最新の地震: 震度").append(scaleToStr(q.maxScale)).append(" ").append(q.place)
        }
        for (s in weatherSections) {
            for (line in s.body.split("\n")) {
                if (line.startsWith("今日：")) {
                    val locName = s.title.replace("☀ 天気予報（", "").replace("）", "")
                    sb.append("\n☀ ").append(locName).append(": ").append(line.substring("今日：".length).trim())
                    break
                }
            }
        }
        var special = false
        for (n in warnNames) if (n.contains("特別警報")) special = true
        val level = if (tsunamiAlert || special) "danger" else if (warnNames.isNotEmpty()) "warn" else "ok"
        return Section("📌 現在のまとめ", sb.toString(), level)
    }

    private fun stripFirstLine(s: String): String {
        val idx = s.indexOf('\n')
        return if (idx >= 0) s.substring(idx + 1).trim() else s.trim()
    }

    // 地震タブ用: 津波セクション + EEWセクション
    // includeEew=false のときは EEW を含めない (モニターのカウントダウンカードで別表示するため)
    fun quakesSections(includeEew: Boolean = true): MutableList<Section> {
        val out = ArrayList<Section>()
        try {
            val t = tsunamiText()
            val alert = t.contains("大津波警報") || t.contains("津波警報:") || t.contains("津波注意報")
            out.add(Section("🌊 津波情報", stripFirstLine(t), if (alert) "danger" else "ok"))
        } catch (e: Exception) {
            out.add(Section("🌊 津波情報", "取得できませんでした：" + friendlyError(e), "warn"))
        }
        if (includeEew) {
            val eew = eewText()
            if (eew != null) {
                out.add(Section("🚨 緊急地震速報", eew, if (eew.contains("取り消し")) "info" else "danger"))
            }
        }
        return out
    }

    // サーバーモード用: イベントをセクションに変換する
    fun serverSections(baseUrl: String?): MutableList<Section> {
        var base = baseUrl?.trim() ?: ""
        if (base.endsWith("/")) base = base.substring(0, base.length - 1)
        val root = JSONObject(fetch(base + "/api/briefing"))
        val events = root.optJSONArray("events")
        val out = ArrayList<Section>()
        if (events == null || events.length() == 0) {
            out.add(Section("🛡️ AEGIS ブリーフィング", "サーバーからイベントが返りませんでした。", "warn"))
            return out
        }
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val sev = ev.optString("severity", "INFO")
            val level = if ("CRITICAL" == sev) "danger" else if ("HIGH" == sev || "MEDIUM" == sev) "warn" else "info"
            val body = StringBuilder(ev.optString("body"))
            val guide = ev.optString("action_guide")
            if (guide.isNotEmpty()) body.append("\n▶ 行動ガイド： ").append(guide)
            out.add(Section(severityEmoji(sev) + " " + ev.optString("title"), body.toString(), level))
        }
        return out
    }

    // ===== 6. 都道府県テーブル (気象庁コード・正式名称・県庁所在地) =====
    class Pref(
        val code: String,
        val name: String,
        val capLat: Double,
        val capLon: Double
    )

    val PREFS: Array<Pref> = arrayOf(
        Pref("010000", "北海道", 43.0642, 141.3469), Pref("020000", "青森県", 40.8244, 140.7400),
        Pref("030000", "岩手県", 39.7019, 141.1545), Pref("040000", "宮城県", 38.2682, 140.8694),
        Pref("050000", "秋田県", 39.7186, 140.1024), Pref("060000", "山形県", 38.2404, 140.3633),
        Pref("070000", "福島県", 37.7503, 140.4676), Pref("080000", "茨城県", 36.3418, 140.4468),
        Pref("090000", "栃木県", 36.5657, 139.8836), Pref("100000", "群馬県", 36.3895, 139.0634),
        Pref("110000", "埼玉県", 35.8617, 139.6455), Pref("120000", "千葉県", 35.6074, 140.1065),
        Pref("130000", "東京都", 35.6895, 139.6917), Pref("140000", "神奈川県", 35.4478, 139.6425),
        Pref("150000", "新潟県", 37.9024, 139.0232), Pref("160000", "富山県", 36.6953, 137.2113),
        Pref("170000", "石川県", 36.5947, 136.6256), Pref("180000", "福井県", 36.0652, 136.2216),
        Pref("190000", "山梨県", 35.6642, 138.5684), Pref("200000", "長野県", 36.6513, 138.1810),
        Pref("210000", "岐阜県", 35.3912, 136.7223), Pref("220000", "静岡県", 34.9769, 138.3831),
        Pref("230000", "愛知県", 35.1815, 136.9066), Pref("240000", "三重県", 34.7303, 136.5086),
        Pref("250000", "滋賀県", 35.0045, 135.8686), Pref("260000", "京都府", 35.0210, 135.7556),
        Pref("270000", "大阪府", 34.6863, 135.5200), Pref("280000", "兵庫県", 34.6913, 135.1830),
        Pref("290000", "奈良県", 34.6851, 135.8048), Pref("300000", "和歌山県", 34.2260, 135.1675),
        Pref("310000", "鳥取県", 35.5039, 134.2377), Pref("320000", "島根県", 35.4723, 133.0505),
        Pref("330000", "岡山県", 34.6618, 133.9350), Pref("340000", "広島県", 34.3966, 132.4596),
        Pref("350000", "山口県", 34.1861, 131.4706), Pref("360000", "徳島県", 34.0658, 134.5593),
        Pref("370000", "香川県", 34.3401, 134.0434), Pref("380000", "愛媛県", 33.8416, 132.7661),
        Pref("390000", "高知県", 33.5597, 133.5311), Pref("400000", "福岡県", 33.6064, 130.4183),
        Pref("410000", "佐賀県", 33.2494, 130.2988), Pref("420000", "長崎県", 32.7448, 129.8737),
        Pref("430000", "熊本県", 32.7898, 130.7417), Pref("440000", "大分県", 33.2382, 131.6126),
        Pref("450000", "宮崎県", 31.9111, 131.4202), Pref("460000", "鹿児島県", 31.5602, 130.5581),
        Pref("470000", "沖縄県", 26.2124, 127.6809)
    )

    // 気象庁の都道府県コード → 正式名称 (EEW の対象地域照合などに使う)
    fun prefNameForCode(prefCode: String?): String {
        if (prefCode != null) {
            for (p in PREFS) if (p.code == prefCode) return p.name
        }
        return ""
    }

    // 県庁所在地が最も近い都道府県を推定する (GPS 地点登録用)
    fun nearestPref(lat: Double, lon: Double): Pref {
        var best: Pref? = null
        var bestKm = Double.MAX_VALUE
        for (p in PREFS) {
            val d = haversineKm(lat, lon, p.capLat, p.capLon)
            if (d < bestKm) { bestKm = d; best = p }
        }
        return best!!
    }

    // 予報JSONから一次細分区域名の一覧を返す (GPS 地点登録の地域選択用)
    fun forecastAreaNames(prefCode: String): MutableList<String> {
        val arr = JSONArray(fetch("https://www.jma.go.jp/bosai/forecast/data/forecast/" + prefCode + ".json"))
        val first = arr.getJSONObject(0)
        val areas = first.getJSONArray("timeSeries").getJSONObject(0).getJSONArray("areas")
        val names = ArrayList<String>()
        for (i in 0 until areas.length()) {
            val n = areas.getJSONObject(i).getJSONObject("area").optString("name")
            if (n.isNotEmpty() && !names.contains(n)) names.add(n)
        }
        return names
    }

    // ===== 7. 気象レーダー (RainViewer + 地理院タイル) =====
    class RadarImage(
        val bitmap: android.graphics.Bitmap,
        val caption: String
    )

    // slippy map (XYZ) のタイル番号
    fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        val x = Math.floor((lon + 180.0) / 360.0 * n).toInt()
        return Math.max(0, Math.min(n - 1, x))
    }

    fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val rad = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)))
        val y = Math.floor((1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * n).toInt()
        return Math.max(0, Math.min(n - 1, y))
    }

    // バイナリ用取得 (レーダータイル等の画像): fetch と違いテキスト化せず、リトライもしない
    fun fetchBytes(urlStr: String): ByteArray {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "AEGIS-Android/1.8")
            if (conn.responseCode != 200) throw IllegalStateException("HTTP " + conn.responseCode)
            var ins: java.io.InputStream = conn.inputStream
            if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) ins = java.util.zip.GZIPInputStream(ins)
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var r = ins.read(buf)
            while (r != -1) {
                bos.write(buf, 0, r)
                r = ins.read(buf)
            }
            return bos.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchTile(url: String): android.graphics.Bitmap? {
        try {
            val bytes = fetchBytes(url)
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            return null
        }
    }

    // 雨タイル (RainViewer はズーム7までしか無い) が、ベース地図 (ズーム8・原点 ox,oy の
    // 3×3タイル = 768×768) をカバーする範囲を返す純粋関数。
    // 各要素は [tx, ty, 描画X, 描画Y] で、z7タイルは 512×512 に拡大して描画する。
    // 描画位置が負になったりキャンバス外にはみ出すのは正常 (描画側でクリッピングされる)。
    fun rainTilesFor(ox: Int, oy: Int): MutableList<IntArray> {
        val out = ArrayList<IntArray>()
        val tx0 = Math.floorDiv(ox, 2)
        val tx1 = Math.floorDiv(ox + 2, 2)
        val ty0 = Math.floorDiv(oy, 2)
        val ty1 = Math.floorDiv(oy + 2, 2)
        for (tx in tx0..tx1) {
            for (ty in ty0..ty1) {
                out.add(intArrayOf(tx, ty, (2 * tx - ox) * 256, (2 * ty - oy) * 256))
            }
        }
        return out
    }

    // 選択地点を中心に ズーム8・3×3タイル (地理院地図) に RainViewer レーダーを合成する。
    // RainViewer のタイルはズーム7までで、z8 では "Zoom Level Not Supported" のバナー画像が
    // 返ってくるため、雨タイルは z7 で取得して 512×512 に拡大描画する。
    fun radarImage(loc: Location): RadarImage {
        val root = JSONObject(fetch("https://api.rainviewer.com/public/weather-maps.json"))
        val past = root.getJSONObject("radar").getJSONArray("past")
        if (past.length() == 0) throw IllegalStateException("レーダーフレームがありません")
        val last = past.getJSONObject(past.length() - 1)
        val path = last.getString("path")
        val epochSec = last.getLong("time")
        val z = 8
        val tile = 256
        val cx = tileX(loc.lon, z)
        val cy = tileY(loc.lat, z)
        val ox = cx - 1
        val oy = cy - 1
        // ベースタイル (z8・9枚) と雨タイル (z7・最大4枚) を並列取得する
        val futures = java.util.HashMap<String, java.util.concurrent.Future<android.graphics.Bitmap?>>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val x = cx + dx
                val y = cy + dy
                if (x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) continue
                futures["b" + dx + "," + dy] = FETCH_POOL.submit(java.util.concurrent.Callable { fetchTile(
                        "https://cyberjapandata.gsi.go.jp/xyz/std/" + z + "/" + x + "/" + y + ".png") })
            }
        }
        val rainTiles = rainTilesFor(ox, oy)
        for (i in rainTiles.indices) {
            val tx = rainTiles[i][0]
            val ty = rainTiles[i][1]
            if (tx < 0 || ty < 0 || tx >= (1 shl 7) || ty >= (1 shl 7)) continue
            futures["r" + i] = FETCH_POOL.submit(java.util.concurrent.Callable { fetchTile(
                    "https://tilecache.rainviewer.com" + path + "/256/7/" + tx + "/" + ty + "/2/1_1.png") })
        }
        val canvas = android.graphics.Bitmap.createBitmap(tile * 3, tile * 3, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(canvas)
        cv.drawColor(android.graphics.Color.parseColor("#0B1220"))
        // ベースタイルを全て描いてから、雨タイル (512×512 に拡大) を重ねる
        for (dx in -1..1) {
            for (dy in -1..1) {
                val px = (dx + 1) * tile
                val py = (dy + 1) * tile
                try {
                    val fb = futures["b" + dx + "," + dy]
                    val b = fb?.get(30, java.util.concurrent.TimeUnit.SECONDS)
                    if (b != null) cv.drawBitmap(b, px.toFloat(), py.toFloat(), null)
                } catch (e: Exception) {}
            }
        }
        var radarOk = 0
        val rainPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        for (i in rainTiles.indices) {
            val rt = rainTiles[i]
            if (rt[0] < 0 || rt[1] < 0 || rt[0] >= (1 shl 7) || rt[1] >= (1 shl 7)) continue
            try {
                val fr = futures["r" + i]
                val r = fr?.get(30, java.util.concurrent.TimeUnit.SECONDS)
                if (r != null) {
                    cv.drawBitmap(r, null, android.graphics.Rect(rt[2], rt[3], rt[2] + 512, rt[3] + 512), rainPaint)
                    radarOk++
                }
            } catch (e: Exception) {}
        }
        // 中心に地点マーカー (二重丸)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = android.graphics.Color.WHITE
        cv.drawCircle(tile * 1.5f, tile * 1.5f, 26f, paint)
        paint.strokeWidth = 4f
        paint.color = android.graphics.Color.parseColor("#FF5252")
        cv.drawCircle(tile * 1.5f, tile * 1.5f, 17f, paint)
        paint.style = android.graphics.Paint.Style.FILL
        cv.drawCircle(tile * 1.5f, tile * 1.5f, 5f, paint)
        val fmt = java.text.SimpleDateFormat("M月d日 H:mm", java.util.Locale.JAPAN)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        val caption = "レーダー観測時刻： " + fmt.format(java.util.Date(epochSec * 1000L)) +
                "\nレーダー: RainViewer（気象庁データ） / 地図: 国土地理院" +
                (if (radarOk == 0) "\n（レーダー画像を取得できませんでした）" else "")
        return RadarImage(canvas, caption)
    }

    // ===== 8. 緊急地震速報 (構造化・主要動到達カウントダウン) =====
    class EewArea(
        val pref: String,
        val name: String,
        val arrivalTime: String
    )

    class EewInfo {
        var originMillis: Long = 0      // 発生時刻
        var reportMillis: Long = 0      // 報の時刻
        var isCancel: Boolean = false
        var isFinal: Boolean = false
        var hypoName: String = "不明"
        var lat: Double = 0.0           // 震源座標 (0 は不明)
        var lon: Double = 0.0
        var depth: Int = -1             // 深さ km (-1 は不明)
        var estMaxScale: Int = -1       // 推定最大震度 (P2P形式)
        val areas: MutableList<EewArea> = ArrayList()
    }

    // 実データのある最新報が1時間以内なら構造化して返す。それ以外は null
    fun eewInfo(): EewInfo? {
        try {
            val data = JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=554&limit=3"))
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val eq = item.optJSONObject("earthquake")
                val areas = item.optJSONArray("areas")
                if (eq == null && areas == null) continue
                val time = item.optString("time")
                val report = parseP2pTime(time)
                if (report == 0L || System.currentTimeMillis() - report > 60 * 60 * 1000L) return null
                val info = EewInfo()
                info.reportMillis = report
                info.isCancel = item.optBoolean("isCancel")
                info.isFinal = item.optBoolean("isFinal")
                info.originMillis = report
                if (eq != null) {
                    val origin = parseP2pTime(eq.optString("originTime"))
                    if (origin > 0) info.originMillis = origin
                    val hypo = eq.optJSONObject("hypocenter")
                    if (hypo != null) {
                        info.hypoName = hypo.optString("name", "不明")
                        info.lat = hypo.optDouble("latitude", 0.0)
                        info.lon = hypo.optDouble("longitude", 0.0)
                        info.depth = hypo.optInt("depth", -1)
                    }
                    info.estMaxScale = eq.optInt("maxScale", -1)
                }
                if (areas != null) {
                    for (j in 0 until areas.length()) {
                        val a = areas.getJSONObject(j)
                        info.areas.add(EewArea(a.optString("pref"), a.optString("name"), a.optString("arrivalTime")))
                    }
                }
                return info
            }
        } catch (e: Exception) {}
        return null
    }

    // 地点への主要動到達予想 (epoch ミリ秒)。該当なしは -1。estimatedOut[0] に推定かどうかを返す
    // arrivalTime があればそれを使い、なければ震源距離と深さから S波 3.5km/s で推定する
    fun arrivalMillis(info: EewInfo, loc: Location, estimatedOut: BooleanArray?): Long {
        if (estimatedOut != null && estimatedOut.isNotEmpty()) estimatedOut[0] = false
        val prefName = prefNameForCode(loc.prefCode)
        var match: EewArea? = null
        for (a in info.areas) {
            if (prefName.isNotEmpty() && prefName == a.pref) { match = a; break }
        }
        if (match == null) {
            for (a in info.areas) {
                if (loc.areaName.isNotEmpty() && a.name.contains(loc.areaName)) { match = a; break }
            }
        }
        if (match != null && match.arrivalTime.isNotEmpty()) {
            val t = parseP2pTime(match.arrivalTime)
            if (t > 0) return t
        }
        if (info.lat != 0.0 && info.lon != 0.0 && info.depth >= 0 && info.originMillis > 0) {
            val epi = haversineKm(loc.lat, loc.lon, info.lat, info.lon)
            val hypo = Math.sqrt(epi * epi + info.depth * info.depth)
            if (estimatedOut != null && estimatedOut.isNotEmpty()) estimatedOut[0] = true
            return info.originMillis + (hypo / 3.5 * 1000.0).toLong()
        }
        return -1
    }

    // カウントダウンがまだ意味を持つか (到達前、または到達後30秒以内)
    fun countdownActive(info: EewInfo?, locs: Array<Location>, now: Long): Boolean {
        if (info == null || info.isCancel) return false
        val est = BooleanArray(1)
        for (loc in locs) {
            val arr = arrivalMillis(info, loc, est)
            if (arr > 0 && now < arr + 30 * 1000L) return true
        }
        return false
    }

    // EEW の静的本文 (eewText と同じ内容を構造化データから作る)
    fun eewStaticBody(info: EewInfo): String {
        if (info.isCancel) {
            val t = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date(info.reportMillis))
            return "緊急地震速報は取り消されました（" + t.substring(5, 16) + "）"
        }
        val sb = StringBuilder()
        if (info.isFinal) sb.append("（最終報）")
        sb.append("震源 ").append(info.hypoName)
        if (info.estMaxScale > 0) sb.append(" / 推定最大震度 ").append(scaleToStr(info.estMaxScale))
        val prefs = java.util.LinkedHashSet<String>()
        for (a in info.areas) {
            if (a.pref.isNotEmpty() && prefs.size < 6) prefs.add(a.pref)
        }
        if (prefs.isNotEmpty()) sb.append("\n対象: ").append(prefs.joinToString("、"))
        sb.append("\n強い揺れに備えてください。")
        return sb.toString()
    }

    // カウントダウンカードの本文 (1秒ごとに再計算される)
    fun eewCountdownBody(info: EewInfo, locs: Array<Location>, now: Long): String {
        val sb = StringBuilder(eewStaticBody(info))
        val est = BooleanArray(1)
        for (loc in locs) {
            val arr = arrivalMillis(info, loc, est)
            if (arr <= 0) continue
            if (now < arr) {
                val sec = (arr - now + 999) / 1000
                sb.append("\n⏱ ").append(loc.name).append(": あと約").append(sec).append("秒")
                if (est[0]) sb.append("（推定）")
            } else if (now < arr + 30 * 1000L) {
                sb.append("\n⏱ ").append(loc.name).append(": 到達した可能性が高いです")
            }
        }
        return sb.toString()
    }

    // ===== 9. 台風 (targetTc.json) =====
    // 台風があるときだけ表示用テキストを返す。なければ null
    fun typhoonText(): String? {
        try {
            val arr = JSONArray(fetch("https://www.jma.go.jp/bosai/typhoon/data/targetTc.json").trim())
            if (arr.length() == 0) return null
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val el = arr.get(i)
                if (i > 0) sb.append("\n")
                if (el is JSONObject) {
                    // 名称・番号らしきフィールドを優先し、なければ文字列フィールドを連結する (防御的)
                    val keys = arrayOf("name", "tcName", "typhoonName", "title", "number", "tcNumber", "id", "href")
                    val parts = ArrayList<String>()
                    for (k in keys) {
                        val v = el.optString(k)
                        if (v.isNotEmpty() && !parts.contains(v)) parts.add(v)
                    }
                    if (parts.isEmpty()) {
                        val it = el.keys()
                        while (it.hasNext()) {
                            val v = el.opt(it.next())
                            if (v is String && v.isNotEmpty()) parts.add(v)
                        }
                    }
                    sb.append(parts.joinToString(" / "))
                } else {
                    sb.append(el.toString())
                }
            }
            return if (sb.toString().trim().isEmpty()) null else sb.toString().trim()
        } catch (e: Exception) {
            return null
        }
    }

    // ===== 10. ニュース要約 (NHK RSS) =====
    class NewsItem(
        val title: String,
        val timeText: String,
        val summary: String,
        val link: String
    )

    private val NEWS_KEYWORDS: Array<String> = arrayOf(
        "地震", "津波", "警報", "特別警報", "台風", "大雨", "洪水", "土砂", "避難", "震度",
        "噴火", "熱中症", "暴風", "大雪", "竜巻", "線状降水帯"
    )

    // description の HTML を除去し、約80字に切り詰める (途中で切れたら「…」)
    fun truncateSummary(raw: String?, maxLen: Int): String {
        if (raw == null) return ""
        val s = raw.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        if (s.length <= maxLen) return s
        return s.substring(0, maxLen) + "…"
    }

    // NHK RSS (XML) をプルパーサで走査し、<item> ごとに NewsItem を作る (解析に失敗した場合は途中までの結果を返す)
    private fun parseRss(xml: String): MutableList<NewsItem> {
        val out = ArrayList<NewsItem>()
        try {
            val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(java.io.StringReader(xml))
            val inFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
            val outFmt = java.text.SimpleDateFormat("H:mm", java.util.Locale.JAPAN)
            outFmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
            var inItem = false
            var tag = ""
            var title = ""
            var link = ""
            var desc = ""
            var pub = ""
            var ev = parser.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    tag = parser.name ?: ""
                    if ("item" == tag) { inItem = true; title = ""; link = ""; desc = ""; pub = "" }
                } else if (ev == org.xmlpull.v1.XmlPullParser.TEXT && inItem) {
                    val text = parser.text
                    if (text != null) {
                        if ("title" == tag) title += text
                        else if ("link" == tag) link += text
                        else if ("description" == tag) desc += text
                        else if ("pubDate" == tag) pub += text
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    if ("item" == parser.name && inItem) {
                        inItem = false
                        var timeText = ""
                        try {
                            val d = inFmt.parse(pub.trim())
                            if (d != null) timeText = outFmt.format(d)
                        } catch (e: Exception) {}
                        out.add(NewsItem(title.trim(), timeText, truncateSummary(desc, 80), link.trim()))
                    }
                    tag = ""
                }
                ev = parser.next()
            }
        } catch (e: Exception) {}
        return out
    }

    // NHK 主要(cat0) + 社会(cat1) をマージし、防災関連を優先して最大8件返す
    fun newsItems(): MutableList<NewsItem> {
        val f0 = FETCH_POOL.submit(java.util.concurrent.Callable { fetch("https://www3.nhk.or.jp/rss/news/cat0.xml") })
        val f1 = FETCH_POOL.submit(java.util.concurrent.Callable { fetch("https://www3.nhk.or.jp/rss/news/cat1.xml") })
        val merged = ArrayList<NewsItem>()
        val seen = java.util.HashSet<String>()
        for (f in listOf(f0, f1)) {
            try {
                for (n in parseRss(f.get(30, java.util.concurrent.TimeUnit.SECONDS))) {
                    if (n.link.isNotEmpty() && seen.add(n.link)) merged.add(n)
                }
            } catch (e: Exception) {}
        }
        if (merged.isEmpty()) throw IllegalStateException("ニュースを取得できませんでした")
        val disaster = ArrayList<NewsItem>()
        val general = ArrayList<NewsItem>()
        for (n in merged) {
            var hit = false
            for (kw in NEWS_KEYWORDS) {
                if (n.title.contains(kw) || n.summary.contains(kw)) { hit = true; break }
            }
            if (hit) disaster.add(n) else general.add(n)
        }
        val out = ArrayList(disaster)
        // 防災関連が少ない場合は主要ニュースも少し混ぜる (合計 最大8件)
        for (n in general) {
            if (out.size >= 8) break
            if (disaster.size >= 4 && out.size >= 4) break
            out.add(n)
        }
        return if (out.size > 8) out.subList(0, 8) else out
    }

    // ===== 11. 接続テスト (自己診断) =====
    // 結果1行の整形 (純粋関数)
    fun diagLine(name: String, ok: Boolean, ms: Long, reason: String?): String {
        if (ok) return "○ " + name + "：接続OK（" + ms + "ms）"
        return "× " + name + "：失敗（" + (if (reason.isNullOrEmpty()) "不明なエラー" else reason) + "）"
    }

    private fun diagTask(name: String, url: String): java.util.concurrent.Callable<String> {
        return java.util.concurrent.Callable {
            val start = System.currentTimeMillis()
            try {
                fetch(url)
                diagLine(name, true, System.currentTimeMillis() - start, "")
            } catch (e: Exception) {
                diagLine(name, false, System.currentTimeMillis() - start, friendlyError(e))
            }
        }
    }

    // 各データソースへ実際にリクエストし、ソースごとの結果行を返す
    fun diagnose(serverMode: Boolean, serverUrl: String?): MutableList<String> {
        val loc = effectiveLocations()[0]
        val tasks = ArrayList<java.util.concurrent.Callable<String>>()
        tasks.add(diagTask("P2P 地震情報（551）", "https://api.p2pquake.net/v2/history?codes=551&limit=1"))
        tasks.add(diagTask("P2P 津波情報（552）", "https://api.p2pquake.net/v2/history?codes=552&limit=1"))
        tasks.add(diagTask("P2P 緊急地震速報（554）", "https://api.p2pquake.net/v2/history?codes=554&limit=1"))
        tasks.add(diagTask("気象庁 予報", "https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.prefCode + ".json"))
        tasks.add(diagTask("気象庁 警報", "https://www.jma.go.jp/bosai/warning/data/warning/" + loc.prefCode + ".json"))
        tasks.add(diagTask("気象庁 台風", "https://www.jma.go.jp/bosai/typhoon/data/targetTc.json"))
        tasks.add(diagTask("Open-Meteo", "https://api.open-meteo.com/v1/forecast?latitude=" + loc.lat + "&longitude=" + loc.lon + "&current=temperature_2m&timezone=Asia%2FTokyo"))
        tasks.add(diagTask("RainViewer レーダー", "https://api.rainviewer.com/public/weather-maps.json"))
        tasks.add(diagTask("NHK ニュース RSS", "https://www3.nhk.or.jp/rss/news/cat0.xml"))
        if (serverMode) {
            var base = serverUrl?.trim() ?: ""
            if (base.endsWith("/")) base = base.substring(0, base.length - 1)
            tasks.add(diagTask("AEGISサーバー", base + "/api/briefing"))
        }
        val futures = ArrayList<java.util.concurrent.Future<String>>()
        for (t in tasks) futures.add(FETCH_POOL.submit(t))
        val lines = ArrayList<String>()
        for (f in futures) {
            try { lines.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS)) }
            catch (e: Exception) { lines.add(diagLine("（不明なソース）", false, 0, friendlyError(e))) }
        }
        return lines
    }

    // ===== 12. バックグラウンド同期・通知判定 =====
    // 通知の対象イベント (kind: quake | tsunami | eew | warning | briefing)
    class SyncEvent(
        val kind: String,
        val id: String,
        val title: String,
        val body: String,
        val urgent: Boolean,      // true = 緊急チャンネル (EEW・津波)
        val eventMillis: Long,    // イベント発生時刻 (epoch ミリ秒, 0 は不明)
        val maxScale: Int,        // 地震の最大震度 (P2P形式、それ以外は -1)
        val hasSpecial: Boolean,  // 警報用 (特別警報を含むか)
        val hasWarning: Boolean   // 警報用 (警報を含むか)
    ) {
        constructor(kind: String, id: String, title: String, body: String, urgent: Boolean, eventMillis: Long) :
                this(kind, id, title, body, urgent, eventMillis, -1, false, false)
    }

    // 警報の通知モード: 0 = 特別警報・警報のみ / 1 = 注意報も含む
    const val WARN_MODE_ALERTS_ONLY = 0
    const val WARN_MODE_INCLUDE_ADVISORY = 1

    // 通知済み ID の保持上限 (古いものから捨てる)
    const val MAX_NOTIFIED_IDS = 200

    // フラッド防止: 発生から1時間超のイベントは通知しない (サーバー版と同じルール)
    const val FRESH_WINDOW_MILLIS = 60 * 60 * 1000L

    // 地震を通知するか (震度しきい値: P2P形式 10/20/30/40/45)
    fun shouldNotifyQuake(maxScale: Int, thresholdScale: Int): Boolean {
        return maxScale > 0 && maxScale >= thresholdScale
    }

    // 気象庁 警報コードの格付け: 2 = 特別警報 / 1 = 警報 / 0 = 注意報 (未知コードも 0 として扱う)
    fun warningCodeRank(code: String?): Int {
        if (code == null) return 0
        return when (code) {
            "32", "33", "35", "36", "37", "38" -> 2
            "02", "03", "04", "05", "06", "07", "08" -> 1
            else -> 0
        }
    }

    // 警報を通知するか (モード: WARN_MODE_*)
    fun warningNotifiable(hasSpecial: Boolean, hasWarning: Boolean, mode: Int): Boolean {
        if (mode == WARN_MODE_INCLUDE_ADVISORY) return true
        return hasSpecial || hasWarning
    }

    // フラッド防止の鮮度判定: 発生から1時間以内 (未来側は時計ずれを考慮し5分まで許容)
    fun isFreshEvent(eventMillis: Long, now: Long): Boolean {
        if (eventMillis <= 0) return false
        return now - eventMillis <= FRESH_WINDOW_MILLIS && eventMillis - now <= 5 * 60 * 1000L
    }

    // 初回同期 (通知済み ID がまだ空) かどうか。初回はイベントを登録のみ行い通知しない
    // (シーディング: サーバー版・Windows Java版と同じ挙動に揃える)
    fun isFirstSync(notifiedIds: Collection<String>?): Boolean {
        return notifiedIds == null || notifiedIds.isEmpty()
    }

    // 1イベント分の通知判定 (純粋関数)。ID の登録そのものは呼び出し側が行う
    // seeding=true (初回同期) の間は常に false (登録のみ)
    fun shouldNotifyEvent(ev: SyncEvent?, seeding: Boolean, allowed: Boolean,
                          quakeThresholdScale: Int, warnMode: Int, now: Long): Boolean {
        if (ev == null || seeding || !allowed) return false
        if (!isFreshEvent(ev.eventMillis, now)) return false // 1時間超は通知しない
        return when (ev.kind) {
            "quake" -> shouldNotifyQuake(ev.maxScale, quakeThresholdScale)
            "warning" -> warningNotifiable(ev.hasSpecial, ev.hasWarning, warnMode)
            else -> true // tsunami / eew は常時通知
        }
    }

    // EEW の重複排除 ID: 報の時刻ではなく発生時刻ベースにする (続報で再通知しない。C#版と同方針)
    fun eewEventId(originMillis: Long): String {
        return "eew:" + originMillis
    }

    // 通知済み ID リストを上限件数に切り詰める (末尾 = 新しいものを残す)
    fun pruneIds(ids: List<String>, max: Int): MutableList<String> {
        if (ids.size <= max) return ArrayList(ids)
        return ArrayList(ids.subList(ids.size - max, ids.size))
    }

    // 通知済み ID の保存形式 (改行区切り) との相互変換
    fun joinIds(ids: List<String>): String {
        return ids.joinToString("\n")
    }

    fun splitIds(stored: String?): MutableList<String> {
        val out = ArrayList<String>()
        if (stored.isNullOrEmpty()) return out
        for (s in stored.split("\n")) {
            if (s.isNotEmpty() && !out.contains(s)) out.add(s)
        }
        return out
    }

    // 警報イベント ID: 発表日時とコード集合が同じなら同一 (コードの順序に依存しない)
    fun warningEventId(prefCode: String, reportDatetime: String, codes: Collection<String>): String {
        val sorted = ArrayList(codes)
        java.util.Collections.sort(sorted)
        return "warn:" + prefCode + ":" + reportDatetime + ":" + sorted.joinToString(",")
    }

    // ISO8601 (気象庁 reportDatetime 等) を epoch ミリ秒にする (失敗時 0)
    fun parseIsoMillis(iso: String?): Long {
        if (iso == null || iso.length < 19) return 0
        try {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.JAPAN).parse(iso)
            return d?.time ?: 0
        } catch (e: Exception) {
            return 0
        }
    }

    // 次回の朝夕ブリーフィング時刻 (当日の hour:minute を過ぎていれば翌日)
    fun nextBriefingMillis(hour: Int, minute: Int, now: Long, tz: java.util.TimeZone): Long {
        val cal = java.util.Calendar.getInstance(tz)
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    // 同期間隔 (分) → ミリ秒
    fun syncIntervalMillis(minutes: Int): Long {
        return minutes * 60 * 1000L
    }

    // ----- 13.1 同期用イベント取得 (軽いものだけ: P2P 551/552/554 + 地点ごとの警報) -----
    fun quakeSyncEvents(limit: Int): MutableList<SyncEvent> {
        val out = ArrayList<SyncEvent>()
        try {
            for (q in quakeItems(limit)) {
                val id = "quake:" + (if (q.id.isNotEmpty()) q.id else (q.originMillis.toString() + ":" + q.place))
                val title = severityEmoji(q.sev) + " 地震 震度" + scaleToStr(q.maxScale) + " " + q.place
                out.add(SyncEvent("quake", id, title, q.detail, false, q.originMillis, q.maxScale, false, false))
            }
        } catch (e: Exception) {}
        return out
    }

    // 津波: 発表中の最新報を1件返す (なければ null)
    fun tsunamiSyncEvent(): SyncEvent? {
        try {
            val data = JSONArray(fetch("https://api.p2pquake.net/v2/history?codes=552&limit=1"))
            if (data.length() == 0) return null
            val item = data.getJSONObject(0)
            val cancelled = item.optBoolean("cancelled")
            val areas = item.optJSONArray("areas")
            var grade = ""
            var best = -1
            val names = StringBuilder()
            if (areas != null) {
                for (i in 0 until areas.length()) {
                    val a = areas.getJSONObject(i)
                    val g = a.optString("grade")
                    val rank = if ("MajorWarning" == g) 3 else if ("Warning" == g) 2 else if ("Watch" == g) 1 else 0
                    if (rank > best) { best = rank; grade = g }
                    if (names.isNotEmpty()) names.append("、")
                    names.append(a.optString("name"))
                    val mh = a.optJSONObject("maxHeight")
                    if (mh != null && mh.optString("description").isNotEmpty()) {
                        names.append("（予想 ").append(mh.optString("description")).append("）")
                    }
                    val fh = a.optJSONObject("firstHeight")
                    if (fh != null && fh.optString("arrivalTime").isNotEmpty()) {
                        val at = fh.optString("arrivalTime")
                        names.append("（第1波到達予想 ").append(if (at.length >= 16) at.substring(11, 16) else at).append("）")
                    }
                }
            }
            val body: String
            val urgent: Boolean
            if (cancelled) {
                body = "津波警報・注意報は解除されました。対象: " + names
                urgent = false
            } else if ("MajorWarning" == grade) {
                body = "🔴 大津波警報: " + names + "\nただちに高台・津波避難ビルへ避難してください。"
                urgent = true
            } else if ("Warning" == grade) {
                body = "🔴 津波警報: " + names + "\nただちに高台・津波避難ビルへ避難してください。"
                urgent = true
            } else if ("Watch" == grade) {
                body = "🟠 津波注意報: " + names + "\n海から上がり、海岸から離れてください。"
                urgent = true
            } else {
                body = "津波予報/情報: " + names
                urgent = false
            }
            val time = item.optString("time")
            val id = "tsunami:" + (if (item.optString("id").isNotEmpty()) item.optString("id") else time)
            return SyncEvent("tsunami", id, "🌊 津波情報", body, urgent, parseP2pTime(time))
        } catch (e: Exception) {
            return null
        }
    }

    // EEW: 新鮮な最新報を1件返す (なければ null。eewInfo が1時間超を除外済み)
    fun eewSyncEvent(): SyncEvent? {
        val info = eewInfo() ?: return null
        val title = if (info.isCancel) "🚨 緊急地震速報（取消）" else "🚨 緊急地震速報"
        return SyncEvent("eew", eewEventId(info.originMillis), title, eewStaticBody(info), !info.isCancel, info.originMillis)
    }

    // 警報: 地点に発表中の警報・注意報があれば1件返す (なければ null)
    // 警報JSONは地域コードのみのため、予報JSONから一次細分区域コードを得て照合する (warningPart と同じ方式)
    fun warningSyncEvent(loc: Location): SyncEvent? {
        try {
            var areaCode = ""
            try {
                val arr = JSONArray(fetch("https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.prefCode + ".json"))
                val areas = arr.getJSONObject(0).getJSONArray("timeSeries").getJSONObject(0).getJSONArray("areas")
                for (i in 0 until areas.length()) {
                    val a = areas.getJSONObject(i)
                    if (loc.areaName == a.getJSONObject("area").optString("name")) {
                        areaCode = a.getJSONObject("area").optString("code")
                        break
                    }
                }
                if (areaCode.isEmpty() && areas.length() > 0) {
                    areaCode = areas.getJSONObject(0).getJSONObject("area").optString("code")
                }
            } catch (e: Exception) {}
            val warn = JSONObject(fetch("https://www.jma.go.jp/bosai/warning/data/warning/" + loc.prefCode + ".json"))
            val codes = java.util.LinkedHashSet<String>()
            val areaTypes = warn.optJSONArray("areaTypes")
            if (areaTypes != null && areaTypes.length() > 0) {
                val areas = areaTypes.getJSONObject(0).optJSONArray("areas")
                if (areas != null) {
                    for (j in 0 until areas.length()) {
                        val area = areas.getJSONObject(j)
                        if (areaCode.isNotEmpty() && areaCode != area.optString("code")) continue
                        val ws = area.optJSONArray("warnings")
                        if (ws == null) continue
                        for (k in 0 until ws.length()) {
                            val w = ws.getJSONObject(k)
                            if ("解除" == w.optString("status")) continue
                            val code = w.optString("code")
                            if (code.isNotEmpty()) codes.add(code)
                        }
                    }
                }
            }
            if (codes.isEmpty()) return null
            val names = java.util.LinkedHashSet<String>()
            var hasSpecial = false
            var hasWarning = false
            for (code in codes) {
                val nm = WARNING_CODES[code]
                names.add(nm ?: "その他の警報・注意報（コード" + code + "）")
                val rank = warningCodeRank(code)
                if (rank >= 2) hasSpecial = true
                else if (rank == 1) hasWarning = true
            }
            val reportDatetime = warn.optString("reportDatetime")
            val body = StringBuilder("発表中： ").append(names.joinToString("、"))
            if (reportDatetime.length >= 16) {
                body.append("（").append(reportDatetime.substring(5, 10).replace('-', '/'))
                    .append(" ").append(reportDatetime.substring(11, 16)).append("発表）")
            }
            val headline = warn.optString("headlineText")
            if (headline.isNotEmpty() && !headline.contains("解除")) body.append("\n").append(headline)
            var whenMillis = parseIsoMillis(reportDatetime)
            if (whenMillis <= 0) whenMillis = System.currentTimeMillis()
            return SyncEvent("warning", warningEventId(loc.prefCode, reportDatetime, codes),
                    "⚠ 気象警報・注意報（" + loc.name + "）", body.toString(), hasSpecial, whenMillis, -1, hasSpecial, hasWarning)
        } catch (e: Exception) {
            return null
        }
    }

    // バックグラウンド同期1回分: 軽い取得だけを並列に行い、イベント一覧を返す (例外は投げない)
    fun collectSyncEvents(): MutableList<SyncEvent> {
        val out: MutableList<SyncEvent> = java.util.Collections.synchronizedList(ArrayList())
        val futures = ArrayList<java.util.concurrent.Future<*>>()
        futures.add(FETCH_POOL.submit(java.util.concurrent.Callable<Void?> { out.addAll(quakeSyncEvents(10)); null }))
        futures.add(FETCH_POOL.submit(java.util.concurrent.Callable<Void?> { val e = tsunamiSyncEvent(); if (e != null) out.add(e); null }))
        futures.add(FETCH_POOL.submit(java.util.concurrent.Callable<Void?> { val e = eewSyncEvent(); if (e != null) out.add(e); null }))
        for (loc in effectiveLocations()) {
            futures.add(FETCH_POOL.submit(java.util.concurrent.Callable<Void?> { val e = warningSyncEvent(loc); if (e != null) out.add(e); null }))
        }
        for (f in futures) {
            try { f.get(60, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
        }
        return out
    }
}
