package jp.aegis.client;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ===== 1. MainActivity (カードUI) =====
public class MainActivity extends Activity {
    private static final String PREFS = "aegis_prefs";
    private static final String KEY_MODE = "mode"; // direct | server
    private static final String KEY_SERVER = "server_url";
    private static final String KEY_CUSTOMS = "custom_locations";

    private LinearLayout container;
    private TextView status;
    private View settingsPanel;
    private RadioGroup modeGroup;
    private EditText serverUrlInput;
    private EditText locNameInput, locLatInput, locLonInput, locPrefInput, locAreaInput;
    private LinearLayout customLocList;
    // バックグラウンド同期・通知の設定コントロール
    private Switch bgSyncSwitch, briefingNotifySwitch;
    private RadioGroup syncIntervalGroup, quakeThresholdGroup, warnModeGroup;
    private TextView bgSyncStatus, notifyPermStatus;
    private String currentTab = "briefing";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 自動更新: 表示中は5分ごとに再取得する
    private final Runnable autoRefresh = this::autoRefreshTick;

    private void autoRefreshTick() {
        if (!"settings".equals(currentTab)) loadCurrent();
        mainHandler.postDelayed(autoRefresh, 5 * 60 * 1000L);
    }

    // 地震モニター: 地震タブ表示中は30秒ごとに自動更新する
    private final Runnable monitorTick = this::monitorTickRun;

    private void monitorTickRun() {
        if (!"quakes".equals(currentTab)) return; // タブを離れたら停止
        if (!demoMode) loadCurrent(); // テスト表示中は実データで上書きしない
        mainHandler.postDelayed(monitorTick, 30 * 1000L);
    }

    // EEW 到達カウントダウン: 1秒ごとにカード本文を再描画する
    private AegisData.EewInfo currentEew;
    private TextView eewBodyView;
    private final Runnable eewTick = this::eewTickRun;

    private void eewTickRun() {
        if (eewBodyView == null || currentEew == null) return;
        long now = System.currentTimeMillis();
        AegisData.Location[] locs = AegisData.effectiveLocations();
        if (AegisData.countdownActive(currentEew, locs, now)) {
            eewBodyView.setText(AegisData.eewCountdownBody(currentEew, locs, now));
            mainHandler.postDelayed(eewTick, 1000L);
        } else {
            eewBodyView.setText(AegisData.eewStaticBody(currentEew)); // 通常の EEW 表示に戻す
        }
    }

    private void startEewTicker() {
        mainHandler.removeCallbacks(eewTick);
        eewTickRun();
    }

    private void stopEewTicker() {
        mainHandler.removeCallbacks(eewTick);
        eewBodyView = null;
        currentEew = null;
    }

    // レーダータブの状態
    private int radarLocIndex = 0;
    private ImageView radarImageView;
    private TextView radarCaptionView;

