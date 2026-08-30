package com.kelvin.parkqr

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 「手机传码」对话框。抽成共享组件，好让停车场编辑框里也能一键跳过来，
 * 并且带上 preselect —— 手机扫码打开时直接选中待编辑的那个场，
 * 不用在手机浏览器里再搜一遍。
 */
object TransferDialog {

    private var server: UploadServer? = null

    fun show(act: Activity, store: LotStore, preselect: String?, onChanged: () -> Unit) {
        val ip = UploadServer.localAddress(act)
        if (ip == null) {
            toast(act, "车机没连上网络。连一下 WiFi，或者开车机热点让手机连上来。")
            return
        }
        // 带时间戳：微信对同 URL 整页缓存极其激进，每次生成"新" URL 绕开它
        val url = "http://$ip:${UploadServer.PORT}/?s=${System.currentTimeMillis() / 1000}"

        if (server == null) {
            server = UploadServer(act.applicationContext, store) {
                act.runOnUiThread { onChanged() }
            }
            runCatching { server!!.start(60_000, true) }
                .onFailure {
                    server = null
                    toast(act, "服务起不来：${it.message}")
                    return
                }
        }
        server?.preselectId = preselect

        val pad = (16 * act.resources.displayMetrics.density).toInt()
        val preName = preselect?.let { store.byId(it)?.name }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(TextView(act).apply {
            text = buildString {
                if (preName != null) append("将为「$preName」传码\n")
                append("① 手机连车机热点（或同一 WiFi）\n")
                append("② 用微信「扫一扫」扫下面的入口码，打开上传页\n")
                append("③ 粘贴物料码链接，或直接传照片")
            }
            textSize = 15f
        })
        box.addView(ImageView(act).apply {
            setImageBitmap(QrUtil.encode(url, 600))
            val s = (act.resources.displayMetrics.heightPixels * 0.42f).toInt().coerceIn(360, 720)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = pad }
        })
        box.addView(TextView(act).apply {
            text = url
            textSize = 18f
            setTypeface(Typeface.MONOSPACE)
            setPadding(0, pad, 0, 0)
        })
        box.addView(TextView(act).apply {
            text = "注意：这个码只是打开上传页的入口，不是缴费码"
            textSize = 13f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, pad / 2, 0, 0)
        })

        AlertDialog.Builder(act)
            .setTitle("手机传码")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("完成") { _, _ -> stop() }
            .setOnDismissListener { stop() }
            .show()
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun toast(act: Activity, s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
}
