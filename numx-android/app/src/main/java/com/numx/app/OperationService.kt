package com.numx.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps long contact operations alive while the screen is off or the activity
 * is in the background. The actual work lives in the application-scoped
 * OperationController, so destroying or recreating the Activity does not
 * cancel it; this service keeps the process and its wake lock alive.
 */
class OperationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                val manager = getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "عمليات الأرقام",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "إشعارات معالجة الأرقام"
                }
                manager.createNotificationChannel(channel)
            }
        }
        // Register as foreground immediately. onStartCommand may be delayed on
        // a busy main thread, and Android kills a startForegroundService() app
        // that does not call startForeground() within its short deadline.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(DEFAULT_TEXT)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        acquireWakeLock()
        val started = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("معالجة الأرقام")
        .setContentText(text)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    override fun onDestroy() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val powerManager = getSystemService(PowerManager::class.java) ?: return
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "numbers:bulk-operation",
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "operations"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TEXT = "text"
        private const val DEFAULT_TEXT = "جارٍ معالجة الأرقام..."
        private const val STOP_DELAY_MS = 1_500L

        private val mainHandler = Handler(Looper.getMainLooper())
        private var pendingStop: Runnable? = null

        fun start(context: Context, text: String) {
            val appContext = context.applicationContext
            if (Looper.myLooper() == Looper.getMainLooper()) {
                startNow(appContext, text)
            } else {
                mainHandler.post { startNow(appContext, text) }
            }
        }

        private fun startNow(appContext: Context, text: String) {
            pendingStop?.let { mainHandler.removeCallbacks(it) }
            pendingStop = null
            runCatching {
                val intent = Intent(appContext, OperationService::class.java)
                    .putExtra(EXTRA_TEXT, text)
                ContextCompat.startForegroundService(appContext, intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            mainHandler.post {
                pendingStop?.let { mainHandler.removeCallbacks(it) }
                val stop = Runnable {
                    pendingStop = null
                    runCatching {
                        appContext.stopService(Intent(appContext, OperationService::class.java))
                    }
                }
                pendingStop = stop
                // Give a just-started service time to reach startForeground()
                // before tearing it down. Without this delay an operation that
                // fails during its first check could trigger the platform's
                // ForegroundServiceDidNotStartInTimeException.
                mainHandler.postDelayed(stop, STOP_DELAY_MS)
            }
        }
    }
}