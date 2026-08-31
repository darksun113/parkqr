package com.kelvin.parkqr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        if (BootAction.run(context, action)) OverlayService.start(context)
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
