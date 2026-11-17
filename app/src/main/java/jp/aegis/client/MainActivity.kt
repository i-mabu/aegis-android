package jp.aegis.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ===== テーマカラー (従来の View 版と同一の配色) =====
private val BgColor = Color(0xFF0B1220)
private val CardColor = Color(0xFF1B2436)
private val CardAltColor = Color(0xFF232F4B)
private val LabelAltColor = Color(0xFF212C42)
private val TableHeaderColor = Color(0xFF2A3852)
private val BodyTextColor = Color(0xFFD8DEE9)
private val SecondaryTextColor = Color(0xFF9AA7BD)
private val MutedTextColor = Color(0xFF8A94A6)
private val AmberColor = Color(0xFFFFB454)

private val AegisColorScheme = darkColorScheme(
    primary = Color(0xFF7FA8E0),
    onPrimary = Color.White,
    background = BgColor,
    onBackground = Color.White,
    surface = CardColor,
    onSurface = Color.White
)

// ===== 1. MainActivity (Jetpack Compose カードUI) =====
class MainActivity : ComponentActivity() {
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

    // ----- Compose が監視する UI 状態 -----
    private var statusText by mutableStateOf("")
    private var currentTab by mutableStateOf("briefing")
    private var content by mutableStateOf<UiContent>(UiContent.Loading("読み込んでいます…"))
    private var eewBodyText by mutableStateOf("")
    private var demoMode by mutableStateOf(false)
    private var radarLocIndex by mutableStateOf(0)
    // 設定フォームの状態
    private var modeSel by mutableStateOf("direct")
    private var serverUrlText by mutableStateOf("")
    private var locNameText by mutableStateOf("")
    private var locLatText by mutableStateOf("")
    private var locLonText by mutableStateOf("")
    private var locPrefText by mutableStateOf("")
    private var locAreaText by mutableStateOf("")
    private var customLocs by mutableStateOf<List<AegisData.Location>>(emptyList())
    private var bgSyncOn by mutableStateOf(false)
    private var briefingNotifyOn by mutableStateOf(false)
    private var intervalMin by mutableStateOf(30)
    private var quakeThr by mutableStateOf(30)
    private var warnModeSel by mutableStateOf(AegisData.WARN_MODE_ALERTS_ONLY)
    private var bgSyncStatusText by mutableStateOf("")
    private var notifyPermStatusText by mutableStateOf("")
    private var gpsChoice by mutableStateOf<GpsChoice?>(null)

    private data class GpsChoice(
        val pref: AegisData.Pref,
        val lat: Double,
        val lon: Double,
        val names: List<String>
    )

    private sealed interface UiContent {
        data class Loading(val message: String) : UiContent
        data class Cards(
            val sections: List<AegisData.Section>,
            val quakes: List<AegisData.Quake>
        ) : UiContent
        data class BriefingC(val briefing: AegisData.Briefing) : UiContent
        data class QuakesC(
            val sections: List<AegisData.Section>,
            val quakes: List<AegisData.Quake>,
            val eew: AegisData.EewInfo?,
            val demo: Boolean
        ) : UiContent
        data class RadarC(val bitmap: Bitmap?, val caption: String) : UiContent
        data class NewsC(val items: List<AegisData.NewsItem>) : UiContent
    }

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
    private val eewTick = Runnable { eewTickRun() }

    private fun eewTickRun() {
        val eew = currentEew ?: return
        val now = System.currentTimeMillis()
        val locs = AegisData.effectiveLocations()
        if (AegisData.countdownActive(eew, locs, now)) {
            eewBodyText = AegisData.eewCountdownBody(eew, locs, now)
            mainHandler.postDelayed(eewTick, 1000L)
        } else {
            eewBodyText = AegisData.eewStaticBody(eew) // 通常の EEW 表示に戻す
        }
    }

    private fun startEewTicker() {
        mainHandler.removeCallbacks(eewTick)
        eewTickRun()
    }

    private fun stopEewTicker() {
        mainHandler.removeCallbacks(eewTick)
        currentEew = null
    }

    // テスト表示 (デモ) の状態
    private var demoEew: AegisData.EewInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        modeSel = prefs.getString(KEY_MODE, "direct") ?: "direct"
        serverUrlText = prefs.getString(KEY_SERVER, "http://10.0.2.2:8765") ?: ""

        loadCustomLocations()
        initNotificationSettings()

        AegisSync.ensureChannels(this)
        AegisSync.scheduleNextSync(this)
        AegisSync.scheduleBriefings(this)

