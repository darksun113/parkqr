package com.kelvin.parkqr

import android.content.Context
import android.provider.Settings

/**
 * 「开机后该做什么」的唯一实现，两条路都走这里：
 *  1. 收到 BOOT_COMPLETED 等广播（标准 Android）
 *  2. ROM 的「开机应用自动启动」直接拉起 App —— 不少车机 ROM 干脆不发广播，
 *     只把 App 启起来，所以必须靠"开机后不久被启动"来识别（见 BootLaunch）
 */
object BootAction {

    fun run(ctx: Context, source: String): Boolean {
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.edit()
            .putLong("lastBootBroadcast", System.currentTimeMillis())
            .putString("lastBootAction", source)
            .apply()
        BootJob.schedule(ctx)

        fun skip(why: String): Boolean {
            sp.edit().putString("lastBootResult", why).apply()
            return false
        }

        // 后台定位与悬浮窗无关，任何情况下都要拉起来
        LocationService.start(ctx)
        Keepalive.schedule(ctx)

        if (!sp.getBoolean("bootOverlay", true)) return skip("App 内「开机悬浮」开关是关的")
        if (!Settings.canDrawOverlays(ctx)) return skip("没有「显示在其他应用上层」权限")
        if (LotStore.get(ctx).all().none { it.hasCode }) return skip("还没有任何带码的停车场")
        if (Home.isNear(ctx)) return skip("判定在家附近（半径 ${Home.radius(ctx)} m）")

        sp.edit().putString("lastBootResult", "已弹出悬浮窗").apply()
        return true
    }
}
