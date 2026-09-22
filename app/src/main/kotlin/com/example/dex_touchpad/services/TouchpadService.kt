package com.example.dex_touchpad.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.dex_touchpad.IMouseControl
import com.example.dex_touchpad.MainActivity
import com.example.dex_touchpad.R

private const val TAG = "TouchpadService"
private const val CHANNEL_ID = "dex_touchpad_channel"
private const val NOTIFICATION_ID = 1

class TouchpadService : Service() {

    private val localBinder = LocalBinder()
    var mouseControl: IMouseControl? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): TouchpadService = this@TouchpadService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        Log.d(TAG, "TouchpadService created")
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not sticky: if the process is killed (force-stop, low memory) the system
        // must not silently resurrect it. A stale restart would bind a second
        // Shizuku user service and therefore a second virtual mouse.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "TouchpadService destroyed")
    }

    fun setMouseControl(service: IMouseControl?) {
        mouseControl = service
        Log.d(TAG, "MouseControl service ${if (service != null) "connected" else "disconnected"}")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dex Touchpad",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Cover display touchpad for Samsung DeX"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Dex Touchpad Active")
            .setContentText("Touch the cover display to control your cursor")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
