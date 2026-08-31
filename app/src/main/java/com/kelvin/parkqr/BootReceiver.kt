package com.kelvin.parkqr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 车机开机自动拉起悬浮候选码。
 *
 * 各家 ROM 发的开机广播不统一，除标准 BOOT_COMPLETED 外还收几个常见变体；
 * 并且把"收到过什么、为什么没弹"记下来，供管理页的自启诊断查看 ——
 * 车机 ROM 常有自启动白名单，收不到广播时得让用户看得见原因。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return

        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.edit()
            .putLong("lastBootBroadcast", System.currentTimeMillis())
            .putString("lastBootAction", action)
            .apply()

        // 后台定位独立于悬浮窗：开机就开始记录，好让进地库前的最后一点被抓到
        LocationService.start(context)

        fun skip(why: String) {
            sp.edit().putString("lastBootResult", why).apply()
        }

        if (!sp.getBoolean("bootOverlay", true)) return skip("App 内「开机悬浮」开关是关的")
        if (!Settings.canDrawOverlays(context)) return skip("没有「显示在其他应用上层」权限")
        if (LotStore.get(context).all().none { it.hasCode }) return skip("还没有任何带码的停车场")
        // 在家附近不弹：自家车位不需要缴费码（手动"测试悬浮窗"不走这里）
        if (Home.isNear(context)) return skip("判定在家附近（半径 ${Home.radius(context)} m）")

        sp.edit().putString("lastBootResult", "已弹出悬浮窗").apply()
        OverlayService.start(context)
    }

    companion object {
        const val ACTION_TEST = "com.kelvin.parkqr.TEST_BOOT"

        /** 各家 ROM 的开机广播变体 */
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT",
            ACTION_TEST
        )
    }
}
