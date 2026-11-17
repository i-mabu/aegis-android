package jp.aegis.client

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ===== 1. MainActivity (カードUI) =====
class MainActivity : Activity() {
    companion object {
        private const val PREFS = "aegis_prefs"
        private const val KEY_MODE = "mode" // direct | server
        private const val KEY_SERVER = "server_url"
        private const val KEY_CUSTOMS = "custom_locations"
        private const val REQ_LOCATION = 1001
        private const val REQ_NOTIFY = 1002
        private val POP_COLS = intArrayOf(110, 0)
        private val HOUR_COLS = intArrayOf(52, 0, 56, 62)
        private val DAY_COLS = intArrayOf(92, 0, 88, 62)
    }

    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private lateinit var settingsPanel: View
    private lateinit var modeGroup: RadioGroup
    private lateinit var serverUrlInput: EditText
    private lateinit var locNameInput: EditText
    private lateinit var locLatInput: EditText
    private lateinit var locLonInput: EditText
    private lateinit var locPrefInput: EditText
    private lateinit var locAreaInput: EditText
    private var customLocList: LinearLayout? = null
    // バックグラウンド同期・通知の設定コントロール
    private lateinit var bgSyncSwitch: Switch
    private lateinit var briefingNotifySwitch: Switch
    private lateinit var syncIntervalGroup: RadioGroup
    private lateinit var quakeThresholdGroup: RadioGroup
    private lateinit var warnModeGroup: RadioGroup
    private var bgSyncStatus: TextView? = null
    private var notifyPermStatus: TextView? = null
    private var currentTab = "briefing"
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    // 自動更新: 表示中は5分ごとに再取得する
    private val autoRefresh = Runnable { autoRefreshTick() }

    private fun autoRefreshTick() {
        if ("settings" != currentTab) loadCurrent()
        mainHandler.postDelayed(autoRefresh, 5 * 60 * 1000L)
    }

    // 地震モニター: 地震タブ表示中は30秒ごとに自動更新する
    private val monitorTick = Runnable { monitorTickRun() }

    private fun monitorTickRun() {
        if ("quakes" != currentTab) return // タブを離れたら停止
        if (!demoMode) loadCurrent() // テスト表示中は実データで上書きしない
        mainHandler.postDelayed(monitorTick, 30 * 1000L)
    }

    // EEW 到達カウントダウン: 1秒ごとにカード本文を再描画する
    private var currentEew: AegisData.EewInfo? = null
    private var eewBodyView: TextView? = null
    private val eewTick = Runnable { eewTickRun() }

    private fun eewTickRun() {
        val bodyView = eewBodyView
        val eew = currentEew
        if (bodyView == null || eew == null) return
        val now = System.currentTimeMillis()
        val locs = AegisData.effectiveLocations()
        if (AegisData.countdownActive(eew, locs, now)) {
            bodyView.text = AegisData.eewCountdownBody(eew, locs, now)
            mainHandler.postDelayed(eewTick, 1000L)
        } else {
            bodyView.text = AegisData.eewStaticBody(eew) // 通常の EEW 表示に戻す
        }
    }

    private fun startEewTicker() {
        mainHandler.removeCallbacks(eewTick)
        eewTickRun()
    }

    private fun stopEewTicker() {
        mainHandler.removeCallbacks(eewTick)
        eewBodyView = null
        currentEew = null
    }

    // レーダータブの状態
    private var radarLocIndex = 0
    private var radarImageView: ImageView? = null
    private var radarCaptionView: TextView? = null

    // テスト表示 (デモ) の状態
    private var demoMode = false
    private var demoEew: AegisData.EewInfo? = null

