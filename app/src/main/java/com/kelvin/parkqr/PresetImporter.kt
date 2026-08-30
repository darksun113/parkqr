package com.kelvin.parkqr

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 从仓库拉城市预设停车场（名称+坐标，无缴费码），按"离当前位置 N 公里内"筛选后合并。
 * 数据格式见仓库 presets/README 部分；coord 字段声明坐标系，导入时统一转成 WGS-84。
 */
object PresetImporter {

    // raw 在国内偶尔不通，jsDelivr 兜底
    private val URLS = listOf(
        "https://raw.githubusercontent.com/darksun113/parkqr/main/presets/shenzhen.json",
        "https://cdn.jsdelivr.net/gh/darksun113/parkqr@main/presets/shenzhen.json"
    )
    private const val DEDUPE_M = 60.0

    fun run(activity: Activity, store: LotStore, radiusKm: Double, onDone: () -> Unit) {
        val loc = Geo.lastKnown(activity)
        if (loc == null) {
            toast(activity, "拿不到当前定位，无法按距离筛选。先让 GPS 定上位。")
            return
        }
        thread {
            val body = URLS.firstNotNullOfOrNull { u ->
                runCatching {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 15000
                    c.inputStream.bufferedReader().readText()
                }.getOrNull()
            }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (body == null) {
                    toast(activity, "下载预设失败（GitHub/jsDelivr 都没连上）")
                    return@runOnUiThread
                }
                val parsed = runCatching { parse(body) }.getOrNull()
                if (parsed == null) {
                    toast(activity, "预设文件格式不对")
                    return@runOnUiThread
                }
                val (city, all) = parsed
                val near = all.filter {
                    Geo.distance(loc.latitude, loc.longitude, it.lat, it.lng) <= radiusKm * 1000
                }
                if (near.isEmpty()) {
                    toast(activity, "$city 预设共 ${all.size} 个，但 ${radiusKm.toInt()} km 内一个都没有")
                    return@runOnUiThread
                }
                AlertDialog.Builder(activity)
                    .setTitle("导入预设（$city）")
                    .setMessage(
                        "共 ${all.size} 个，${radiusKm.toInt()} km 内有 ${near.size} 个。\n" +
                            "只带名称和坐标，缴费码到场后再补。确定导入？"
                    )
                    .setPositiveButton("导入") { _, _ ->
                        var added = 0
                        val existing = store.all()
                        for (p in near) {
                            val dup = existing.any {
                                it.hasLocation &&
                                    Geo.distance(it.lat!!, it.lng!!, p.lat, p.lng) < DEDUPE_M &&
                                    it.name == p.name
                            }
                            if (dup) continue
                            store.save(store.newLot(p.name).apply {
                                lat = p.lat
                                lng = p.lng
                                note = p.note
                            })
                            added++
                        }
                        toast(activity, "导入 $added 个（跳过重复 ${near.size - added} 个）")
                        onDone()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private data class P(val name: String, val lat: Double, val lng: Double, val note: String)

    private fun parse(body: String): Pair<String, List<P>> {
        val o = JSONObject(body)
        val coord = o.optString("coord", "wgs84").lowercase()
        val arr = o.getJSONArray("lots")
        val list = mutableListOf<P>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            var lat = e.getDouble("lat")
            var lng = e.getDouble("lng")
            when (coord) {
                "gcj02" -> CoordConv.gcjToWgs(lat, lng).let { lat = it.first; lng = it.second }
                "bd09" -> CoordConv.bdToWgs(lat, lng).let { lat = it.first; lng = it.second }
            }
            list.add(P(e.getString("name"), lat, lng, e.optString("note")))
        }
        return o.optString("city", "未知城市") to list
    }

    private fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_LONG).show()
}
