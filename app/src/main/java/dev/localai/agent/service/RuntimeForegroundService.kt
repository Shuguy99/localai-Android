package dev.localai.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.localai.agent.MainActivity
import dev.localai.agent.R

/**
 * Keeps the process alive while a model is loaded or a download is running.
 * The actual work lives in the application-scoped runtime; this service only
 * raises the process priority and posts the required notification.
 */
class RuntimeForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_runtime),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_runtime_title))
            .setContentText(getString(R.string.notification_runtime_text))
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "runtime"
        private const val NOTIFICATION_ID = 1001

        // A set of independent owners (model chat/models screen, each download).
        // The service runs while at least one owner is active.
        private val activeKeys = mutableSetOf<String>()

        @Synchronized
        fun acquire(context: Context, key: String) {
            activeKeys.add(key)
            start(context)
        }

        @Synchronized
        fun release(context: Context, key: String) {
            activeKeys.remove(key)
            if (activeKeys.isEmpty()) stop(context)
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RuntimeForegroundService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RuntimeForegroundService::class.java))
        }
    }
}