    private fun dp(v: Int): Int {
        return Math.round(v * resources.displayMetrics.density)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.contentContainer)
        status = findViewById(R.id.status)
        settingsPanel = findViewById(R.id.settingsPanel)
        modeGroup = findViewById(R.id.modeGroup)
        serverUrlInput = findViewById(R.id.serverUrl)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "direct")
        modeGroup.check(if ("server" == mode) R.id.modeServer else R.id.modeDirect)
        serverUrlInput.setText(prefs.getString(KEY_SERVER, "http://10.0.2.2:8765"))

        locNameInput = findViewById(R.id.locName)
        locLatInput = findViewById(R.id.locLat)
        locLonInput = findViewById(R.id.locLon)
        locPrefInput = findViewById(R.id.locPref)
        locAreaInput = findViewById(R.id.locArea)
        customLocList = findViewById(R.id.customLocList)
        loadCustomLocations()
        val addLocation: Button = findViewById(R.id.addLocation)
        addLocation.setOnClickListener { addCustomLocation() }

        val tabBriefing: Button = findViewById(R.id.tabBriefing)
        val tabQuakes: Button = findViewById(R.id.tabQuakes)
        val tabRadar: Button = findViewById(R.id.tabRadar)
        val tabNews: Button = findViewById(R.id.tabNews)
        val tabSettings: Button = findViewById(R.id.tabSettings)
        val refresh: Button = findViewById(R.id.refreshButton)
        val saveSettings: Button = findViewById(R.id.saveSettings)
        val testConnection: Button = findViewById(R.id.testConnection)
        val gpsButton: Button = findViewById(R.id.gpsButton)
        val diagTest: Button = findViewById(R.id.diagTest)
        val demoTest: Button = findViewById(R.id.demoTest)

        tabBriefing.setOnClickListener { showTab("briefing") }
        tabQuakes.setOnClickListener { showTab("quakes") }
        tabRadar.setOnClickListener { showTab("radar") }
        tabNews.setOnClickListener { showTab("news") }
        tabSettings.setOnClickListener { showTab("settings") }
        refresh.setOnClickListener { loadCurrent() }
        gpsButton.setOnClickListener { onGpsClicked() }
        diagTest.setOnClickListener { runDiagnose() }
        demoTest.setOnClickListener {
            if (demoMode) disableDemo()
            else enableDemo()
        }
        testConnection.setOnClickListener { testServer() }
        saveSettings.setOnClickListener {
            val m = if (modeGroup.checkedRadioButtonId == R.id.modeServer) "server" else "direct"
            prefs.edit()
                    .putString(KEY_MODE, m)
                    .putString(KEY_SERVER, serverUrlInput.text.toString().trim())
                    .apply()
            status.text = "設定を保存しました（モード: " + (if ("server" == m) "AEGISサーバー" else "直接取得") + "）"
            showTab("briefing")
        }

        initNotificationSettings()
        AegisSync.ensureChannels(this)
        AegisSync.scheduleNextSync(this)
        AegisSync.scheduleBriefings(this)

        // 通知タップで開かれた場合は該当タブを初期表示にする
        val initialTab = intent?.getStringExtra(AegisSync.EXTRA_TAB)
        showTab(if ("quakes" == initialTab) "quakes" else "briefing")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getStringExtra(AegisSync.EXTRA_TAB)
        if ("quakes" == tab) showTab("quakes")
        else if ("briefing" == tab) showTab("briefing")
    }

    private fun showTab(tab: String) {
        currentTab = tab
        mainHandler.removeCallbacks(monitorTick)
        if ("settings" == tab) {
            stopEewTicker()
            refreshSyncStatus()
            settingsPanel.visibility = View.VISIBLE
            val sections = ArrayList<AegisData.Section>()
            sections.add(AegisData.Section("⚙ 設定について",
                    "・直接取得: スマホが P2P地震情報・気象庁・Open-Meteo から直接取ります（推奨）\n"
                            + "・AEGISサーバー: PC等で動いている AEGIS (serve) の /api/briefing を表示します\n\n"
                            + "サーバーURLの例:\n"
                            + "・同じWi-FiのPC: http://192.168.1.10:8765\n"
                            + "・AndroidエミュレータからPC: http://10.0.2.2:8765\n\n"
                            + "レーダー・ニュースは直接取得の機能です（サーバーモードでも使えます）。\n"
                            + "表示中は5分ごとに自動更新されます（地震タブは30秒ごと）。\n"
                            + "バックグラウンド同期が ON の間は、アプリを閉じていても定期的に新しい地震・警報を確認して通知します。",
                    "info"))
            render(sections, ArrayList())
            status.text = "設定"
            return
        }
        settingsPanel.visibility = View.GONE
        if ("quakes" == tab) {
            mainHandler.postDelayed(monitorTick, 30 * 1000L)
        }
        if (demoMode && ("briefing" == tab || "quakes" == tab)) {
            renderDemo(tab)
            return
        }
        loadCurrent()
    }

    // コンテナ内のカード類だけを取り除く (設定パネルはコンテナの子なので残す)
    private fun clearContainer() {
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child !== settingsPanel) container.removeView(child)
        }
    }

    // ----- 任意地点 (カスタム監視地点) -----
    private fun loadCustomLocations() {
        val list = ArrayList<AegisData.Location>()
        try {
            val json = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOMS, "[]")
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(AegisData.Location(o.optString("n"), o.optDouble("la", 0.0), o.optDouble("lo", 0.0), o.optString("p"), o.optString("a")))
            }
        } catch (e: Exception) {}
        AegisData.customLocations = list
        renderCustomList()
    }

    private fun saveCustomLocations() {
        try {
            val arr = org.json.JSONArray()
            for (l in AegisData.customLocations) {
                arr.put(org.json.JSONObject().put("n", l.name).put("la", l.lat).put("lo", l.lon).put("p", l.prefCode).put("a", l.areaName))
            }
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOMS, arr.toString()).apply()
        } catch (e: Exception) {}
    }

    private fun renderCustomList() {
        val listView = customLocList ?: return
        listView.removeAllViews()
        val list = AegisData.customLocations
        if (list.isEmpty()) {
            val none = TextView(this)
            none.text = "追加された地点はありません（高崎・大宮は標準で監視しています）"
            none.setTextColor(Color.parseColor("#8A94A6"))
            none.setTextSize(12f)
            listView.addView(none)
            return
        }
        for (i in list.indices) {
            val idx = i
            val l = list[i]
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val label = TextView(this)
            label.text = "📍 " + l.name + "（" + l.lat + ", " + l.lon + "）"
            label.setTextColor(Color.WHITE)
            label.setTextSize(13f)
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val del = Button(this)
            del.text = "削除"
            del.setOnClickListener {
                // 追加と同じく「コピー→差し替え」に統一する (共有リストのインプレース変更はしない)
                val next = ArrayList(AegisData.customLocations)
                if (idx < next.size) next.removeAt(idx)
                AegisData.customLocations = next
                saveCustomLocations()
                renderCustomList()
                status.text = "地点を削除しました"
            }
            row.addView(label)
            row.addView(del)
            listView.addView(row)
        }
    }

    private fun addCustomLocation() {
        val name = locNameInput.text.toString().trim()
        val pref = locPrefInput.text.toString().trim()
        val area = locAreaInput.text.toString().trim()
        val lat: Double
        val lon: Double
        try {
            lat = locLatInput.text.toString().trim().toDouble()
            lon = locLonInput.text.toString().trim().toDouble()
        } catch (e: Exception) {
            status.text = "緯度・経度は数値で入力してください"
            return
        }
        if (name.isEmpty() || pref.isEmpty() || area.isEmpty()) {
            status.text = "地点名・都道府県コード・地域名をすべて入力してください"
            return
        }
        val list = ArrayList(AegisData.customLocations)
        list.add(AegisData.Location(name, lat, lon, pref, area))
        AegisData.customLocations = list
        saveCustomLocations()
        renderCustomList()
        locNameInput.setText("")
        status.text = "地点「" + name + "」を追加しました。ブリーフィングに反映されます"
    }

    private var loadGeneration = 0 // 古い取得結果が新しい画面を上書きしないための世代番号

    private fun cacheKey(tab: String, mode: String?, server: String?): String {
        // 任意地点の構成が変わったら別キャッシュにする
        var locSig = 0
        try { locSig = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOMS, "").hashCode() } catch (e: Exception) {}
        return "cache_" + tab + "_" + mode + "_" + (server?.hashCode() ?: "") + "_" + locSig
    }

    // キャッシュの保存時刻 (HH:mm:ss)。無い/壊れている場合は null
    private fun cacheTime(key: String): String? {
        try {
            val json = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
            val ts = org.json.JSONObject(json).optLong("ts", 0)
            if (ts <= 0) return null
            return SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(ts))
        } catch (e: Exception) {
            return null
        }
    }

    // 前回の表示内容をキャッシュから復元する (体感速度の改善: まず表示して裏で更新)
    private fun renderCache(key: String): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(key, null) ?: return false
        try {
            val root = org.json.JSONObject(json)
            val sa = root.optJSONArray("sections")
            val qa = root.optJSONArray("quakes")
            val sections = ArrayList<AegisData.Section>()
            val quakes = ArrayList<AegisData.Quake>()
            if (sa != null) {
                for (i in 0 until sa.length()) {
                    val o = sa.getJSONObject(i)
                    sections.add(AegisData.Section(o.optString("t"), o.optString("b"), o.optString("l")))
                }
            }
            if (qa != null) {
                for (i in 0 until qa.length()) {
                    val o = qa.getJSONObject(i)
                    quakes.add(AegisData.Quake(o.optString("p"), o.optString("d"), o.optString("s"), o.optInt("m"), o.optDouble("la", 0.0), o.optDouble("lo", 0.0), o.optLong("tm", 0)))
                }
            }
            if (sections.isEmpty() && quakes.isEmpty()) return false
            if ("quakes" == currentTab) renderQuakes(sections, quakes, null, false)
            else render(sections, quakes)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun saveCache(key: String, sections: List<AegisData.Section>, quakes: List<AegisData.Quake>) {
        // 取得失敗を含む内容はキャッシュしない
        for (s in sections) {
            if (s.body.contains("取得できませんでした") || s.title.contains("取得エラー")) return
        }
        try {
            val sa = org.json.JSONArray()
            for (s in sections) {
                sa.put(org.json.JSONObject().put("t", s.title).put("b", s.body).put("l", s.level))
            }
            val qa = org.json.JSONArray()
            for (q in quakes) {
                qa.put(org.json.JSONObject().put("p", q.place).put("d", q.detail).put("s", q.sev).put("m", q.maxScale).put("la", q.lat).put("lo", q.lon).put("tm", q.originMillis))
            }
            val root = org.json.JSONObject().put("sections", sa).put("quakes", qa).put("ts", System.currentTimeMillis())
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, root.toString()).apply()
        } catch (e: Exception) {}
    }

    private fun loadCurrent() {
        // 実データの取得が始まったらテスト表示は解除する
        demoMode = false
        demoEew = null
        stopEewTicker()
        if ("radar" == currentTab) { loadRadar(); return }
        if ("news" == currentTab) { loadNews(); return }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "direct")
        val server = prefs.getString(KEY_SERVER, "http://10.0.2.2:8765")
        val tab = currentTab
        val key = cacheKey(tab, mode, server)
        val gen = ++loadGeneration
        val cached = renderCache(key)
        val ct = if (cached) cacheTime(key) else null
        status.text = if (cached) "キャッシュ" + (if (ct != null) "（" + ct + "時点）" else "") + "を表示中（更新中…）" else "取得中…"
        if (!cached) {
            clearContainer()
            val loading = TextView(this)
            loading.text = "読み込んでいます…"
            loading.setTextColor(Color.parseColor("#8A94A6"))
            loading.setTextSize(14f)
            container.addView(loading)
        }
        executor.execute {
            val sections = ArrayList<AegisData.Section>()
            val quakes = ArrayList<AegisData.Quake>()
            var fetchedEew: AegisData.EewInfo? = null
            var fetchedBriefing: AegisData.Briefing? = null
            try {
                if ("quakes" == tab && "server" != mode) {
                    sections.addAll(AegisData.quakesSections(false))
                    quakes.addAll(AegisData.quakeItems(15))
                    fetchedEew = AegisData.eewInfo()
                    if (quakes.isEmpty()) {
                        sections.add(AegisData.Section("🌏 地震情報", "最近の地震情報はありません。", "ok"))
                    }
                } else if ("server" == mode) {
                    sections.addAll(AegisData.serverSections(server))
                } else {
                    val b = AegisData.briefing()
                    fetchedBriefing = b
                    sections.addAll(b.sections)
                }
            } catch (e: Exception) {
                sections.clear()
                quakes.clear()
                val msg = "取得できませんでした：" + AegisData.friendlyError(e) +
                        "\n\n通信状態を確認してください。" +
                        (if ("server" == mode) "\nサーバーモードの場合は、AEGISサーバーが起動していて、URLが正しいか確認してください。" else "")
                sections.add(AegisData.Section("⚠ 取得エラー", msg, "warn"))
            }
            val resultSections: List<AegisData.Section> = sections
            val resultQuakes: List<AegisData.Quake> = quakes
            val resultEew = fetchedEew
            val resultBriefing = fetchedBriefing
            val now = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
            // 全セクションが取得失敗なら「最終更新」と表示しない (鮮度の誤認防止)
            var allFailed = resultSections.isNotEmpty()
            for (s in resultSections) {
                if (!s.body.contains("取得できませんでした") && !s.title.contains("取得エラー")) { allFailed = false; break }
            }
            val failed = allFailed && resultQuakes.isEmpty()
            mainHandler.post {
                if (gen != loadGeneration) return@post // 既に新しい読み込みが始まっている
                if (resultBriefing != null) {
                    renderBriefing(resultBriefing)
                } else if ("quakes" == tab && "server" != mode) {
                    renderQuakes(resultSections, resultQuakes, resultEew, false)
                } else {
                    render(resultSections, resultQuakes)
                }
                saveCache(key, resultSections, resultQuakes)
                status.text = if (failed) "⚠ 取得に失敗しました（通信状態を確認してください）"
                        else "最終更新: " + now + "（" + (if ("server" == mode) "サーバー" else "直接取得") + "）"
            }
        }
    }

