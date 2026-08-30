package com.kelvin.parkqr

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 中国地图坐标系换算。GPS/OSM/本 App 内部统一用 WGS-84；
 * 高德/腾讯 App 里复制出来的是 GCJ-02（差 300~600 米），百度是 BD-09。
 * 手动输入坐标时按来源换算，否则会偏一条街。
 */
object CoordConv {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    private fun outOfChina(lat: Double, lng: Double) =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun tLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun tLng(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }

    fun wgsToGcj(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var dLat = tLat(lng - 105.0, lat - 35.0)
        var dLng = tLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / (A * (1 - EE) / (magic * sqrtMagic) * PI)
        dLng = dLng * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return lat + dLat to lng + dLng
    }

    /** 迭代反解，误差 < 1e-6 度（~0.1 m）。 */
    fun gcjToWgs(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        var wLat = lat
        var wLng = lng
        repeat(6) {
            val (gLat, gLng) = wgsToGcj(wLat, wLng)
            wLat += lat - gLat
            wLng += lng - gLng
        }
        return wLat to wLng
    }

    fun bdToGcj(lat: Double, lng: Double): Pair<Double, Double> {
        val xPi = PI * 3000.0 / 180.0
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * xPi)
        val theta = kotlin.math.atan2(y, x) - 0.000003 * cos(x * xPi)
        return z * sin(theta) to z * cos(theta)
    }

    fun bdToWgs(lat: Double, lng: Double): Pair<Double, Double> {
        val (gLat, gLng) = bdToGcj(lat, lng)
        return gcjToWgs(gLat, gLng)
    }
}
