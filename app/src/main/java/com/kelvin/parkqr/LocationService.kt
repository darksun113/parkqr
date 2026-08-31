package com.kelvin.parkqr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 后台持续记录定位的前台服务。
 *
 * 为什么必须是前台服务：App 切到后台后普通定位监听会被系统掐掉，
 * 而"开进地库前的最后一个有效点"恰恰是在开车途中（App 多半不在前台）产生的。
 * 前台服务 + foregroundServiceType=location 是 Android 10+ 唯一稳的路子。
 *
 * 车机常电，功耗不是问题；通知用 IMPORTANCE_MIN，不打扰。
 */
class LocationService : Service() {

    private var tracking: AutoCloseable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        // 1 分钟一次、位移 20 米以上才回调，足够抓住"进地库前最后一点"又不费电
        tracking = Geo.startTracking(this, minTimeMs = 60_000L, minDistanceM = 20f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY   // 被杀后系统重拉
    }

    override fun onDestroy() {
        runCatching { tracking?.close() }
        tracking = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "后台定位", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("停车码：后台记录位置")
            .setContentText("用于进地库后仍能匹配到正确的停车场")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "location"
        private const val NOTIF_ID = 2
        const val ACTION_STOP = "com.kelvin.parkqr.STOP_LOCATION"
        private const val PREF_KEY = "bgLocation"

        fun isEnabled(ctx: Context) =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY, on).apply()
            if (on) start(ctx) else stop(ctx)
        }

        fun start(ctx: Context) {
            if (!isEnabled(ctx) || !Geo.hasPermission(ctx)) return
            val i = Intent(ctx, LocationService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LocationService::class.java)) }
        }
    }
}