// ----- カード描画 -----
private fun render(sections: List<AegisData.Section>, quakes: List<AegisData.Quake>) {
clearContainer()
for (s in sections) container.addView(makeCard(s))
for (q in quakes) container.addView(makeQuakeCard(q, false))
}

// 地震モニター用描画: EEWカウントダウンカードを最上部に固定し、最新の地震を大きく出す
private fun renderQuakes(sections: List<AegisData.Section>, quakes: List<AegisData.Quake>, eew: AegisData.EewInfo?, demo: Boolean) {
clearContainer()
stopEewTicker()
if (eew!= null &&!eew.isCancel) {
currentEew = eew
container.addView(makeEewCountdownCard(eew, demo))
startEewTicker()
} else if (eew!= null) {
container.addView(makeCard(AegisData.Section("🚨 緊急地震速報", AegisData.eewStaticBody(eew), "info")))
}
for (s in sections) container.addView(makeCard(s))
for (i in quakes.indices) {
container.addView(makeQuakeCard(quakes[i], i == 0))
}
}

// EEW カウントダウンカード (本文 TextView を保持し、1秒ごとに再描画する)
private fun makeEewCountdownCard(eew: AegisData.EewInfo, demo: Boolean): View {
val card = LinearLayout(this)
card.orientation = LinearLayout.VERTICAL
card.setPadding(dp(14), dp(12), dp(14), dp(12))
card.background = cardBackground()
card.layoutParams = cardParams()

val titleRow = LinearLayout(this)
titleRow.orientation = LinearLayout.HORIZONTAL
titleRow.gravity = Gravity.CENTER_VERTICAL
val dot = TextView(this)
dot.text = "●"
dot.setTextColor(levelColor("danger"))
dot.setTextSize(11f)
dot.setPadding(0, 0, dp(8), 0)
val title = TextView(this)
title.text = if (demo) "🚨 緊急地震速報" else "🚨 緊急地震速報"
title.setTextColor(Color.WHITE)
title.setTextSize(16f)
title.typeface = Typeface.DEFAULT_BOLD
titleRow.addView(dot)
titleRow.addView(title)
card.addView(titleRow)

val body = TextView(this)
body.text = AegisData.eewCountdownBody(eew, AegisData.effectiveLocations(), System.currentTimeMillis())
body.setTextColor(Color.parseColor("#D8DEE9"))
body.setTextSize(15f)
body.setLineSpacing(0f, 1.3f)
body.setPadding(0, dp(6), 0, 0)
card.addView(body)
eewBodyView = body
return card
}

