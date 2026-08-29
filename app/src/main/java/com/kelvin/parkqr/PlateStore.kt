package com.kelvin.parkqr

import android.content.Context
import org.json.JSONArray

/**
 * 全局车牌列表。车牌不与停车场绑定 —— 车是你的，场只是地点。
 * 主界面显示"当前选中"的那个，可随时切换。
 */
class PlateStore private constructor(context: Context) {

    private val sp = context.getSharedPreferences("plates", Context.MODE_PRIVATE)

    fun all(): List<String> {
        val arr = runCatching { JSONArray(sp.getString(K_LIST, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun selected(): String? = sp.getString(K_SELECTED, null)?.takeIf { it in all() }

    fun select(plate: String) {
        sp.edit().putString(K_SELECTED, plate).apply()
    }

    fun add(plate: String) {
        val p = plate.trim().uppercase()
        if (p.isBlank()) return
        val list = all()
        if (p !in list) persist(list + p)
        if (selected() == null) select(p)
    }

    fun remove(plate: String) {
        persist(all() - plate)
        if (sp.getString(K_SELECTED, null) == plate) {
            sp.edit().remove(K_SELECTED).apply()
            all().firstOrNull()?.let { select(it) }
        }
    }

    /** 旧版本车牌存在每个停车场条目里，迁移进全局列表（只跑一次，幂等）。 */
    fun migrateFrom(lots: List<Lot>) {
        if (sp.getBoolean(K_MIGRATED, false)) return
        lots.mapNotNull { it.plate.takeIf(String::isNotBlank) }.distinct().forEach { add(it) }
        sp.edit().putBoolean(K_MIGRATED, true).apply()
    }

    private fun persist(list: List<String>) {
        sp.edit().putString(K_LIST, JSONArray(list).toString()).apply()
    }

    companion object {
        private const val K_LIST = "list"
        private const val K_SELECTED = "selected"
        private const val K_MIGRATED = "migrated"

        @Volatile
        private var instance: PlateStore? = null

        fun get(context: Context): PlateStore =
            instance ?: synchronized(this) {
                instance ?: PlateStore(context.applicationContext).also { instance = it }
            }
    }
}
