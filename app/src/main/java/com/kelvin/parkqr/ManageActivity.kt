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

class ManageActivity : CoordActivity() {

    private lateinit var store: LotStore
    private var query: String = ""
    private val lotMap = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val id = r.data?.getStringExtra("lotId")
        if (r.resultCode == RESULT_OK && id != null) {
            store.byId(id)?.let { editLot(it, isNew = false) }
        }
    }
    private lateinit var list: RecyclerView
    private lateinit var emptyHint: TextView
    private val adapter = Adapter()

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
        findViewById<Button>(R.id.btnBootDiag).setOnClickListener { showBootDiagnostics() }

        val btnNav = findViewById<Button>(R.id.btnNavTrigger)
        fun renderNav() {
            btnNav.text = when {
                !NavWatcher.isEnabled(this) -> "导航联动：关"
                !NavWatcher.hasPermission(this) -> "导航联动：缺权限"
                else -> "导航联动：开"
            }
        }
        renderNav()
        btnNav.setOnClickListener {
            if (!NavWatcher.hasPermission(this)) {
                AlertDialog.Builder(this)
                    .setTitle("需要「有权查看使用情况」权限")
                    .setMessage(
                        "开启后，一打开高德等导航 App 就会自动浮出停车码——" +
                        "比等开机自启更贴合实际场景（停好车回来、开导航准备走时正好要缴费）。\n\n" +
                        "这个权限只用来读「当前前台是哪个 App」，不读任何使用内容。"
                    )
                    .setPositiveButton("去授权") { _, _ ->
                        runCatching {
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }.onFailure { toast("这台车机没有这个设置页") }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }
            showNavDialog(::renderNav)
        }

        val btnBg = findViewById<Button>(R.id.btnBgLocation)
        fun renderBg() {
            btnBg.text = if (LocationService.isEnabled(this)) "后台定位：开" else "后台定位：关"
        }
        renderBg()
        btnBg.setOnClickListener {
            LocationService.setEnabled(this, !LocationService.isEnabled(this))
            renderBg()
            toast(
                if (LocationService.isEnabled(this))
                    "已开启：每分钟记录一次位置，进地库后仍能匹配到正确的场"
                else "已关闭：进地库后可能匹配不到停车场"
            )
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

        findViewById<EditText>(R.id.inSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim().orEmpty()
                reload()
            }
        })
        findViewById<Button>(R.id.btnMap).setOnClickListener {
            lotMap.launch(android.content.Intent(this, LotMapActivity::class.java))
        }

        if (intent.getBooleanExtra("focusSearch", false)) {
            findViewById<EditText>(R.id.inSearch).requestFocus()
        }

        reload()
    }

    override fun onDestroy() {
        TransferDialog.stop()
        super.onDestroy()
    }

    private fun reload() {
        val all = store.all()
        adapter.items = (if (query.isBlank()) all else all.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.note.contains(query, ignoreCase = true)
        }).sortedBy { it.name }
        adapter.notifyDataSetChanged()
        emptyHint.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        emptyHint.text = if (query.isBlank()) "还没有停车场" else "没有匹配「$query」的停车场"
    }

    // ---------- 新建 / 编辑 ----------

    private fun editLot(lot: Lot, isNew: Boolean) {
        LotEditDialog.show(this, store, lot, isNew, onChanged = { reload() }) { saved ->
            // 新建保存后顺手把码传了——省得再去列表里找
            if (!saved.hasCode) {
                AlertDialog.Builder(this)
                    .setTitle("「${saved.name}」已创建")
                    .setMessage("现在就传缴费码？上传页会自动选中它。")
                    .setPositiveButton("手机传码") { _, _ -> showServer(saved.id) }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }

    // ---------- 手机传码 ----------

    private fun showServer(preselect: String? = null) {
        TransferDialog.show(this, store, preselect) { reload() }
    }

    /** 开机没弹悬浮窗时，把每个环节的状态摊开给用户看——ROM 自启白名单我改不了，但能说清楚。 */
    private fun showBootDiagnostics() {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val last = sp.getLong("lastBootBroadcast", 0L)
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val pm = getSystemService(android.os.PowerManager::class.java)
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        val codedLots = store.all().count { it.hasCode }

        val msg = buildString {
            appendLine("开机悬浮开关：" + if (sp.getBoolean("bootOverlay", true)) "开" else "关 ✗")
            appendLine("悬浮窗权限：" + if (android.provider.Settings.canDrawOverlays(this@ManageActivity)) "已授权" else "未授权 ✗")
            appendLine("带码的停车场：$codedLots 个" + if (codedLots == 0) " ✗" else "")
            appendLine("家：" + (Home.get(this@ManageActivity)?.let {
                "已设定，${Home.radius(this@ManageActivity)} m 内开机不弹"
            } ?: "未设定"))
            appendLine("电池优化：" + if (ignoringBattery) "已豁免" else "未豁免（可能被杀）")
            appendLine("后台定位：" + if (LocationService.isEnabled(this@ManageActivity)) "开" else "关 ✗")
            appendLine("持久化开机任务：" + if (BootJob.isScheduled(this@ManageActivity)) "已排期" else "未排期 ✗")
            appendLine("导航联动：" + when {
                !NavWatcher.isEnabled(this@ManageActivity) -> "关"
                !NavWatcher.hasPermission(this@ManageActivity) -> "缺「查看使用情况」权限 ✗"
                else -> "开（前台：${NavWatcher.foregroundPackage(this@ManageActivity) ?: "未知"}）"
            })
            val fix = Geo.cachedFix(this@ManageActivity)
            appendLine("最后记录的位置：" + (fix?.let {
                val ageMin = (System.currentTimeMillis() - it.time) / 60_000
                val stale = if (ageMin > 30) "  ✗ 已停更 ${ageMin / 60} 小时${ageMin % 60} 分" else ""
                "%s（%.5f, %.5f）%s".format(fmt.format(java.util.Date(it.time)), it.latitude, it.longitude, stale)
            } ?: "无"))
            appendLine("开机后已运行：${android.os.SystemClock.elapsedRealtime() / 60_000} 分钟")
            appendLine()
            if (last == 0L) {
                appendLine("⚠ 从没走通过开机流程。")
                appendLine("这台 ROM 既不发开机广播、自启白名单也可能不对第三方生效。")
                appendLine("已改用两条不依赖广播的路：")
                appendLine("  ① 持久化系统任务（重启后由系统重新调度）")
                appendLine("  ② 导航联动：打开高德等导航时自动浮出停车码")
                appendLine("建议开启上面的「导航联动」，它比开机自启更可靠。")
            } else {
                appendLine("上次开机处理：${fmt.format(java.util.Date(last))}")
                appendLine("　方式：${sp.getString("lastBootAction", "-")}")
                appendLine("　结果：${sp.getString("lastBootResult", "-")}")
            }
            if (!ignoringBattery) {
                appendLine()
                appendLine("⚠ 电池优化未豁免时，车机可能把后台定位服务杀掉")
                appendLine("（曾观察到定位停更 9 小时）。建议点下方按钮豁免。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("开机自启诊断")
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .setNeutralButton("电池优化设置") { _, _ ->
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }.onFailure { toast("这台车机没有这个设置页") }
            }
            .setNegativeButton("应用详情") { _, _ ->
                runCatching {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }.onFailure { toast("打不开设置") }
            }
            .show()
    }

    /** 导航联动设置：开关 + 从最近用过的应用里指认触发目标 */
    private fun showNavDialog(onChanged: () -> Unit) {
        val custom = NavWatcher.custom(this)
        val builtinHit = NavWatcher.triggers(this) - custom
        val msg = buildString {
            appendLine("打开下列应用时，自动浮出停车码：")
            appendLine()
            appendLine("内置识别：高德/百度/腾讯地图（含车机版）")
            if (custom.isEmpty()) {
                appendLine("自选：无")
            } else {
                appendLine("自选：" + custom.joinToString("、") { NavWatcher.label(this@ManageActivity, it) })
            }
            appendLine()
            appendLine("如果你的导航打开后没反应，说明它的包名不在内置列表里——")
            appendLine("先打开一次那个 App，再回来点「指认触发应用」把它选上。")
        }
        AlertDialog.Builder(this)
            .setTitle("导航联动")
            .setMessage(msg)
            .setPositiveButton(if (NavWatcher.isEnabled(this)) "关闭联动" else "开启联动") { _, _ ->
                NavWatcher.setEnabled(this, !NavWatcher.isEnabled(this))
                LocationService.start(this)
                onChanged()
                toast(if (NavWatcher.isEnabled(this)) "已开启" else "已关闭")
            }
            .setNeutralButton("指认触发应用") { _, _ -> showNavPicker(onChanged) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showNavPicker(onChanged: () -> Unit) {
        val recent = NavWatcher.recent(this)
        if (recent.isEmpty()) {
            toast("还没记录到其他应用。先打开一次你的导航 App，等几秒再回来。")
            return
        }
        val labels = recent.map { "${NavWatcher.label(this, it)}  ($it)" }.toTypedArray()
        val chosen = NavWatcher.custom(this)
        val checked = recent.map { it in chosen }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("勾选要联动的应用")
            .setMultiChoiceItems(labels, checked) { _, i, isOn -> checked[i] = isOn }
            .setPositiveButton("保存") { _, _ ->
                NavWatcher.setCustom(this, recent.filterIndexed { i, _ -> checked[i] }.toSet())
                LocationService.start(this)
                onChanged()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
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
