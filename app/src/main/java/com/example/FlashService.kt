package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FlashService : Service() {

    companion object {
        private const val TAG = "FlashService"
        const val ACTION_START = "com.example.action.START_FLASH"
        const val ACTION_STOP = "com.example.action.STOP_FLASH"

        private const val CHANNEL_ID = "flash_service_channel"
        private const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            try {
                val intent = Intent(context, FlashService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${e.message}")
                // Fallback to in-app flash start
                FlashManager.startFlash(context)
            }
        }

        fun stopService(context: Context) {
            try {
                // First turn off hardware and audio immediately
                FlashManager.stopFlash(context)
                val intent = Intent(context, FlashService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: ${e.message}")
            }
        }

        fun toggleService(context: Context) {
            if (FlashManager.isFlashActive.value) {
                stopService(context)
            } else {
                startService(context)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Always satisfy Android foreground service requirement immediately on creation
        startForegroundWithNotification()

        serviceScope.launch {
            FlashManager.isFlashActive.collectLatest { isActive ->
                if (!isActive) {
                    stopForegroundNotification()
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                FlashManager.stopFlash(this)
                stopForegroundNotification()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForegroundWithNotification()
                val success = FlashManager.startFlash(this)
                if (!success) {
                    stopForegroundNotification()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in startForeground: ${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(this, 0, contentIntent, flags)

        val stopIntent = Intent(this, FlashService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_flashlight_tile)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_flashlight_tile,
                getString(R.string.action_turn_off),
                stopPendingIntent
            )
            .build()
    }

    private fun stopForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in stopForeground: ${t.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            } catch (t: Throwable) {
                Log.e(TAG, "Error creating notification channel: ${t.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FlashManager.stopFlash(this)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
