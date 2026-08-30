package com.kelvin.parkqr

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OSM 官方地理编码（免费，按政策带 UA、手动触发）。阻塞调用，放线程里跑。
 *
 * 必须带地域偏好：不加 viewbox 时搜"和睦家"会返回桂林的同名地点，
 * 而带上当前位置周边的 viewbox 就能正确命中深圳的那家。
 */
object Nominatim {

    data class Place(val lat: Double, val lng: Double, val name: String, val distanceM: Double?)

    private const val UA = "ParkQR/1.6 (github.com/darksun113/parkqr)"
    /** 视野框半边长（度）。0.5° ≈ 55 km，覆盖一个城市及近郊 */
    private const val BOX_DEG = 0.5

    /**
     * @param near 当前位置，用于地域偏好与距离排序；为 null 时退化为全国搜索
     */
    fun search(q: String, near: Pair<Double, Double>?, limit: Int = 8): List<Place> {
        if (q.isBlank()) return emptyList()

        // 1) 先在当前位置周边找（bounded=1 硬性限制在框内）
        if (near != null) {
            val (lat, lng) = near
            val box = "${lng - BOX_DEG},${lat + BOX_DEG},${lng + BOX_DEG},${lat - BOX_DEG}"
            val local = query(q, limit, "&bounded=1&viewbox=$box", near)
            if (local.isNotEmpty()) return local
        }
        // 2) 框内没有再放开到全国，但按离当前位置的距离排序，最近的在前
        return query(q, limit, "", near).sortedBy { it.distanceM ?: Double.MAX_VALUE }
    }

    private fun query(
        q: String, limit: Int, extra: String, near: Pair<Double, Double>?
    ): List<Place> = runCatching {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=$limit" +
            "&countrycodes=cn$extra&q=" + URLEncoder.encode(q, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", UA)
        val arr = JSONArray(c.inputStream.bufferedReader().readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val lat = o.getDouble("lat")
            val lng = o.getDouble("lon")
            Place(
                lat, lng, o.optString("display_name"),
                near?.let { Geo.distance(it.first, it.second, lat, lng) }
            )
        }
    }.getOrElse { emptyList() }

    /** 结果在选择列表里的展示文本：地名 + 距离，方便一眼分辨同名地点。 */
    fun label(p: Place): String {
        val short = p.name.split(",").take(3).joinToString(",").trim()
        val dist = p.distanceM?.let { "  ·  ${Geo.format(it)}" } ?: ""
        return "$short$dist"
    }
}
