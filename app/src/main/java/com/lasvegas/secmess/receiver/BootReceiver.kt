package com.lasvegas.secmess.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lasvegas.secmess.service.RelayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                RelayService.start(context)
                com.lasvegas.secmess.service.KeepAliveJobService.schedule(context)
            }
        }
    }
}