// 発生からの経過表示 (モニターの拡大カード用)
private fun elapsedText(originMillis: Long): String {
if (originMillis <= 0) return ""
val min = (System.currentTimeMillis() - originMillis) / 60000L
if (min < 1) return "たった今発生"
if (min < 60) return min.toString() + "分前に発生"
if (min < 60 * 24) return (min / 60).toString() + "時間" + (min % 60) + "分前に発生"
return (min / (60 * 24)).toString() + "日前に発生"
}

private fun levelColor(level: String?): Int {
return when (level?: "") {
"danger" -> Color.parseColor("#FF6B6B")
"warn" -> Color.parseColor("#FFB454")
"ok" -> Color.parseColor("#58C184")
else -> Color.parseColor("#7FA8E0")
}
}

private fun cardParams(): LinearLayout.LayoutParams {
val lp = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
lp.bottomMargin = dp(10)
return lp
}

private fun cardBackground(): GradientDrawable {
val bg = GradientDrawable()
bg.setColor(Color.parseColor("#1B2436"))
bg.cornerRadius = dp(12).toFloat()
return bg
}

// カードの外枠 (タイトル行つき) を作る
private fun cardShell(title: String, level: String): LinearLayout {
val card = LinearLayout(this)
card.orientation = LinearLayout.VERTICAL
card.setPadding(dp(14), dp(12), dp(14), dp(12))
card.background = cardBackground()
card.layoutParams = cardParams()

val titleRow = LinearLayout(this)
titleRow.orientation = LinearLayout.HORIZONTAL
titleRow.gravity = Gravity.CENTER_VERTICAL
val dot = TextView(this)
dot.text = "●"
dot.setTextColor(levelColor(level))
dot.setTextSize(11f)
dot.setPadding(0, 0, dp(8), 0)
val titleView = TextView(this)
titleView.text = title
titleView.setTextColor(Color.WHITE)
titleView.setTextSize(15f)
titleView.typeface = Typeface.DEFAULT_BOLD
titleRow.addView(dot)
titleRow.addView(titleView)
card.addView(titleRow)
return card
}

private fun makeCard(s: AegisData.Section): View {
val card = cardShell(s.title, s.level)
val body = TextView(this)
body.text = s.body
body.setTextColor(Color.parseColor("#D8DEE9"))
body.setTextSize(14f)
body.setLineSpacing(0f, 1.3f)
body.setPadding(0, dp(6), 0, 0)
card.addView(body)
return card
}

