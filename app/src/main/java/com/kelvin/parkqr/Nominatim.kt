package com.kelvin.parkqr

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** OSM 官方地理编码（免费，按政策带 UA、手动触发）。阻塞调用，放线程里跑。 */
object Nominatim {

    data class Place(val lat: Double, val lng: Double, val name: String)

    fun search(q: String): Place? = runCatching {
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1" +
            "&countrycodes=cn&q=" + URLEncoder.encode(q, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "ParkQR/1.5 (github.com/darksun113/parkqr)")
        val a = JSONArray(c.inputStream.bufferedReader().readText())
        if (a.length() == 0) null
        else a.getJSONObject(0).let {
            Place(it.getDouble("lat"), it.getDouble("lon"), it.optString("display_name"))
        }
    }.getOrNull()
}
