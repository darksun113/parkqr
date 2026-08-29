package com.kelvin.parkqr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrUtil {

    /** 文本 -> QR 位图。强制纯黑白，不跟随暗色主题，否则扫码器可能读不出。 */
    fun encode(text: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val off = y * w
            for (x in 0 until w) {
                pixels[off + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /**
     * 从手机拍的物料码照片里解出文本。
     * 拍照的码有透视、反光、旋转，所以多种 binarizer + 四个方向 + 反色都试一遍。
     * 解不出来返回 null —— 那多半是微信小程序码(不是 QR)，调用方应回退到存原图。
     */
    fun decode(bytes: ByteArray): String? {
        val bmp = decodeScaled(bytes, 1600) ?: return null
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val normal: LuminanceSource = RGBLuminanceSource(w, h, pixels)
        val sources = mutableListOf<LuminanceSource>()
        // 反色也试：有些物料码是深底白码，标准解码器读不出正片。
        for (base in listOf(normal, normal.invert())) {
            var cur = base
            for (i in 0 until 4) {
                sources.add(cur)
                if (!cur.isRotateSupported) break
                cur = cur.rotateCounterClockwise()
            }
        }

        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX)
        )
        val reader = MultiFormatReader().apply { setHints(hints) }

        for (src in sources) {
            for (bin in listOf(HybridBinarizer(src), GlobalHistogramBinarizer(src))) {
                val r = runCatching { reader.decodeWithState(BinaryBitmap(bin)) }.getOrNull()
                reader.reset()
                if (r != null && r.text.isNotBlank()) return r.text
            }
        }
        return null
    }

    /** 手机照片动辄 4000px，先降采样，否则解码慢且容易 OOM。 */
    private fun decodeScaled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        var sample = 1
        while (probe.outWidth / sample > maxDim || probe.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