// ----- 表形式の描画ヘルパー -----
// ラベル列を揃えた [ラベル | 値] 行。値が空の行は注記として小さく全文表示する
private fun labelValueRow(label: String, value: String?, labelWidthDp: Int, alt: Boolean): View {
if (value.isNullOrEmpty()) {
val note = TextView(this)
note.text = label
note.setTextColor(Color.parseColor("#9AA7BD"))
note.setTextSize(12f)
note.setPadding(dp(2), dp(3), dp(2), dp(3))
return note
}
val row = LinearLayout(this)
row.orientation = LinearLayout.HORIZONTAL
row.setPadding(dp(2), dp(4), dp(2), dp(4))
if (alt) row.setBackgroundColor(Color.parseColor("#212C42"))
val l = TextView(this)
l.text = label
l.setTextColor(Color.parseColor("#9AA7BD"))
l.setTextSize(13f)
l.typeface = Typeface.DEFAULT_BOLD
l.layoutParams = LinearLayout.LayoutParams(dp(labelWidthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
row.addView(l)
val v = TextView(this)
v.text = value
v.setTextColor(Color.parseColor("#D8DEE9"))
v.setTextSize(13f)
v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
row.addView(v)
return row
}

// 「ラベル：値」テキストをラベル列を揃えた行の集まりとしてカードに追加する
private fun addLabelRows(card: LinearLayout, text: String?, labelWidthDp: Int) {
if (text.isNullOrEmpty()) return
val rows = AegisData.summaryRows(text)
for (i in rows.indices) {
card.addView(labelValueRow(rows[i][0], rows[i][1], labelWidthDp, i % 2 == 1))
}
}

// 表の1行 (widths: dp指定、0 は残り幅いっぱいの可変列)。ヘッダー行と交互背景に対応
private fun tableRow(cells: Array<String>, widths: IntArray, header: Boolean, rowIndex: Int): View {
val row = LinearLayout(this)
row.orientation = LinearLayout.HORIZONTAL
row.gravity = Gravity.CENTER_VERTICAL
row.setPadding(dp(8), dp(5), dp(8), dp(5))
row.setBackgroundColor(if (header) Color.parseColor("#2A3852")
else (if (rowIndex % 2 == 0) Color.parseColor("#1B2436") else Color.parseColor("#232F4B")))
for (i in cells.indices) {
val tv = TextView(this)
tv.text = cells[i]
tv.setTextSize(13f)
tv.setTextColor(if (header) Color.WHITE else Color.parseColor("#D8DEE9"))
if (header) tv.typeface = Typeface.DEFAULT_BOLD
if (widths[i] > 0) {
tv.layoutParams = LinearLayout.LayoutParams(dp(widths[i]), LinearLayout.LayoutParams.WRAP_CONTENT)
} else {
tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
}
row.addView(tv)
}
return row
}

// 表内の日付見出し行
private fun dateHeaderRow(date: String, weekday: String): View {
val tv = TextView(this)
tv.text = "📅 " + date + (if (weekday.isEmpty()) "" else "（" + weekday + "）")
tv.setTextColor(Color.parseColor("#FFB454"))
tv.setTextSize(12f)
tv.typeface = Typeface.DEFAULT_BOLD
tv.setPadding(dp(2), dp(8), dp(2), dp(3))
return tv
}

// 表の小見出し
private fun tableSubheading(text: String): View {
val tv = TextView(this)
tv.text = text
tv.setTextColor(Color.parseColor("#9AA7BD"))
tv.setTextSize(12f)
tv.typeface = Typeface.DEFAULT_BOLD
tv.setPadding(dp(2), dp(10), dp(2), dp(4))
return tv
}

// 「📌 現在のまとめ」カード (ラベル列を揃えた行レイアウト)
private fun makeSummaryCard(s: AegisData.Section): View {
val card = cardShell(s.title, s.level)
val rows = AegisData.summaryRows(s.body)
for (i in rows.indices) {
card.addView(labelValueRow(rows[i][0], rows[i][1], 108, i % 2 == 1))
}
return card
}

// 天気カード (ラベル行 + 時間別/週間/降水確率の表)
private fun makeWeatherCard(w: AegisData.WeatherView): View {
val card = cardShell("☀ 天気予報（" + w.loc.name + "）", w.level)
addLabelRows(card, w.daysText, 84)
addLabelRows(card, w.tempsText, 84)
if (w.popRows.isNotEmpty()) {
card.addView(tableSubheading("降水確率（気象庁・6時間ごと）"))
card.addView(tableRow(arrayOf("時間帯", "降水確率"), POP_COLS, true, 0))
var prevDate = ""
var idx = 0
for (r in w.popRows) {
if (r.date!= prevDate) {
card.addView(dateHeaderRow(r.date, r.weekday))
prevDate = r.date
}
card.addView(tableRow(arrayOf(r.fromHour.toString() + "〜" + r.toHour + "時",
if (r.pop < 0) "-%" else r.pop.toString() + "%"), POP_COLS, false, idx++))
}
}
addLabelRows(card, w.currentText, 84)
addLabelRows(card, w.tomorrowText, 84)
if (w.hourRows.isNotEmpty()) {
card.addView(tableSubheading("時間別予報（3時間ごと）"))
card.addView(tableRow(arrayOf("時刻", "天気", "気温", "降水"), HOUR_COLS, true, 0))
var prevDate = ""
var idx = 0
for (r in w.hourRows) {
if (r.date!= prevDate) {
card.addView(dateHeaderRow(r.date, r.weekday))
prevDate = r.date
}
card.addView(tableRow(arrayOf(r.hour.toString() + "時", r.emoji + " " + r.desc,
r.temp.toString() + "℃", if (r.pop < 0) "-%" else r.pop.toString() + "%"), HOUR_COLS, false, idx++))
}
}
if (w.dayRows.isNotEmpty()) {
card.addView(tableSubheading("週間予報"))
card.addView(tableRow(arrayOf("日付", "天気", "気温", "降水"), DAY_COLS, true, 0))
var idx = 0
for (r in w.dayRows) {
card.addView(tableRow(arrayOf(r.date + (if (r.weekday.isEmpty()) "" else "（" + r.weekday + "）"),
r.emoji + " " + r.desc, r.minTemp.toString() + "〜" + r.maxTemp + "℃",
if (r.pop < 0) "-%" else r.pop.toString() + "%"), DAY_COLS, false, idx++))
}
}
if (w.warningText.isNotEmpty()) {
val warn = TextView(this)
warn.text = w.warningText
warn.setTextColor(Color.parseColor("#FFB454"))
warn.setTextSize(13f)
warn.setLineSpacing(0f, 1.3f)
warn.setPadding(0, dp(8), 0, 0)
card.addView(warn)
}
return card
}

// ブリーフィング (直接取得) の描画: まとめ=ラベル行、天気=表、それ以外は通常カード
private fun renderBriefing(b: AegisData.Briefing) {
clearContainer()
stopEewTicker()
val sections = b.sections
if (sections.isNotEmpty()) container.addView(makeSummaryCard(sections[0]))
for (w in b.weathers) container.addView(makeWeatherCard(w))
for (i in 1 + b.weathers.size until sections.size) {
container.addView(makeCard(sections[i]))
}
}

private fun makeQuakeCard(q: AegisData.Quake, big: Boolean): View {
val card = LinearLayout(this)
card.orientation = LinearLayout.HORIZONTAL
card.gravity = Gravity.CENTER_VERTICAL
card.setPadding(dp(12), dp(if (big) 16 else 10), dp(12), dp(if (big) 16 else 10))
card.background = cardBackground()
card.layoutParams = cardParams()

val badge = TextView(this)
badge.text = "震度" + AegisData.scaleToStr(q.maxScale)
badge.setTextColor(Color.WHITE)
badge.setTextSize(if (big) 17f else 13f)
badge.typeface = Typeface.DEFAULT_BOLD
badge.gravity = Gravity.CENTER
val badgeBg = GradientDrawable()
badgeBg.setColor(scaleColor(q.maxScale))
badgeBg.cornerRadius = dp(8).toFloat()
badge.background = badgeBg
badge.setPadding(dp(10), dp(6), dp(10), dp(6))
card.addView(badge)

val texts = LinearLayout(this)
texts.orientation = LinearLayout.VERTICAL
texts.setPadding(dp(12), 0, 0, 0)
val place = TextView(this)
place.text = q.place
place.setTextColor(Color.WHITE)
place.setTextSize(if (big) 19f else 15f)
place.typeface = Typeface.DEFAULT_BOLD
val detail = TextView(this)
detail.text = q.detail
detail.setTextColor(Color.parseColor("#9AA7BD"))
detail.setTextSize(if (big) 14f else 12f)
detail.setPadding(0, dp(2), 0, 0)
texts.addView(place)
texts.addView(detail)
if (big) {
val elapsed = elapsedText(q.originMillis)
if (elapsed.isNotEmpty()) {
val el = TextView(this)
el.text = "⏱ " + elapsed
el.setTextColor(Color.parseColor("#FFB454"))
el.setTextSize(13f)
el.setPadding(0, dp(3), 0, 0)
texts.addView(el)
}
}
texts.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
card.addView(texts)
if (q.lat!= 0.0 || q.lon!= 0.0) {
val mapBtn = Button(this)
mapBtn.text = "🗺 地図"
mapBtn.setTextSize(12f)
mapBtn.setOnClickListener { openQuakeMap(q)}
card.addView(mapBtn)
}
return card
}

// 震源を地図アプリで開く (なければブラウザで OpenStreetMap)
private fun openQuakeMap(q: AegisData.Quake) {
val label = "震源: " + q.place
val geo = Uri.parse("geo:" + q.lat + "," + q.lon + "?q=" + q.lat + "," + q.lon + "(" + Uri.encode(label) + ")")
val intent = Intent(Intent.ACTION_VIEW, geo)
try {
startActivity(intent)
} catch (e: Exception) {
val web = Uri.parse("https://www.openstreetmap.org/?mlat=" + q.lat + "&mlon=" + q.lon + "#map=8/" + q.lat + "/" + q.lon)
try { startActivity(Intent(Intent.ACTION_VIEW, web))}
catch (e2: Exception) { status.text = "地図を開けませんでした"}
}
}

private fun scaleColor(scale: Int): Int {
if (scale >= 45) return Color.parseColor("#C93A3A") // 5弱以上: 赤
if (scale >= 40) return Color.parseColor("#D97A2B") // 4: オレンジ
if (scale >= 30) return Color.parseColor("#A8871D") // 3: 黄土
if (scale >= 20) return Color.parseColor("#3E6B9E") // 2: 青
return Color.parseColor("#54687F") // 1: グレー青
}

// ----- レーダータブ -----
private fun loadRadar() {
stopEewTicker()
val locs = AegisData.effectiveLocations()
if (radarLocIndex >= locs.size) radarLocIndex = 0
val loc = locs[radarLocIndex]
renderRadarShell(locs)
status.text = "レーダーを取得中…（" + loc.name + "）"
val gen = ++loadGeneration
executor.execute {
try {
val img = AegisData.radarImage(loc)
val now = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
mainHandler.post {
val iv = radarImageView
val cv = radarCaptionView
if (gen!= loadGeneration || iv == null || cv == null) return@post
iv.setImageBitmap(img.bitmap)
cv.text = loc.name + " 周辺\n" + img.caption
status.text = "最終更新: " + now + "（レーダー）"
}
} catch (e: Exception) {
val msg = "レーダーを取得できませんでした：" + AegisData.friendlyError(e)
mainHandler.post {
val cv = radarCaptionView
if (gen!= loadGeneration || cv == null) return@post
cv.text = msg + "\n通信状態を確認して、更新ボタンで再試行してください。"
status.text = "⚠ レーダーの取得に失敗しました"
}
}
}
}

// 地点切替チップ + 画像エリア + キャプションの枠を描く
private fun renderRadarShell(locs: Array<AegisData.Location>) {
clearContainer()
val head = LinearLayout(this)
head.orientation = LinearLayout.VERTICAL
head.setPadding(dp(14), dp(12), dp(14), dp(12))
head.background = cardBackground()
head.layoutParams = cardParams()
val title = TextView(this)
title.text = "🌧 気象レーダー"
title.setTextColor(Color.WHITE)
title.setTextSize(15f)
title.typeface = Typeface.DEFAULT_BOLD
head.addView(title)
val chips = LinearLayout(this)
chips.orientation = LinearLayout.HORIZONTAL
chips.setPadding(0, dp(8), 0, 0)
for (i in locs.indices) {
val idx = i
val chip = Button(this)
chip.text = locs[i].name
chip.setTextSize(12f)
if (i == radarLocIndex) chip.setTextColor(Color.parseColor("#FFB454"))
chip.setOnClickListener {
radarLocIndex = idx
loadRadar()
}
chips.addView(chip)
}
val scroll = android.widget.HorizontalScrollView(this)
scroll.addView(chips)
head.addView(scroll)
container.addView(head)

val imageView = ImageView(this)
imageView.adjustViewBounds = true
imageView.scaleType = ImageView.ScaleType.FIT_CENTER
val ip = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
ip.bottomMargin = dp(8)
imageView.layoutParams = ip
container.addView(imageView)
radarImageView = imageView

val captionView = TextView(this)
captionView.text = "読み込んでいます…"
captionView.setTextColor(Color.parseColor("#9AA7BD"))
captionView.setTextSize(12f)
container.addView(captionView)
radarCaptionView = captionView
}

// ----- ニュースタブ -----
private fun loadNews() {
stopEewTicker()
clearContainer()
val loading = TextView(this)
loading.text = "ニュースを取得中…"
loading.setTextColor(Color.parseColor("#8A94A6"))
loading.setTextSize(14f)
container.addView(loading)
status.text = "取得中…"
val gen = ++loadGeneration
executor.execute {
try {
val items = AegisData.newsItems()
val now = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
mainHandler.post {
if (gen!= loadGeneration) return@post
renderNews(items)
status.text = "最終更新: " + now + "（NHKニュース）"
}
} catch (e: Exception) {
mainHandler.post {
if (gen!= loadGeneration) return@post
val sections = ArrayList<AegisData.Section>()
sections.add(AegisData.Section("⚠ 取得エラー",
"ニュースを取得できませんでした：" + AegisData.friendlyError(e) + "\n\n通信状態を確認してください。", "warn"))
render(sections, ArrayList())
status.text = "⚠ ニュースの取得に失敗しました"
}
}
}
}

private fun renderNews(items: List<AegisData.NewsItem>) {
clearContainer()
val head = TextView(this)
head.text = "📰 防災・主要ニュース（NHK）— タップで記事を開きます"
head.setTextColor(Color.parseColor("#8A94A6"))
head.setTextSize(12f)
head.setPadding(0, 0, 0, dp(8))
container.addView(head)
for (n in items) {
val card = LinearLayout(this)
card.orientation = LinearLayout.VERTICAL
card.setPadding(dp(14), dp(12), dp(14), dp(12))
card.background = cardBackground()
card.layoutParams = cardParams()
val title = TextView(this)
title.text = (if (n.timeText.isEmpty()) "" else n.timeText + "　") + n.title
title.setTextColor(Color.WHITE)
title.setTextSize(15f)
title.typeface = Typeface.DEFAULT_BOLD
card.addView(title)
if (n.summary.isNotEmpty()) {
val summary = TextView(this)
summary.text = n.summary
summary.setTextColor(Color.parseColor("#D8DEE9"))
summary.setTextSize(13f)
summary.setLineSpacing(0f, 1.3f)
summary.setPadding(0, dp(5), 0, 0)
card.addView(summary)
}
card.setOnClickListener {
try {
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link)))
} catch (e: Exception) {
status.text = "記事を開けませんでした"
}
}
container.addView(card)
}
if (items.isEmpty()) {
val sections = ArrayList<AegisData.Section>()
sections.add(AegisData.Section("📰 ニュース", "表示できるニュースがありません。", "info"))
render(sections, ArrayList())
}
}

