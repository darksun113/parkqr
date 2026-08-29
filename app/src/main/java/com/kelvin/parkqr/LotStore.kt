package com.kelvin.parkqr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * lots.json + qr/ 图片目录。数据量很小，全量读写即可。
 *
 * 必须是进程内单例：上传服务在后台线程写，MainActivity 在前台读，
 * 各持一个实例的话缓存不同步——传完码回主界面还是"没有停车场"。
 */
class LotStore private constructor(context: Context) {

    private val dir = context.filesDir
    private val jsonFile = File(dir, "lots.json")
    private val qrDir = File(dir, "qr").apply { mkdirs() }

    @Volatile
    private var cache: MutableList<Lot>? = null

    @Synchronized
    fun all(): List<Lot> {
        cache?.let { return it }
        val list = mutableListOf<Lot>()
        if (jsonFile.exists()) {
            runCatching {
                val arr = JSONArray(jsonFile.readText())
                for (i in 0 until arr.length()) list.add(Lot.fromJson(arr.getJSONObject(i)))
            }
        }
        cache = list
        return list
    }

    fun byId(id: String): Lot? = all().firstOrNull { it.id == id }

    @Synchronized
    fun save(lot: Lot) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == lot.id }
        if (idx >= 0) list[idx] = lot else list.add(lot)
        persist(list)
    }

    @Synchronized
    fun delete(id: String) {
        val list = all().toMutableList()
        list.firstOrNull { it.id == id }?.imageFile?.let { File(qrDir, it).delete() }
        list.removeAll { it.id == id }
        persist(list)
    }

    private fun persist(list: MutableList<Lot>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        jsonFile.writeText(arr.toString(2))
        cache = list
    }

    companion object {
        @Volatile
        private var instance: LotStore? = null

        fun get(context: Context): LotStore =
            instance ?: synchronized(this) {
                instance ?: LotStore(context.applicationContext).also { instance = it }
            }
    }

    fun newLot(name: String) = Lot(
        id = UUID.randomUUID().toString().take(8),
        name = name, plate = "", lat = null, lng = null,
        payload = null, imageFile = null, note = ""
    )

    /** 存原图(小程序码等无法解码的情况)，返回文件名。 */
    fun writeImage(lotId: String, bytes: ByteArray): String {
        val name = "$lotId.png"
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("不是有效的图片")
        File(qrDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    fun deleteImage(name: String) {
        File(qrDir, name).delete()
    }

    fun readImage(name: String): Bitmap? {
        val f = File(qrDir, name)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }
}
