package com.kelvin.parkqr

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 免费地图选点：WebView + Leaflet（打进 assets）+ OpenStreetMap 瓦片。
 * OSM 是 WGS-84，和 GPS 一致，点哪是哪，不需要坐标换算。瓦片加载需要网络。
 *
 * 输入 extras：lat/lng（初始中心，可选）、radius（>0 时画半径圈，家用）。
 * 结果：RESULT_OK + lat/lng。
 */
class MapPickerActivity : AppCompatActivity() {

    @Volatile private var pickedLat: Double? = null
    @Volatile private var pickedLng: Double? = null
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initLat = intent.getDoubleExtra("lat", Double.NaN)
        val initLng = intent.getDoubleExtra("lng", Double.NaN)
        val radius = intent.getDoubleExtra("radius", 0.0)
        val hasInit = !initLat.isNaN() && !initLng.isNaN()
        // 没有初始点就用最后定位，再不行落到深圳市中心
        val loc = if (!hasInit) Geo.lastKnown(this) else null
        val cLat = if (hasInit) initLat else loc?.latitude ?: 22.543
        val cLng = if (hasInit) initLng else loc?.longitude ?: 114.058

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPick(lat: Double, lng: Double) {
                    pickedLat = lat
                    pickedLng = lng
                    runOnUiThread {
                        status.text = "已选：%.6f, %.6f".format(lat, lng)
                    }
                }
            }, "Bridge")
        }

        status = TextView(this).apply {
            text = "还没选点"
            setTextColor(0xFFECEDEE.toInt())
            textSize = 15f
            setPadding(24, 18, 24, 18)
        }
        val ok = Button(this).apply {
            text = "确定"
            setOnClickListener {
                val la = pickedLat
                val ln = pickedLng
                if (la == null || ln == null) {
                    android.widget.Toast.makeText(
                        this@MapPickerActivity, "先在地图上点一下", android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    setResult(RESULT_OK, Intent().putExtra("lat", la).putExtra("lng", ln))
                    finish()
                }
            }
        }
        val cancel = Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF101114.toInt())
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(cancel)
            addView(ok)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)

        val marked = if (hasInit) "true" else "false"
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // 在页面脚本执行前注入初始参数
                web.evaluateJavascript(
                    "window.__init={lat:$cLat,lng:$cLng,radius:$radius,marked:$marked};", null
                )
            }
        }
        web.loadUrl("file:///android_asset/map.html")
    }
}
