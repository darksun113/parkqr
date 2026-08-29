package com.kelvin.parkqr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 车机开机（BOOT_COMPLETED）自动拉起悬浮候选码。
 * TEST_BOOT 是调试入口：adb shell am broadcast -a com.kelvin.parkqr.TEST_BOOT
 * （BOOT_COMPLETED 是受保护广播，shell 发不了，测试只能走这个）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_TEST) return

        val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("bootOverlay", true)
        if (!enabled) return
        if (!Settings.canDrawOverlays(context)) return
        // 在家附近不弹：自家车位不需要缴费码（手动"测试悬浮窗"不走这里，不受限）
        if (Home.isNear(context)) return

        OverlayService.start(context)
    }

    companion object {
        const val ACTION_TEST = "com.kelvin.parkqr.TEST_BOOT"
    }
}
