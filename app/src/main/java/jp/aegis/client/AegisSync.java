package jp.aegis.client;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

// ===== AegisSync (バックグラウンド同期と通知: 設定・スケジュール・通知投稿・同期本体) =====
// 同期は AlarmManager のチェーン方式: SyncReceiver がアラームを受けるたびに次回を予約する。
// 正確なアラーム権限 (SCHEDULE_EXACT_ALARM) は要求せず setAndAllowWhileIdle を使う。
public final class AegisSync {
private AegisSync() {}

public static final String PREFS = "aegis_prefs"; // MainActivity と同じ
private static final String KEY_BG_ENABLED = "bg_sync_enabled";
private static final String KEY_BG_INTERVAL = "bg_sync_interval"; // 分
private static final String KEY_QUAKE_THRESHOLD = "notify_quake_threshold"; // P2P震度 (10/20/30/40/45)
private static final String KEY_WARN_MODE = "notify_warn_mode"; // AegisData.WARN_MODE_*
private static final String KEY_BRIEFING = "briefing_notify";
private static final String KEY_NOTIFIED_IDS = "notified_ids";
private static final String KEY_LAST_SYNC = "last_sync_millis";
private static final String KEY_CUSTOMS = "custom_locations"; // MainActivity と同じ

public static final String EXTRA_TAB = "aegis_tab";

public static final String CH_URGENT = "urgent"; // 緊急通知 (EEW・津波)
public static final String CH_NOTICE = "notice"; // 防災通知 (地震・警報・ブリーフィング)

private static final int REQ_SYNC = 2001;
private static final int REQ_BRIEFING_AM = 2002;
private static final int REQ_BRIEFING_PM = 2003;

private static SharedPreferences prefs(Context ctx) {
return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
}

// ----- 設定の読み書き -----
public static boolean isBgSyncEnabled(Context ctx) {
return prefs(ctx).getBoolean(KEY_BG_ENABLED, true);
}

public static void setBgSyncEnabled(Context ctx, boolean on) {
prefs(ctx).edit().putBoolean(KEY_BG_ENABLED, on).apply();
}

public static int intervalMinutes(Context ctx) {
return prefs(ctx).getInt(KEY_BG_INTERVAL, 30);
}

public static void setIntervalMinutes(Context ctx, int minutes) {
prefs(ctx).edit().putInt(KEY_BG_INTERVAL, minutes).apply();
}

public static int quakeThresholdScale(Context ctx) {
return prefs(ctx).getInt(KEY_QUAKE_THRESHOLD, 30);
}

public static void setQuakeThresholdScale(Context ctx, int scale) {
prefs(ctx).edit().putInt(KEY_QUAKE_THRESHOLD, scale).apply();
}

public static int warnMode(Context ctx) {
return prefs(ctx).getInt(KEY_WARN_MODE, AegisData.WARN_MODE_ALERTS_ONLY);
}

public static void setWarnMode(Context ctx, int mode) {
prefs(ctx).edit().putInt(KEY_WARN_MODE, mode).apply();
}

public static boolean isBriefingEnabled(Context ctx) {
return prefs(ctx).getBoolean(KEY_BRIEFING, false);
}

public static void setBriefingEnabled(Context ctx, boolean on) {
prefs(ctx).edit().putBoolean(KEY_BRIEFING, on).apply();
}

public static long lastSyncMillis(Context ctx) {
return prefs(ctx).getLong(KEY_LAST_SYNC, 0);
}

// ----- 任意地点 (MainActivity と同じ保存形式を読む) -----
public static void loadCustomLocations(Context ctx) {
List<AegisData.Location> list = new ArrayList<>();
try {
String json = prefs(ctx).getString(KEY_CUSTOMS, "[]");
org.json.JSONArray arr = new org.json.JSONArray(json);
for (int i = 0; i < arr.length(); i++) {
org.json.JSONObject o = arr.getJSONObject(i);
list.add(new AegisData.Location(o.optString("n"), o.optDouble("la", 0), o.optDouble("lo", 0), o.optString("p"), o.optString("a")));
}
} catch (Exception ignored) {}
AegisData.customLocations = list;
}

// ----- 通知チャンネル -----
public static void ensureChannels(Context ctx) {
NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
if (nm == null) return;
NotificationChannel urgent = new NotificationChannel(CH_URGENT, "緊急通知", NotificationManager.IMPORTANCE_HIGH);
urgent.setDescription("緊急地震速報・津波警報などの緊急情報");
urgent.enableVibration(true);
nm.createNotificationChannel(urgent);
NotificationChannel notice = new NotificationChannel(CH_NOTICE, "防災通知", NotificationManager.IMPORTANCE_HIGH);
notice.setDescription("地震情報・気象警報・朝夕ブリーフィング");
notice.enableVibration(true);
nm.createNotificationChannel(notice);
}

// 通知が出せる状態か (API 33+ は POST_NOTIFICATIONS 許可、それ以前も端末設定を確認)
public static boolean notificationsAllowed(Context ctx) {
if (Build.VERSION.SDK_INT >= 33
&& ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!= PackageManager.PERMISSION_GRANTED) {
return false;
}
NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
return nm == null || nm.areNotificationsEnabled();
}

// ----- 通知の投稿 -----
public static void notifyEvent(Context ctx, AegisData.SyncEvent ev) {
ensureChannels(ctx);
NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
if (nm == null) return;
String tab = ("quake".equals(ev.kind) || "tsunami".equals(ev.kind) || "eew".equals(ev.kind))? "quakes": "briefing";
Intent open = new Intent(ctx, MainActivity.class);
open.putExtra(EXTRA_TAB, tab);
open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
int reqCode = ev.id.hashCode();
PendingIntent pi = PendingIntent.getActivity(ctx, reqCode, open,
PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
Notification n = new Notification.Builder(ctx, ev.urgent? CH_URGENT: CH_NOTICE)
.setSmallIcon(ev.urgent? android.R.drawable.ic_dialog_alert: android.R.drawable.ic_dialog_info)
.setContentTitle(ev.title)
.setContentText(ev.body)
.setStyle(new Notification.BigTextStyle().bigText(ev.body))
.setContentIntent(pi)
.setAutoCancel(true)
.setWhen(ev.eventMillis > 0? ev.eventMillis: System.currentTimeMillis())
.build();
nm.notify(reqCode, n);
}

// 通知テスト: その場でテスト通知を1件出す。許可がなければ false
public static boolean postTestNotification(Context ctx) {
if (!notificationsAllowed(ctx)) return false;
long now = System.currentTimeMillis();
notifyEvent(ctx, new AegisData.SyncEvent("test", "test:" + now,
"🧪 AEGIS 通知テスト",
"通知は正常に届いています。実際の地震・警報ではありません。\nバックグラウンド同期で新しい地震・警報があると、このように通知されます。",
false, now));
return true;
}

// ----- スケジューリング (AlarmManager チェーン方式) -----
private static PendingIntent syncIntent(Context ctx) {
Intent i = new Intent(ctx, SyncReceiver.class).setAction(SyncReceiver.ACTION_SYNC);
return PendingIntent.getBroadcast(ctx, REQ_SYNC, i,
PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
}

private static PendingIntent briefingIntent(Context ctx, boolean morning) {
Intent i = new Intent(ctx, SyncReceiver.class)
.setAction(morning? SyncReceiver.ACTION_BRIEFING_AM: SyncReceiver.ACTION_BRIEFING_PM);
return PendingIntent.getBroadcast(ctx, morning? REQ_BRIEFING_AM: REQ_BRIEFING_PM, i,
PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
}

// 次回の定期同期を予約する (OFF のときは予約を取り消す)
public static void scheduleNextSync(Context ctx) {
AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
if (am == null) return;
PendingIntent pi = syncIntent(ctx);
if (!isBgSyncEnabled(ctx)) {
am.cancel(pi);
return;
}
long at = System.currentTimeMillis() + AegisData.syncIntervalMillis(intervalMinutes(ctx));
am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
}

// 朝夕ブリーフィング (7:00 / 21:00) の次回を予約する (OFF のときは取り消す)
public static void scheduleBriefings(Context ctx) {
AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
if (am == null) return;
if (!isBriefingEnabled(ctx)) {
am.cancel(briefingIntent(ctx, true));
am.cancel(briefingIntent(ctx, false));
return;
}
scheduleBriefing(ctx, true);
scheduleBriefing(ctx, false);
}

public static void scheduleBriefing(Context ctx, boolean morning) {
AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
if (am == null ||!isBriefingEnabled(ctx)) return;
long at = AegisData.nextBriefingMillis(morning? 7: 21, 0, System.currentTimeMillis(), TimeZone.getDefault());
am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, briefingIntent(ctx, morning));
}

// ----- 同期本体 (SyncReceiver からバックグラウンドスレッドで呼ばれる) -----
public static void doSyncWork(Context ctx) {
loadCustomLocations(ctx);
List<AegisData.SyncEvent> events = AegisData.collectSyncEvents();
long now = System.currentTimeMillis();
SharedPreferences p = prefs(ctx);
List<String> idList = AegisData.splitIds(p.getString(KEY_NOTIFIED_IDS, ""));
LinkedHashSet<String> seen = new LinkedHashSet<>(idList);
boolean allowed = notificationsAllowed(ctx);
int threshold = quakeThresholdScale(ctx);
int mode = warnMode(ctx);
boolean changed = false;
for (AegisData.SyncEvent ev: events) {
if (seen.contains(ev.id)) continue;
// 通知しなかったイベントも「確認済み」として記録し、再評価とフラッドを防ぐ
seen.add(ev.id);
idList.add(ev.id);
changed = true;
if (!allowed) continue;
if (!AegisData.isFreshEvent(ev.eventMillis, now)) continue; // 1時間超は通知しない
boolean notify;
switch (ev.kind) {
case "quake":
notify = AegisData.shouldNotifyQuake(ev.maxScale, threshold);
break;
case "warning":
notify = AegisData.warningNotifiable(ev.hasSpecial, ev.hasWarning, mode);
break;
default: // tsunami / eew は常時通知
notify = true;
break;
}
if (notify) notifyEvent(ctx, ev);
}
SharedPreferences.Editor ed = p.edit().putLong(KEY_LAST_SYNC, now);
if (changed) ed.putString(KEY_NOTIFIED_IDS, AegisData.joinIds(AegisData.pruneIds(idList, AegisData.MAX_NOTIFIED_IDS)));
ed.apply();
}

// ----- 朝夕ブリーフィング通知 -----
public static void doBriefingWork(Context ctx, boolean morning) {
if (!isBriefingEnabled(ctx) ||!notificationsAllowed(ctx)) return;
loadCustomLocations(ctx);
try {
List<AegisData.Section> sections = AegisData.briefingSections();
if (sections.isEmpty()) return;
AegisData.Section summary = sections.get(0); // 先頭は「📌 現在のまとめ」
String date = new SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(new Date());
notifyEvent(ctx, new AegisData.SyncEvent("briefing",
"briefing:" + date + (morning? ":am": ":pm"),
(morning? "🌅 朝": "🌆 夜") + "のブリーフィング",
summary.body, false, System.currentTimeMillis()));
} catch (Exception ignored) {}
}
}
