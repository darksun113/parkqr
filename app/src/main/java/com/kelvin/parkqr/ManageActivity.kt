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
    /** 地图选点回调：MapPicker 是独立 Activity，结果回来时对话框还开着，走这个回调把坐标塞回去 */
    private var pendingPick: ((Double, Double) -> Unit)? = null
    private val mapPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            pendingPick?.invoke(d.getDoubleExtra("lat", 0.0), d.getDoubleExtra("lng", 0.0))
        }
        pendingPick = null
    }
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

        val btnHome = findViewById<Button>(R.id.btnHome)
        fun renderHome() {
            btnHome.text = if (Home.get(this) != null) {
                "家：已设定（${Home.radius(this)} 米内开机不弹）"
            } else {
                "家：未设定"
            }
        }
        renderHome()
        btnHome.setOnClickListener {
            val pad = (20 * resources.displayMetrics.density).toInt()
            val h = Home.get(this)
            var stagedLat = h?.first
            var stagedLng = h?.second

            val status = TextView(this).apply { textSize = 14f }
            fun renderStatus() {
                status.text = if (stagedLat != null) {
                    "家的位置：%.6f, %.6f".format(stagedLat, stagedLng)
                } else {
                    "还没设定家的位置"
                }
            }
            renderStatus()

            val radiusIn = EditText(this).apply {
                hint = "不弹码半径（米），默认 ${Home.DEFAULT_RADIUS_M}"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(Home.radius(this@ManageActivity).toString())
            }
            fun stageBtn(label: String, onClick: () -> Unit) = Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(status)
                addView(radiusIn)
                addView(stageBtn("把当前位置设为家") {
                    Geo.requestFix(this@ManageActivity, timeoutMs = 10_000) { loc ->
                        if (loc == null) {
                            toast("拿不到定位，稍后再试")
                        } else {
                            stagedLat = loc.latitude; stagedLng = loc.longitude
                            renderStatus()
                        }
                    }
                })
                addView(stageBtn("手动输入坐标") {
                    promptManualCoord { la, ln ->
                        stagedLat = la; stagedLng = ln
                        renderStatus()
                    }
                })
                addView(stageBtn("地图选点") {
                    val r = radiusIn.text.toString().toIntOrNull() ?: Home.DEFAULT_RADIUS_M
                    pickOnMap(stagedLat, stagedLng, r.toDouble()) { la, ln ->
                        stagedLat = la; stagedLng = ln
                        renderStatus()
                    }
                })
            }

            val b = AlertDialog.Builder(this)
                .setTitle("家（此范围内开机不弹悬浮码）")
                .setView(android.widget.ScrollView(this).apply { addView(box) })
                .setPositiveButton("保存") { _, _ ->
                    Home.setRadius(this, radiusIn.text.toString().toIntOrNull() ?: Home.DEFAULT_RADIUS_M)
                    val la = stagedLat
                    val ln = stagedLng
                    if (la != null && ln != null) Home.set(this, la, ln)
                    renderHome()
                    toast("已保存")
                }
                .setNegativeButton("取消", null)
            if (h != null) {
                b.setNeutralButton("清除家") { _, _ ->
                    Home.clear(this)
                    renderHome()
                    toast("已清除")
                }
            }
            b.show()
        }

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            val pad = (20 * resources.displayMetrics.density).toInt()
            val input = EditText(this).apply {
                hint = "导入范围（公里），默认 5"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("5")
            }
            AlertDialog.Builder(this)
                .setTitle("导入预设停车场（按距离筛选）")
                .setView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                    addView(input)
                })
                .setPositiveButton("下载") { _, _ ->
                    val km = input.text.toString().toDoubleOrNull() ?: 5.0
                    PresetImporter.run(this, store, km.coerceIn(1.0, 100.0)) { reload() }
                }
                .setNegativeButton("取消", null)
                .show()
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

        view.findViewById<Button>(R.id.btnCoordManual).setOnClickListener {
            promptManualCoord { la, ln ->
                lat = la; lng = ln
                renderLoc()
                toast("已填入（已转 WGS-84）")
            }
        }
        view.findViewById<Button>(R.id.btnCoordMap).setOnClickListener {
            pickOnMap(lat, lng, 0.0) { la, ln ->
                lat = la; lng = ln
                renderLoc()
            }
        }

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
            server = UploadServer(this, store) { runOnUiThread { reload() } }
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

    /** 手动输入坐标，支持三种来源坐标系，统一转成 WGS-84 回调。 */
    private fun promptManualCoord(onResult: (Double, Double) -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "纬度,经度  例：22.5361,113.9345"
        }
        val group = android.widget.RadioGroup(this)
        val labels = listOf(
            "GPS / 本App / OSM（WGS-84）",
            "高德 / 腾讯地图复制的（GCJ-02）",
            "百度地图复制的（BD-09）"
        )
        labels.forEachIndexed { i, s ->
            group.addView(android.widget.RadioButton(this).apply { text = s; id = i })
        }
        group.check(1)   // 大多数人从高德抄，默认 GCJ-02
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(group)
        }
        AlertDialog.Builder(this)
            .setTitle("手动输入坐标")
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val parts = input.text.toString()
                    .replace("，", ",").replace(" ", "").split(",")
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lng = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                    toast("格式不对：要「纬度,经度」两个数字")
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

    private fun pickOnMap(lat: Double?, lng: Double?, radius: Double, onResult: (Double, Double) -> Unit) {
        pendingPick = onResult
        val i = android.content.Intent(this, MapPickerActivity::class.java)
        if (lat != null && lng != null) {
            i.putExtra("lat", lat).putExtra("lng", lng)
        }
        i.putExtra("radius", radius)
        mapPicker.launch(i)
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
