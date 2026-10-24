package com.lasvegas.secmess.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lasvegas.secmess.util.NotificationHelper

/**
 * Gatilho de teste das notificações (só builds de debug):
 *
 *   am broadcast -n com.lasvegas.secmess/.debug.NotifProbeReceiver \
 *      -a com.lasvegas.secmess.PROBE_MSG --es uid user_test --es name kika \
 *      --es text "Consulta do médico amanhã às 15:00"
 */
class NotifProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MSG -> NotificationHelper.onMessageReceived(
                context,
                intent.getStringExtra("uid") ?: "user_test",
                intent.getStringExtra("name") ?: "kika",
                intent.getStringExtra("text") ?: "Consulta do médico amanhã às 15:00",
            )
            ACTION_CALL -> NotificationHelper.onCallReceived(
                context,
                intent.getStringExtra("uid") ?: "user_test",
                intent.getStringExtra("name") ?: "kika",
            )
            ACTION_DISMISS -> intent.getStringExtra("uid")?.let {
                NotificationHelper.onDismissed(it)
                NotificationHelper.stopRinging()
            }
        }
    }

    companion object {
        const val ACTION_MSG = "com.lasvegas.secmess.PROBE_MSG"
        const val ACTION_CALL = "com.lasvegas.secmess.PROBE_CALL"
        const val ACTION_DISMISS = "com.lasvegas.secmess.PROBE_DISMISS"
    }
}
