package com.kelvin.parkqr

import android.content.Context

/**
 * "家"：在家附近（RADIUS_M 内）开机不弹悬浮码——自家车位不需要缴费码。
 *
 * 判断用的是最后有效定位：车在家启动时，它就是上次开回家的落点，所以很可靠。
 * 拿不到定位或没设家一律返回 false（宁可多弹，不能在真停车场漏弹）。
 */
object Home {

    private const val PREFS = "settings"
    private const val K_LAT = "homeLat"
    private const val K_LNG = "homeLng"
    const val RADIUS_M = 300.0

    fun get(ctx: Context): Pair<Double, Double>? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.contains(K_LAT)) return null
        return java.lang.Double.longBitsToDouble(sp.getLong(K_LAT, 0L)) to
            java.lang.Double.longBitsToDouble(sp.getLong(K_LNG, 0L))
    }

    fun set(ctx: Context, lat: Double, lng: Double) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(K_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(K_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_LAT).remove(K_LNG).apply()
    }

    fun isNear(ctx: Context): Boolean {
        val h = get(ctx) ?: return false
        val loc = Geo.lastKnown(ctx) ?: return false
        return Geo.distance(loc.latitude, loc.longitude, h.first, h.second) <= RADIUS_M
    }
}
