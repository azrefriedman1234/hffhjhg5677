package com.pasiflonet.mobile.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.td.TdLibManager

class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        // יצירת התראה קבועה
        startForeground(1, createNotification())
        
        // מניעת הירדמות המעבד (CPU)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pasiflonet:KeepAlive")
        wakeLock?.acquire(10*60*1000L /* 10 minutes */)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // וידוא ש-TDLib רץ
        // אנחנו לא קוראים ל-init שוב אם הוא כבר רץ, אבל מוודאים שהתהליך חי
        return START_STICKY // אם המערכת הורגת אותנו, שתחייה אותנו מיד מחדש
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
    }

    private fun createNotification(): Notification {
        val channelId = "PasiflonetService"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Pasiflonet Mobile")
            .setContentText("Keeping connection alive...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
