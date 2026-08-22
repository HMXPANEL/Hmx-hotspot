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

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

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

        /**
         * Idempotent and crash-safe: Android throws ForegroundServiceStartNotAllowedException
         * when called from the background; we degrade to no-FGS instead of crashing and let
         * resync() retry next time the app is foregrounded.
         */
        fun start(context: Context, text: String) {
            if (running) return
            runCatching {
                context.startForegroundService(
                    Intent(context, HmxSessionService::class.java).putExtra(EXTRA_TEXT, text)
                )
                running = true
            }.onFailure { android.util.Log.w("HMX/FGS", "fgs start blocked: ${it.message}") }
        }

        /** Idempotent: stopping when not running is a no-op. */
        fun stop(context: Context) {
            if (!running) return
            running = false
            runCatching { context.stopService(Intent(context, HmxSessionService::class.java)) }
        }

        /** Call from Activity onResume: retry FGS if a session is active but service missing. */
        fun resync(context: Context, active: Boolean, text: String) {
            if (active && !running) start(context, text)
        }
    }
}
