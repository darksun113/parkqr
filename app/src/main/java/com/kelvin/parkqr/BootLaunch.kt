package com.kelvin.parkqr

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

/**
 * 识别"这次是被 ROM 的开机自启动拉起来的"。
 *
 * 很多车机 ROM 不发 BOOT_COMPLETED，只在「开机应用自动启动」列表里直接启动 App。
 * 用开机后经过的时间（elapsedRealtime）判断：App 在开机 5 分钟内被启动，
 * 就当作一次开机事件。同一次开机只处理一次（用开机时刻做标识）。
 */
object BootLaunch {

    /** App 被直接拉起时的判定窗口 */
    const val WINDOW_MS = 5 * 60_000L
    /** JobScheduler 周期最短 15 分钟，开机后可能过一会儿才跑到，窗口放宽 */
    const val JOB_WINDOW_MS = 35 * 60_000L

    /** @return 本次是否判定为开机启动，且规则允许弹悬浮窗 */
    fun check(ctx: Context, windowMs: Long = WINDOW_MS): Boolean {
        val uptime = SystemClock.elapsedRealtime()
        if (uptime > windowMs) return false

        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val bootAt = System.currentTimeMillis() - uptime      // 本次开机的墙钟时刻
        if (abs(sp.getLong("lastBootHandled", 0L) - bootAt) < 60_000L) return false
        sp.edit().putLong("lastBootHandled", bootAt).apply()

        return BootAction.run(ctx, "ROM 开机自启动（未收到广播）")
    }
}
