package jp.aegis.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ===== SyncReceiver (AlarmManager からの定期同期・朝夕ブリーフィングの受け口) =====
// チェーン方式: 次回のアラームを先に予約してから作業するので、
// 作業中にプロセスが終了しても同期の連鎖が切れない。
class SyncReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SYNC = "jp.aegis.client.ACTION_SYNC"
        const val ACTION_BRIEFING_AM = "jp.aegis.client.ACTION_BRIEFING_AM"
        const val ACTION_BRIEFING_PM = "jp.aegis.client.ACTION_BRIEFING_PM"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = if (intent == null) "" else intent.action.toString()
        val app = context.applicationContext
        if (ACTION_SYNC == action) {
            AegisSync.scheduleNextSync(app)
            val result = goAsync()
            Thread {
                try { AegisSync.doSyncWork(app) } catch (e: Exception) {}
                result.finish()
            }.start()
        } else if (ACTION_BRIEFING_AM == action || ACTION_BRIEFING_PM == action) {
            val morning = ACTION_BRIEFING_AM == action
            AegisSync.scheduleBriefing(app, morning)
            val result = goAsync()
            Thread {
                try { AegisSync.doBriefingWork(app, morning) } catch (e: Exception) {}
                result.finish()
            }.start()
        }
    }
}
