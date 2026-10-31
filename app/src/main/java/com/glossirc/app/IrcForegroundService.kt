package com.glossirc.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps the shared IRC model and DCC coroutine scope alive while the UI is closed.
 * Explicitly stopping the service disconnects sessions and cancels transfers.
 * Android may still kill the process; this stage does not restore connections after process death.
 */
object GlossRuntime {
    @Volatile private var instance: IrcViewModel? = null
    @Synchronized fun model(app: Application): IrcViewModel =
        instance ?: IrcViewModel(app).also { instance = it }
    @Synchronized fun shutdown() { instance?.shutdown(); instance = null }
}

class IrcForegroundService : Service() {
    companion object {
        const val ACTION_STOP = "com.glossirc.app.STOP_CONNECTIONS"
        private const val CHANNEL = "irc_connection_status"
        private const val NOTIFICATION_ID = 1201
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "IRC connections", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GlossRuntime.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        GlossRuntime.model(application)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, IrcForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("GlossIRC connected")
            .setContentText("IRC sessions and DCC transfers running")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect all", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
