package jp.aegis.client;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ===== 1. MainActivity =====
public class MainActivity extends Activity {
    private static final String PREFS = "aegis_prefs";
    private static final String KEY_MODE = "mode"; // direct | server
    private static final String KEY_SERVER = "server_url";

    private TextView content;
    private TextView status;
    private View settingsPanel;
    private RadioGroup modeGroup;
    private EditText serverUrlInput;
    private String currentTab = "briefing";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 自動更新: 表示中は5分ごとに再取得する
    private final Runnable autoRefresh = this::autoRefreshTick;

    private void autoRefreshTick() {
        if (!"settings".equals(currentTab)) loadCurrent();
        mainHandler.postDelayed(autoRefresh, 5 * 60 * 1000L);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);
        status = findViewById(R.id.status);
        settingsPanel = findViewById(R.id.settingsPanel);
        modeGroup = findViewById(R.id.modeGroup);
        serverUrlInput = findViewById(R.id.serverUrl);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, "direct");
        modeGroup.check("server".equals(mode) ? R.id.modeServer : R.id.modeDirect);
        serverUrlInput.setText(prefs.getString(KEY_SERVER, "http://10.0.2.2:8765"));

        Button tabBriefing = findViewById(R.id.tabBriefing);
        Button tabQuakes = findViewById(R.id.tabQuakes);
        Button tabSettings = findViewById(R.id.tabSettings);
        Button refresh = findViewById(R.id.refreshButton);
        Button saveSettings = findViewById(R.id.saveSettings);
        Button testConnection = findViewById(R.id.testConnection);

        tabBriefing.setOnClickListener(v -> showTab("briefing"));
        tabQuakes.setOnClickListener(v -> showTab("quakes"));
        tabSettings.setOnClickListener(v -> showTab("settings"));
        refresh.setOnClickListener(v -> loadCurrent());
        testConnection.setOnClickListener(v -> testServer());
        saveSettings.setOnClickListener(v -> {
            String m = modeGroup.getCheckedRadioButtonId() == R.id.modeServer ? "server" : "direct";
            prefs.edit()
                    .putString(KEY_MODE, m)
                    .putString(KEY_SERVER, serverUrlInput.getText().toString().trim())
                    .apply();
            status.setText("設定を保存しました（モード: " + ("server".equals(m) ? "AEGISサーバー" : "直接取得") + "）");
            showTab("briefing");
        });

        showTab("briefing");
    }

    private void showTab(String tab) {
        currentTab = tab;
        if ("settings".equals(tab)) {
            settingsPanel.setVisibility(View.VISIBLE);
            content.setText("⚙ 設定\n\n"
                    + "・直接取得: スマホが P2P地震情報・気象庁・Open-Meteo から直接取ります（推奨）\n"
                    + "・AEGISサーバー: PC等で動いている AEGIS (aegis serve) の /api/briefing を表示します\n\n"
                    + "サーバーURLの例:\n"
                    + "・同じWi-FiのPC: http://192.168.1.10:8765\n"
                    + "・AndroidエミュレータからPC: http://10.0.2.2:8765\n");
            status.setText("設定");
            return;
        }
        settingsPanel.setVisibility(View.GONE);
        loadCurrent();
    }

    private void loadCurrent() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, "direct");
        String server = prefs.getString(KEY_SERVER, "http://10.0.2.2:8765");
        String tab = currentTab;
        status.setText("取得中…");
        content.setText("読み込んでいます…");
        executor.execute(() -> {
            String text;
            try {
                if ("quakes".equals(tab)) {
                    if ("server".equals(mode)) {
                        text = AegisData.briefingFromServer(server);
                    } else {
                        text = "🛡️ 地震情報（最新15件）\n" + "=".repeat(48) + "\n"
                                + AegisData.earthquakesText(15)
                                + AegisData.tsunamiText();
                    }
                } else {
                    text = "server".equals(mode)
                            ? AegisData.briefingFromServer(server)
                            : AegisData.briefingDirect();
                }
            } catch (Exception e) {
                text = "取得に失敗しました: " + e.getMessage()
                        + "\n\n通信状態を確認してください。"
                        + ("server".equals(mode) ? "\nサーバーモードの場合は、AEGISサーバーが起動していて、URLが正しいか確認してください。" : "");
            }
            String result = text;
            String now = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(new Date());
            mainHandler.post(() -> {
                content.setText(result);
                status.setText("最終更新: " + now + "（" + ("server".equals(mode) ? "サーバー" : "直接取得") + "）");
            });
        });
    }

    // 設定タブの接続テスト: 入力中のURLで /api/history を叩いて結果を表示する
    private void testServer() {
        String base = serverUrlInput.getText().toString().trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        final String url = base + "/api/history";
        status.setText("接続テスト中… " + url);
        executor.execute(() -> {
            String msg;
            try {
                String body = AegisData.fetch(url);
                msg = "✅ 接続OK: " + url + "（応答 " + body.length() + " 文字）";
            } catch (Exception e) {
                msg = "❌ 接続失敗: " + e.getMessage() + "（サーバー起動とURL・同一Wi-Fiを確認）";
            }
            String result = msg;
            mainHandler.post(() -> status.setText(result));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(autoRefresh);
        mainHandler.postDelayed(autoRefresh, 5 * 60 * 1000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(autoRefresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