// ----- GPS 地点登録 -----
private var gpsListener: GpsListener? = null
private val gpsTimeout = Runnable {
if (gpsListener!= null) {
stopGpsUpdates()
status.text = "現在地を取得できませんでした（タイムアウト）。電波の良い場所で再試行してください"
}
}

// GPS リスナーを LocationManager から解除して破棄する (登録していなくても安全)
private fun stopGpsUpdates() {
val listener = gpsListener?: return
try {
val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
lm?.removeUpdates(listener)
} catch (e: Exception) {}
gpsListener = null
}

// LocationListener は名前付きクラスにする (匿名クラスは d8 が処理できないため)
private class GpsListener(private val activity: MainActivity): android.location.LocationListener {
override fun onLocationChanged(location: android.location.Location) {
activity.onGpsFix(location)
}
override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
override fun onProviderEnabled(provider: String) {}
override fun onProviderDisabled(provider: String) {}
}

private fun onGpsClicked() {
if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!= PackageManager.PERMISSION_GRANTED
&& checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!= PackageManager.PERMISSION_GRANTED) {
status.text = "位置情報の許可が必要です"
requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
return
}
fetchGpsLocation()
}

override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
super.onRequestPermissionsResult(requestCode, permissions, grantResults)
if (requestCode == REQ_NOTIFY) {
refreshSyncStatus()
status.text = if (AegisSync.notificationsAllowed(this))
"通知が許可されました" else "通知は許可されませんでした（端末の設定から許可できます）"
return
}
if (requestCode!= REQ_LOCATION) return
var granted = false
for (g in grantResults) if (g == PackageManager.PERMISSION_GRANTED) granted = true
if (granted) fetchGpsLocation()
else status.text = "位置情報の許可が得られなかったため、現在地を登録できません"
}

