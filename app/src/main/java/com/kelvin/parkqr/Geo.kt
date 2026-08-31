package com.kelvin.parkqr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 车机定位。两个现实约束决定了这里的做法：
 *
 *  1. 车机普遍没有 GMS，不能用 FusedLocationProvider，只能用 LocationManager。
 *  2. 地库里收不到星。真正有用的是"开进车库入口前的最后一个有效点"，所以只要 App 在前台
 *     就持续记录定位，并把最新的一个落盘；即便进了地库冷启动，也还能拿到进库前那个点。
 *
 * 另外 getLastKnownLocation 在没有任何进程请求过定位时会返回 null，
 * 所以不能只依赖它 —— [requestFix] 会主动拉一次。
 */
object Geo {

    private const val PREFS = "geo"
    private const val K_LAT = "lat"
    private const val K_LNG = "lng"
    private const val K_TIME = "time"
    private const val K_ACC = "acc"

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun lm(ctx: Context) =
        ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun providers(ctx: Context): List<String> {
        val m = lm(ctx) ?: return emptyList()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** 系统的 last-known 与我们自己落盘的那个，取时间较新的。 */
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        var best: Location? = cachedFix(ctx)
        val m = lm(ctx) ?: return best
        val all = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (p in all) {
            val loc = runCatching { m.getLastKnownLocation(p) }.getOrNull() ?: continue
            val cur = best
            if (cur == null || loc.time > cur.time) best = loc
        }
        return best
    }

    /**
     * 主动拉一次定位：先给出手上最好的一个，同时监听真实 fix，
     * [timeoutMs] 内拿到就用新的，拿不到就用旧的。回调一定会被调用一次。
     */
    fun requestFix(ctx: Context, timeoutMs: Long = 8000, onResult: (Location?) -> Unit) {
        if (!hasPermission(ctx)) {
            onResult(null)
            return
        }
        val m = lm(ctx)
        val ps = providers(ctx)
        if (m == null || ps.isEmpty()) {
            onResult(lastKnown(ctx))
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var done = false
        lateinit var listener: LocationListener

        fun finish(loc: Location?) {
            if (done) return
            done = true
            runCatching { m.removeUpdates(listener) }
            handler.removeCallbacksAndMessages(null)
            onResult(loc)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                cacheFix(ctx, location)
                finish(location)
            }
            @Deprecated("required by the old LocationListener interface")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        for (p in ps) {
            runCatching { m.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
        }
        handler.postDelayed({ finish(lastKnown(ctx)) }, timeoutMs)
    }

    /**
     * App 在前台时持续记录定位，把最新的落盘。返回一个用于停止的句柄。
     * 这就是"进地库前最后一个有效点"的来源。
     */
    /**
     * @param onFix 每次拿到新定位时回调（主线程）。界面必须靠它重绘，
     *              否则只缓存不刷新 —— 用户会看到距离永远不变。
     */
    fun startTracking(
        ctx: Context,
        minTimeMs: Long = 2000L,
        minDistanceM: Float = 5f,
        onFix: ((Location) -> Unit)? = null
    ): AutoCloseable {
        val m = lm(ctx)
        if (!hasPermission(ctx) || m == null) return AutoCloseable { }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                cacheFix(ctx, location)
                onFix?.invoke(location)
            }
            @Deprecated("required by the old LocationListener interface")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        for (p in providers(ctx)) {
            runCatching {
                m.requestLocationUpdates(p, minTimeMs, minDistanceM, listener, Looper.getMainLooper())
            }
        }
        return AutoCloseable { runCatching { m.removeUpdates(listener) } }
    }

    fun cacheFix(ctx: Context, loc: Location) {
        // 只保留更新的那个，别让一个陈旧的粗定位盖掉刚才的好点
        val old = cachedFix(ctx)
        if (old != null && old.time >= loc.time) return
        // 经纬度必须按 double 存：Float 只有 ~7 位有效数字，经度 113.93450 会被截到米级误差
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(K_LAT, java.lang.Double.doubleToRawLongBits(loc.latitude))
            .putLong(K_LNG, java.lang.Double.doubleToRawLongBits(loc.longitude))
            .putLong(K_TIME, loc.time)
            .putFloat(K_ACC, loc.accuracy)
            .apply()
    }

    fun cachedFix(ctx: Context): Location? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.contains(K_TIME)) return null
        return Location("cached").apply {
            latitude = java.lang.Double.longBitsToDouble(sp.getLong(K_LAT, 0L))
            longitude = java.lang.Double.longBitsToDouble(sp.getLong(K_LNG, 0L))
            time = sp.getLong(K_TIME, 0L)
            accuracy = sp.getFloat(K_ACC, 0f)
        }
    }

    /** 米。 */
    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun format(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else String.format("%.1f km", meters / 1000)
}
