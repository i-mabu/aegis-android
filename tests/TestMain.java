import jp.aegis.client.AegisData;

// AEGIS Android 純粋関数の軽量テスト (org.json / Android API に触れない関数のみ)
// 実行: javac -cp build/manual/classes:<android.jar> -d /tmp/aegis-test tests/TestMain.java
//       java  -cp /tmp/aegis-test:build/manual/classes:<android.jar> TestMain
public class TestMain {
    private static int passed = 0;

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("ok: " + name);
    }

    public static void main(String[] args) {
        // 接続テスト (自己診断) の結果整形
        check(AegisData.diagLine("P2P", true, 123, "").equals("○ P2P：接続OK（123ms）"), "diagLine ok");
        check(AegisData.diagLine("P2P", false, 0, "通信エラー").equals("× P2P：失敗（通信エラー）"), "diagLine fail");
        check(AegisData.diagLine("P2P", false, 0, "").contains("不明なエラー"), "diagLine fail empty reason");

        // 球面距離: 高崎〜大宮の直線距離は約72.5km (高崎線の営業キロ74.7kmとも整合)
        double d = AegisData.haversineKm(36.3219, 139.0023, 35.9064, 139.6239);
        check(d > 68 && d < 77, "haversine takasaki-omiya ~72.5km (=" + d + ")");

        // レーダーのタイル番号 (ズーム8, 高崎)
        check(AegisData.tileX(139.0023, 8) == 226, "tileX takasaki z8");
        check(AegisData.tileY(36.3219, 8) == 100, "tileY takasaki z8");
        check(AegisData.tileX(-180, 8) == 0 && AegisData.tileX(180, 8) == 255, "tileX clamp");

        // 都道府県テーブル
        check("群馬県".equals(AegisData.prefNameForCode("100000")), "prefNameForCode gunma");
        check("東京都".equals(AegisData.prefNameForCode("130000")), "prefNameForCode tokyo");
        check("100000".equals(AegisData.nearestPref(36.3219, 139.0023).code), "nearestPref takasaki");
        check("110000".equals(AegisData.nearestPref(35.9064, 139.6239).code), "nearestPref omiya");

        // ニュース要約の切り詰め (HTML除去 + 80字 + …)
        String sum = AegisData.truncateSummary("<p>abc</p>  def", 80);
        check(sum.equals("abc def"), "truncateSummary strips html");
        String longText = "あ".repeat(100);
        String truncated = AegisData.truncateSummary(longText, 80);
        check(truncated.length() == 81 && truncated.endsWith("…"), "truncateSummary truncates with ellipsis");

        // EEW 到達推定: 震源直上 (震央距離0)・深さ35km → 35/3.5 = 10秒後
        AegisData.EewInfo info = new AegisData.EewInfo();
        info.originMillis = 1_000_000L;
        info.lat = AegisData.TAKASAKI.lat;
        info.lon = AegisData.TAKASAKI.lon;
        info.depth = 35;
        boolean[] est = new boolean[1];
        long arr = AegisData.arrivalMillis(info, AegisData.TAKASAKI, est);
        check(arr == 1_010_000L && est[0], "arrivalMillis S-wave estimate");

        // EEW 到達: area の arrivalTime があればそれを優先する
        AegisData.EewInfo info2 = new AegisData.EewInfo();
        info2.originMillis = 1_000_000L;
        info2.areas.add(new AegisData.EewArea("群馬県", "群馬県南部", "1970/01/01 09:20:00"));
        long expected = AegisData.parseP2pTime("1970/01/01 09:20:00");
        long arr2 = AegisData.arrivalMillis(info2, AegisData.TAKASAKI, est);
        check(arr2 == expected && !est[0], "arrivalMillis uses area arrivalTime");

        // カウントダウンの活性判定: 到達前は true、到達+30秒を過ぎたら false
        check(AegisData.countdownActive(info2, new AegisData.Location[]{AegisData.TAKASAKI}, expected - 5000), "countdownActive before arrival");
        check(!AegisData.countdownActive(info2, new AegisData.Location[]{AegisData.TAKASAKI}, expected + 31000), "countdownActive ends after +30s");

        // 天気絵文字
        check("☀".equals(AegisData.weatherEmoji(0)), "weatherEmoji clear");
        check("🌧".equals(AegisData.weatherEmoji(61)), "weatherEmoji rain");
        check("⛈".equals(AegisData.weatherEmoji(95)), "weatherEmoji thunder");

        // ----- v1.6.0: バックグラウンド同期・通知判定 -----
        // 地震の通知しきい値 (境界値)
        check(AegisData.shouldNotifyQuake(30, 30), "shouldNotifyQuake at threshold");
        check(AegisData.shouldNotifyQuake(45, 30), "shouldNotifyQuake above threshold");
        check(!AegisData.shouldNotifyQuake(20, 30), "shouldNotifyQuake below threshold");
        check(!AegisData.shouldNotifyQuake(-1, 10), "shouldNotifyQuake unknown scale");
        check(AegisData.shouldNotifyQuake(45, 45), "shouldNotifyQuake 5弱 at 5弱");

        // 警報コードの格付け
        check(AegisData.warningCodeRank("33") == 2, "warningCodeRank special (大雨特別)");
        check(AegisData.warningCodeRank("38") == 2, "warningCodeRank special (大雪特別)");
        check(AegisData.warningCodeRank("03") == 1, "warningCodeRank warning (大雨警報)");
        check(AegisData.warningCodeRank("10") == 0, "warningCodeRank advisory (大雨注意報)");
        check(AegisData.warningCodeRank("99") == 0, "warningCodeRank unknown -> advisory");

        // 警報の通知可否 (モード別)
        check(AegisData.warningNotifiable(false, true, AegisData.WARN_MODE_ALERTS_ONLY), "warningNotifiable warning in alerts-only");
        check(AegisData.warningNotifiable(true, false, AegisData.WARN_MODE_ALERTS_ONLY), "warningNotifiable special in alerts-only");
        check(!AegisData.warningNotifiable(false, false, AegisData.WARN_MODE_ALERTS_ONLY), "warningNotifiable advisory-only excluded");
        check(AegisData.warningNotifiable(false, false, AegisData.WARN_MODE_INCLUDE_ADVISORY), "warningNotifiable advisory-only included");

        // 鮮度判定 (フラッド防止: 1時間超は通知しない)
        long now = 1_700_000_000_000L;
        check(AegisData.isFreshEvent(now - 30 * 60 * 1000L, now), "isFreshEvent 30min ago");
        check(AegisData.isFreshEvent(now - 60 * 60 * 1000L, now), "isFreshEvent exactly 1h ago");
        check(!AegisData.isFreshEvent(now - 61 * 60 * 1000L, now), "isFreshEvent 61min ago is stale");
        check(!AegisData.isFreshEvent(0, now), "isFreshEvent unknown time");
        check(AegisData.isFreshEvent(now + 60 * 1000L, now), "isFreshEvent slight future (clock skew)");
        check(!AegisData.isFreshEvent(now + 10 * 60 * 1000L, now), "isFreshEvent far future rejected");

        // 通知済み ID の切り詰め (末尾 = 新しいものを残す)
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) ids.add("id" + i);
        java.util.List<String> pruned = AegisData.pruneIds(ids, AegisData.MAX_NOTIFIED_IDS);
        check(pruned.size() == 200 && pruned.get(0).equals("id50") && pruned.get(199).equals("id249"), "pruneIds keeps newest 200");
        check(AegisData.pruneIds(ids.subList(0, 3), 200).size() == 3, "pruneIds under limit unchanged");

        // 通知済み ID の保存形式 (往復・重複排除・空)
        java.util.List<String> roundtrip = AegisData.splitIds(AegisData.joinIds(java.util.Arrays.asList("a", "b", "c")));
        check(roundtrip.equals(java.util.Arrays.asList("a", "b", "c")), "ids join/split roundtrip");
        check(AegisData.splitIds("").isEmpty() && AegisData.splitIds(null).isEmpty(), "splitIds empty");
        check(AegisData.splitIds("a\na\nb").size() == 2, "splitIds dedupes");

        // 警報イベント ID はコードの順序に依存しない
        String wid1 = AegisData.warningEventId("100000", "2026-11-17T11:00:00+09:00", java.util.Arrays.asList("10", "03"));
        String wid2 = AegisData.warningEventId("100000", "2026-11-17T11:00:00+09:00", java.util.Arrays.asList("03", "10"));
        check(wid1.equals(wid2) && wid1.startsWith("warn:100000:"), "warningEventId order-independent");
        check(!wid1.equals(AegisData.warningEventId("100000", "2026-11-17T12:00:00+09:00", java.util.Arrays.asList("03", "10"))),
                "warningEventId differs by report time");

        // ISO8601 パース (気象庁 reportDatetime 形式)
        java.util.Calendar jst = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        jst.set(2026, java.util.Calendar.NOVEMBER, 17, 11, 0, 0);
        jst.set(java.util.Calendar.MILLISECOND, 0);
        check(AegisData.parseIsoMillis("2026-11-17T11:00:00+09:00") == jst.getTimeInMillis(), "parseIsoMillis reportDatetime");
        check(AegisData.parseIsoMillis("not-a-date") == 0 && AegisData.parseIsoMillis(null) == 0, "parseIsoMillis invalid -> 0");

        // 朝夕ブリーフィングの次回時刻 (JST)
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Asia/Tokyo");
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.set(2026, java.util.Calendar.NOVEMBER, 17, 6, 0, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long before7 = c.getTimeInMillis();
        c.set(java.util.Calendar.HOUR_OF_DAY, 7);
        long at7 = c.getTimeInMillis();
        check(AegisData.nextBriefingMillis(7, 0, before7, tz) == at7, "nextBriefing same day 7:00");
        c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        long nextDay7 = c.getTimeInMillis();
        check(AegisData.nextBriefingMillis(7, 0, at7, tz) == nextDay7, "nextBriefing at exactly 7:00 -> next day");
        c.set(2026, java.util.Calendar.NOVEMBER, 17, 21, 30, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        check(AegisData.nextBriefingMillis(7, 0, c.getTimeInMillis(), tz) == nextDay7, "nextBriefing 21:30 -> next day 7:00");
        c.set(2026, java.util.Calendar.NOVEMBER, 17, 20, 0, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long sameDay21 = AegisData.nextBriefingMillis(21, 0, c.getTimeInMillis(), tz);
        java.util.Calendar expect21 = java.util.Calendar.getInstance(tz);
        expect21.set(2026, java.util.Calendar.NOVEMBER, 17, 21, 0, 0);
        expect21.set(java.util.Calendar.MILLISECOND, 0);
        check(sameDay21 == expect21.getTimeInMillis(), "nextBriefing same day 21:00");

        // 同期間隔
        check(AegisData.syncIntervalMillis(15) == 900_000L, "syncIntervalMillis 15min");
        check(AegisData.syncIntervalMillis(30) == 1_800_000L, "syncIntervalMillis 30min");
        check(AegisData.syncIntervalMillis(60) == 3_600_000L, "syncIntervalMillis 60min");

        // ----- 予報絵文字 (降水確率・夜間を考慮) -----
        // 実機スクリーンショットの不整合: 晴れコード + 降水86% (11/18 3時) → 雨系になること
        check("🌦".equals(AegisData.forecastEmoji(0, 86, 3)), "forecastEmoji clear+pop86 -> rain (shower)");
        check("🌦".equals(AegisData.forecastEmoji(2, 55, 15)), "forecastEmoji partly cloudy+pop55 -> shower");
        check("☔".equals(AegisData.forecastEmoji(3, 60, 12)), "forecastEmoji cloudy+pop60 -> umbrella");
        check("🌧".equals(AegisData.forecastEmoji(3, 90, 12)), "forecastEmoji cloudy+pop90 -> rain");
        // 夜間の晴れは月 (雨系が優先される場合を除く)
        check("🌙".equals(AegisData.forecastEmoji(0, 10, 3)), "forecastEmoji night clear -> moon");
        check("🌙".equals(AegisData.forecastEmoji(1, 0, 22)), "forecastEmoji 22h clear -> moon");
        check("☀".equals(AegisData.forecastEmoji(0, 10, 12)), "forecastEmoji day clear -> sun");
        check("☁".equals(AegisData.forecastEmoji(3, 10, 23)), "forecastEmoji night cloudy stays cloudy");
        // 境界・既存コードはそのまま
        check("☀".equals(AegisData.forecastEmoji(0, 49, 12)), "forecastEmoji pop49 no override");
        check("☀".equals(AegisData.forecastEmoji(0, -1, 12)), "forecastEmoji unknown pop no override");
        check("🌧".equals(AegisData.forecastEmoji(61, 90, 12)), "forecastEmoji rain code unchanged");
        check("🌨".equals(AegisData.forecastEmoji(71, 90, 12)), "forecastEmoji snow code unchanged");
        check("⛈".equals(AegisData.forecastEmoji(95, 20, 12)), "forecastEmoji thunder unchanged");

        System.out.println("ALL TESTS PASSED (" + passed + ")");
    }
}