        setContent { AegisApp() }

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
            content = UiContent.Cards(sections, ArrayList())
            statusText = "設定"
            return
        }
        if ("quakes" == tab) {
            mainHandler.postDelayed(monitorTick, 30 * 1000L)
        }
        if (demoMode && ("briefing" == tab || "quakes" == tab)) {
            renderDemo(tab)
            return
        }
        loadCurrent()
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
        customLocs = ArrayList(list)
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

    private fun deleteCustomLocation(idx: Int) {
        // 追加と同じく「コピー→差し替え」に統一する (共有リストのインプレース変更はしない)
        val next = ArrayList(AegisData.customLocations)
        if (idx < next.size) next.removeAt(idx)
        AegisData.customLocations = next
        customLocs = ArrayList(next)
        saveCustomLocations()
        statusText = "地点を削除しました"
    }

    private fun addCustomLocation() {
        val name = locNameText.trim()
        val pref = locPrefText.trim()
        val area = locAreaText.trim()
        val lat: Double
        val lon: Double
        try {
            lat = locLatText.trim().toDouble()
            lon = locLonText.trim().toDouble()
        } catch (e: Exception) {
            statusText = "緯度・経度は数値で入力してください"
            return
        }
        if (name.isEmpty() || pref.isEmpty() || area.isEmpty()) {
            statusText = "地点名・都道府県コード・地域名をすべて入力してください"
            return
        }
        val list = ArrayList(AegisData.customLocations)
        list.add(AegisData.Location(name, lat, lon, pref, area))
        AegisData.customLocations = list
        customLocs = ArrayList(list)
        saveCustomLocations()
        locNameText = ""
        statusText = "地点「" + name + "」を追加しました。ブリーフィングに反映されます"
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
        statusText = if (cached) "キャッシュ" + (if (ct != null) "（" + ct + "時点）" else "") + "を表示中（更新中…）" else "取得中…"
        if (!cached) {
            content = UiContent.Loading("読み込んでいます…")
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
                statusText = if (failed) "⚠ 取得に失敗しました（通信状態を確認してください）"
                        else "最終更新: " + now + "（" + (if ("server" == mode) "サーバー" else "直接取得") + "）"
            }
        }
    }

// ----- 描画 (状態の差し替え) -----
private fun render(sections: List<AegisData.Section>, quakes: List<AegisData.Quake>) {
content = UiContent.Cards(sections, quakes)
}

// 地震モニター用描画: EEWカウントダウンカードを最上部に固定し、最新の地震を大きく出す
private fun renderQuakes(sections: List<AegisData.Section>, quakes: List<AegisData.Quake>, eew: AegisData.EewInfo?, demo: Boolean) {
stopEewTicker()
if (eew!= null &&!eew.isCancel) {
currentEew = eew
eewBodyText = AegisData.eewCountdownBody(eew, AegisData.effectiveLocations(), System.currentTimeMillis())
}
content = UiContent.QuakesC(sections, quakes, eew, demo)
if (eew!= null &&!eew.isCancel) startEewTicker()
}

// ブリーフィング (直接取得) の描画: まとめ=ラベル行、天気=表、それ以外は通常カード
private fun renderBriefing(b: AegisData.Briefing) {
stopEewTicker()
content = UiContent.BriefingC(b)
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
catch (e2: Exception) { statusText = "地図を開けませんでした"}
}
}

// ----- レーダータブ -----
private fun loadRadar() {
stopEewTicker()
val locs = AegisData.effectiveLocations()
if (radarLocIndex >= locs.size) radarLocIndex = 0
val loc = locs[radarLocIndex]
content = UiContent.RadarC(null, "読み込んでいます…")
statusText = "レーダーを取得中…（" + loc.name + "）"
val gen = ++loadGeneration
executor.execute {
try {
val img = AegisData.radarImage(loc)
val now = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
mainHandler.post {
if (gen!= loadGeneration) return@post
content = UiContent.RadarC(img.bitmap, loc.name + " 周辺\n" + img.caption)
statusText = "最終更新: " + now + "（レーダー）"
}
} catch (e: Exception) {
val msg = "レーダーを取得できませんでした：" + AegisData.friendlyError(e)
mainHandler.post {
if (gen!= loadGeneration) return@post
content = UiContent.RadarC(null, msg + "\n通信状態を確認して、更新ボタンで再試行してください。")
statusText = "⚠ レーダーの取得に失敗しました"
}
}
}
}

// ----- ニュースタブ -----
private fun loadNews() {
stopEewTicker()
content = UiContent.Loading("ニュースを取得中…")
statusText = "取得中…"
val gen = ++loadGeneration
executor.execute {
try {
val items = AegisData.newsItems()
val now = SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date())
mainHandler.post {
if (gen!= loadGeneration) return@post
if (items.isEmpty()) {
val sections = ArrayList<AegisData.Section>()
sections.add(AegisData.Section("📰 ニュース", "表示できるニュースがありません。", "info"))
render(sections, ArrayList())
} else {
content = UiContent.NewsC(items)
}
statusText = "最終更新: " + now + "（NHKニュース）"
}
} catch (e: Exception) {
mainHandler.post {
if (gen!= loadGeneration) return@post
val sections = ArrayList<AegisData.Section>()
sections.add(AegisData.Section("⚠ 取得エラー",
"ニュースを取得できませんでした：" + AegisData.friendlyError(e) + "\n\n通信状態を確認してください。", "warn"))
render(sections, ArrayList())
statusText = "⚠ ニュースの取得に失敗しました"
}
}
}
}

