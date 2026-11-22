package app.oralis.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import app.oralis.MainActivity
import app.oralis.R
import java.util.UUID

object Notifications {
    const val CH_GENERATION = "generation"
    const val CH_DOWNLOAD = "model_download"
    const val CH_DONE = "done"
    const val ID_GENERATION = 1001
    const val ID_MODEL = 1002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_GENERATION, context.getString(R.string.channel_generation), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_generation_desc); setShowBadge(false) },
                NotificationChannel(CH_DOWNLOAD, context.getString(R.string.channel_download), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_download_desc); setShowBadge(false) },
                NotificationChannel(CH_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_done_desc) },
            ),
        )
    }

    private fun openApp(context: Context, route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(context, route.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun generation(context: Context, title: String, text: String, fraction: Float?, workId: UUID): Notification =
        NotificationCompat.Builder(context, CH_GENERATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .setContentIntent(openApp(context, "generation"))
            .addAction(0, "Cancelar", WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()

    fun modelDownload(context: Context, fraction: Float, indeterminate: Boolean, workId: UUID): Notification =
        NotificationCompat.Builder(context, CH_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Instalando a voz neural Kokoro")
            .setContentText(if (indeterminate) "Verificando…" else "${(fraction * 100).toInt()}%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(1000, (fraction * 1000).toInt(), indeterminate)
            .setContentIntent(openApp(context, "model"))
            .addAction(0, "Pausar", WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()

    /** POST_NOTIFICATIONS exists only on Android 13+; before that, the user setting decides. */
    private fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // checked by canPost()
    fun generationDone(context: Context, audioId: String, title: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Áudio pronto")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, "player/$audioId"))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(audioId.hashCode(), n) }
    }

    @SuppressLint("MissingPermission") // checked by canPost()
    fun generationFailed(context: Context, audioId: String, title: String, message: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Geração interrompida: $title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openApp(context, "generation/$audioId"))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(audioId.hashCode(), n) }
    }
}
