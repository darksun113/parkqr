package com.kelvin.parkqr

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * 从高德/百度/腾讯 App 的"分享链接"里解出坐标（手机地图不给裸坐标，只给短链）。
 *
 * 做法：跟随重定向收集整条 URL 链 + 落地页 HTML，正则抽坐标；
 * 按域名定坐标系（amap/qq → GCJ-02，baidu → BD-09，百度还常用墨卡托米制），
 * 统一转 WGS-84 返回。
 */
object LinkResolver {

    /** 阻塞调用，放线程里。返回 WGS-84 (lat, lng)，解不出返回 null。 */
    fun resolve(text: String): Pair<Double, Double>? {
        val url = Regex("""https?://[^\s"'，。<>]+""").find(text)?.value ?: return null
        val chain = mutableListOf(url)
        var body: String? = null
        var cur = url
        var hops = 0
        while (hops++ < 6) {
            val conn = runCatching {
                (URL(cur).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; Mobile) ParkQR"
                    )
                }
            }.getOrNull() ?: break
            val code = runCatching { conn.responseCode }.getOrNull() ?: break
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: break
                cur = runCatching { URL(URL(cur), loc).toString() }.getOrNull() ?: break
                chain.add(cur)
            } else {
                body = runCatching {
                    conn.inputStream.bufferedReader().use { r ->
                        // read() 单次可能只返回一段，循环读满上限
                        val sb = StringBuilder()
                        val buf = CharArray(16_384)
                        while (sb.length < 300_000) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            sb.append(buf, 0, n)
                        }
                        sb.toString()
                    }
                }.getOrNull()
                break
            }
        }
        val isBaidu = chain.any { it.contains("baidu.com") }
        for (u in chain.reversed()) extract(u, isBaidu)?.let { return it }
        body?.let { b -> extract(b, isBaidu)?.let { return it } }
        return null
    }

    /** 从一段文本中抽出第一对可信坐标并转 WGS-84。isBaidu 决定坐标系。 */
    fun extract(raw: String, isBaidu: Boolean): Pair<Double, Double>? {
        // 高德的重定向 URL 把逗号编码成 %2C（如 p=POIID%2C22.55%2C113.90%2C名称），先解回来
        val s = raw.replace("%2C", ",", ignoreCase = true).replace("%2c", ",")
        // 百度页面壳里带一个全国默认中心（≈104,37 经纬度），会污染通用规则；
        // 而真正的 POI 坐标是墨卡托米制 —— 百度域名必须先走墨卡托模式。
        if (isBaidu) {
            // 渲染后的页面里坐标常拆成 "x":12680841.16,"y":2563119.75 —— 去掉字段胶水再配对
            val norm = s.replace(Regex("""\\?"[xy]\\?"\s*:\s*"""), "")
            Regex("""(1[0-9]{7}(?:\.[0-9]+)?),([2-9][0-9]{5,6}(?:\.[0-9]+)?)""")
                .find(norm)?.let { m ->
                    val (llng, llat) = mcToLl(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
                        ?: return@let
                    val r = CoordConv.bdToWgs(llat, llng)
                    if (!isDefaultCenter(r.first, r.second)) return r
                }
        }
        // 高德/腾讯惯用 position=lng,lat 或 coord=lat,lng 之类的显式参数
        Regex("""(?:position|location|coords?|latlng|center|marker)=([0-9]{1,3}\.[0-9]{3,}(?:%2C|,)[0-9]{1,3}\.[0-9]{3,})""", RegexOption.IGNORE_CASE)
            .findAll(s).forEach { m ->
                val parts = m.groupValues[1].replace("%2C", ",").split(",")
                plausiblePair(parts[0].toDouble(), parts[1].toDouble())?.let {
                    val r = convert(it, isBaidu)
                    if (!isDefaultCenter(r.first, r.second)) return r
                }
            }
        // 2.5) 键值对形式：渲染后的页面常见 \"loc\":{\"lng\":113.91,\"lat\":22.56}
        //      （转义引号是胶水），剥掉引号和反斜杠后按 lng/lat 键名配对
        run {
            val clean = s.replace("\\", "").replace("\"", "")
            val pats = listOf(
                Regex("""lng:([0-9]{1,3}\.[0-9]{3,}),\s*lat:([0-9]{1,3}\.[0-9]{3,})""") to false,
                Regex("""lat:([0-9]{1,3}\.[0-9]{3,}),\s*lng:([0-9]{1,3}\.[0-9]{3,})""") to true
            )
            for ((re, latFirst) in pats) {
                re.findAll(clean).forEach { m ->
                    val a = m.groupValues[1].toDouble()
                    val b = m.groupValues[2].toDouble()
                    val pair = if (latFirst) plausiblePair(b, a)?.let { a to b }
                    else plausiblePair(a, b)
                    pair?.let {
                        val r = convert(it, isBaidu)
                        if (!isDefaultCenter(r.first, r.second)) return r
                    }
                }
            }
        }

        // 3) 兜底：任何"看起来像中国经纬度"的相邻数对
        Regex("""([0-9]{1,3}\.[0-9]{4,})\s*[,，]\s*([0-9]{1,3}\.[0-9]{4,})""")
            .findAll(s).forEach { m ->
                plausiblePair(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())?.let {
                    val r = convert(it, isBaidu)
                    if (!isDefaultCenter(r.first, r.second)) return r
                }
            }
        return null
    }

    /** 判断 (a,b) 是 (lng,lat) 还是 (lat,lng)，返回 (lat, lng)；都不像返回 null。 */
    private fun plausiblePair(a: Double, b: Double): Pair<Double, Double>? = when {
        a in 73.0..136.0 && b in 3.0..54.0 -> b to a    // lng,lat（高德惯例）
        a in 3.0..54.0 && b in 73.0..136.0 -> a to b    // lat,lng
        else -> null
    }

    private fun convert(latLng: Pair<Double, Double>, isBaidu: Boolean): Pair<Double, Double> =
        if (isBaidu) CoordConv.bdToWgs(latLng.first, latLng.second)
        else CoordConv.gcjToWgs(latLng.first, latLng.second)

    /**
     * 百度页面壳在拿不到 POI 数据时会填一个"全国默认中心"（≈37.55N,104.11E，
     * 腾格里沙漠里）——那里没有停车场，出现即视为无效。
     */
    fun isDefaultCenter(lat: Double, lng: Double): Boolean =
        abs(lat - 37.55) < 1.0 && abs(lng - 104.11) < 1.0

    // ---- 百度墨卡托(BD09MC) -> 经纬度(BD09LL)，标准分段多项式 ----

    private val MC_BAND = doubleArrayOf(12890594.86, 8362377.87, 5591021.0, 3481989.83, 1678043.12, 0.0)
    private val MC2LL = arrayOf(
        doubleArrayOf(1.410526172116255e-8, 8.98305509648872e-6, -1.9939833816331, 200.9824383106796, -187.2403703815547, 91.6087516669843, -23.38765649603339, 2.57121317296198, -0.03801003308653, 17337981.2),
        doubleArrayOf(-7.435856389565537e-9, 8.983055097726239e-6, -0.78625201886289, 96.32687599759846, -1.85204757529826, -59.36935905485877, 47.40033549296737, -16.50741931063887, 2.28786674699375, 10260144.86),
        doubleArrayOf(-3.030883460898826e-8, 8.98305509983578e-6, 0.30071316287616, 59.74293618442277, 7.357984074871, -25.38371002664745, 13.45380521110908, -3.29883767235584, 0.32710905363475, 6856817.37),
        doubleArrayOf(-1.981981304930552e-8, 8.983055099779535e-6, 0.03278182852591, 40.31678527705744, 0.65659298677277, -4.44255534477492, 0.85341911805263, 0.12923347998204, -0.04625736007561, 4482777.06),
        doubleArrayOf(3.09191371068437e-9, 8.983055096812155e-6, 6.995724062e-5, 23.10934304144901, -2.3663490511e-4, -0.6321817810242, -0.00663494467273, 0.03430082397953, -0.00466043876332, 2555164.4),
        doubleArrayOf(2.890871144776878e-9, 8.983055095805407e-6, -3.068298e-8, 7.47137025468032, -3.53937994e-6, -0.02145144861037, -1.234426596e-5, 1.0322952773e-4, -3.23890364e-6, 826088.5)
    )

    fun mcToLl(x: Double, y: Double): Pair<Double, Double>? {
        val ay = abs(y)
        val f = MC_BAND.indices.firstOrNull { ay >= MC_BAND[it] }?.let { MC2LL[it] } ?: return null
        val ax = abs(x)
        var lng = f[0] + f[1] * ax
        val cc = ay / f[9]
        var lat = 0.0
        for (i in 2..8) lat += f[i] * Math.pow(cc, (i - 2).toDouble())
        lng *= if (x < 0) -1 else 1
        lat *= if (y < 0) -1 else 1
        return if (lng in 73.0..136.0 && lat in 3.0..54.0) lng to lat else null
    }
}