// ----- GPS 地点登録 -----
private var gpsListener: GpsListener? = null
private val gpsTimeout = Runnable {
if (gpsListener!= null) {
stopGpsUpdates()
statusText = "現在地を取得できませんでした（タイムアウト）。電波の良い場所で再試行してください"
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
statusText = "位置情報の許可が必要です"
requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
return
}
fetchGpsLocation()
}

override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
super.onRequestPermissionsResult(requestCode, permissions, grantResults)
if (requestCode == REQ_NOTIFY) {
refreshSyncStatus()
statusText = if (AegisSync.notificationsAllowed(this))
"通知が許可されました" else "通知は許可されませんでした（端末の設定から許可できます）"
return
}
if (requestCode!= REQ_LOCATION) return
var granted = false
for (g in grantResults) if (g == PackageManager.PERMISSION_GRANTED) granted = true
if (granted) fetchGpsLocation()
else statusText = "位置情報の許可が得られなかったため、現在地を登録できません"
}

private fun fetchGpsLocation() {
statusText = "現在地を取得中…"
val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager?
if (lm == null) {
statusText = "位置情報サービスを利用できません"
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
statusText = "位置情報が無効です。端末の設定で位置情報を有効にしてください"
return
}
} catch (e: SecurityException) {
stopGpsUpdates()
statusText = "位置情報の許可が得られなかったため、現在地を登録できません"
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
statusText = "現在地を取得しました（都道府県は " + pref.name + " と推定）。地域を取得中…"
executor.execute {
var names: List<String> = ArrayList()
try { names = AegisData.forecastAreaNames(pref.code)}
catch (e: Exception) {}
val result = names
mainHandler.post {
if (isFinishing || isDestroyed) return@post // 画面が閉じていたらダイアログを出さない
if (result.isEmpty()) {
statusText = "地域区分を取得できなかったため、現在地を登録できませんでした"
return@post
}
gpsChoice = GpsChoice(pref, lat, lon, result)
}
}
}

private fun registerGpsLocation(pref: AegisData.Pref, lat: Double, lon: Double, areaName: String) {
val list = ArrayList(AegisData.customLocations)
list.add(AegisData.Location("現在地（" + areaName + "）", lat, lon, pref.code, areaName))
AegisData.customLocations = list
customLocs = ArrayList(list)
saveCustomLocations()
statusText = "現在地（" + areaName + " / " + pref.name + "）を登録しました。ブリーフィングに反映されます"
}

// ----- 接続テスト (自己診断) -----
private fun runDiagnose() {
val serverMode = modeSel == "server"
val server = serverUrlText.trim()
statusText = "接続テスト中…（各データソースへ実際にリクエストしています）"
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
statusText = "接続テスト完了: " + summary
}
}
}

// ----- テスト表示 (デモ) -----
private fun enableDemo() {
demoMode = true
val start = System.currentTimeMillis()
demoEew = buildDemoEew(start)
statusText = "テスト表示中（サンプルデータです。実際の警報ではありません）"
showTab("briefing")
}

private fun disableDemo() {
demoMode = false
demoEew = null
statusText = "テスト表示を終了しました"
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
}
statusText = "テスト表示中（サンプルデータです。実際の警報ではありません）"
}

private fun demoBanner(): AegisData.Section {
return AegisData.Section("🧪 テスト表示中",
"これはサンプルデータです。実際の警報・地震ではありません。\n更新ボタンや自動更新で実際のデータに切り替わると、テスト表示は終了します。",
"warn")
}

// 設定タブの接続テスト: 入力中のURLで /api/history を叩いて結果を表示する
private fun testServer() {
var base = serverUrlText.trim()
if (base.endsWith("/")) base = base.substring(0, base.length - 1)
val url = base + "/api/history"
statusText = "接続テスト中… " + url
executor.execute {
var msg: String
try {
val body = AegisData.fetch(url)
msg = "✅ 接続OK: " + url + "（応答 " + body.length + " 文字）"
} catch (e: Exception) {
msg = "❌ 接続失敗: " + AegisData.friendlyError(e) + "（サーバー起動とURL・同一Wi-Fiを確認）"
}
val result = msg
mainHandler.post { statusText = result}
}
}

// ----- バックグラウンド同期・通知の設定 -----
private fun initNotificationSettings() {
// 現在の設定を Compose 状態へ反映する (副作用は各コントロールの変更時に実行)
bgSyncOn = AegisSync.isBgSyncEnabled(this)
briefingNotifyOn = AegisSync.isBriefingEnabled(this)
intervalMin = AegisSync.intervalMinutes(this)
quakeThr = AegisSync.quakeThresholdScale(this)
warnModeSel = AegisSync.warnMode(this)
refreshSyncStatus()
}

private fun saveModeSettings() {
val m = modeSel
getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
.putString(KEY_MODE, m)
.putString(KEY_SERVER, serverUrlText.trim())
.apply()
statusText = "設定を保存しました（モード: " + (if ("server" == m) "AEGISサーバー" else "直接取得") + "）"
showTab("briefing")
}

