package com.kishan.together

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class PresenceService : Service() {
    private var eventId = 100

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> Repo.setActive(true)
                Intent.ACTION_SCREEN_ON -> if (!locked()) Repo.setActive(true)
                Intent.ACTION_SCREEN_OFF -> Repo.setActive(false)
            }
        }
    }

    private fun locked() = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("running", "Running", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("alerts", "Alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("talk", "Wants to talk", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "running")
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("ChatGPT is sharing your status")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)

        Repo.onEvent = { kind, text -> alert(kind, text) }
        Repo.start()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        Repo.setActive(pm.isInteractive && !locked())
    }

    private fun alert(kind: String, text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, if (kind == "talk") "talk" else "alerts")
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(eventId++, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(receiver)
        Repo.onEvent = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, PresenceService::class.java))
    }
}
