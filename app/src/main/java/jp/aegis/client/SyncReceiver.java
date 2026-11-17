package jp.aegis.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// ===== SyncReceiver (AlarmManager からの定期同期・朝夕ブリーフィングの受け口) =====
// チェーン方式: 次回のアラームを先に予約してから作業するので、
// 作業中にプロセスが終了しても同期の連鎖が切れない。
public class SyncReceiver extends BroadcastReceiver {
    public static final String ACTION_SYNC = "jp.aegis.client.ACTION_SYNC";
    public static final String ACTION_BRIEFING_AM = "jp.aegis.client.ACTION_BRIEFING_AM";
    public static final String ACTION_BRIEFING_PM = "jp.aegis.client.ACTION_BRIEFING_PM";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        final Context app = context.getApplicationContext();
        if (ACTION_SYNC.equals(action)) {
            AegisSync.scheduleNextSync(app);
            final PendingResult result = goAsync();
            new Thread(() -> {
                try { AegisSync.doSyncWork(app); } catch (Exception ignored) {}
                result.finish();
            }).start();
        } else if (ACTION_BRIEFING_AM.equals(action) || ACTION_BRIEFING_PM.equals(action)) {
            final boolean morning = ACTION_BRIEFING_AM.equals(action);
            AegisSync.scheduleBriefing(app, morning);
            final PendingResult result = goAsync();
            new Thread(() -> {
                try { AegisSync.doBriefingWork(app, morning); } catch (Exception ignored) {}
                result.finish();
            }).start();
        }
    }
}
