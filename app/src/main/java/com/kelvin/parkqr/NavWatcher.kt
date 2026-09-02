package com.kelvin.parkqr

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * 监视前台应用，导航类 App 一到前台就浮出停车码。
 *
 * 为什么要这个：车机 ROM 常常既不发开机广播、自启白名单也不生效，
 * 开机自启这条路不一定走得通。而用户真正的需求是"看导航时能看到停车码"——
 * 那就直接盯住导航 App 的启动事件，比等开机更贴合场景。
 *
 * 用 UsageStatsManager 读前台切换事件（需要「有权查看使用情况」授权，
 * 系统设置里手动开一次即可，不需要 root，也不需要无障碍服务那么重的权限）。
 */
object NavWatcher {

    /** 常见导航 App。amapauto 是高德车机版，多数车机装的是这个 */
    private val NAV_PACKAGES = setOf(
        "com.autonavi.amapauto",     // 高德地图车机版
        "com.autonavi.minimap",      // 高德地图手机版
        "com.baidu.BaiduMap",
        "com.baidu.naviauto",
        "com.tencent.map",
        "com.tencent.wecarnavi",
        "com.sogou.map.android.maps"
    )

    private const val PREFS = "settings"
    private const val K_ENABLED = "navTrigger"
    private const val K_CUSTOM = "navCustom"     // 用户自选的触发应用
    private const val K_RECENT = "navRecent"     // 最近见过的前台应用，供用户挑选

    /** 不值得记的系统组件 */
    private val IGNORE_PREFIX = listOf(
        "com.android.systemui", "com.android.launcher", "com.google.android.apps.nexuslauncher",
        "com.android.settings", "android", "com.kelvin.parkqr"
    )

    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_ENABLED, on).apply()
    }

    /** 「有权查看使用情况」是否已授权 */
    fun hasPermission(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** 最近 [windowMs] 内最后一个切到前台的包名；拿不到返回 null */
    fun foregroundPackage(ctx: Context, windowMs: Long = 12_000L): String? = runCatching {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - windowMs, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                last = e.packageName
            }
        }
        last
    }.getOrNull()

    // ---- 触发应用集合：内置导航包 + 用户自选 ----

    fun custom(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(K_CUSTOM, emptySet()) ?: emptySet()

    fun setCustom(ctx: Context, pkgs: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(K_CUSTOM, pkgs).apply()
    }

    fun triggers(ctx: Context): Set<String> = NAV_PACKAGES + custom(ctx)

    fun isNav(ctx: Context, pkg: String?) = pkg != null && pkg in triggers(ctx)

    /**
     * 记下见过的前台应用，供用户从中挑选触发目标 ——
     * 各家车机的高德包名不一定是内置列表里那几个，让用户自己指认最可靠。
     */
    fun remember(ctx: Context, pkg: String) {
        if (IGNORE_PREFIX.any { pkg.startsWith(it) }) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = (sp.getStringSet(K_RECENT, emptySet()) ?: emptySet()).toMutableSet()
        if (!cur.add(pkg)) return
        // 只留最近 30 个，避免无限增长
        val trimmed = if (cur.size > 30) cur.toList().takeLast(30).toSet() else cur
        sp.edit().putStringSet(K_RECENT, trimmed).apply()
    }

    fun recent(ctx: Context): List<String> =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(K_RECENT, emptySet()) ?: emptySet()).sorted()

    /** 包名 -> 可读应用名，取不到就回退包名 */
    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
