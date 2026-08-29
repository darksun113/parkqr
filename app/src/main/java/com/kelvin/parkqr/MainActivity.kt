package com.kelvin.parkqr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * 打开就显示"离你最近的那个停车场"的缴费码 + 车牌。
 * 用手机扫车机屏幕上的这个码去付款。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: LotStore
    private lateinit var plates: PlateStore
    private lateinit var qrHolder: FrameLayout
    private lateinit var qrCard: FrameLayout
    private lateinit var qrImage: ImageView
    private lateinit var emptyHint: TextView
    private lateinit var lotName: TextView
    private lateinit var lotMeta: TextView
    private lateinit var plate: TextView
    private lateinit var plateRow: LinearLayout

    /** 按距离排好序的列表，Double 为米；没坐标的排最后。 */
    private var ranked: List<Pair<Lot, Double?>> = emptyList()
    private var current: Lot? = null
    private var tracking: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = LotStore.get(this)
        plates = PlateStore.get(this)
        plates.migrateFrom(store.all())

        qrHolder = findViewById(R.id.qrHolder)
        qrCard = findViewById(R.id.qrCard)
        qrImage = findViewById(R.id.qrImage)
        emptyHint = findViewById(R.id.emptyHint)
        lotName = findViewById(R.id.lotName)
        lotMeta = findViewById(R.id.lotMeta)
        plate = findViewById(R.id.plate)
        plateRow = findViewById(R.id.plateRow)

        findViewById<Button>(R.id.btnSwitch).setOnClickListener { showSwitcher() }
        findViewById<Button>(R.id.btnManage).setOnClickListener {
            startActivity(Intent(this, ManageActivity::class.java))
        }
        plateRow.setOnClickListener { showPlateDialog() }

        // 车机屏幕通常偏暗，扫码时把亮度拉满，并且别息屏。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        if (!Geo.hasPermission(this)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 前台就一直记录定位，把最新的落盘 —— 进地库后拿到的就是入口前那个点
        tracking = Geo.startTracking(this)
        refresh()
    }

    override fun onPause() {
        tracking?.let { runCatching { it.close() } }
        tracking = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val loc = Geo.lastKnown(this)
        ranked = store.all()
            .map { lot ->
                val d = if (loc != null && lot.hasLocation) {
                    Geo.distance(loc.latitude, loc.longitude, lot.lat!!, lot.lng!!)
                } else null
                lot to d
            }
            .sortedWith(compareBy({ it.second == null }, { it.second ?: 0.0 }))

        // 优先选"最近且已经有码"的；一个有码的都没有就退回第一个。
        val pick = ranked.firstOrNull { it.first.hasCode } ?: ranked.firstOrNull()
        current = pick?.first
        render()
    }

    private fun render() {
        val lot = current
        if (lot == null) {
            emptyHint.visibility = View.VISIBLE
            qrCard.visibility = View.GONE
            // 车牌是全局的，没有停车场也照常显示
            plateRow.visibility = View.VISIBLE
            plate.text = plates.selected() ?: "未设置"
            lotName.text = "—"
            lotMeta.text = ""
            return
        }

        plateRow.visibility = View.VISIBLE
        lotName.text = lot.name
        plate.text = plates.selected() ?: "未设置"

        val dist = ranked.firstOrNull { it.first.id == lot.id }?.second
        lotMeta.text = buildString {
            append(if (dist != null) "距离约 ${Geo.format(dist)}" else "无坐标")
            if (lot.note.isNotBlank()) append("  ·  ${lot.note}")
        }

        if (!lot.hasCode) {
            emptyHint.visibility = View.VISIBLE
            emptyHint.text = "「${lot.name}」还没有缴费码\n\n点右上角「管理」→「手机传码」"
            qrCard.visibility = View.GONE
            return
        }

        emptyHint.visibility = View.GONE
        qrCard.visibility = View.VISIBLE
        // 等布局完成拿到实际尺寸，再按尺寸生成 —— 直接拉伸小图会糊，糊了就扫不出。
        qrHolder.post { drawCode(lot) }
    }

    private fun drawCode(lot: Lot) {
        val side = minOf(qrHolder.width, qrHolder.height)
        if (side <= 0) return
        qrCard.layoutParams = (qrCard.layoutParams as FrameLayout.LayoutParams).apply {
            width = side
            height = side
        }
        val inner = (side - dp(24)).coerceAtLeast(200)

        val bmp: Bitmap? = lot.payload?.let { text ->
            runCatching { QrUtil.encode(text, inner) }.getOrNull()
        } ?: lot.imageFile?.let { store.readImage(it) }

        if (bmp == null) {
            emptyHint.visibility = View.VISIBLE
            emptyHint.text = "「${lot.name}」的码读不出来了，请重新上传"
            qrCard.visibility = View.GONE
        } else {
            qrImage.setImageBitmap(bmp)
        }
    }

    private fun showSwitcher() {
        if (ranked.isEmpty()) {
            toast("还没有停车场")
            return
        }
        val labels = ranked.map { (lot, d) ->
            val dist = if (d != null) Geo.format(d) else "无坐标"
            val code = if (lot.hasCode) "" else "  ·  无码"
            "${lot.name}\n$dist$code"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择停车场（按距离）")
            .setItems(labels) { _, i ->
                current = ranked[i].first
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 车牌是全局的：列表单选即切换，另有 复制/添加/删除。 */
    private fun showPlateDialog() {
        val list = plates.all()
        if (list.isEmpty()) {
            promptAddPlate()
            return
        }
        val cur = plates.selected()
        val checked = list.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("车牌")
            .setSingleChoiceItems(list.toTypedArray(), checked) { d, i ->
                plates.select(list[i])
                render()
                d.dismiss()
            }
            .setPositiveButton("复制当前") { _, _ -> copyPlate() }
            .setNeutralButton("添加") { _, _ -> promptAddPlate() }
            .setNegativeButton("删除当前") { _, _ ->
                cur?.let {
                    plates.remove(it)
                    render()
                    toast("已删除 $it")
                }
            }
            .show()
    }

    private fun promptAddPlate() {
        val input = android.widget.EditText(this).apply {
            hint = "例如：粤B12345"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        AlertDialog.Builder(this)
            .setTitle("添加车牌")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotBlank()) {
                    plates.add(v)
                    plates.select(v.uppercase())
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyPlate() {
        val p = plates.selected()
        if (p.isNullOrBlank()) {
            toast("还没有车牌，点车牌行添加")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("车牌", p))
        toast("已复制 $p")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
