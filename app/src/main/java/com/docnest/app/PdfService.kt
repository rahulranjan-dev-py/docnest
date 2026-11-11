package com.docnest.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Runs for as long as [PdfJob] is making a PDF. A foreground service is what tells Android the
 * work matters while DocNest is off screen; in return it must show a notification, which carries
 * the progress and a Cancel button. If DocNest is off screen when the PDF is finished, a second
 * notification says so.
 */
class PdfService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            PdfJob.cancel()
            if (watching == null) stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Making a PDF", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "PDF ready", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.cancel(RESULT_ID)
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progress(PdfJob.state.value as? PdfJob.Running),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watching == null) {
            watching = scope.launch {
                val end = PdfJob.state
                    .onEach { if (it is PdfJob.Running) manager.notify(PROGRESS_ID, progress(it)) }
                    .first { it !is PdfJob.Running }
                ServiceCompat.stopForeground(this@PdfService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                // On screen, DocNest says it itself.
                val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (!visible) result(end)?.let { manager.notify(RESULT_ID, it) }
                watching = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Android 15+ gives this kind of service a few hours a day. Past that the PDF carries on,
    // only without the service's protection.
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(s: PdfJob.Running?): Notification {
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, PdfService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_pdf)
            .setContentTitle("Making your PDF")
            .setContentText(s?.text)
            .setProgress(1000, ((s?.fraction ?: 0f) * 1000).toInt(), s == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    /** What to say when the PDF ended while DocNest was off screen; null after a cancel. */
    private fun result(end: PdfJob.State): Notification? {
        val (title, text) = when (end) {
            is PdfJob.Done -> "Your PDF is ready" to (end.saved?.let { "${it.name} is in My documents." } ?: "Open DocNest to share it.")
            is PdfJob.Failed -> "Couldn't make the PDF" to "Open DocNest to try again."
            else -> return null
        }
        return NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_pdf)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    companion object {
        private const val ACTION_CANCEL = "com.docnest.app.CANCEL_PDF"
        private const val CHANNEL_PROGRESS = "pdf_progress"
        private const val CHANNEL_RESULT = "pdf_result"
        private const val PROGRESS_ID = 1
        private const val RESULT_ID = 2

        /** Takes down "Your PDF is ready" once the main screen has said it. */
        fun clearResult(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
        }
    }
}