private fun onBgSyncChanged(checked: Boolean) {
bgSyncOn = checked
AegisSync.setBgSyncEnabled(this, checked)
AegisSync.scheduleNextSync(this)
refreshSyncStatus()
statusText = if (checked) "バックグラウンド同期を ON にしました" else "バックグラウンド同期を OFF にしました"
}

private fun onBriefingNotifyChanged(checked: Boolean) {
briefingNotifyOn = checked
AegisSync.setBriefingEnabled(this, checked)
AegisSync.scheduleBriefings(this)
statusText = if (checked) "朝夕ブリーフィング通知を ON にしました（7:00 / 21:00）"
else "朝夕ブリーフィング通知を OFF にしました"
}

private fun onIntervalChanged(minutes: Int) {
intervalMin = minutes
AegisSync.setIntervalMinutes(this, minutes)
AegisSync.scheduleNextSync(this)
refreshSyncStatus()
}

private fun onQuakeThresholdChanged(scale: Int) {
quakeThr = scale
AegisSync.setQuakeThresholdScale(this, scale)
statusText = "地震の通知しきい値を震度" + AegisData.scaleToStr(scale) + "以上にしました"
}

private fun onWarnModeChanged(mode: Int) {
warnModeSel = mode
AegisSync.setWarnMode(this, mode)
statusText = "警報の通知範囲を保存しました"
}

private fun onNotifyPermClicked() {
if (android.os.Build.VERSION.SDK_INT >= 33) {
requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
} else {
statusText = "この端末（Android 12以下）では通知の許可リクエストは不要です"
refreshSyncStatus()
}
}

private fun onNotifyTestClicked() {
if (AegisSync.postTestNotification(this)) {
statusText = "テスト通知を出しました（通知欄を確認してください）"
} else {
statusText = "通知が許可されていないため、テスト通知を出せませんでした"
refreshSyncStatus()
}
}

// 設定画面の同期・許可状態表示を最新にする
private fun refreshSyncStatus() {
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
bgSyncStatusText = sb.toString()
notifyPermStatusText = if (AegisSync.notificationsAllowed(this))
"通知の許可: 許可済み"
else "通知の許可: 未許可（下のボタン、または端末の設定で許可してください）"
}

override fun onResume() {
super.onResume()
mainHandler.removeCallbacks(autoRefresh)
mainHandler.postDelayed(autoRefresh, 5 * 60 * 1000L)
if ("quakes" == currentTab) {
mainHandler.removeCallbacks(monitorTick)
mainHandler.postDelayed(monitorTick, 30 * 1000L)
}
if (currentEew!= null) startEewTicker()
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

// ================= Compose UI =================

@Composable
private fun AegisApp() {
MaterialTheme(colorScheme = AegisColorScheme) {
Surface(color = BgColor, modifier = Modifier.fillMaxSize()) {
Column(modifier = Modifier.fillMaxSize()) {
Text(
"🛡️ AEGIS — 地震・津波・気象",
color = Color.White,
fontSize = 19.sp,
fontWeight = FontWeight.Bold,
modifier = Modifier.padding(14.dp)
)
TabBar()
Text(
statusText,
color = MutedTextColor,
fontSize = 12.sp,
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 14.dp, vertical = 6.dp)
)
LazyColumn(
modifier = Modifier
.weight(1f)
.fillMaxWidth()
.padding(horizontal = 12.dp)
) {
if (currentTab == "settings") {
item { SettingsPanel()}
}
if (demoMode && ("briefing" == currentTab || "quakes" == currentTab)) {
item {
Button(
onClick = { disableDemo()},
modifier = Modifier
.fillMaxWidth()
.padding(bottom = 10.dp)
) { Text("テスト表示を終了")}
}
}
when (val c = content) {
is UiContent.Loading -> item {
Text(c.message, color = MutedTextColor, fontSize = 14.sp)
}
is UiContent.Cards -> {
items(c.sections) { s -> SectionCard(s)}
items(c.quakes) { q -> QuakeCard(q, big = false)}
}
is UiContent.BriefingC -> {
val sections = c.briefing.sections
val weathers = c.briefing.weathers
if (sections.isNotEmpty()) {
item { SummaryCard(sections[0])}
}
items(weathers) { w -> WeatherCard(w)}
items(sections.drop(1 + weathers.size)) { s -> SectionCard(s)}
}
is UiContent.QuakesC -> {
val eew = c.eew
if (eew!= null &&!eew.isCancel) {
item { EewCountdownCard(eew, c.demo)}
} else if (eew!= null) {
item {
SectionCard(AegisData.Section("🚨 緊急地震速報",
AegisData.eewStaticBody(eew), "info"))
}
}
items(c.sections) { s -> SectionCard(s)}
itemsIndexed(c.quakes) { i, q -> QuakeCard(q, big = i == 0)}
}
is UiContent.RadarC -> {
item { RadarContent(c)}
}
is UiContent.NewsC -> {
item {
Text(
"📰 防災・主要ニュース（NHK）— タップで記事を開きます",
color = MutedTextColor,
fontSize = 12.sp,
modifier = Modifier.padding(bottom = 8.dp)
)
}
items(c.items) { n -> NewsCard(n)}
}
}
}
}
}
}
// GPS 地域選択ダイアログ
val choice = gpsChoice
if (choice!= null) {
AlertDialog(
onDismissRequest = { gpsChoice = null},
title = { Text(choice.pref.name + " の地域区分を選択")},
text = {
LazyColumn {
items(choice.names) { name ->
Text(
name,
color = BodyTextColor,
fontSize = 15.sp,
modifier = Modifier
.fillMaxWidth()
.clickable {
gpsChoice = null
registerGpsLocation(choice.pref, choice.lat, choice.lon, name)
}
.padding(vertical = 10.dp)
)
}
}
},
confirmButton = {},
dismissButton = {
TextButton(onClick = { gpsChoice = null}) { Text("キャンセル")}
}
)
}
}