private fun fetchGpsLocation() {
status.text = "現在地を取得中…"
val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
if (lm == null) {
status.text = "位置情報サービスを利用できません"
return
}
var best: android.location.Location? = null
try {
for (p in arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
val l = lm.getLastKnownLocation(p)
if (l!= null && (best == null || l.time > best.time)) best = l
}
} catch (e: SecurityException) {}
val bestLoc = best
if (bestLoc!= null && System.currentTimeMillis() - bestLoc.time < 10 * 60 * 1000L) {
onGpsFix(bestLoc)
return
}
val listener = GpsListener(this)
gpsListener = listener
try {
if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
} else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
} else {
stopGpsUpdates()
status.text = "位置情報が無効です。端末の設定で位置情報を有効にしてください"
return
}
} catch (e: SecurityException) {
stopGpsUpdates()
status.text = "位置情報の許可が得られなかったため、現在地を登録できません"
return
}
mainHandler.postDelayed(gpsTimeout, 15000L)
}

private fun onGpsFix(fix: android.location.Location) {
mainHandler.removeCallbacks(gpsTimeout)
stopGpsUpdates()
val lat = fix.latitude
val lon = fix.longitude
val pref = AegisData.nearestPref(lat, lon)
status.text = "現在地を取得しました（都道府県は " + pref.name + " と推定）。地域を取得中…"
executor.execute {
var names: List<String> = ArrayList()
try { names = AegisData.forecastAreaNames(pref.code)}
catch (e: Exception) {}
val result = names
mainHandler.post {
if (isFinishing || isDestroyed) return@post // 画面が閉じていたらダイアログを出さない
if (result.isEmpty()) {
status.text = "地域区分を取得できなかったため、現在地を登録できませんでした"
return@post
}
try {
val items = Array<CharSequence>(result.size) { i -> result[i]}
AlertDialog.Builder(this@MainActivity)
.setTitle(pref.name + " の地域区分を選択")
.setItems(items) { _, which ->
registerGpsLocation(pref, lat, lon, result[which])
}
.setNegativeButton("キャンセル", null)
.show()
} catch (e: Exception) {}
}
}
}

private fun registerGpsLocation(pref: AegisData.Pref, lat: Double, lon: Double, areaName: String) {
val list = ArrayList(AegisData.customLocations)
list.add(AegisData.Location("現在地（" + areaName + "）", lat, lon, pref.code, areaName))
AegisData.customLocations = list
saveCustomLocations()
renderCustomList()
status.text = "現在地（" + areaName + " / " + pref.name + "）を登録しました。ブリーフィングに反映されます"
}

// ----- 接続テスト (自己診断) -----
private fun runDiagnose() {
val serverMode = modeGroup.checkedRadioButtonId == R.id.modeServer
val server = serverUrlInput.text.toString().trim()
status.text = "接続テスト中…（各データソースへ実際にリクエストしています）"
executor.execute {
val lines = AegisData.diagnose(serverMode, server)
val body = StringBuilder()
var ok = 0
for (line in lines) {
if (line.startsWith("○")) ok++
if (body.isNotEmpty()) body.append("\n")
body.append(line)
}
val summary = ok.toString() + " / " + lines.size + " 接続OK"
val allOk = ok == lines.size
val resultBody = body.toString()
mainHandler.post {
val sections = ArrayList<AegisData.Section>()
sections.add(AegisData.Section("🩺 接続テスト結果（" + summary + "）", resultBody,
if (allOk) "ok" else "warn"))
render(sections, ArrayList())
status.text = "接続テスト完了: " + summary
}
}
}

// ----- テスト表示 (デモ) -----
private fun enableDemo() {
demoMode = true
val start = System.currentTimeMillis()
demoEew = buildDemoEew(start)
status.text = "テスト表示中（サンプルデータです。実際の警報ではありません）"
showTab("briefing")
}

private fun disableDemo() {
demoMode = false
demoEew = null
status.text = "テスト表示を終了しました"
if ("briefing" == currentTab || "quakes" == currentTab) {
loadCurrent()
} else {
showTab("briefing")
}
}

// サンプル EEW: 発生は現在時刻、登録地点への到達は約30秒後 (カウントダウンが実際に動く)
private fun buildDemoEew(start: Long): AegisData.EewInfo {
val info = AegisData.EewInfo()
info.originMillis = start
info.reportMillis = start
info.hypoName = "テスト震源（サンプル）"
info.lat = 36.10
info.lon = 139.30
info.depth = 40
info.estMaxScale = 45 // 推定最大震度 5弱
val arrival = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(start + 30 * 1000L))
info.areas.add(AegisData.EewArea("群馬県", "群馬県南部", arrival))
info.areas.add(AegisData.EewArea("埼玉県", "埼玉県南部", arrival))
return info
}

private fun renderDemo(tab: String) {
stopEewTicker()
val endBtn = Button(this)
endBtn.text = "テスト表示を終了"
endBtn.setOnClickListener { disableDemo()}
if ("quakes" == tab) {
val sections = ArrayList<AegisData.Section>()
sections.add(demoBanner())
sections.add(AegisData.Section("🌊 津波情報", "津波の心配はありません（サンプル）。", "ok"))
val quakes = ArrayList<AegisData.Quake>()
val now = System.currentTimeMillis()
quakes.add(AegisData.Quake("テスト震源（サンプル）",
SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(now)) + "発生 / M5.8 / 深さ約40km / 津波の心配なし",
"HIGH", 45, 36.10, 139.30, now))
quakes.add(AegisData.Quake("サンプル震源2",
SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN).format(Date(now - 42 * 60000L)) + "発生 / M4.6 / 深さ約60km / 津波の心配なし",
"MEDIUM", 30, 35.60, 140.10, now - 42 * 60000L))
renderQuakes(sections, quakes, demoEew, true)
container.addView(endBtn, 0)
} else {
val sections = ArrayList<AegisData.Section>()
sections.add(demoBanner())
sections.add(AegisData.Section("📌 現在のまとめ",
"⚠ 発表中：大雨警報\n🌊 津波: なし\n🌏 最新の地震: 震度5弱 テスト震源（サンプル）\n☀ 高崎: 雨", "warn"))
sections.add(AegisData.Section("☀ 天気予報（高崎）",
"今日： 雨\n⚠ 発表中： 大雨警報（11/17 12:00発表）\n　大雨による土砂災害に警戒してください（サンプル文）。", "warn"))
sections.add(AegisData.Section("🚨 緊急地震速報",
AegisData.eewStaticBody(demoEew!!), "danger"))
sections.add(AegisData.Section("🌏 最新の地震（1件）",
"🟠 震度5弱 テスト震源（サンプル）", "info"))
render(sections, ArrayList())
container.addView(endBtn, 0)
}
status.text = "テスト表示中（サンプルデータです。実際の警報ではありません）"
}

