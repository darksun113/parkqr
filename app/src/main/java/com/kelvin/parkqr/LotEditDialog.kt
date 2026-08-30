package com.kelvin.parkqr

import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 停车场编辑对话框：名称/备注/坐标/码各项独立改，不动的保存时原样保留。
 * 抽出来是为了主界面也能一键编辑当前停车场，不必进管理页翻列表。
 */
object LotEditDialog {

    fun show(act: CoordActivity, store: LotStore, lot: Lot, isNew: Boolean, onChanged: () -> Unit) {
        val view = LayoutInflater.from(act).inflate(R.layout.dialog_lot, null)
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
            act.promptManualCoord { la, ln ->
                lat = la; lng = ln
                renderLoc()
                toast(act, "已填入（已转 WGS-84）")
            }
        }
        view.findViewById<Button>(R.id.btnCoordMap).setOnClickListener {
            act.pickOnMap(lat, lng, 0.0) { la, ln ->
                lat = la; lng = ln
                renderLoc()
            }
        }
        btnLoc.setOnClickListener {
            btnLoc.isEnabled = false
            btnLoc.text = "定位中…"
            locText.text = "正在等 GPS，最多 10 秒。停在露天/入口处成功率最高。"
            Geo.requestFix(act, timeoutMs = 10_000) { loc ->
                btnLoc.isEnabled = true
                btnLoc.text = "记录当前位置"
                if (loc == null) {
                    renderLoc()
                    toast(act, "拿不到定位：检查权限和系统定位开关，或车机 GPS 还没定上")
                } else {
                    lat = loc.latitude
                    lng = loc.longitude
                    renderLoc()
                    toast(act, "已记录（精度约 ${loc.accuracy.toInt()} m）")
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
            val input = EditText(act).apply {
                hint = "微信扫物料码后复制的链接，粘/输到这里"
                setText(payload.orEmpty())
                minLines = 2
            }
            AlertDialog.Builder(act)
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

        val b = AlertDialog.Builder(act)
            .setTitle(if (isNew) "新建停车场" else "编辑停车场")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = inName.text.toString().trim()
                if (name.isEmpty()) {
                    toast(act, "名称不能为空")
                    return@setPositiveButton
                }
                lot.name = name
                lot.note = inNote.text.toString().trim()
                lot.lat = lat
                lot.lng = lng
                lot.payload = payload
                lot.imageFile = imageFile
                store.save(lot)
                onChanged()
            }
            .setNegativeButton("取消", null)

        if (!isNew) {
            b.setNeutralButton("删除") { _, _ ->
                AlertDialog.Builder(act)
                    .setTitle("删除「${lot.name}」？")
                    .setPositiveButton("删除") { _, _ ->
                        store.delete(lot.id)
                        onChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        b.show()
    }

    private fun toast(act: CoordActivity, s: String) =
        Toast.makeText(act, s, Toast.LENGTH_LONG).show()
}
