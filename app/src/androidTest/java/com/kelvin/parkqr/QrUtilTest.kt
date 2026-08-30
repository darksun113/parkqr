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

@RunWith(AndroidJUnit4::class)
class CoordConvTest {

    /** 深圳一点：GCJ 相对 WGS 的偏移应在 100~800 米量级（中国区特征） */
    @Test
    fun gcjOffsetIsSane() {
        val (gLat, gLng) = CoordConv.wgsToGcj(22.5361, 113.9345)
        val d = Geo.distance(22.5361, 113.9345, gLat, gLng)
        assertTrue("offset=$d", d in 100.0..800.0)
    }

    /** wgs -> gcj -> wgs 往返误差应小于 1 米 */
    @Test
    fun gcjRoundTrip() {
        val (gLat, gLng) = CoordConv.wgsToGcj(22.5361, 113.9345)
        val (wLat, wLng) = CoordConv.gcjToWgs(gLat, gLng)
        assertTrue(Geo.distance(22.5361, 113.9345, wLat, wLng) < 1.0)
    }

    /** bd09 链路：bd -> wgs 与已知 gcj->bd 正变换互逆，误差 < 2 米 */
    @Test
    fun bdChain() {
        // 用 gcj 点构造一个 bd 点（bd = gcj + 官方正变换），再走 bdToWgs 回来
        val wgs = 22.5361 to 113.9345
        val (gLat, gLng) = CoordConv.wgsToGcj(wgs.first, wgs.second)
        // 官方 gcj->bd 正变换
        val xPi = Math.PI * 3000.0 / 180.0
        val z = Math.sqrt(gLng * gLng + gLat * gLat) + 0.00002 * Math.sin(gLat * xPi)
        val theta = Math.atan2(gLat, gLng) + 0.000003 * Math.cos(gLng * xPi)
        val bdLng = z * Math.cos(theta) + 0.0065
        val bdLat = z * Math.sin(theta) + 0.006
        val (wLat, wLng) = CoordConv.bdToWgs(bdLat, bdLng)
        assertTrue(Geo.distance(wgs.first, wgs.second, wLat, wLng) < 2.0)
    }

    /** 国外坐标不做偏移 */
    @Test
    fun outOfChinaUntouched() {
        val (lat, lng) = CoordConv.wgsToGcj(37.7749, -122.4194)
        assertEquals(37.7749, lat, 1e-9)
        assertEquals(-122.4194, lng, 1e-9)
    }
}

@RunWith(AndroidJUnit4::class)
class LinkResolverTest {

    /** 高德 marker URL：position=lng,lat 是 GCJ，应转成 WGS */
    @Test
    fun amapMarkerUrl() {
        val (gLat, gLng) = CoordConv.wgsToGcj(22.5361, 113.9345)
        val url = "https://uri.amap.com/marker?position=%.6f,%.6f&name=test".format(gLng, gLat)
        val r = LinkResolver.extract(url, isBaidu = false)!!
        assertTrue(Geo.distance(22.5361, 113.9345, r.first, r.second) < 2.0)
    }

    /** 裸数对（lat,lng 顺序）也能识别 */
    @Test
    fun barePairLatLng() {
        val r = LinkResolver.extract("找到了 22.53331,113.93937 这里", isBaidu = false)!!
        // GCJ->WGS 后应落在万象天地附近（几十米内）
        assertTrue(Geo.distance(22.5361, 113.9345, r.first, r.second) < 100.0)
    }

    /** 百度墨卡托米制 -> 经纬度：链路 sanity（深圳范围） */
    @Test
    fun baiduMercator() {
        // 深圳大致 mc 坐标（lng≈113.93 -> x≈1.268e7, lat≈22.53 -> y≈2.57e6）
        val ll = LinkResolver.mcToLl(12683054.0, 2568813.0)
        assertTrue(ll != null)
        assertTrue("got $ll", ll!!.first in 113.0..115.0 && ll.second in 22.0..23.5)
    }

    /** 无坐标的文本返回 null */
    @Test
    fun noCoordReturnsNull() {
        assertTrue(LinkResolver.extract("https://surl.amap.com/abc 快来停车", false) == null)
    }
}
