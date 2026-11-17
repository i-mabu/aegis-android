package jp.aegis.client

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone

// ===== AegisSync (バックグラウンド同期と通知: 設定・スケジュール・通知投稿・同期本体) =====
// 同期は AlarmManager のチェーン方式: SyncReceiver がアラームを受けるたびに次回を予約する。
// 正確なアラーム権限 (SCHEDULE_EXACT_ALARM) は要求せず setAndAllowWhileIdle を使う。
object AegisSync {
    const val PREFS = "aegis_prefs" // MainActivity と同じ
    private const val KEY_BG_ENABLED = "bg_sync_enabled"
    private const val KEY_BG_INTERVAL = "bg_sync_interval" // 分
    private const val KEY_QUAKE_THRESHOLD = "notify_quake_threshold" // P2P震度 (10/20/30/40/45)
    private const val KEY_WARN_MODE = "notify_warn_mode" // AegisData.WARN_MODE_*
    private const val KEY_BRIEFING = "briefing_notify"
    private const val KEY_NOTIFIED_IDS = "notified_ids"
    private const val KEY_LAST_SYNC = "last_sync_millis"
    private const val KEY_CUSTOMS = "custom_locations" // MainActivity と同じ

    const val EXTRA_TAB = "aegis_tab"

    const val CH_URGENT = "urgent" // 緊急通知 (EEW・津波)
    const val CH_NOTICE = "notice" // 防災通知 (地震・警報・ブリーフィング)

    private const val REQ_SYNC = 2001
    private const val REQ_BRIEFING_AM = 2002
    private const val REQ_BRIEFING_PM = 2003

    private fun prefs(ctx: Context): SharedPreferences {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ----- 設定の読み書き -----
    fun isBgSyncEnabled(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_BG_ENABLED, true)
    }

