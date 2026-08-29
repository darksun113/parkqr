package com.kelvin.parkqr

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ManageActivity : AppCompatActivity() {

    private lateinit var store: LotStore
    private lateinit var list: RecyclerView
    private lateinit var emptyHint: TextView
    private val adapter = Adapter()
    private var server: UploadServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)
        store = LotStore.get(this)

        list = findViewById(R.id.list)
        emptyHint = findViewById(R.id.emptyHint)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.btnNew).setOnClickListener { editLot(store.newLot(""), isNew = true) }
        findViewById<Button>(R.id.btnServer).setOnClickListener { showServer() }

        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val btnBoot = findViewById<Button>(R.id.btnBootOverlay)
        fun renderBoot() {
            btnBoot.text = if (sp.getBoolean("bootOverlay", true)) "开机悬浮：开" else "开机悬浮：关"
        }
        renderBoot()
        btnBoot.setOnClickListener {
            val now = !sp.getBoolean("bootOverlay", true)
            sp.edit().putBoolean("bootOverlay", now).apply()
            renderBoot()
            if (now && !android.provider.Settings.canDrawOverlays(this)) requestOverlayPermission()
        }
        findViewById<Button>(R.id.btnTestOverlay).setOnClickListener {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                OverlayService.start(this)
            }
        }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            UpdateChecker.check(this, manual = true)
        }
        reload()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun reload() {
        adapter.items = store.all().sortedBy { it.name }
        adapter.notifyDataSetChanged()
        emptyHint.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- 新建 / 编辑 ----------

    private fun editLot(lot: Lot, isNew: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_lot, null)
        val inName = view.findViewById<EditText>(R.id.inName)
        val inNote = view.findViewById<EditText>(R.id.inNote)
        val locText = view.findViewById<TextView>(R.id.locText)
        val btnLoc = view.findViewById<Button>(R.id.btnLoc)
        val codeText = view.findViewById<TextView>(R.id.codeText)
        val btnCode = view.findViewById<Button>(R.id.btnCode)

        inName.setText(lot.name)
        inNote.setText(lot.note)

        var lat = lot.lat
        var lng = lot.lng
        fun renderLoc() {
            locText.text = if (lat != null && lng != null) {
                "已记录坐标：%.6f, %.6f".format(lat, lng)
            } else {
                "还没有坐标。到了停车场（最好在进地库之前）点下面这个按钮。"
            }
        }
        renderLoc()

        btnLoc.setOnClickListener {
            btnLoc.isEnabled = false
            btnLoc.text = "定位中…"
            locText.text = "正在等 GPS，最多 10 秒。停在露天/入口处成功率最高。"
            Geo.requestFix(this, timeoutMs = 10_000) { loc ->
                btnLoc.isEnabled = true
                btnLoc.text = "记录当前位置"
                if (loc == null) {
                    renderLoc()
                    toast("拿不到定位：检查权限和系统定位开关，或车机 GPS 还没定上")
                } else {
                    lat = loc.latitude
                    lng = loc.longitude
                    renderLoc()
                    toast("已记录（精度约 ${loc.accuracy.toInt()} m）")
                }
            }
        }

        var payload = lot.payload
        var imageFile = lot.imageFile
        fun renderCode() {
            codeText.text = when {
                !payload.isNullOrBlank() -> "缴费码：标准二维码（存文本，显示时重绘）\n$payload"
                !imageFile.isNullOrBlank() -> "缴费码：原图（解不出内容，多半是小程序码）"
                else -> "缴费码：还没有。用「手机传码」上传，或手动输入。"
            }
        }
        renderCode()

        btnCode.setOnClickListener {
            val input = EditText(this).apply {
                hint = "微信扫物料码后复制的链接，粘/输到这里"
                setText(payload.orEmpty())
                minLines = 2
            }
            AlertDialog.Builder(this)
                .setTitle("手动输入码内容")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val v = input.text.toString().trim()
                    if (v.isNotBlank()) {
                        payload = v
                        imageFile?.let { store.deleteImage(it) }
                        imageFile = null
                        renderCode()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val b = AlertDialog.Builder(this)
            .setTitle(if (isNew) "新建停车场" else "编辑停车场")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = inName.text.toString().trim()
                if (name.isEmpty()) {
                    toast("名称不能为空")
                    return@setPositiveButton
                }
                lot.name = name
                lot.note = inNote.text.toString().trim()
                lot.lat = lat
                lot.lng = lng
                lot.payload = payload
                lot.imageFile = imageFile
                store.save(lot)
                reload()
            }
            .setNegativeButton("取消", null)

        if (!isNew) {
            b.setNeutralButton("删除") { _, _ -> confirmDelete(lot) }
        }
        b.show()
    }

    private fun confirmDelete(lot: Lot) {
        AlertDialog.Builder(this)
            .setTitle("删除「${lot.name}」？")
            .setPositiveButton("删除") { _, _ ->
                store.delete(lot.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 手机传码 ----------

    private fun showServer() {
        val ip = UploadServer.localAddress(this)
        if (ip == null) {
            toast("车机没连上网络。连一下 WiFi，或者开车机热点让手机连上来。")
            return
        }
        val url = "http://$ip:${UploadServer.PORT}/"

        if (server == null) {
            server = UploadServer(store) { runOnUiThread { reload() } }
            runCatching { server!!.start(NANO_TIMEOUT, true) }
                .onFailure {
                    server = null
                    toast("服务起不来：${it.message}")
                    return
                }
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        box.addView(TextView(this).apply {
            text = "① 手机连车机热点（或同一 WiFi）\n② 用微信「扫一扫」扫下面的入口码，打开上传页\n③ 粘贴物料码链接，或直接传照片"
            textSize = 15f
        })
        box.addView(ImageView(this).apply {
            setImageBitmap(QrUtil.encode(url, 600))
            // 入口码尽量大，方便在驾驶位扫
            val s = (resources.displayMetrics.heightPixels * 0.42f).toInt().coerceIn(360, 720)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = pad }
        })
        box.addView(TextView(this).apply {
            text = url
            textSize = 18f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, pad, 0, 0)
        })
        box.addView(TextView(this).apply {
            text = "注意：这个码只是打开上传页的入口，不是缴费码"
            textSize = 13f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, pad / 2, 0, 0)
        })

        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("手机传码")
            .setView(scroll)
            .setPositiveButton("完成") { _, _ -> stopServer() }
            .setOnDismissListener { stopServer() }
            .show()
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun requestOverlayPermission() {
        toast("需要「显示在其他应用上层」权限，请在设置里允许后回来")
        startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
        )
    }

    // ---------- 列表 ----------

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<Lot> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_lot, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val lot = items[position]
            h.name.text = lot.name
            h.meta.text = buildString {
                append(if (lot.hasCode) "✓ 已有码" else "✗ 无码")
                append("   ")
                append(if (lot.hasLocation) "✓ 有坐标" else "✗ 无坐标")
                if (lot.note.isNotBlank()) append("\n${lot.note}")
            }
            h.itemView.setOnClickListener { editLot(lot, isNew = false) }
        }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private companion object {
        const val NANO_TIMEOUT = 60_000
    }
}
