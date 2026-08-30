package com.kelvin.parkqr

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlin.concurrent.thread

/** 地名搜索 + 多结果选择：同名地点太多，必须让人自己确认是哪一个。 */
object PlaceSearch {

    fun run(act: Activity, q: String, onPicked: (Nominatim.Place) -> Unit) {
        if (q.isBlank()) return
        val loc = Geo.lastKnown(act)
        val near = loc?.let { it.latitude to it.longitude }
        thread {
            val results = Nominatim.search(q, near)
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                when {
                    results.isEmpty() ->
                        Toast.makeText(act, "没搜到「$q」（要联网；试试更完整的地名）", Toast.LENGTH_LONG).show()
                    results.size == 1 -> onPicked(results[0])
                    else -> AlertDialog.Builder(act)
                        .setTitle("选择「$q」")
                        .setItems(results.map { Nominatim.label(it) }.toTypedArray()) { _, i ->
                            onPicked(results[i])
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }
}
