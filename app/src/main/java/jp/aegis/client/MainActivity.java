package jp.aegis.client;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
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
    private String currentTab = "briefing";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 自動更新: 表示中は5分ごとに再取得する
    private final Runnable autoRefresh = this::autoRefreshTick;

    private void autoRefreshTick() {
        if (!"settings".equals(currentTab)) loadCurrent();
        mainHandler.postDelayed(autoRefresh, 5 * 60 * 1000L);
    }

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
            List<AegisData.Section> sections = new ArrayList<>();
            sections.add(new AegisData.Section("⚙ 設定について",
                    "・直接取得: スマホが P2P地震情報・気象庁・Open-Meteo から直接取ります（推奨）\n"
                            + "・AEGISサーバー: PC等で動いている AEGIS (serve) の /api/briefing を表示します\n\n"
                            + "サーバーURLの例:\n"
                            + "・同じWi-FiのPC: http://192.168.1.10:8765\n"
                            + "・AndroidエミュレータからPC: http://10.0.2.2:8765\n\n"
                            + "表示中は5分ごとに自動更新されます。",
                    "info"));
            render(sections, new ArrayList<>());
            status.setText("設定");
            return;
        }
        settingsPanel.setVisibility(View.GONE);
        loadCurrent();
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
                    quakes.add(new AegisData.Quake(o.optString("p"), o.optString("d"), o.optString("s"), o.optInt("m"), o.optDouble("la", 0), o.optDouble("lo", 0)));
                }
            }
            if (sections.isEmpty() && quakes.isEmpty()) return false;
            render(sections, quakes);
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
                qa.put(new org.json.JSONObject().put("p", q.place).put("d", q.detail).put("s", q.sev).put("m", q.maxScale).put("la", q.lat).put("lo", q.lon));
            }
            org.json.JSONObject root = new org.json.JSONObject().put("sections", sa).put("quakes", qa).put("ts", System.currentTimeMillis());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadCurrent() {
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
            container.removeAllViews();
            TextView loading = new TextView(this);
            loading.setText("読み込んでいます…");
            loading.setTextColor(Color.parseColor("#8A94A6"));
            loading.setTextSize(14);
            container.addView(loading);
        }
        executor.execute(() -> {
            List<AegisData.Section> sections = new ArrayList<>();
            List<AegisData.Quake> quakes = new ArrayList<>();
            try {
                if ("quakes".equals(tab) && !"server".equals(mode)) {
                    sections.addAll(AegisData.quakesSections());
                    quakes.addAll(AegisData.quakeItems(15));
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
            String now = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(new Date());
            // 全セクションが取得失敗なら「最終更新」と表示しない (鮮度の誤認防止)
            boolean allFailed = !resultSections.isEmpty();
            for (AegisData.Section s : resultSections) {
                if (!s.body.contains("取得できませんでした") && !s.title.contains("取得エラー")) { allFailed = false; break; }
            }
            final boolean failed = allFailed && resultQuakes.isEmpty();
            mainHandler.post(() -> {
                if (gen != loadGeneration) return; // 既に新しい読み込みが始まっている
                render(resultSections, resultQuakes);
                saveCache(key, resultSections, resultQuakes);
                status.setText(failed ? "⚠ 取得に失敗しました（通信状態を確認してください）"
                        : "最終更新: " + now + "（" + ("server".equals(mode) ? "サーバー" : "直接取得") + "）");
            });
        });
    }

    // ----- カード描画 -----
    private void render(List<AegisData.Section> sections, List<AegisData.Quake> quakes) {
        container.removeAllViews();
        for (AegisData.Section s : sections) container.addView(makeCard(s));
        for (AegisData.Quake q : quakes) container.addView(makeQuakeCard(q));
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

    private View makeQuakeCard(AegisData.Quake q) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(cardBackground());
        card.setLayoutParams(cardParams());

        TextView badge = new TextView(this);
        badge.setText("震度" + AegisData.scaleToStr(q.maxScale));
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(13);
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
        place.setTextSize(15);
        place.setTypeface(Typeface.DEFAULT_BOLD);
        TextView detail = new TextView(this);
        detail.setText(q.detail);
        detail.setTextColor(Color.parseColor("#9AA7BD"));
        detail.setTextSize(12);
        detail.setPadding(0, dp(2), 0, 0);
        texts.addView(place);
        texts.addView(detail);
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