    fun setBgSyncEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BG_ENABLED, on).apply()
    }

    fun intervalMinutes(ctx: Context): Int {
        return prefs(ctx).getInt(KEY_BG_INTERVAL, 30)
    }

    fun setIntervalMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_BG_INTERVAL, minutes).apply()
    }

    fun quakeThresholdScale(ctx: Context): Int {
        return prefs(ctx).getInt(KEY_QUAKE_THRESHOLD, 30)
    }

    fun setQuakeThresholdScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_QUAKE_THRESHOLD, scale).apply()
    }

    fun warnMode(ctx: Context): Int {
        return prefs(ctx).getInt(KEY_WARN_MODE, AegisData.WARN_MODE_ALERTS_ONLY)
    }

    fun setWarnMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_WARN_MODE, mode).apply()
    }

    fun isBriefingEnabled(ctx: Context): Boolean {
        return prefs(ctx).getBoolean(KEY_BRIEFING, false)
    }

    fun setBriefingEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BRIEFING, on).apply()
    }

    fun lastSyncMillis(ctx: Context): Long {
        return prefs(ctx).getLong(KEY_LAST_SYNC, 0)
    }

    // ----- 任意地点 (MainActivity と同じ保存形式を読む) -----
    fun loadCustomLocations(ctx: Context) {
        val list = ArrayList<AegisData.Location>()
        try {
            val json = prefs(ctx).getString(KEY_CUSTOMS, "[]")
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(AegisData.Location(o.optString("n"), o.optDouble("la", 0.0), o.optDouble("lo", 0.0), o.optString("p"), o.optString("a")))
            }
        } catch (e: Exception) {}
        AegisData.customLocations = list
    }

    // ----- 通知チャンネル -----
    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager? ?: return
        val urgent = NotificationChannel(CH_URGENT, "緊急通知", NotificationManager.IMPORTANCE_HIGH)
        urgent.description = "緊急地震速報・津波警報などの緊急情報"
        urgent.enableVibration(true)
        nm.createNotificationChannel(urgent)
        val notice = NotificationChannel(CH_NOTICE, "防災通知", NotificationManager.IMPORTANCE_HIGH)
        notice.description = "地震情報・気象警報・朝夕ブリーフィング"
        notice.enableVibration(true)
        nm.createNotificationChannel(notice)
    }

    // 通知が出せる状態か (API 33+ は POST_NOTIFICATIONS 許可、それ以前も端末設定を確認)
    fun notificationsAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        return nm == null || nm.areNotificationsEnabled()
    }

    // ----- 通知の投稿 -----
    fun notifyEvent(ctx: Context, ev: AegisData.SyncEvent) {
        ensureChannels(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager? ?: return
        val tab = if ("quake" == ev.kind || "tsunami" == ev.kind || "eew" == ev.kind) "quakes" else "briefing"
        val open = Intent(ctx, MainActivity::class.java)
        open.putExtra(EXTRA_TAB, tab)
        open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val reqCode = ev.id.hashCode()
        val pi = PendingIntent.getActivity(ctx, reqCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, if (ev.urgent) CH_URGENT else CH_NOTICE)
                .setSmallIcon(if (ev.urgent) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
                .setContentTitle(ev.title)
                .setContentText(ev.body)
                .setStyle(Notification.BigTextStyle().bigText(ev.body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setWhen(if (ev.eventMillis > 0) ev.eventMillis else System.currentTimeMillis())
                .build()
        nm.notify(reqCode, n)
    }

    // 通知テスト: その場でテスト通知を1件出す。許可がなければ false
    fun postTestNotification(ctx: Context): Boolean {
        if (!notificationsAllowed(ctx)) return false
        val now = System.currentTimeMillis()
        notifyEvent(ctx, AegisData.SyncEvent("test", "test:" + now,
                "🧪 AEGIS 通知テスト",
                "通知は正常に届いています。実際の地震・警報ではありません。\nバックグラウンド同期で新しい地震・警報があると、このように通知されます。",
                false, now))
        return true
    }

    // ----- スケジューリング (AlarmManager チェーン方式) -----
    private fun syncIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, SyncReceiver::class.java).setAction(SyncReceiver.ACTION_SYNC)
        return PendingIntent.getBroadcast(ctx, REQ_SYNC, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun briefingIntent(ctx: Context, morning: Boolean): PendingIntent {
        val i = Intent(ctx, SyncReceiver::class.java)
                .setAction(if (morning) SyncReceiver.ACTION_BRIEFING_AM else SyncReceiver.ACTION_BRIEFING_PM)
        return PendingIntent.getBroadcast(ctx, if (morning) REQ_BRIEFING_AM else REQ_BRIEFING_PM, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // 次回の定期同期を予約する (OFF のときは予約を取り消す)
    fun scheduleNextSync(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager? ?: return
        val pi = syncIntent(ctx)
        if (!isBgSyncEnabled(ctx)) {
            am.cancel(pi)
            return
        }
        val at = System.currentTimeMillis() + AegisData.syncIntervalMillis(intervalMinutes(ctx))
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    // 朝夕ブリーフィング (7:00 / 21:00) の次回を予約する (OFF のときは取り消す)
    fun scheduleBriefings(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager? ?: return
        if (!isBriefingEnabled(ctx)) {
            am.cancel(briefingIntent(ctx, true))
            am.cancel(briefingIntent(ctx, false))
            return
        }
        scheduleBriefing(ctx, true)
        scheduleBriefing(ctx, false)
    }

    fun scheduleBriefing(ctx: Context, morning: Boolean) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager? ?: return
        if (!isBriefingEnabled(ctx)) return
        val at = AegisData.nextBriefingMillis(if (morning) 7 else 21, 0, System.currentTimeMillis(), TimeZone.getDefault())
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, briefingIntent(ctx, morning))
    }

    // ----- 同期本体 (SyncReceiver からバックグラウンドスレッドで呼ばれる) -----
    // 保存は段階的に行う: 最終同期時刻は開始直後、通知済み ID はイベントを処理するたびに
    // commit() で即時保存する。goAsync の時間制限でプロセスが終了しても記録が残るようにするため。
    fun doSyncWork(ctx: Context) {
        loadCustomLocations(ctx)
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        p.edit().putLong(KEY_LAST_SYNC, now).commit()
        val events = AegisData.collectSyncEvents()
        var idList = AegisData.splitIds(p.getString(KEY_NOTIFIED_IDS, ""))
        // 初回同期 (通知済み ID が空) は登録のみで通知しない (シーディングによるフラッド防止)
        val seeding = AegisData.isFirstSync(idList)
        val seen = LinkedHashSet(idList)
        val allowed = notificationsAllowed(ctx)
        val threshold = quakeThresholdScale(ctx)
        val mode = warnMode(ctx)
        for (ev in events) {
            if (seen.contains(ev.id)) continue
            // 通知しなかったイベントも「確認済み」として記録し、再評価とフラッドを防ぐ
            seen.add(ev.id)
            idList.add(ev.id)
            idList = AegisData.pruneIds(idList, AegisData.MAX_NOTIFIED_IDS)
            p.edit().putString(KEY_NOTIFIED_IDS, AegisData.joinIds(idList)).commit()
            if (AegisData.shouldNotifyEvent(ev, seeding, allowed, threshold, mode, now)) notifyEvent(ctx, ev)
        }
    }

    // ----- 朝夕ブリーフィング通知 -----
    fun doBriefingWork(ctx: Context, morning: Boolean) {
        if (!isBriefingEnabled(ctx) || !notificationsAllowed(ctx)) return
        loadCustomLocations(ctx)
        try {
            val sections = AegisData.briefingSections()
            if (sections.isEmpty()) return
            val summary = sections[0] // 先頭は「📌 現在のまとめ」
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(Date())
            notifyEvent(ctx, AegisData.SyncEvent("briefing",
                    "briefing:" + date + (if (morning) ":am" else ":pm"),
                    (if (morning) "🌅 朝" else "🌆 夜") + "のブリーフィング",
                    summary.body, false, System.currentTimeMillis()))
        } catch (e: Exception) {}
    }
}
