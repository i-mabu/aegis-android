package jp.aegis.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ===== BootReceiver (端末再起動後にバックグラウンド同期の予約を復旧する) =====
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val app = context.applicationContext
            AegisSync.scheduleNextSync(app)
            AegisSync.scheduleBriefings(app)
        }
    }
}