@Composable
private fun TabBar() {
val tabs = listOf(
"briefing" to "ブリーフィング",
"quakes" to "地震",
"radar" to "レーダー",
"news" to "ニュース",
"settings" to "設定"
)
Row(modifier = Modifier.padding(horizontal = 8.dp)) {
for ((id, label) in tabs) {
Button(
onClick = { showTab(id)},
colors = ButtonDefaults.buttonColors(
containerColor = CardColor,
contentColor = if (currentTab == id) AmberColor else Color.White
),
modifier = Modifier
.weight(1f)
.padding(horizontal = 2.dp)
) {
Text(label, fontSize = 11.sp)
}
}
Button(
onClick = { loadCurrent()},
colors = ButtonDefaults.buttonColors(
containerColor = CardColor,
contentColor = Color.White
),
modifier = Modifier.padding(horizontal = 2.dp)
) {
Text("↻", fontSize = 13.sp)
}
}
}

private fun levelColor(level: String?): Color {
return when (level?: "") {
"danger" -> Color(0xFFFF6B6B)
"warn" -> AmberColor
"ok" -> Color(0xFF58C184)
else -> Color(0xFF7FA8E0)
}
}

private fun scaleColor(scale: Int): Color {
if (scale >= 45) return Color(0xFFC93A3A) // 5弱以上: 赤
if (scale >= 40) return Color(0xFFD97A2B) // 4: オレンジ
if (scale >= 30) return Color(0xFFA8871D) // 3: 黄土
if (scale >= 20) return Color(0xFF3E6B9E) // 2: 青
return Color(0xFF54687F) // 1: グレー青
}

