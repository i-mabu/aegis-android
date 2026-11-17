package jp.aegis.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// ===== BootReceiver (端末再起動後にバックグラウンド同期の予約を復旧する) =====
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Context app = context.getApplicationContext();
            AegisSync.scheduleNextSync(app);
            AegisSync.scheduleBriefings(app);
        }
    }
}
