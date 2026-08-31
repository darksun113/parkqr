package com.kelvin.parkqr

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 定时把后台定位服务捞回来。
 *
 * 车机 ROM 会在没有电池优化豁免时把前台服务也杀掉（实测出现过定位停更 9 小时），
 * START_STICKY 不一定救得回来，所以再挂一个 15 分钟的非精确闹钟兜底。
 */
class Keepalive : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        LocationService.start(context)
        schedule(context)
    }

    companion object {
        private const val INTERVAL_MS = 15 * 60_000L
        const val ACTION = "com.kelvin.parkqr.KEEPALIVE"

        private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, Keepalive::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching {
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    INTERVAL_MS,
                    pi(ctx)
                )
            }
        }
    }
}
