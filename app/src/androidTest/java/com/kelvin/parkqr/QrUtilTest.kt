package com.kelvin.parkqr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class QrUtilTest {

    private fun png(bmp: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** 屏幕上画出来的码必须真能被扫出来 —— 这是整个 App 唯一不能出错的地方。 */
    @Test
    fun encodedCodeIsScannable() {
        val payload = "https://wxaurl.cn/tESTpark9x?pid=SZ0042"
        val decoded = QrUtil.decode(png(QrUtil.encode(payload, 900)))
        assertEquals(payload, decoded)
    }

    @Test
    fun encodedCodeSurvivesSmallScreen() {
        val payload = "https://parking.example.com/pay?lot=SZ-0042&t=1"
        assertEquals(payload, QrUtil.decode(png(QrUtil.encode(payload, 320))))
    }

    @Test
    fun handlesChinesePayload() {
        val payload = "停车缴费 万象天地B2 出口"
        assertEquals(payload, QrUtil.decode(png(QrUtil.encode(payload, 700))))
    }

    /** 反色的码也要能解 —— 有些物料是深底白码。 */
    @Test
    fun decodesInvertedCode() {
        val payload = "https://wxaurl.cn/inverted"
        val src = QrUtil.encode(payload, 800)
        val inv = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            inv.setPixel(x, y, if (src.getPixel(x, y) == Color.BLACK) Color.WHITE else Color.BLACK)
        }
        assertEquals(payload, QrUtil.decode(png(inv)))
    }

    /** 旋转 90 度（手机竖着拍横贴的码）也要能解。 */
    @Test
    fun decodesRotatedCode() {
        val payload = "https://wxaurl.cn/rotated"
        val src = QrUtil.encode(payload, 800)
        val rot = Bitmap.createBitmap(src.height, src.width, Bitmap.Config.ARGB_8888)
        Canvas(rot).apply {
            drawColor(Color.WHITE)
            rotate(90f)
            drawBitmap(src, 0f, -src.height.toFloat(), Paint())
        }
        assertEquals(payload, QrUtil.decode(png(rot)))
    }

    /** 不是二维码的图必须返回 null，好让调用方回退到"存原图"。 */
    @Test
    fun nonQrImageReturnsNull() {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        for (y in 0 until 300) for (x in 0 until 300) {
            bmp.setPixel(x, y, Color.rgb((x * 7) % 256, (y * 13) % 256, 128))
        }
        assertNull(QrUtil.decode(png(bmp)))
    }

    @Test
    fun storeRoundTrips() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LotStore.get(ctx)
        val lot = store.newLot("测试停车场 B2").apply {
            plate = "粤B12345"
            payload = "https://example.com/x"
            lat = 22.5361
            lng = 113.9345
        }
        store.save(lot)

        val back = store.byId(lot.id)!!
        assertEquals("测试停车场 B2", back.name)
        assertEquals("粤B12345", back.plate)
        assertEquals(22.5361, back.lat!!, 1e-9)
        assertTrue(back.hasCode && back.hasLocation)

        store.delete(lot.id)
        assertNull(store.byId(lot.id))
    }

    @Test
    fun distanceIsSane() {
        // 深圳万象天地 -> 海岸城，实际约 3 公里
        val d = Geo.distance(22.5361, 113.9345, 22.5197, 113.9291)
        assertTrue("got $d", d in 1500.0..3500.0)
    }
}