// カードの外枠 (タイトル行つき)
@Composable
private fun CardShell(title: String, level: String, body: @Composable () -> Unit) {
Column(
modifier = Modifier
.fillMaxWidth()
.padding(bottom = 10.dp)
.clip(RoundedCornerShape(12.dp))
.background(CardColor)
.padding(horizontal = 14.dp, vertical = 12.dp)
) {
Row(verticalAlignment = Alignment.CenterVertically) {
Text("●", color = levelColor(level), fontSize = 11.sp,
modifier = Modifier.padding(end = 8.dp))
Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
body()
}
}

@Composable
private fun SectionCard(s: AegisData.Section) {
CardShell(s.title, s.level) {
Text(
s.body,
color = BodyTextColor,
fontSize = 14.sp,
lineHeight = (14 * 1.3).sp,
modifier = Modifier.padding(top = 6.dp)
)
}
}

// ----- 表形式の描画ヘルパー -----
// ラベル列を揃えた [ラベル | 値] 行。値が空の行は注記として小さく全文表示する
@Composable
private fun LabelValueRow(label: String, value: String?, labelWidthDp: Int, alt: Boolean) {
if (value.isNullOrEmpty()) {
Text(
label,
color = SecondaryTextColor,
fontSize = 12.sp,
modifier = Modifier
.fillMaxWidth()
.padding(horizontal = 2.dp, vertical = 3.dp)
)
return
}
Row(
modifier = Modifier
.fillMaxWidth()
.then(if (alt) Modifier.background(LabelAltColor) else Modifier)
.padding(horizontal = 2.dp, vertical = 4.dp)
) {
Text(
label,
color = SecondaryTextColor,
fontSize = 13.sp,
fontWeight = FontWeight.Bold,
modifier = Modifier.width(labelWidthDp.dp)
)
Text(value, color = BodyTextColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
}
}

// 「ラベル：値」テキストをラベル列を揃えた行の集まりとして追加する
@Composable
private fun LabelRows(text: String?, labelWidthDp: Int) {
if (text.isNullOrEmpty()) return
val rows = AegisData.summaryRows(text)
for (i in rows.indices) {
LabelValueRow(rows[i][0], rows[i][1], labelWidthDp, i % 2 == 1)
}
}

// 表の1行 (widths: dp指定、0 は残り幅いっぱいの可変列)。ヘッダー行と交互背景に対応
@Composable
private fun TableRowCells(cells: Array<String>, widths: IntArray, header: Boolean, rowIndex: Int) {
Row(
verticalAlignment = Alignment.CenterVertically,
modifier = Modifier
.fillMaxWidth()
.background(
if (header) TableHeaderColor
else if (rowIndex % 2 == 0) CardColor else CardAltColor
)
.padding(horizontal = 8.dp, vertical = 5.dp)
) {
for (i in cells.indices) {
val m = if (widths[i] > 0) Modifier.width(widths[i].dp) else Modifier.weight(1f)
Text(
cells[i],
color = if (header) Color.White else BodyTextColor,
fontSize = 13.sp,
fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
modifier = m
)
}
}
}

// 表内の日付見出し行
@Composable
private fun DateHeaderRow(date: String, weekday: String) {
Text(
"📅 " + date + (if (weekday.isEmpty()) "" else "（" + weekday + "）"),
color = AmberColor,
fontSize = 12.sp,
fontWeight = FontWeight.Bold,
modifier = Modifier.padding(start = 2.dp, top = 8.dp, end = 2.dp, bottom = 3.dp)
)
}

// 表の小見出し
@Composable
private fun TableSubheading(text: String) {
Text(
text,
color = SecondaryTextColor,
fontSize = 12.sp,
fontWeight = FontWeight.Bold,
modifier = Modifier.padding(start = 2.dp, top = 10.dp, end = 2.dp, bottom = 4.dp)
)
}

// 「📌 現在のまとめ」カード (ラベル列を揃えた行レイアウト)
@Composable
private fun SummaryCard(s: AegisData.Section) {
CardShell(s.title, s.level) {
val rows = AegisData.summaryRows(s.body)
for (i in rows.indices) {
LabelValueRow(rows[i][0], rows[i][1], 108, i % 2 == 1)
}
}
}

// 天気カード (ラベル行 + 時間別/週間/降水確率の表)
@Composable
private fun WeatherCard(w: AegisData.WeatherView) {
CardShell("☀ 天気予報（" + w.loc.name + "）", w.level) {
LabelRows(w.daysText, 84)
LabelRows(w.tempsText, 84)
if (w.popRows.isNotEmpty()) {
TableSubheading("降水確率（気象庁・6時間ごと）")
TableRowCells(arrayOf("時間帯", "降水確率"), POP_COLS, true, 0)
var prevDate = ""
var idx = 0
for (r in w.popRows) {
if (r.date!= prevDate) {
DateHeaderRow(r.date, r.weekday)
prevDate = r.date
}
TableRowCells(
arrayOf(r.fromHour.toString() + "〜" + r.toHour + "時",
if (r.pop < 0) "-%" else r.pop.toString() + "%"),
POP_COLS, false, idx++
)
}
}
LabelRows(w.currentText, 84)
LabelRows(w.tomorrowText, 84)
if (w.hourRows.isNotEmpty()) {
TableSubheading("時間別予報（3時間ごと）")
TableRowCells(arrayOf("時刻", "天気", "気温", "降水"), HOUR_COLS, true, 0)
var prevDate = ""
var idx = 0
for (r in w.hourRows) {
if (r.date!= prevDate) {
DateHeaderRow(r.date, r.weekday)
prevDate = r.date
}
TableRowCells(
arrayOf(r.hour.toString() + "時", r.emoji + " " + r.desc,
r.temp.toString() + "℃", if (r.pop < 0) "-%" else r.pop.toString() + "%"),
HOUR_COLS, false, idx++
)
}
}
if (w.dayRows.isNotEmpty()) {
TableSubheading("週間予報")
TableRowCells(arrayOf("日付", "天気", "気温", "降水"), DAY_COLS, true, 0)
var idx = 0
for (r in w.dayRows) {
TableRowCells(
arrayOf(r.date + (if (r.weekday.isEmpty()) "" else "（" + r.weekday + "）"),
r.emoji + " " + r.desc, r.minTemp.toString() + "〜" + r.maxTemp + "℃",
if (r.pop < 0) "-%" else r.pop.toString() + "%"),
DAY_COLS, false, idx++
)
}
}
if (w.warningText.isNotEmpty()) {
Text(
w.warningText,
color = AmberColor,
fontSize = 13.sp,
lineHeight = (13 * 1.3).sp,
modifier = Modifier.padding(top = 8.dp)
)
}
}
}

// EEW カウントダウンカード (本文は状態 eewBodyText を1秒ごとに差し替える)
@Composable
private fun EewCountdownCard(eew: AegisData.EewInfo, demo: Boolean) {
CardShell(if (demo) "🚨 緊急地震速報" else "🚨 緊急地震速報", "danger") {
Text(
eewBodyText,
color = BodyTextColor,
fontSize = 15.sp,
lineHeight = (15 * 1.3).sp,
modifier = Modifier.padding(top = 6.dp)
)
}
}

@Composable
private fun QuakeCard(q: AegisData.Quake, big: Boolean) {
Row(
verticalAlignment = Alignment.CenterVertically,
modifier = Modifier
.fillMaxWidth()
.padding(bottom = 10.dp)
.clip(RoundedCornerShape(12.dp))
.background(CardColor)
.padding(horizontal = 12.dp, vertical = if (big) 16.dp else 10.dp)
) {
Box(
contentAlignment = Alignment.Center,
modifier = Modifier
.clip(RoundedCornerShape(8.dp))
.background(scaleColor(q.maxScale))
.padding(horizontal = 10.dp, vertical = 6.dp)
) {
Text(
"震度" + AegisData.scaleToStr(q.maxScale),
color = Color.White,
fontSize = if (big) 17.sp else 13.sp,
fontWeight = FontWeight.Bold
)
}
Column(modifier = Modifier
.weight(1f)
.padding(start = 12.dp)) {
Text(q.place, color = Color.White, fontSize = if (big) 19.sp else 15.sp,
fontWeight = FontWeight.Bold)
Text(q.detail, color = SecondaryTextColor, fontSize = if (big) 14.sp else 12.sp,
modifier = Modifier.padding(top = 2.dp))
if (big) {
val elapsed = elapsedText(q.originMillis)
if (elapsed.isNotEmpty()) {
Text("⏱ " + elapsed, color = AmberColor, fontSize = 13.sp,
modifier = Modifier.padding(top = 3.dp))
}
}
}
if (q.lat!= 0.0 || q.lon!= 0.0) {
Button(onClick = { openQuakeMap(q)}) {
Text("🗺 地図", fontSize = 12.sp)
}
}
}
}

// ----- レーダータブ -----
@Composable
private fun RadarContent(c: UiContent.RadarC) {
val locs = AegisData.effectiveLocations()
Column(
modifier = Modifier
.fillMaxWidth()
.padding(bottom = 10.dp)
.clip(RoundedCornerShape(12.dp))
.background(CardColor)
.padding(horizontal = 14.dp, vertical = 12.dp)
) {
Text("🌧 気象レーダー", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
Row(
modifier = Modifier
.padding(top = 8.dp)
.horizontalScroll(rememberScrollState())
) {
for (i in locs.indices) {
Button(
onClick = {
radarLocIndex = i
loadRadar()
},
colors = ButtonDefaults.buttonColors(
containerColor = CardAltColor,
contentColor = if (i == radarLocIndex) AmberColor else Color.White
),
modifier = Modifier.padding(end = 6.dp)
) {
Text(locs[i].name, fontSize = 12.sp)
}
}
}
}
val bmp = c.bitmap
if (bmp!= null) {
Image(
bitmap = bmp.asImageBitmap(),
contentDescription = "気象レーダー画像",
contentScale = ContentScale.FillWidth,
modifier = Modifier
.fillMaxWidth()
.padding(bottom = 8.dp)
)
}
Text(c.caption, color = SecondaryTextColor, fontSize = 12.sp)
}

// ----- ニュースタブ -----
@Composable
private fun NewsCard(n: AegisData.NewsItem) {
Column(
modifier = Modifier
.fillMaxWidth()
.padding(bottom = 10.dp)
.clip(RoundedCornerShape(12.dp))
.background(CardColor)
.clickable {
try {
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link)))
} catch (e: Exception) {
statusText = "記事を開けませんでした"
}
}
.padding(horizontal = 14.dp, vertical = 12.dp)
) {
Text(
(if (n.timeText.isEmpty()) "" else n.timeText + "　") + n.title,
color = Color.White,
fontSize = 15.sp,
fontWeight = FontWeight.Bold
)
if (n.summary.isNotEmpty()) {
Text(
n.summary,
color = BodyTextColor,
fontSize = 13.sp,
lineHeight = (13 * 1.3).sp,
modifier = Modifier.padding(top = 5.dp)
)
}
}
}

    // ----- 設定パネル -----
    @Composable
    private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onClick() }
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = AmberColor)
            )
            Text(label, color = Color.White, fontSize = 14.sp)
        }
    }

    @Composable
    private fun SettingsTextField(
        value: String,
        onValueChange: (String) -> Unit,
        hint: String,
        keyboardType: KeyboardType = KeyboardType.Text,
        modifier: Modifier = Modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, color = MutedTextColor) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AmberColor,
                unfocusedBorderColor = SecondaryTextColor,
                cursorColor = AmberColor,
                focusedContainerColor = CardColor,
                unfocusedContainerColor = CardColor
            ),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
    }

    @Composable
    private fun SettingsPanel() {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            // 取得モード
            RadioOption("直接取得（推奨）", modeSel == "direct") { modeSel = "direct" }
            RadioOption("AEGISサーバーから取得", modeSel == "server") { modeSel = "server" }
            SettingsTextField(serverUrlText, { serverUrlText = it }, "http://192.168.1.10:8765", KeyboardType.Uri)
            Button(onClick = { testServer() }, modifier = Modifier.fillMaxWidth()) {
                Text("接続テスト（サーバーURL）")
            }
            Button(onClick = { saveModeSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text("設定を保存")
            }
            Button(onClick = { runDiagnose() }, modifier = Modifier.fillMaxWidth()) {
                Text("🩺 接続テスト（自己診断）")
            }
            Button(
                onClick = { if (demoMode) disableDemo() else enableDemo() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🧪 テスト表示（デモ）")
            }
            Text(
                "接続テストは各データソースへの到達を確認します。テスト表示はサンプルの警報・緊急地震速報（カウントダウン付き）を表示するデモで、実際のデータに切り替わると自動で終了します。",
                color = MutedTextColor, fontSize = 11.sp
            )

            // バックグラウンド同期と通知
            Text(
                "🔔 バックグラウンド同期と通知",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(bgSyncStatusText, color = BodyTextColor, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("バックグラウンド同期（アプリを閉じていても新着を通知）",
                    color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = bgSyncOn, onCheckedChange = { onBgSyncChanged(it) })
            }
            Text("同期間隔", color = MutedTextColor, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp))
            Row {
                RadioOption("15分", intervalMin == 15) { onIntervalChanged(15) }
                RadioOption("30分", intervalMin == 30) { onIntervalChanged(30) }
                RadioOption("60分", intervalMin == 60) { onIntervalChanged(60) }
            }
            Text(notifyPermStatusText, color = BodyTextColor, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
            Button(onClick = { onNotifyPermClicked() }, modifier = Modifier.fillMaxWidth()) {
                Text("通知の許可をリクエスト")
            }
            Text("地震の通知しきい値", color = MutedTextColor, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                RadioOption("震度1", quakeThr == 10) { onQuakeThresholdChanged(10) }
                RadioOption("震度2", quakeThr == 20) { onQuakeThresholdChanged(20) }
                RadioOption("震度3", quakeThr == 30) { onQuakeThresholdChanged(30) }
                RadioOption("震度4", quakeThr == 40) { onQuakeThresholdChanged(40) }
                RadioOption("震度5弱", quakeThr == 45) { onQuakeThresholdChanged(45) }
            }
            Text("警報の通知範囲", color = MutedTextColor, fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp))
            RadioOption("特別警報・警報のみ", warnModeSel == AegisData.WARN_MODE_ALERTS_ONLY) {
                onWarnModeChanged(AegisData.WARN_MODE_ALERTS_ONLY)
            }
            RadioOption("注意報も含む", warnModeSel == AegisData.WARN_MODE_INCLUDE_ADVISORY) {
                onWarnModeChanged(AegisData.WARN_MODE_INCLUDE_ADVISORY)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("朝夕ブリーフィング通知（7:00 / 21:00、任意）",
                    color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = briefingNotifyOn, onCheckedChange = { onBriefingNotifyChanged(it) })
            }
            Button(onClick = { onNotifyTestClicked() }, modifier = Modifier.fillMaxWidth()) {
                Text("🔔 通知テスト")
            }
            Text(
                "緊急地震速報と津波は常に通知します。地震は上のしきい値以上、警報は発表・変更時に通知します。通知済みの情報は再通知しません。発生から1時間以上たった情報は通知しません。",
                color = MutedTextColor, fontSize = 11.sp
            )

            // 監視地点の追加
            Text(
                "📍 監視地点の追加（任意地点）",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                "都道府県コード例: 群馬県 100000 / 埼玉県 110000 / 東京都 130000（気象庁の都道府県コード）。地域名は気象庁の一次細分区域名（例: 南部）。緯度経度は地図アプリ等で調べられます。",
                color = MutedTextColor, fontSize = 11.sp
            )
            SettingsTextField(locNameText, { locNameText = it }, "地点名（例: 実家）")
            Row {
                SettingsTextField(locLatText, { locLatText = it }, "緯度（例: 36.32）",
                    KeyboardType.Decimal, Modifier.weight(1f))
                SettingsTextField(locLonText, { locLonText = it }, "経度（例: 139.00）",
                    KeyboardType.Decimal, Modifier.weight(1f))
            }
            Row {
                SettingsTextField(locPrefText, { locPrefText = it }, "都道府県コード",
                    KeyboardType.Number, Modifier.weight(1f))
                SettingsTextField(locAreaText, { locAreaText = it }, "地域名（例: 南部）",
                    KeyboardType.Text, Modifier.weight(1f))
            }
            Button(onClick = { addCustomLocation() }, modifier = Modifier.fillMaxWidth()) {
                Text("地点を追加")
            }
            Button(onClick = { onGpsClicked() }, modifier = Modifier.fillMaxWidth()) {
                Text("📍 現在地（GPS）を取得して地点登録")
            }
            Text(
                "GPSで取得した位置から都道府県を推定し、地域区分を選んで「現在地」として登録します。",
                color = MutedTextColor, fontSize = 11.sp
            )
            if (customLocs.isEmpty()) {
                Text(
                    "追加された地点はありません（高崎・大宮は標準で監視しています）",
                    color = MutedTextColor, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                for (i in customLocs.indices) {
                    val l = customLocs[i]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            "📍 " + l.name + "（" + l.lat + ", " + l.lon + "）",
                            color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { deleteCustomLocation(i) }) { Text("削除") }
                    }
                }
            }
        }
    }
}
