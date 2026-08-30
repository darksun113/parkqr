package com.kelvin.parkqr

import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 提供"手动输入坐标 / 地图选点"能力的基类：主界面和管理页都要用
 * （编辑对话框可以从任一界面打开）。
 */
abstract class CoordActivity : AppCompatActivity() {

    private var pendingPick: ((Double, Double) -> Unit)? = null

    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            pendingPick?.invoke(d.getDoubleExtra("lat", 0.0), d.getDoubleExtra("lng", 0.0))
        }
        pendingPick = null
    }

    fun pickOnMap(lat: Double?, lng: Double?, radius: Double, onResult: (Double, Double) -> Unit) {
        pendingPick = onResult
        val i = Intent(this, MapPickerActivity::class.java)
        if (lat != null && lng != null) i.putExtra("lat", lat).putExtra("lng", lng)
        i.putExtra("radius", radius)
        mapPicker.launch(i)
    }

    /** 手动输入坐标，支持三种来源坐标系，统一转成 WGS-84 回调。 */
    fun promptManualCoord(onResult: (Double, Double) -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "纬度,经度；或直接粘高德/百度分享链接"
        }
        val group = RadioGroup(this)
        listOf(
            "GPS / 本App / OSM（WGS-84）",
            "高德 / 腾讯地图复制的（GCJ-02）",
            "百度地图复制的（BD-09）"
        ).forEachIndexed { i, s ->
            group.addView(RadioButton(this).apply { text = s; id = i })
        }
        group.check(1)   // 大多数人从高德抄，默认 GCJ-02
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(group)
        }
        AlertDialog.Builder(this)
            .setTitle("手动输入坐标")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val raw = input.text.toString().trim()
                // 粘的是链接：手机地图不给裸坐标，只给分享短链——解析它
                if (raw.contains("http")) {
                    Toast.makeText(this, "正在解析链接…", Toast.LENGTH_SHORT).show()
                    kotlin.concurrent.thread {
                        val r = WebResolver.resolveSmart(this, raw)
                        runOnUiThread {
                            if (r == null) {
                                Toast.makeText(this, "解析不出坐标：检查网络，或换「地图选点」", Toast.LENGTH_LONG).show()
                            } else {
                                onResult(r.first, r.second)
                            }
                        }
                    }
                    return@setPositiveButton
                }
                val parts = raw
                    .replace("，", ",").replace(" ", "").split(",")
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lng = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                    Toast.makeText(this, "格式不对：要「纬度,经度」两个数字", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val (wLat, wLng) = when (group.checkedRadioButtonId) {
                    1 -> CoordConv.gcjToWgs(lat, lng)
                    2 -> CoordConv.bdToWgs(lat, lng)
                    else -> lat to lng
                }
                onResult(wLat, wLng)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