private fun demoBanner(): AegisData.Section {
return AegisData.Section("🧪 テスト表示中",
"これはサンプルデータです。実際の警報・地震ではありません。\n更新ボタンや自動更新で実際のデータに切り替わると、テスト表示は終了します。",
"warn")
}

// 設定タブの接続テスト: 入力中のURLで /api/history を叩いて結果を表示する
private fun testServer() {
var base = serverUrlInput.text.toString().trim()
if (base.endsWith("/")) base = base.substring(0, base.length - 1)
val url = base + "/api/history"
status.text = "接続テスト中… " + url
executor.execute {
var msg: String
try {
val body = AegisData.fetch(url)
msg = "✅ 接続OK: " + url + "（応答 " + body.length + " 文字）"
} catch (e: Exception) {
msg = "❌ 接続失敗: " + AegisData.friendlyError(e) + "（サーバー起動とURL・同一Wi-Fiを確認）"
}
val result = msg
mainHandler.post { status.text = result}
}
}

// ----- バックグラウンド同期・通知の設定 -----
private fun initNotificationSettings() {
bgSyncSwitch = findViewById(R.id.bgSyncSwitch)
briefingNotifySwitch = findViewById(R.id.briefingNotifySwitch)
syncIntervalGroup = findViewById(R.id.syncIntervalGroup)
quakeThresholdGroup = findViewById(R.id.quakeThresholdGroup)
warnModeGroup = findViewById(R.id.warnModeGroup)
bgSyncStatus = findViewById(R.id.bgSyncStatus)
notifyPermStatus = findViewById(R.id.notifyPermStatus)
val notifyPermButton: Button = findViewById(R.id.notifyPermButton)
val notifyTest: Button = findViewById(R.id.notifyTest)

// 現在の設定を反映してからリスナーを付ける (初期反映でリスナーが発火しないように)
bgSyncSwitch.isChecked = AegisSync.isBgSyncEnabled(this)
briefingNotifySwitch.isChecked = AegisSync.isBriefingEnabled(this)
val minutes = AegisSync.intervalMinutes(this)
syncIntervalGroup.check(if (minutes == 15) R.id.interval15 else if (minutes == 60) R.id.interval60 else R.id.interval30)
val thr = AegisSync.quakeThresholdScale(this)
quakeThresholdGroup.check(if (thr == 10) R.id.thrScale10 else if (thr == 20) R.id.thrScale20
else if (thr == 40) R.id.thrScale40 else if (thr == 45) R.id.thrScale45 else R.id.thrScale30)
warnModeGroup.check(if (AegisSync.warnMode(this) == AegisData.WARN_MODE_INCLUDE_ADVISORY)
R.id.warnIncludeAdvisory else R.id.warnAlertsOnly)

bgSyncSwitch.setOnCheckedChangeListener { _, checked ->
AegisSync.setBgSyncEnabled(this, checked)
AegisSync.scheduleNextSync(this)
refreshSyncStatus()
status.text = if (checked) "バックグラウンド同期を ON にしました" else "バックグラウンド同期を OFF にしました"
}
briefingNotifySwitch.setOnCheckedChangeListener { _, checked ->
AegisSync.setBriefingEnabled(this, checked)
AegisSync.scheduleBriefings(this)
status.text = if (checked) "朝夕ブリーフィング通知を ON にしました（7:00 / 21:00）"
else "朝夕ブリーフィング通知を OFF にしました"
}
syncIntervalGroup.setOnCheckedChangeListener { _, checkedId ->
val m = if (checkedId == R.id.interval15) 15 else if (checkedId == R.id.interval60) 60 else 30
AegisSync.setIntervalMinutes(this, m)
AegisSync.scheduleNextSync(this)
refreshSyncStatus()
}
quakeThresholdGroup.setOnCheckedChangeListener { _, checkedId ->
val s = if (checkedId == R.id.thrScale10) 10 else if (checkedId == R.id.thrScale20) 20
else if (checkedId == R.id.thrScale40) 40 else if (checkedId == R.id.thrScale45) 45 else 30
AegisSync.setQuakeThresholdScale(this, s)
status.text = "地震の通知しきい値を震度" + AegisData.scaleToStr(s) + "以上にしました"
}
warnModeGroup.setOnCheckedChangeListener { _, checkedId ->
AegisSync.setWarnMode(this, if (checkedId == R.id.warnIncludeAdvisory)
AegisData.WARN_MODE_INCLUDE_ADVISORY else AegisData.WARN_MODE_ALERTS_ONLY)
status.text = "警報の通知範囲を保存しました"
}
notifyPermButton.setOnClickListener {
if (android.os.Build.VERSION.SDK_INT >= 33) {
requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
} else {
status.text = "この端末（Android 12以下）では通知の許可リクエストは不要です"
refreshSyncStatus()
}
}
notifyTest.setOnClickListener {
if (AegisSync.postTestNotification(this)) {
status.text = "テスト通知を出しました（通知欄を確認してください）"
} else {
status.text = "通知が許可されていないため、テスト通知を出せませんでした"
refreshSyncStatus()
}
}
refreshSyncStatus()
}

// 設定画面の同期・許可状態表示を最新にする
private fun refreshSyncStatus() {
val syncStatus = bgSyncStatus?: return
val sb = StringBuilder("バックグラウンド同期: ")
if (AegisSync.isBgSyncEnabled(this)) {
sb.append("ON（").append(AegisSync.intervalMinutes(this)).append("分間隔）")
} else {
sb.append("OFF")
}
val last = AegisSync.lastSyncMillis(this)
if (last > 0) {
sb.append(" / 最終同期 ").append(SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(last)))
} else {
sb.append(" / 最終同期 まだありません")
}
syncStatus.text = sb.toString()
val permStatus = notifyPermStatus
if (permStatus!= null) {
permStatus.text = if (AegisSync.notificationsAllowed(this))
"通知の許可: 許可済み"
else "通知の許可: 未許可（下のボタン、または端末の設定で許可してください）"
}
}

override fun onResume() {
super.onResume()
mainHandler.removeCallbacks(autoRefresh)
mainHandler.postDelayed(autoRefresh, 5 * 60 * 1000L)
if ("quakes" == currentTab) {
mainHandler.removeCallbacks(monitorTick)
mainHandler.postDelayed(monitorTick, 30 * 1000L)
}
if (currentEew!= null && eewBodyView!= null) startEewTicker()
}

override fun onPause() {
super.onPause()
mainHandler.removeCallbacks(autoRefresh)
mainHandler.removeCallbacks(monitorTick)
mainHandler.removeCallbacks(eewTick)
mainHandler.removeCallbacks(gpsTimeout)
stopGpsUpdates()
}

override fun onDestroy() {
super.onDestroy()
stopGpsUpdates()
executor.shutdownNow()
}
}
