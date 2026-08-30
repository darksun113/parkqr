package com.kelvin.parkqr

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 停车场地图总览：所有场以 pin 显示（蓝=有码，橙=缺码），点 pin 后底栏出现「编辑」。
 * 顶部地名搜索走 Nominatim（OSM 官方地理编码，免费）：输"南山区科技园"这类
 * 省市区街道，地图直接跳过去 —— 数据里不存地址，把地名变坐标反而更省。
 * 结果：RESULT_OK + lotId（宿主打开编辑框）。
 */
class LotMapActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var selBar: LinearLayout
    private lateinit var selName: TextView
    private var selId: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LotStore.get(this)
        val lots = store.all().filter { it.hasLocation }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onLotTap(id: String, name: String) {
                    runOnUiThread {
                        selId = id
                        selName.text = name
                        selBar.visibility = android.view.View.VISIBLE
                    }
                }
            }, "Bridge")
        }

        val search = EditText(this).apply {
            hint = "搜地名：市/区/街道/商场"
            maxLines = 1
        }
        val btnSearch = Button(this).apply {
            text = "搜地点"
            setOnClickListener { geocode(search.text.toString().trim()) }
        }
        val btnClose = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF101114.toInt())
            addView(search, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnSearch)
            addView(btnClose)
        }

        selName = TextView(this).apply {
            setTextColor(0xFFECEDEE.toInt())
            textSize = 16f
            setPadding(24, 0, 0, 0)
        }
        val btnEdit = Button(this).apply {
            text = "编辑这个停车场"
            setOnClickListener {
                selId?.let {
                    setResult(RESULT_OK, Intent().putExtra("lotId", it))
                    finish()
                }
            }
        }
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1D22.toInt())
            visibility = android.view.View.GONE
            addView(selName, LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnEdit)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(selBar, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)

        // 中心：最后定位，退而求其次第一个场
        val loc = Geo.lastKnown(this)
        val cLat = loc?.latitude ?: lots.firstOrNull()?.lat ?: 22.543
        val cLng = loc?.longitude ?: lots.firstOrNull()?.lng ?: 114.058
        val arr = JSONArray()
        lots.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("lat", it.lat!!).put("lng", it.lng!!).put("hasCode", it.hasCode)
            )
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                web.evaluateJavascript(
                    "window.__lots=$arr;window.__center={lat:$cLat,lng:$cLng};", null
                )
            }
        }
        web.loadUrl("file:///android_asset/lots_map.html")

        if (lots.isEmpty()) toast("还没有带坐标的停车场")
    }

    private fun geocode(q: String) {
        if (q.isBlank()) return
        thread {
            val r = Nominatim.search(q)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (r == null) {
                    toast("没搜到「$q」（要联网；试试更完整的地名）")
                } else {
                    web.evaluateJavascript("jumpTo(${r.lat},${r.lng},15);", null)
                    toast(r.name.take(60))
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
