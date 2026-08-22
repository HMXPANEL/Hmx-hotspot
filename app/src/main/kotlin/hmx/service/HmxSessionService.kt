package hmx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that keeps the process alive while a real sharing or
 * tunnel session is active, so screen-off/background does not kill networking.
 * Holds no logic of its own; RealEngine owns all state.
 */
class HmxSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Session active"
        startForeground(NOTIFY_ID, notification(text))
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "HMX sessions", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("HMX Remote Internet")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "hmx_sessions"
        private const val NOTIFY_ID = 42
        private const val EXTRA_TEXT = "text"

        @Volatile private var running = false

        /** Idempotent: repeated starts only update the notification text. */
        fun start(context: Context, text: String) {
            if (running) return
            running = true
            context.startForegroundService(
                Intent(context, HmxSessionService::class.java).putExtra(EXTRA_TEXT, text)
            )
        }

        /** Idempotent: stopping when not running is a no-op. */
        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, HmxSessionService::class.java))
        }
    }
}
