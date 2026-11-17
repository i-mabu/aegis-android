@file:JvmName("TestMain")

import jp.aegis.client.AegisData

// AEGIS Android 純粋関数の軽量テスト (org.json / Android API に触れない関数のみ)
// 実行: kotlinc -cp build/manual/classes:<android.jar>:<kotlin-stdlib.jar> -d /tmp/aegis-test tests/TestMain.kt
//       java  -cp /tmp/aegis-test:build/manual/classes:<android.jar>:<kotlin-stdlib.jar> TestMain
private var passed = 0

private fun check(cond: Boolean, name: String) {
    if (!cond) throw AssertionError("FAILED: " + name)
    passed++
    println("ok: " + name)
}

private fun rainHas(tiles: List<IntArray>, tx: Int, ty: Int, dx: Int, dy: Int): Boolean {
    for (t in tiles) {
        if (t[0] == tx && t[1] == ty && t[2] == dx && t[3] == dy) return true
    }
    return false
}

fun main() {
    // 接続テスト (自己診断) の結果整形
    check(AegisData.diagLine("P2P", true, 123, "") == "○ P2P：接続OK（123ms）", "diagLine ok")
    check(AegisData.diagLine("P2P", false, 0, "通信エラー") == "× P2P：失敗（通信エラー）", "diagLine fail")
    check(AegisData.diagLine("P2P", false, 0, "").contains("不明なエラー"), "diagLine fail empty reason")

    // 球面距離: 高崎〜大宮の直線距離は約72.5km (高崎線の営業キロ74.7kmとも整合)
    val d = AegisData.haversineKm(36.3219, 139.0023, 35.9064, 139.6239)
    check(d > 68 && d < 77, "haversine takasaki-omiya ~72.5km (=" + d + ")")

    // レーダーのタイル番号 (ズーム8, 高崎)
    check(AegisData.tileX(139.0023, 8) == 226, "tileX takasaki z8")
    check(AegisData.tileY(36.3219, 8) == 100, "tileY takasaki z8")
    check(AegisData.tileX(-180.0, 8) == 0 && AegisData.tileX(180.0, 8) == 255, "tileX clamp")

    // レーダーの雨タイル (z7) カバー範囲: [tx, ty, 描画X, 描画Y]、描画サイズは 512×512
    // 中心タイルが偶数 (高崎 cx=226, cy=100 → 原点 ox=225, oy=99): 2×2枚、オフセット -256/256
    val rainEven = AegisData.rainTilesFor(225, 99)
    check(rainEven.size == 4, "rainTiles even count")
    check(rainHas(rainEven, 112, 49, -256, -256) && rainHas(rainEven, 113, 49, 256, -256)
            && rainHas(rainEven, 112, 50, -256, 256) && rainHas(rainEven, 113, 50, 256, 256),
            "rainTiles even set/offsets")
    // 中心タイルが奇数 (ox=226, oy=100): 2×2枚、オフセット 0/512 (キャンバス外へのはみ出しは正常)
    val rainOdd = AegisData.rainTilesFor(226, 100)
    check(rainOdd.size == 4, "rainTiles odd count")
    check(rainHas(rainOdd, 113, 50, 0, 0) && rainHas(rainOdd, 114, 50, 512, 0)
            && rainHas(rainOdd, 113, 51, 0, 512) && rainHas(rainOdd, 114, 51, 512, 512),
            "rainTiles odd set/offsets")

    // 都道府県テーブル
    check("群馬県" == AegisData.prefNameForCode("100000"), "prefNameForCode gunma")
    check("東京都" == AegisData.prefNameForCode("130000"), "prefNameForCode tokyo")
    check("100000" == AegisData.nearestPref(36.3219, 139.0023).code, "nearestPref takasaki")
    check("110000" == AegisData.nearestPref(35.9064, 139.6239).code, "nearestPref omiya")

    // ニュース要約の切り詰め (HTML除去 + 80字 + …)
    val sum = AegisData.truncateSummary("<p>abc</p>  def", 80)
    check(sum == "abc def", "truncateSummary strips html")
    val longText = "あ".repeat(100)
    val truncated = AegisData.truncateSummary(longText, 80)
    check(truncated.length == 81 && truncated.endsWith("…"), "truncateSummary truncates with ellipsis")

    // EEW 到達推定: 震源直上 (震央距離0)・深さ35km → 35/3.5 = 10秒後
    val info = AegisData.EewInfo()
    info.originMillis = 1_000_000L
    info.lat = AegisData.TAKASAKI.lat
    info.lon = AegisData.TAKASAKI.lon
    info.depth = 35
    val est = BooleanArray(1)
    val arr = AegisData.arrivalMillis(info, AegisData.TAKASAKI, est)
    check(arr == 1_010_000L && est[0], "arrivalMillis S-wave estimate")

    // EEW 到達: area の arrivalTime があればそれを優先する
    val info2 = AegisData.EewInfo()
    info2.originMillis = 1_000_000L
    info2.areas.add(AegisData.EewArea("群馬県", "群馬県南部", "1970/01/01 09:20:00"))
    val expected = AegisData.parseP2pTime("1970/01/01 09:20:00")
    val arr2 = AegisData.arrivalMillis(info2, AegisData.TAKASAKI, est)
    check(arr2 == expected && !est[0], "arrivalMillis uses area arrivalTime")

    // カウントダウンの活性判定: 到達前は true、到達+30秒を過ぎたら false
    check(AegisData.countdownActive(info2, arrayOf(AegisData.TAKASAKI), expected - 5000), "countdownActive before arrival")
    check(!AegisData.countdownActive(info2, arrayOf(AegisData.TAKASAKI), expected + 31000), "countdownActive ends after +30s")

    // 天気絵文字
    check("☀" == AegisData.weatherEmoji(0), "weatherEmoji clear")
    check("🌧" == AegisData.weatherEmoji(61), "weatherEmoji rain")
    check("⛈" == AegisData.weatherEmoji(95), "weatherEmoji thunder")

    // ----- v1.6.0: バックグラウンド同期・通知判定 -----
    // 地震の通知しきい値 (境界値)
    check(AegisData.shouldNotifyQuake(30, 30), "shouldNotifyQuake at threshold")
    check(AegisData.shouldNotifyQuake(45, 30), "shouldNotifyQuake above threshold")
    check(!AegisData.shouldNotifyQuake(20, 30), "shouldNotifyQuake below threshold")
    check(!AegisData.shouldNotifyQuake(-1, 10), "shouldNotifyQuake unknown scale")
    check(AegisData.shouldNotifyQuake(45, 45), "shouldNotifyQuake 5弱 at 5弱")

    // 警報コードの格付け
    check(AegisData.warningCodeRank("33") == 2, "warningCodeRank special (大雨特別)")
    check(AegisData.warningCodeRank("38") == 2, "warningCodeRank special (大雪特別)")
    check(AegisData.warningCodeRank("03") == 1, "warningCodeRank warning (大雨警報)")
    check(AegisData.warningCodeRank("10") == 0, "warningCodeRank advisory (大雨注意報)")
    check(AegisData.warningCodeRank("99") == 0, "warningCodeRank unknown -> advisory")

    // 警報の通知可否 (モード別)
    check(AegisData.warningNotifiable(false, true, AegisData.WARN_MODE_ALERTS_ONLY), "warningNotifiable warning in alerts-only")
    check(AegisData.warningNotifiable(true, false, AegisData.WARN_MODE_ALERTS_ONLY), "warningNotifiable special in alerts-only")
    check(!AegisData.warningNotifiable(false, false, AegisData.WARN_MODE_ALERTS_ONLY), "warningNotifiable advisory-only excluded")
    check(AegisData.warningNotifiable(false, false, AegisData.WARN_MODE_INCLUDE_ADVISORY), "warningNotifiable advisory-only included")

    // 鮮度判定 (フラッド防止: 1時間超は通知しない)
    val now = 1_700_000_000_000L
    check(AegisData.isFreshEvent(now - 30 * 60 * 1000L, now), "isFreshEvent 30min ago")
    check(AegisData.isFreshEvent(now - 60 * 60 * 1000L, now), "isFreshEvent exactly 1h ago")
    check(!AegisData.isFreshEvent(now - 61 * 60 * 1000L, now), "isFreshEvent 61min ago is stale")
    check(!AegisData.isFreshEvent(0, now), "isFreshEvent unknown time")
    check(AegisData.isFreshEvent(now + 60 * 1000L, now), "isFreshEvent slight future (clock skew)")
    check(!AegisData.isFreshEvent(now + 10 * 60 * 1000L, now), "isFreshEvent far future rejected")

    // 通知済み ID の切り詰め (末尾 = 新しいものを残す)
    val ids = ArrayList<String>()
    for (i in 0 until 250) ids.add("id" + i)
    val pruned = AegisData.pruneIds(ids, AegisData.MAX_NOTIFIED_IDS)
    check(pruned.size == 200 && pruned[0] == "id50" && pruned[199] == "id249", "pruneIds keeps newest 200")
    check(AegisData.pruneIds(ids.subList(0, 3), 200).size == 3, "pruneIds under limit unchanged")

    // 通知済み ID の保存形式 (往復・重複排除・空)
    val roundtrip = AegisData.splitIds(AegisData.joinIds(listOf("a", "b", "c")))
    check(roundtrip == listOf("a", "b", "c"), "ids join/split roundtrip")
    check(AegisData.splitIds("").isEmpty() && AegisData.splitIds(null).isEmpty(), "splitIds empty")
    check(AegisData.splitIds("a\na\nb").size == 2, "splitIds dedupes")

    // 警報イベント ID はコードの順序に依存しない
    val wid1 = AegisData.warningEventId("100000", "2026-11-17T11:00:00+09:00", listOf("10", "03"))
    val wid2 = AegisData.warningEventId("100000", "2026-11-17T11:00:00+09:00", listOf("03", "10"))
    check(wid1 == wid2 && wid1.startsWith("warn:100000:"), "warningEventId order-independent")
    check(wid1 != AegisData.warningEventId("100000", "2026-11-17T12:00:00+09:00", listOf("03", "10")),
            "warningEventId differs by report time")

    // ISO8601 パース (気象庁 reportDatetime 形式)
    val jst = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
    jst.set(2026, java.util.Calendar.NOVEMBER, 17, 11, 0, 0)
    jst.set(java.util.Calendar.MILLISECOND, 0)
    check(AegisData.parseIsoMillis("2026-11-17T11:00:00+09:00") == jst.timeInMillis, "parseIsoMillis reportDatetime")
    check(AegisData.parseIsoMillis("not-a-date") == 0L && AegisData.parseIsoMillis(null) == 0L, "parseIsoMillis invalid -> 0")

    // 朝夕ブリーフィングの次回時刻 (JST)
    val tz = java.util.TimeZone.getTimeZone("Asia/Tokyo")
    val c = java.util.Calendar.getInstance(tz)
    c.set(2026, java.util.Calendar.NOVEMBER, 17, 6, 0, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    val before7 = c.timeInMillis
    c.set(java.util.Calendar.HOUR_OF_DAY, 7)
    val at7 = c.timeInMillis
    check(AegisData.nextBriefingMillis(7, 0, before7, tz) == at7, "nextBriefing same day 7:00")
    c.add(java.util.Calendar.DAY_OF_MONTH, 1)
    val nextDay7 = c.timeInMillis
    check(AegisData.nextBriefingMillis(7, 0, at7, tz) == nextDay7, "nextBriefing at exactly 7:00 -> next day")
    c.set(2026, java.util.Calendar.NOVEMBER, 17, 21, 30, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    check(AegisData.nextBriefingMillis(7, 0, c.timeInMillis, tz) == nextDay7, "nextBriefing 21:30 -> next day 7:00")
    c.set(2026, java.util.Calendar.NOVEMBER, 17, 20, 0, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    val sameDay21 = AegisData.nextBriefingMillis(21, 0, c.timeInMillis, tz)
    val expect21 = java.util.Calendar.getInstance(tz)
    expect21.set(2026, java.util.Calendar.NOVEMBER, 17, 21, 0, 0)
    expect21.set(java.util.Calendar.MILLISECOND, 0)
    check(sameDay21 == expect21.timeInMillis, "nextBriefing same day 21:00")

    // 同期間隔
    check(AegisData.syncIntervalMillis(15) == 900_000L, "syncIntervalMillis 15min")
    check(AegisData.syncIntervalMillis(30) == 1_800_000L, "syncIntervalMillis 30min")
    check(AegisData.syncIntervalMillis(60) == 3_600_000L, "syncIntervalMillis 60min")

    // ----- 予報絵文字 (降水確率・夜間を考慮) -----
    // 実機スクリーンショットの不整合: 晴れコード + 降水86% (11/18 3時) → 雨系になること
    check("🌦" == AegisData.forecastEmoji(0, 86.0, 3), "forecastEmoji clear+pop86 -> rain (shower)")
    check("🌦" == AegisData.forecastEmoji(2, 55.0, 15), "forecastEmoji partly cloudy+pop55 -> shower")
    check("☔" == AegisData.forecastEmoji(3, 60.0, 12), "forecastEmoji cloudy+pop60 -> umbrella")
    check("🌧" == AegisData.forecastEmoji(3, 90.0, 12), "forecastEmoji cloudy+pop90 -> rain")
    // 夜間の晴れは月 (雨系が優先される場合を除く)
    check("🌙" == AegisData.forecastEmoji(0, 10.0, 3), "forecastEmoji night clear -> moon")
    check("🌙" == AegisData.forecastEmoji(1, 0.0, 22), "forecastEmoji 22h clear -> moon")
    check("☀" == AegisData.forecastEmoji(0, 10.0, 12), "forecastEmoji day clear -> sun")
    check("☁" == AegisData.forecastEmoji(3, 10.0, 23), "forecastEmoji night cloudy stays cloudy")
    // 境界・既存コードはそのまま
    check("☀" == AegisData.forecastEmoji(0, 49.0, 12), "forecastEmoji pop49 no override")
    check("☀" == AegisData.forecastEmoji(0, -1.0, 12), "forecastEmoji unknown pop no override")
    check("🌧" == AegisData.forecastEmoji(61, 90.0, 12), "forecastEmoji rain code unchanged")
    check("🌨" == AegisData.forecastEmoji(71, 90.0, 12), "forecastEmoji snow code unchanged")
    check("⛈" == AegisData.forecastEmoji(95, 20.0, 12), "forecastEmoji thunder unchanged")

    // ----- 表表示の行データ (純粋ビルダー) -----
    // 曜日 (2026-11-17 は火曜、11/15 は日曜)
    check("火" == AegisData.weekdayJa("2026-11-17"), "weekdayJa tue")
    check("日" == AegisData.weekdayJa("2026-11-15"), "weekdayJa sun")
    check("水" == AegisData.weekdayJa("2026-11-18"), "weekdayJa wed")
    check("" == AegisData.weekdayJa("invalid"), "weekdayJa invalid -> empty")

    // 時間別: 過去と3時間刻みでないものを除き、降水86%の深夜は雨系絵文字になる
    val tzJst = java.util.TimeZone.getTimeZone("Asia/Tokyo")
    val nowCal = java.util.Calendar.getInstance(tzJst)
    nowCal.set(2026, java.util.Calendar.NOVEMBER, 17, 13, 0, 0)
    nowCal.set(java.util.Calendar.MILLISECOND, 0)
    val hourRows = AegisData.buildHourRows(
            arrayOf("2026-11-17T12:00", "2026-11-17T15:00", "2026-11-17T16:00", "2026-11-18T03:00"),
            doubleArrayOf(10.0, 12.0, 13.0, 9.0),
            doubleArrayOf(10.0, 20.0, 30.0, 86.0),
            intArrayOf(0, 1, 61, 0),
            nowCal.timeInMillis, tzJst)
    check(hourRows.size == 2, "buildHourRows filters past and non-3h")
    check(hourRows[0].hour == 15 && "11/17" == hourRows[0].date && "火" == hourRows[0].weekday,
            "buildHourRows first row date/weekday")
    check("☀" == hourRows[0].emoji && hourRows[0].temp == 12L && hourRows[0].pop == 20,
            "buildHourRows first row values")
    check(hourRows[1].hour == 3 && "11/18" == hourRows[1].date && "水" == hourRows[1].weekday,
            "buildHourRows second row next day")
    check("🌦" == hourRows[1].emoji && hourRows[1].pop == 86,
            "buildHourRows pop86 clear code -> rain emoji")

    // 週間: 降水確率最大 50% 以上で雨系を優先、気温は丸める
    val dayRows = AegisData.buildDayRows(
            arrayOf("2026-11-17", "2026-11-18"),
            intArrayOf(0, 0),
            doubleArrayOf(21.4, 18.2),
            doubleArrayOf(9.3, 8.1),
            doubleArrayOf(10.0, 86.0))
    check(dayRows.size == 2, "buildDayRows size")
    check("☀" == dayRows[0].emoji && dayRows[0].maxTemp == 21L && dayRows[0].minTemp == 9L
            && dayRows[0].pop == 10, "buildDayRows first row")
    check("🌦" == dayRows[1].emoji && dayRows[1].pop == 86 && "水" == dayRows[1].weekday,
            "buildDayRows rainy day emoji")

    // 気象庁 降水確率: 時間帯の繰り上げと、空値は不明 (-1)
    val popRows = AegisData.buildPopRows(
            arrayOf("2026-11-17T12:00:00+09:00", "2026-11-17T18:00:00+09:00", "2026-11-18T00:00:00+09:00"),
            arrayOf("30", "", "50"))
    check(popRows.size == 3, "buildPopRows size")
    check(popRows[0].fromHour == 12 && popRows[0].toHour == 18 && popRows[0].pop == 30
            && "11/17" == popRows[0].date, "buildPopRows first row")
    check(popRows[1].fromHour == 18 && popRows[1].toHour == 0 && popRows[1].pop == -1,
            "buildPopRows wraps midnight, empty -> -1")
    check("11/18" == popRows[2].date && "水" == popRows[2].weekday && popRows[2].pop == 50,
            "buildPopRows next day")

    // ----- v1.6.1: 初回同期のシーディングと通知判定 -----
    check(AegisData.isFirstSync(ArrayList<String>()), "isFirstSync empty -> true")
    check(AegisData.isFirstSync(null), "isFirstSync null -> true")
    check(!AegisData.isFirstSync(listOf("quake:1")), "isFirstSync with ids -> false")

    val t0 = 1_700_000_000_000L
    val freshQuake = AegisData.SyncEvent("quake", "quake:1", "t", "b", false, t0, 45, false, false)
    val smallQuake = AegisData.SyncEvent("quake", "quake:2", "t", "b", false, t0, 20, false, false)
    val freshEew = AegisData.SyncEvent("eew", AegisData.eewEventId(t0), "t", "b", true, t0)
    val staleEew = AegisData.SyncEvent("eew", AegisData.eewEventId(t0 - 2 * 3600_000L), "t", "b", true, t0 - 2 * 3600_000L)
    val advisoryOnly = AegisData.SyncEvent("warning", "warn:1", "t", "b", false, t0, -1, false, false)
    val warningEv = AegisData.SyncEvent("warning", "warn:2", "t", "b", false, t0, -1, false, true)
    // 初回 (シーディング中) は新鮮なイベントでも通知しない (登録のみ)
    check(!AegisData.shouldNotifyEvent(freshQuake, true, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent seeding suppresses quake")
    check(!AegisData.shouldNotifyEvent(freshEew, true, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent seeding suppresses eew")
    // 2回目以降は条件どおり
    check(AegisData.shouldNotifyEvent(freshQuake, false, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent quake above threshold")
    check(!AegisData.shouldNotifyEvent(smallQuake, false, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent quake below threshold")
    check(AegisData.shouldNotifyEvent(freshEew, false, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent eew always")
    check(!AegisData.shouldNotifyEvent(staleEew, false, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent stale eew suppressed")
    check(!AegisData.shouldNotifyEvent(freshQuake, false, false, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent notifications not allowed")
    check(!AegisData.shouldNotifyEvent(advisoryOnly, false, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent advisory-only excluded")
    check(AegisData.shouldNotifyEvent(advisoryOnly, false, true, 30, AegisData.WARN_MODE_INCLUDE_ADVISORY, t0), "shouldNotifyEvent advisory included by mode")
    check(AegisData.shouldNotifyEvent(warningEv, false, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent warning in alerts-only")
    check(!AegisData.shouldNotifyEvent(null, false, true, 30, AegisData.WARN_MODE_ALERTS_ONLY, t0), "shouldNotifyEvent null event")

    // EEW の重複排除 ID は発生時刻ベース (続報=reportMillis が変わっても同一 ID)
    check("eew:1700000000000" == AegisData.eewEventId(1_700_000_000_000L), "eewEventId origin-based format")
    check(AegisData.eewEventId(t0) == AegisData.eewEventId(t0), "eewEventId stable for same origin")

    // ラベル：値 の分解 (全角/半角コロン、区切りなし、長すぎるラベルは分解しない)
    val srows = AegisData.summaryRows("⚠ 発表中：大雨警報\n🌊 津波: なし\n注記のみの行")
    check(srows.size == 3, "summaryRows size")
    check("⚠ 発表中" == srows[0][0] && "大雨警報" == srows[0][1], "summaryRows fullwidth colon")
    check("🌊 津波" == srows[1][0] && "なし" == srows[1][1], "summaryRows ascii colon")
    check("注記のみの行" == srows[2][0] && srows[2][1].isEmpty(), "summaryRows no separator")
    val longLabel = AegisData.summaryRows("（天気予報を取得できませんでした：通信エラー）")
    check(longLabel.size == 1 && longLabel[0][1].isEmpty(), "summaryRows long label not split")
    check(AegisData.summaryRows("").isEmpty() && AegisData.summaryRows(null).isEmpty(), "summaryRows empty")

    println("ALL TESTS PASSED (" + passed + ")")
}
