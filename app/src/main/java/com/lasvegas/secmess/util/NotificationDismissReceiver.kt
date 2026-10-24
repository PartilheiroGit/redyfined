package com.lasvegas.secmess.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Swipe/dismiss de uma notificação: corta o toque da chamada (loop) e deixa o estado
 * da mensagem/chamada intacto na app.
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == NotificationHelper.ACTION_DISMISS) {
            intent.getStringExtra(NotificationHelper.EXTRA_UID)?.let {
                NotificationHelper.onDismissed(it)
            }
            NotificationHelper.stopRinging()
        }
    }
}
