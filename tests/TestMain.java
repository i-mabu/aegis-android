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

        System.out.println("ALL TESTS PASSED (" + passed + ")");
    }
}
