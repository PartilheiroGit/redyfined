package com.lasvegas.secmess.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import com.lasvegas.secmess.R

/**
 * UpdateManager — download da app completa.
 *
 * P2: o download de `.onion` **tem de passar pelo SOCKS do Tor embebido**.
 * O `DownloadManager` do Android corre no processo do sistema, não usa o
 * SOCKS da app e não resolve `.onion`; por isso é substituído por um
 * `HttpURLConnection` com o proxy do [TorManager].
 */
object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val COMPLETE_APP_PACKAGE = "com.lasvegas.secmess"
    private const val CH_UPDATE = "updates_v1"
    private const val NOTIF_ID = 9101
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** -1 = indeterminado; null = sem download; 0..1 = progresso. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress = _progress.asStateFlow()
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastStartAt = 0L

    /** True enquanto há um download desta app em curso. */
    fun isDownloading(): Boolean = inFlight.get()

    fun startUpdate(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) {
        val now = System.currentTimeMillis()
        if (!inFlight.compareAndSet(false, true)) {
            onFailure("Download já em curso")
            return
        }
        if (now - lastStartAt < 30_000L) {
            inFlight.set(false)
            onFailure("Aguarda alguns segundos antes de tentar outra vez")
            return
        }
        lastStartAt = now
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "System permission needed to sync.", Toast.LENGTH_LONG).show()
                inFlight.set(false)
                onFailure("Permissão para instalar apps necessária")
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        }

        _progress.value = -1f
        notifyUpdate(context, null, "A preparar atualização…")

        scope.launch {
          try {
            var last: Throwable? = null
            repeat(2) { attempt ->
                try {
                    val file = download(context, apkUrl) { p ->
                        onProgress(p)
                        val v = if (p < 0f) -1f else p.coerceIn(0f, 1f)
                        _progress.value = v
                        notifyUpdate(
                            context,
                            if (v < 0f) null else v,
                            if (v < 0f) "A descarregar…" else "A descarregar… ${(v * 100).toInt()}%",
                        )
                    }
                    withContext(Dispatchers.Main) {
                        _progress.value = null
                        cancelUpdate(context)
                        install(context, file)
                    }
                    return@launch
                } catch (t: Throwable) {
                    last = t
                    Log.w(TAG, "Tentativa ${attempt + 1}/2 falhou: ${t.message}")
                    if (attempt == 0) delay(3_000)
                }
            }

            val failure = last ?: IOException("download failed")
            val message = failure.message ?: failure.javaClass.simpleName
            Log.e(TAG, "Falha ao descarregar $apkUrl", failure)
            _progress.value = null
            cancelUpdate(context)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Falha no download: $message",
                    Toast.LENGTH_LONG,
                ).show()
                onFailure(message)
            }
          } finally {
            inFlight.set(false)
          }
        }
    }

    private fun download(context: Context, apkUrl: String, onProgress: (Float) -> Unit): File {
        val file = File(context.cacheDir, "temp_update.apk")
        if (file.exists()) file.delete()

        val main = Handler(Looper.getMainLooper())
        fun report(value: Float) {
            main.post { onProgress(value) }
        }

        Log.d(TAG, "Download via Tor: $apkUrl (SOCKS=${TorManager.socksPort.value})")
        val connection = TorManager.openConnection(apkUrl)
        connection.connectTimeout = 300_000
        // O download de ~85 MB por Tor pode demorar vários minutos.
        connection.readTimeout = 600_000
        connection.instanceFollowRedirects = true

        try {
            val code = connection.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val total = connection.contentLengthLong
            if (total <= 0L) report(-1f) // sem Content-Length: barra indeterminada
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0L) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                report((read.toDouble() / total.toDouble()).toFloat())
                            }
                        }
                    }
                    if (total > 0L) report(1f)
                }
            }
        } finally {
            connection.disconnect()
        }

        if (file.length() < 1024L) throw IOException("APK inválido (${file.length()} bytes)")

        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("O ficheiro não é um APK")
        if (info.packageName != COMPLETE_APP_PACKAGE) {
            throw IOException("Pacote inesperado: ${info.packageName}")
        }

        Log.d(TAG, "APK OK: ${info.packageName} code=${info.longVersionCode} size=${file.length()}")
        return file
    }

    /**
     * Se o APK desta versão já está em cache (descarregado em background), abre o
     * instalador sem voltar a descarregar. Usado no auto-update ao abrir a app.
     */
    fun installCached(context: Context, expectedCode: Int): Boolean {
        val file = File(context.cacheDir, "temp_update.apk")
        if (!file.exists()) return false
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        if (info.packageName != COMPLETE_APP_PACKAGE) return false
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
                   else @Suppress("DEPRECATION") info.versionCode
        if (code != expectedCode) return false
        install(context, file)
        return true
    }

    private fun notifyUpdate(context: Context, progress: Float?, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_UPDATE, "Atualizações", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                }
            )
            val b = NotificationCompat.Builder(context, CH_UPDATE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("O.G. MESS — atualização")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (progress == null) b.setProgress(0, 0, true)
            else b.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            nm.notify(NOTIF_ID, b.build())
        } catch (_: Exception) {}
    }

    private fun cancelUpdate(context: Context) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        } catch (_: Exception) {}
    }

    private fun install(context: Context, file: File) {
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    fun cleanup(context: Context) {
        val file = File(context.cacheDir, "temp_update.apk")
        if (file.exists()) file.delete()
    }
}
