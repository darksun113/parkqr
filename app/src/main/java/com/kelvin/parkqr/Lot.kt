package com.kelvin.parkqr

import org.json.JSONObject

/**
 * 一个停车场条目。
 *
 * 二维码有两种存法，取决于物料码本身：
 *  - [payload] 非空：物料码是标准 QR，扫出来是一段文本(通常是 URL)。只存文本，
 *    显示时用 zxing 现场重绘，任意尺寸都清晰。
 *  - [imageFile] 非空：物料码解不出来(多半是微信小程序码，不是 QR)，只能存原图。
 */
data class Lot(
    val id: String,
    var name: String,
    /** 已废弃：车牌改为全局(见 PlateStore)，此字段仅用于读旧数据做迁移。 */
    var plate: String,
    var lat: Double?,
    var lng: Double?,
    var payload: String?,
    var imageFile: String?,
    var note: String
) {
    val hasCode: Boolean get() = !payload.isNullOrBlank() || !imageFile.isNullOrBlank()
    val hasLocation: Boolean get() = lat != null && lng != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("plate", plate)
        lat?.let { put("lat", it) }
        lng?.let { put("lng", it) }
        payload?.let { put("payload", it) }
        imageFile?.let { put("imageFile", it) }
        put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject) = Lot(
            id = o.optString("id"),
            name = o.optString("name"),
            plate = o.optString("plate"),
            lat = if (o.has("lat")) o.optDouble("lat") else null,
            lng = if (o.has("lng")) o.optDouble("lng") else null,
            payload = o.optString("payload").ifBlank { null },
            imageFile = o.optString("imageFile").ifBlank { null },
            note = o.optString("note")
        )
    }
}