    // テスト表示 (デモ) の状態
    private boolean demoMode = false;
    private AegisData.EewInfo demoEew;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        container = findViewById(R.id.contentContainer);
        status = findViewById(R.id.status);
        settingsPanel = findViewById(R.id.settingsPanel);
        modeGroup = findViewById(R.id.modeGroup);
        serverUrlInput = findViewById(R.id.serverUrl);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, "direct");
        modeGroup.check("server".equals(mode) ? R.id.modeServer : R.id.modeDirect);
        serverUrlInput.setText(prefs.getString(KEY_SERVER, "http://10.0.2.2:8765"));

        locNameInput = findViewById(R.id.locName);
        locLatInput = findViewById(R.id.locLat);
        locLonInput = findViewById(R.id.locLon);
        locPrefInput = findViewById(R.id.locPref);
        locAreaInput = findViewById(R.id.locArea);
        customLocList = findViewById(R.id.customLocList);
        loadCustomLocations();
        Button addLocation = findViewById(R.id.addLocation);
        addLocation.setOnClickListener(v -> addCustomLocation());

        Button tabBriefing = findViewById(R.id.tabBriefing);
        Button tabQuakes = findViewById(R.id.tabQuakes);
        Button tabRadar = findViewById(R.id.tabRadar);
        Button tabNews = findViewById(R.id.tabNews);
        Button tabSettings = findViewById(R.id.tabSettings);
        Button refresh = findViewById(R.id.refreshButton);
        Button saveSettings = findViewById(R.id.saveSettings);
        Button testConnection = findViewById(R.id.testConnection);
        Button gpsButton = findViewById(R.id.gpsButton);
        Button diagTest = findViewById(R.id.diagTest);
        Button demoTest = findViewById(R.id.demoTest);

        tabBriefing.setOnClickListener(v -> showTab("briefing"));
        tabQuakes.setOnClickListener(v -> showTab("quakes"));
        tabRadar.setOnClickListener(v -> showTab("radar"));
        tabNews.setOnClickListener(v -> showTab("news"));
        tabSettings.setOnClickListener(v -> showTab("settings"));
        refresh.setOnClickListener(v -> loadCurrent());
        gpsButton.setOnClickListener(v -> onGpsClicked());
        diagTest.setOnClickListener(v -> runDiagnose());
        demoTest.setOnClickListener(v -> {
            if (demoMode) disableDemo();
            else enableDemo();
        });
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

        initNotificationSettings();
        AegisSync.ensureChannels(this);
        AegisSync.scheduleNextSync(this);
        AegisSync.scheduleBriefings(this);

        // 通知タップで開かれた場合は該当タブを初期表示にする
        String initialTab = getIntent() == null ? null : getIntent().getStringExtra(AegisSync.EXTRA_TAB);
        showTab("quakes".equals(initialTab) ? "quakes" : "briefing");
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String tab = intent == null ? null : intent.getStringExtra(AegisSync.EXTRA_TAB);
        if ("quakes".equals(tab)) showTab("quakes");
        else if ("briefing".equals(tab)) showTab("briefing");
    }

    private void showTab(String tab) {
        currentTab = tab;
        mainHandler.removeCallbacks(monitorTick);
        if ("settings".equals(tab)) {
            stopEewTicker();
            refreshSyncStatus();
            settingsPanel.setVisibility(View.VISIBLE);
            List<AegisData.Section> sections = new ArrayList<>();
            sections.add(new AegisData.Section("⚙ 設定について",
                    "・直接取得: スマホが P2P地震情報・気象庁・Open-Meteo から直接取ります（推奨）\n"
                            + "・AEGISサーバー: PC等で動いている AEGIS (serve) の /api/briefing を表示します\n\n"
                            + "サーバーURLの例:\n"
                            + "・同じWi-FiのPC: http://192.168.1.10:8765\n"
                            + "・AndroidエミュレータからPC: http://10.0.2.2:8765\n\n"
                            + "レーダー・ニュースは直接取得の機能です（サーバーモードでも使えます）。\n"
                            + "表示中は5分ごとに自動更新されます（地震タブは30秒ごと）。\n"
                            + "バックグラウンド同期が ON の間は、アプリを閉じていても定期的に新しい地震・警報を確認して通知します。",
                    "info"));
            render(sections, new ArrayList<>());
            status.setText("設定");
            return;
        }
        settingsPanel.setVisibility(View.GONE);
        if ("quakes".equals(tab)) {
            mainHandler.postDelayed(monitorTick, 30 * 1000L);
        }
        if (demoMode && ("briefing".equals(tab) || "quakes".equals(tab))) {
            renderDemo(tab);
            return;
        }
        loadCurrent();
    }

    // コンテナ内のカード類だけを取り除く (設定パネルはコンテナの子なので残す)
    private void clearContainer() {
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (child != settingsPanel) container.removeView(child);
        }
    }

    // ----- 任意地点 (カスタム監視地点) -----
    private void loadCustomLocations() {
        List<AegisData.Location> list = new ArrayList<>();
        try {
            String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CUSTOMS, "[]");
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                list.add(new AegisData.Location(o.optString("n"), o.optDouble("la", 0), o.optDouble("lo", 0), o.optString("p"), o.optString("a")));
            }
        } catch (Exception ignored) {}
        AegisData.customLocations = list;
        renderCustomList();
    }

    private void saveCustomLocations() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (AegisData.Location l : AegisData.customLocations) {
                arr.put(new org.json.JSONObject().put("n", l.name).put("la", l.lat).put("lo", l.lon).put("p", l.prefCode).put("a", l.areaName));
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_CUSTOMS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void renderCustomList() {
        if (customLocList == null) return;
        customLocList.removeAllViews();
        List<AegisData.Location> list = AegisData.customLocations;
        if (list.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("追加された地点はありません（高崎・大宮は標準で監視しています）");
            none.setTextColor(Color.parseColor("#8A94A6"));
            none.setTextSize(12);
            customLocList.addView(none);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            AegisData.Location l = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this);
            label.setText("📍 " + l.name + "（" + l.lat + ", " + l.lon + "）");
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Button del = new Button(this);
            del.setText("削除");
            del.setOnClickListener(v -> {
                AegisData.customLocations.remove(idx);
                saveCustomLocations();
                renderCustomList();
                status.setText("地点を削除しました");
            });
            row.addView(label);
            row.addView(del);
            customLocList.addView(row);
        }
    }

    private void addCustomLocation() {
        String name = locNameInput.getText().toString().trim();
        String pref = locPrefInput.getText().toString().trim();
        String area = locAreaInput.getText().toString().trim();
        double lat, lon;
        try {
            lat = Double.parseDouble(locLatInput.getText().toString().trim());
            lon = Double.parseDouble(locLonInput.getText().toString().trim());
        } catch (Exception e) {
            status.setText("緯度・経度は数値で入力してください");
            return;
        }
        if (name.isEmpty() || pref.isEmpty() || area.isEmpty()) {
            status.setText("地点名・都道府県コード・地域名をすべて入力してください");
            return;
        }
        List<AegisData.Location> list = new ArrayList<>(AegisData.customLocations);
        list.add(new AegisData.Location(name, lat, lon, pref, area));
        AegisData.customLocations = list;
        saveCustomLocations();
        renderCustomList();
        locNameInput.setText("");
        status.setText("地点「" + name + "」を追加しました。ブリーフィングに反映されます");
    }

    private int loadGeneration = 0; // 古い取得結果が新しい画面を上書きしないための世代番号

    private String cacheKey(String tab, String mode, String server) {
        // 任意地点の構成が変わったら別キャッシュにする
        int locSig = 0;
        try { locSig = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CUSTOMS, "").hashCode(); } catch (Exception ignored) {}
        return "cache_" + tab + "_" + mode + "_" + (server == null ? "" : server.hashCode()) + "_" + locSig;
    }

    // キャッシュの保存時刻 (HH:mm:ss)。無い/壊れている場合は null
    private String cacheTime(String key) {
        try {
            String json = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, null);
            if (json == null) return null;
            long ts = new org.json.JSONObject(json).optLong("ts", 0);
            if (ts <= 0) return null;
            return new SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(new Date(ts));
        } catch (Exception e) {
            return null;
        }
    }

    // 前回の表示内容をキャッシュから復元する (体感速度の改善: まず表示して裏で更新)
    private boolean renderCache(String key) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String json = prefs.getString(key, null);
        if (json == null) return false;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray sa = root.optJSONArray("sections");
            org.json.JSONArray qa = root.optJSONArray("quakes");
            List<AegisData.Section> sections = new ArrayList<>();
            List<AegisData.Quake> quakes = new ArrayList<>();
            if (sa != null) {
                for (int i = 0; i < sa.length(); i++) {
                    org.json.JSONObject o = sa.getJSONObject(i);
                    sections.add(new AegisData.Section(o.optString("t"), o.optString("b"), o.optString("l")));
                }
            }
            if (qa != null) {
                for (int i = 0; i < qa.length(); i++) {
                    org.json.JSONObject o = qa.getJSONObject(i);
                    quakes.add(new AegisData.Quake(o.optString("p"), o.optString("d"), o.optString("s"), o.optInt("m"), o.optDouble("la", 0), o.optDouble("lo", 0), o.optLong("tm", 0)));
                }
            }
            if (sections.isEmpty() && quakes.isEmpty()) return false;
            if ("quakes".equals(currentTab)) renderQuakes(sections, quakes, null, false);
            else render(sections, quakes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveCache(String key, List<AegisData.Section> sections, List<AegisData.Quake> quakes) {
        // 取得失敗を含む内容はキャッシュしない
        for (AegisData.Section s : sections) {
            if (s.body.contains("取得できませんでした") || s.title.contains("取得エラー")) return;
        }
        try {
            org.json.JSONArray sa = new org.json.JSONArray();
            for (AegisData.Section s : sections) {
                sa.put(new org.json.JSONObject().put("t", s.title).put("b", s.body).put("l", s.level));
            }
            org.json.JSONArray qa = new org.json.JSONArray();
            for (AegisData.Quake q : quakes) {
                qa.put(new org.json.JSONObject().put("p", q.place).put("d", q.detail).put("s", q.sev).put("m", q.maxScale).put("la", q.lat).put("lo", q.lon).put("tm", q.originMillis));
            }
            org.json.JSONObject root = new org.json.JSONObject().put("sections", sa).put("quakes", qa).put("ts", System.currentTimeMillis());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadCurrent() {
        // 実データの取得が始まったらテスト表示は解除する
        demoMode = false;
        demoEew = null;
        stopEewTicker();
        if ("radar".equals(currentTab)) { loadRadar(); return; }
        if ("news".equals(currentTab)) { loadNews(); return; }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, "direct");
        String server = prefs.getString(KEY_SERVER, "http://10.0.2.2:8765");
        String tab = currentTab;
        String key = cacheKey(tab, mode, server);
        final int gen = ++loadGeneration;
        boolean cached = renderCache(key);
        String ct = cached ? cacheTime(key) : null;
        status.setText(cached ? "キャッシュ" + (ct != null ? "（" + ct + "時点）" : "") + "を表示中（更新中…）" : "取得中…");
        if (!cached) {
            clearContainer();
            TextView loading = new TextView(this);
            loading.setText("読み込んでいます…");
            loading.setTextColor(Color.parseColor("#8A94A6"));
            loading.setTextSize(14);
            container.addView(loading);
        }
        executor.execute(() -> {
            List<AegisData.Section> sections = new ArrayList<>();
            List<AegisData.Quake> quakes = new ArrayList<>();
            AegisData.EewInfo fetchedEew = null;
            try {
                if ("quakes".equals(tab) && !"server".equals(mode)) {
                    sections.addAll(AegisData.quakesSections(false));
                    quakes.addAll(AegisData.quakeItems(15));
                    fetchedEew = AegisData.eewInfo();
                    if (quakes.isEmpty()) {
                        sections.add(new AegisData.Section("🌏 地震情報", "最近の地震情報はありません。", "ok"));
                    }
                } else if ("server".equals(mode)) {
                    sections.addAll(AegisData.serverSections(server));
                } else {
                    sections.addAll(AegisData.briefingSections());
                }
            } catch (Exception e) {
                sections.clear();
                quakes.clear();
                String msg = "取得できませんでした：" + AegisData.friendlyError(e)
                        + "\n\n通信状態を確認してください。"
                        + ("server".equals(mode) ? "\nサーバーモードの場合は、AEGISサーバーが起動していて、URLが正しいか確認してください。" : "");
                sections.add(new AegisData.Section("⚠ 取得エラー", msg, "warn"));
            }
            List<AegisData.Section> resultSections = sections;
            List<AegisData.Quake> resultQuakes = quakes;
            AegisData.EewInfo resultEew = fetchedEew;
            String now = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(new Date());
            // 全セクションが取得失敗なら「最終更新」と表示しない (鮮度の誤認防止)
            boolean allFailed = !resultSections.isEmpty();
            for (AegisData.Section s : resultSections) {
                if (!s.body.contains("取得できませんでした") && !s.title.contains("取得エラー")) { allFailed = false; break; }
            }
            final boolean failed = allFailed && resultQuakes.isEmpty();
            mainHandler.post(() -> {
                if (gen != loadGeneration) return; // 既に新しい読み込みが始まっている
                if ("quakes".equals(tab) && !"server".equals(mode)) {
                    renderQuakes(resultSections, resultQuakes, resultEew, false);
                } else {
                    render(resultSections, resultQuakes);
                }
                saveCache(key, resultSections, resultQuakes);
                status.setText(failed ? "⚠ 取得に失敗しました（通信状態を確認してください）"
                        : "最終更新: " + now + "（" + ("server".equals(mode) ? "サーバー" : "直接取得") + "）");
            });
        });
    }

    // ----- カード描画 -----
    private void render(List<AegisData.Section> sections, List<AegisData.Quake> quakes) {
        clearContainer();
        for (AegisData.Section s : sections) container.addView(makeCard(s));
        for (AegisData.Quake q : quakes) container.addView(makeQuakeCard(q, false));
    }

    // 地震モニター用描画: EEWカウントダウンカードを最上部に固定し、最新の地震を大きく出す
    private void renderQuakes(List<AegisData.Section> sections, List<AegisData.Quake> quakes, AegisData.EewInfo eew, boolean demo) {
        clearContainer();
        stopEewTicker();
        if (eew != null && !eew.isCancel) {
            currentEew = eew;
            container.addView(makeEewCountdownCard(eew, demo));
            startEewTicker();
        } else if (eew != null) {
            container.addView(makeCard(new AegisData.Section("🚨 緊急地震速報", AegisData.eewStaticBody(eew), "info")));
        }
        for (AegisData.Section s : sections) container.addView(makeCard(s));
        for (int i = 0; i < quakes.size(); i++) {
            container.addView(makeQuakeCard(quakes.get(i), i == 0));
        }
    }

    // EEW カウントダウンカード (本文 TextView を保持し、1秒ごとに再描画する)
    private View makeEewCountdownCard(AegisData.EewInfo eew, boolean demo) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(cardBackground());
        card.setLayoutParams(cardParams());

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextColor(levelColor("danger"));
        dot.setTextSize(11);
        dot.setPadding(0, 0, dp(8), 0);
        TextView title = new TextView(this);
        title.setText(demo ? "🚨 【テスト】緊急地震速報" : "🚨 緊急地震速報");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(dot);
        titleRow.addView(title);
        card.addView(titleRow);

        TextView body = new TextView(this);
        body.setText(AegisData.eewCountdownBody(eew, AegisData.effectiveLocations(), System.currentTimeMillis()));
        body.setTextColor(Color.parseColor("#D8DEE9"));
        body.setTextSize(15);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, dp(6), 0, 0);
        card.addView(body);
        eewBodyView = body;
        return card;
    }

    // 発生からの経過表示 (モニターの拡大カード用)
    private String elapsedText(long originMillis) {
        if (originMillis <= 0) return "";
        long min = (System.currentTimeMillis() - originMillis) / 60000L;
        if (min < 1) return "たった今発生";
        if (min < 60) return min + "分前に発生";
        if (min < 60 * 24) return (min / 60) + "時間" + (min % 60) + "分前に発生";
        return (min / (60 * 24)) + "日前に発生";
    }

    private int levelColor(String level) {
        switch (level == null ? "" : level) {
            case "danger": return Color.parseColor("#FF6B6B");
            case "warn": return Color.parseColor("#FFB454");
            case "ok": return Color.parseColor("#58C184");
            default: return Color.parseColor("#7FA8E0");
        }
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1B2436"));
        bg.setCornerRadius(dp(12));
        return bg;
    }

    private View makeCard(AegisData.Section s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(cardBackground());
        card.setLayoutParams(cardParams());

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextColor(levelColor(s.level));
        dot.setTextSize(11);
        dot.setPadding(0, 0, dp(8), 0);
        TextView title = new TextView(this);
        title.setText(s.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(dot);
        titleRow.addView(title);
        card.addView(titleRow);

        TextView body = new TextView(this);
        body.setText(s.body);
        body.setTextColor(Color.parseColor("#D8DEE9"));
        body.setTextSize(14);
        body.setLineSpacing(0, 1.3f);
        body.setPadding(0, dp(6), 0, 0);
        card.addView(body);
        return card;
    }

    private View makeQuakeCard(AegisData.Quake q, boolean big) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(big ? 16 : 10), dp(12), dp(big ? 16 : 10));
        card.setBackground(cardBackground());
        card.setLayoutParams(cardParams());

        TextView badge = new TextView(this);
        badge.setText("震度" + AegisData.scaleToStr(q.maxScale));
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(big ? 17 : 13);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(scaleColor(q.maxScale));
        badgeBg.setCornerRadius(dp(8));
        badge.setBackground(badgeBg);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        card.addView(badge);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView place = new TextView(this);
        place.setText(q.place);
        place.setTextColor(Color.WHITE);
        place.setTextSize(big ? 19 : 15);
        place.setTypeface(Typeface.DEFAULT_BOLD);
        TextView detail = new TextView(this);
        detail.setText(q.detail);
        detail.setTextColor(Color.parseColor("#9AA7BD"));
        detail.setTextSize(big ? 14 : 12);
        detail.setPadding(0, dp(2), 0, 0);
        texts.addView(place);
        texts.addView(detail);
        if (big) {
            String elapsed = elapsedText(q.originMillis);
            if (!elapsed.isEmpty()) {
                TextView el = new TextView(this);
                el.setText("⏱ " + elapsed);
                el.setTextColor(Color.parseColor("#FFB454"));
                el.setTextSize(13);
                el.setPadding(0, dp(3), 0, 0);
                texts.addView(el);
            }
        }
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(texts);
        if (q.lat != 0 || q.lon != 0) {
            Button mapBtn = new Button(this);
            mapBtn.setText("🗺 地図");
            mapBtn.setTextSize(12);
            mapBtn.setOnClickListener(v -> openQuakeMap(q));
            card.addView(mapBtn);
        }
        return card;
    }

    // 震源を地図アプリで開く (なければブラウザで OpenStreetMap)
    private void openQuakeMap(AegisData.Quake q) {
        String label = "震源: " + q.place;
        android.net.Uri geo = android.net.Uri.parse("geo:" + q.lat + "," + q.lon + "?q=" + q.lat + "," + q.lon + "(" + android.net.Uri.encode(label) + ")");
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, geo);
        try {
            startActivity(intent);
        } catch (Exception e) {
            android.net.Uri web = android.net.Uri.parse("https://www.openstreetmap.org/?mlat=" + q.lat + "&mlon=" + q.lon + "#map=8/" + q.lat + "/" + q.lon);
            try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, web)); }
            catch (Exception ignored) { status.setText("地図を開けませんでした"); }
        }
    }

    private int scaleColor(int scale) {
        if (scale >= 45) return Color.parseColor("#C93A3A"); // 5弱以上: 赤
        if (scale >= 40) return Color.parseColor("#D97A2B"); // 4: オレンジ
        if (scale >= 30) return Color.parseColor("#A8871D"); // 3: 黄土
        if (scale >= 20) return Color.parseColor("#3E6B9E"); // 2: 青
        return Color.parseColor("#54687F");                  // 1: グレー青
    }

    // ----- レーダータブ -----
    private void loadRadar() {
        stopEewTicker();
        AegisData.Location[] locs = AegisData.effectiveLocations();
        if (radarLocIndex >= locs.length) radarLocIndex = 0;
        final AegisData.Location loc = locs[radarLocIndex];
        renderRadarShell(locs);
        status.setText("レーダーを取得中…（" + loc.name + "）");
        final int gen = ++loadGeneration;
        executor.execute(() -> {
            try {
                AegisData.RadarImage img = AegisData.radarImage(loc);
                String now = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(new Date());
                mainHandler.post(() -> {
                    if (gen != loadGeneration || radarImageView == null) return;
                    radarImageView.setImageBitmap(img.bitmap);
                    radarCaptionView.setText(loc.name + " 周辺\n" + img.caption);
                    status.setText("最終更新: " + now + "（レーダー）");
                });
            } catch (Exception e) {
                String msg = "レーダーを取得できませんでした：" + AegisData.friendlyError(e);
                mainHandler.post(() -> {
                    if (gen != loadGeneration || radarCaptionView == null) return;
                    radarCaptionView.setText(msg + "\n通信状態を確認して、更新ボタンで再試行してください。");
                    status.setText("⚠ レーダーの取得に失敗しました");
                });
            }
        });
    }

    // 地点切替チップ + 画像エリア + キャプションの枠を描く
    private void renderRadarShell(AegisData.Location[] locs) {
        clearContainer();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(14), dp(12), dp(14), dp(12));
        head.setBackground(cardBackground());
        head.setLayoutParams(cardParams());
        TextView title = new TextView(this);
        title.setText("🌧 気象レーダー");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < locs.length; i++) {
            final int idx = i;
            Button chip = new Button(this);
            chip.setText(locs[i].name);
            chip.setTextSize(12);
            if (i == radarLocIndex) chip.setTextColor(Color.parseColor("#FFB454"));
            chip.setOnClickListener(v -> {
                radarLocIndex = idx;
                loadRadar();
            });
            chips.addView(chip);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.addView(chips);
        head.addView(scroll);
        container.addView(head);

        radarImageView = new ImageView(this);
        radarImageView.setAdjustViewBounds(true);
        radarImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.bottomMargin = dp(8);
        radarImageView.setLayoutParams(ip);
        container.addView(radarImageView);

        radarCaptionView = new TextView(this);
        radarCaptionView.setText("読み込んでいます…");
        radarCaptionView.setTextColor(Color.parseColor("#9AA7BD"));
        radarCaptionView.setTextSize(12);
        container.addView(radarCaptionView);
    }

    // ----- ニュースタブ -----
    private void loadNews() {
        stopEewTicker();
        clearContainer();
        TextView loading = new TextView(this);
        loading.setText("ニュースを取得中…");
        loading.setTextColor(Color.parseColor("#8A94A6"));
        loading.setTextSize(14);
        container.addView(loading);
        status.setText("取得中…");
        final int gen = ++loadGeneration;
        executor.execute(() -> {
            try {
                List<AegisData.NewsItem> items = AegisData.newsItems();
                String now = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(new Date());
                mainHandler.post(() -> {
                    if (gen != loadGeneration) return;
                    renderNews(items);
                    status.setText("最終更新: " + now + "（NHKニュース）");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (gen != loadGeneration) return;
                    List<AegisData.Section> sections = new ArrayList<>();
                    sections.add(new AegisData.Section("⚠ 取得エラー",
                            "ニュースを取得できませんでした：" + AegisData.friendlyError(e) + "\n\n通信状態を確認してください。", "warn"));
                    render(sections, new ArrayList<>());
                    status.setText("⚠ ニュースの取得に失敗しました");
                });
            }
        });
    }

    private void renderNews(List<AegisData.NewsItem> items) {
        clearContainer();
        TextView head = new TextView(this);
        head.setText("📰 防災・主要ニュース（NHK）— タップで記事を開きます");
        head.setTextColor(Color.parseColor("#8A94A6"));
        head.setTextSize(12);
        head.setPadding(0, 0, 0, dp(8));
        container.addView(head);
        for (AegisData.NewsItem n : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(cardBackground());
            card.setLayoutParams(cardParams());
            TextView title = new TextView(this);
            title.setText((n.timeText.isEmpty() ? "" : n.timeText + "　") + n.title);
            title.setTextColor(Color.WHITE);
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(title);
            if (!n.summary.isEmpty()) {
                TextView summary = new TextView(this);
                summary.setText(n.summary);
                summary.setTextColor(Color.parseColor("#D8DEE9"));
                summary.setTextSize(13);
                summary.setLineSpacing(0, 1.3f);
                summary.setPadding(0, dp(5), 0, 0);
                card.addView(summary);
            }
            card.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(n.link)));
                } catch (Exception ignored) {
                    status.setText("記事を開けませんでした");
                }
            });
            container.addView(card);
        }
        if (items.isEmpty()) {
            List<AegisData.Section> sections = new ArrayList<>();
            sections.add(new AegisData.Section("📰 ニュース", "表示できるニュースがありません。", "info"));
            render(sections, new ArrayList<>());
        }
    }

    // ----- GPS 地点登録 -----
    private static final int REQ_LOCATION = 1001;
    private GpsListener gpsListener;
    private final Runnable gpsTimeout = () -> {
        if (gpsListener != null) {
            gpsListener = null;
            status.setText("現在地を取得できませんでした（タイムアウト）。電波の良い場所で再試行してください");
        }
    };

    // LocationListener は名前付きクラスにする (匿名クラスは d8 が処理できないため)
    private static class GpsListener implements android.location.LocationListener {
        private final MainActivity activity;
        GpsListener(MainActivity activity) { this.activity = activity; }
        @Override public void onLocationChanged(android.location.Location location) {
            activity.onGpsFix(location);
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    }

    private void onGpsClicked() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status.setText("位置情報の許可が必要です");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        fetchGpsLocation();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFY) {
            refreshSyncStatus();
            status.setText(AegisSync.notificationsAllowed(this)
                    ? "通知が許可されました" : "通知は許可されませんでした（端末の設定から許可できます）");
            return;
        }
        if (requestCode != REQ_LOCATION) return;
        boolean granted = false;
        for (int g : grantResults) if (g == PackageManager.PERMISSION_GRANTED) granted = true;
        if (granted) fetchGpsLocation();
        else status.setText("位置情報の許可が得られなかったため、現在地を登録できません");
    }

    private void fetchGpsLocation() {
        status.setText("現在地を取得中…");
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) {
            status.setText("位置情報サービスを利用できません");
            return;
        }
        android.location.Location best = null;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {}
        if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60 * 1000L) {
            onGpsFix(best);
            return;
        }
        gpsListener = new GpsListener(this);
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, gpsListener, Looper.getMainLooper());
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, gpsListener, Looper.getMainLooper());
            } else {
                gpsListener = null;
                status.setText("位置情報が無効です。端末の設定で位置情報を有効にしてください");
                return;
            }
        } catch (SecurityException e) {
            gpsListener = null;
            status.setText("位置情報の許可が得られなかったため、現在地を登録できません");
            return;
        }
        mainHandler.postDelayed(gpsTimeout, 15000L);
    }

    private void onGpsFix(android.location.Location fix) {
        mainHandler.removeCallbacks(gpsTimeout);
        gpsListener = null;
        final double lat = fix.getLatitude(), lon = fix.getLongitude();
        final AegisData.Pref pref = AegisData.nearestPref(lat, lon);
        status.setText("現在地を取得しました（都道府県は " + pref.name + " と推定）。地域を取得中…");
        executor.execute(() -> {
            List<String> names = new ArrayList<>();
            try { names = AegisData.forecastAreaNames(pref.code); }
            catch (Exception ignored) {}
            List<String> result = names;
            mainHandler.post(() -> {
                if (result.isEmpty()) {
                    status.setText("地域区分を取得できなかったため、現在地を登録できませんでした");
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(pref.name + " の地域区分を選択")
                        .setItems(result.toArray(new String[0]), (dialog, which) ->
                                registerGpsLocation(pref, lat, lon, result.get(which)))
                        .setNegativeButton("キャンセル", null)
                        .show();
            });
        });
    }

    private void registerGpsLocation(AegisData.Pref pref, double lat, double lon, String areaName) {
        List<AegisData.Location> list = new ArrayList<>(AegisData.customLocations);
        list.add(new AegisData.Location("現在地（" + areaName + "）", lat, lon, pref.code, areaName));
        AegisData.customLocations = list;
        saveCustomLocations();
        renderCustomList();
        status.setText("現在地（" + areaName + " / " + pref.name + "）を登録しました。ブリーフィングに反映されます");
    }

    // ----- 接続テスト (自己診断) -----
    private void runDiagnose() {
        boolean serverMode = modeGroup.getCheckedRadioButtonId() == R.id.modeServer;
        String server = serverUrlInput.getText().toString().trim();
        status.setText("接続テスト中…（各データソースへ実際にリクエストしています）");
        executor.execute(() -> {
            List<String> lines = AegisData.diagnose(serverMode, server);
            StringBuilder body = new StringBuilder();
            int ok = 0;
            for (String line : lines) {
                if (line.startsWith("○")) ok++;
                if (body.length() > 0) body.append("\n");
                body.append(line);
            }
            String summary = ok + " / " + lines.size() + " 接続OK";
            final boolean allOk = ok == lines.size();
            final String resultBody = body.toString();
            mainHandler.post(() -> {
                List<AegisData.Section> sections = new ArrayList<>();
                sections.add(new AegisData.Section("🩺 接続テスト結果（" + summary + "）", resultBody,
                        allOk ? "ok" : "warn"));
                render(sections, new ArrayList<>());
                status.setText("接続テスト完了: " + summary);
            });
        });
    }

    // ----- テスト表示 (デモ) -----
    private void enableDemo() {
        demoMode = true;
        long start = System.currentTimeMillis();
        demoEew = buildDemoEew(start);
        status.setText("テスト表示中（サンプルデータです。実際の警報ではありません）");
        showTab("briefing");
    }

    private void disableDemo() {
        demoMode = false;
        demoEew = null;
        status.setText("テスト表示を終了しました");
        if ("briefing".equals(currentTab) || "quakes".equals(currentTab)) {
            loadCurrent();
        } else {
            showTab("briefing");
        }
    }

    // サンプル EEW: 発生は現在時刻、登録地点への到達は約30秒後 (カウントダウンが実際に動く)
    private AegisData.EewInfo buildDemoEew(long start) {
        AegisData.EewInfo info = new AegisData.EewInfo();
        info.originMillis = start;
        info.reportMillis = start;
        info.hypoName = "テスト震源（サンプル）";
        info.lat = 36.10;
        info.lon = 139.30;
        info.depth = 40;
        info.estMaxScale = 45; // 推定最大震度 5弱
        String arrival = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(new Date(start + 30 * 1000L));
        info.areas.add(new AegisData.EewArea("群馬県", "群馬県南部", arrival));
        info.areas.add(new AegisData.EewArea("埼玉県", "埼玉県南部", arrival));
        return info;
    }

    private void renderDemo(String tab) {
        stopEewTicker();
        Button endBtn = new Button(this);
        endBtn.setText("テスト表示を終了");
        endBtn.setOnClickListener(v -> disableDemo());
        if ("quakes".equals(tab)) {
            List<AegisData.Section> sections = new ArrayList<>();
            sections.add(demoBanner());
            sections.add(new AegisData.Section("🌊 【テスト】津波情報", "津波の心配はありません（サンプル）。", "ok"));
            List<AegisData.Quake> quakes = new ArrayList<>();
            long now = System.currentTimeMillis();
            quakes.add(new AegisData.Quake("テスト震源（サンプル）",
                    new SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(new Date(now)) + "発生 / M5.8 / 深さ約40km / 津波の心配なし",
                    "HIGH", 45, 36.10, 139.30, now));
            quakes.add(new AegisData.Quake("サンプル震源2",
                    new SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(new Date(now - 42 * 60000L)) + "発生 / M4.6 / 深さ約60km / 津波の心配なし",
                    "MEDIUM", 30, 35.60, 140.10, now - 42 * 60000L));
            renderQuakes(sections, quakes, demoEew, true);
            container.addView(endBtn, 0);
        } else {
            List<AegisData.Section> sections = new ArrayList<>();
            sections.add(demoBanner());
            sections.add(new AegisData.Section("📌 【テスト】現在のまとめ",
                    "⚠ 発表中：大雨警報\n🌊 津波: なし\n🌏 最新の地震: 震度5弱 テスト震源（サンプル）\n☀ 高崎: 雨", "warn"));
            sections.add(new AegisData.Section("☀ 【テスト】天気予報（高崎）",
                    "今日： 雨\n⚠ 発表中： 大雨警報（11/17 12:00発表）\n　大雨による土砂災害に警戒してください（サンプル文）。", "warn"));
            sections.add(new AegisData.Section("🚨 【テスト】緊急地震速報",
                    AegisData.eewStaticBody(demoEew), "danger"));
            sections.add(new AegisData.Section("🌏 【テスト】最新の地震（1件）",
                    "🟠 震度5弱 テスト震源（サンプル）", "info"));
            render(sections, new ArrayList<>());
            container.addView(endBtn, 0);
        }
        status.setText("テスト表示中（サンプルデータです。実際の警報ではありません）");
    }

    private AegisData.Section demoBanner() {
        return new AegisData.Section("🧪 テスト表示中",
                "これはサンプルデータです。実際の警報・地震ではありません。\n更新ボタンや自動更新で実際のデータに切り替わると、テスト表示は終了します。",
                "warn");
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
                msg = "❌ 接続失敗: " + AegisData.friendlyError(e) + "（サーバー起動とURL・同一Wi-Fiを確認）";
            }
            String result = msg;
            mainHandler.post(() -> status.setText(result));
        });
    }

    // ----- バックグラウンド同期・通知の設定 -----
    private static final int REQ_NOTIFY = 1002;

    private void initNotificationSettings() {
        bgSyncSwitch = findViewById(R.id.bgSyncSwitch);
        briefingNotifySwitch = findViewById(R.id.briefingNotifySwitch);
        syncIntervalGroup = findViewById(R.id.syncIntervalGroup);
        quakeThresholdGroup = findViewById(R.id.quakeThresholdGroup);
        warnModeGroup = findViewById(R.id.warnModeGroup);
        bgSyncStatus = findViewById(R.id.bgSyncStatus);
        notifyPermStatus = findViewById(R.id.notifyPermStatus);
        Button notifyPermButton = findViewById(R.id.notifyPermButton);
        Button notifyTest = findViewById(R.id.notifyTest);

        // 現在の設定を反映してからリスナーを付ける (初期反映でリスナーが発火しないように)
        bgSyncSwitch.setChecked(AegisSync.isBgSyncEnabled(this));
        briefingNotifySwitch.setChecked(AegisSync.isBriefingEnabled(this));
        int minutes = AegisSync.intervalMinutes(this);
        syncIntervalGroup.check(minutes == 15 ? R.id.interval15 : minutes == 60 ? R.id.interval60 : R.id.interval30);
        int thr = AegisSync.quakeThresholdScale(this);
        quakeThresholdGroup.check(thr == 10 ? R.id.thrScale10 : thr == 20 ? R.id.thrScale20
                : thr == 40 ? R.id.thrScale40 : thr == 45 ? R.id.thrScale45 : R.id.thrScale30);
        warnModeGroup.check(AegisSync.warnMode(this) == AegisData.WARN_MODE_INCLUDE_ADVISORY
                ? R.id.warnIncludeAdvisory : R.id.warnAlertsOnly);

        bgSyncSwitch.setOnCheckedChangeListener((btn, checked) -> {
            AegisSync.setBgSyncEnabled(this, checked);
            AegisSync.scheduleNextSync(this);
            refreshSyncStatus();
            status.setText(checked ? "バックグラウンド同期を ON にしました" : "バックグラウンド同期を OFF にしました");
        });
        briefingNotifySwitch.setOnCheckedChangeListener((btn, checked) -> {
            AegisSync.setBriefingEnabled(this, checked);
            AegisSync.scheduleBriefings(this);
            status.setText(checked ? "朝夕ブリーフィング通知を ON にしました（7:00 / 21:00）"
                    : "朝夕ブリーフィング通知を OFF にしました");
        });
        syncIntervalGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int m = checkedId == R.id.interval15 ? 15 : checkedId == R.id.interval60 ? 60 : 30;
            AegisSync.setIntervalMinutes(this, m);
            AegisSync.scheduleNextSync(this);
            refreshSyncStatus();
        });
        quakeThresholdGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int s = checkedId == R.id.thrScale10 ? 10 : checkedId == R.id.thrScale20 ? 20
                    : checkedId == R.id.thrScale40 ? 40 : checkedId == R.id.thrScale45 ? 45 : 30;
            AegisSync.setQuakeThresholdScale(this, s);
            status.setText("地震の通知しきい値を震度" + AegisData.scaleToStr(s) + "以上にしました");
        });
        warnModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            AegisSync.setWarnMode(this, checkedId == R.id.warnIncludeAdvisory
                    ? AegisData.WARN_MODE_INCLUDE_ADVISORY : AegisData.WARN_MODE_ALERTS_ONLY);
            status.setText("警報の通知範囲を保存しました");
        });
        notifyPermButton.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            } else {
                status.setText("この端末（Android 12以下）では通知の許可リクエストは不要です");
                refreshSyncStatus();
            }
        });
        notifyTest.setOnClickListener(v -> {
            if (AegisSync.postTestNotification(this)) {
                status.setText("テスト通知を出しました（通知欄を確認してください）");
            } else {
                status.setText("通知が許可されていないため、テスト通知を出せませんでした");
                refreshSyncStatus();
            }
        });
        refreshSyncStatus();
    }

    // 設定画面の同期・許可状態表示を最新にする
    private void refreshSyncStatus() {
        if (bgSyncStatus == null) return;
        StringBuilder sb = new StringBuilder("バックグラウンド同期: ");
        if (AegisSync.isBgSyncEnabled(this)) {
            sb.append("ON（").append(AegisSync.intervalMinutes(this)).append("分間隔）");
        } else {
            sb.append("OFF");
        }
        long last = AegisSync.lastSyncMillis(this);
        if (last > 0) {
            sb.append(" / 最終同期 ").append(new SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(new Date(last)));
        } else {
            sb.append(" / 最終同期 まだありません");
        }
        bgSyncStatus.setText(sb.toString());
        if (notifyPermStatus != null) {
            notifyPermStatus.setText(AegisSync.notificationsAllowed(this)
                    ? "通知の許可: 許可済み"
                    : "通知の許可: 未許可（下のボタン、または端末の設定で許可してください）");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(autoRefresh);
        mainHandler.postDelayed(autoRefresh, 5 * 60 * 1000L);
        if ("quakes".equals(currentTab)) {
            mainHandler.removeCallbacks(monitorTick);
            mainHandler.postDelayed(monitorTick, 30 * 1000L);
        }
        if (currentEew != null && eewBodyView != null) startEewTicker();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(autoRefresh);
        mainHandler.removeCallbacks(monitorTick);
        mainHandler.removeCallbacks(eewTick);
        mainHandler.removeCallbacks(gpsTimeout);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
