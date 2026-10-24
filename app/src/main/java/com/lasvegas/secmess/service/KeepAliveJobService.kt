package com.lasvegas.secmess.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Rede de segurança do serviço de mensagens.
 *
 * O Android 15/16 pode congelar/matar o processo quando a app está em segundo plano
 * (o FGS `dataSync` é restrito a partir de BOOT_COMPLETED). Este job corre a cada
 * 15 minutos (mínimo do JobScheduler) e, se o `RelayService` não estiver vivo ou não
 * tiver dado sinal há mais de 3 minutos, volta a arrancá-lo.
 *
 * O FGS passou a `remoteMessaging`, que o sistema trata como mensagens e não como
 * sincronização — é o tipo correto para este caso e sobrevive ao boot.
 */
class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val now = System.currentTimeMillis()
        val stale = now - RelayService.lastAliveAt > 3 * 60_000L
        Log.i(TAG, "job: running=${RelayService.isRunning} stale=$stale")
        if (!RelayService.isRunning || stale) {
            try {
                RelayService.start(applicationContext)
            } catch (t: Throwable) {
                Log.w(TAG, "não arrancou o serviço: ${t.message}")
            }
        }
        schedule(applicationContext)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "OGKeepAlive"
        private const val JOB_ID = 7101

        fun schedule(context: Context) {
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (js.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(
                    JOB_ID,
                    ComponentName(context, KeepAliveJobService::class.java),
                )
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build()
                js.schedule(job)
                Log.i(TAG, "job agendado (15 min)")
            } catch (t: Throwable) {
                Log.w(TAG, "schedule falhou: ${t.message}")
            }
        }
    }
}
