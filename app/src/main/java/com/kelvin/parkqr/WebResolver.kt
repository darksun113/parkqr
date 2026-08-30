package com.kelvin.parkqr

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 隐形 WebView 解析兜底：百度按 TLS 指纹区分客户端，HttpURLConnection 拿到的
 * 页面被去掉了 POI 数据（只剩全国默认中心）。WebView 是真浏览器内核——
 * 指纹是 Chrome、还会执行 JS，页面渲染完从最终 URL 和 DOM 里抽坐标。
 *
 * 阻塞调用（内部切主线程建 WebView），放后台线程里跑，最多等 [timeoutMs]。
 */
object WebResolver {

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(ctx: Context, url: String, timeoutMs: Long = 30_000): Pair<Double, Double>? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "必须在后台线程调用" }
        val isBaidu = url.contains("baidu.com")
        val latch = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Pair<Double, Double>?>(null)
        val main = Handler(Looper.getMainLooper())
        var web: WebView? = null

        main.post {
            runCatching {
                val w = WebView(ctx.applicationContext)
                web = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        v: WebView?, code: Int, desc: String?, failingUrl: String?
                    ) {
                        android.util.Log.w("WebResolver", "error $code $desc $failingUrl")
                    }
                    override fun onPageFinished(v: WebView, pageUrl: String) {
                        android.util.Log.i("WebResolver", "pageFinished: ${pageUrl.take(120)}")
                        // 动态页面内容随渲染进度变化，多个时点各抓一次
                        LinkResolver.extract(pageUrl, isBaidu)?.let {
                            result.set(it)
                            latch.countDown()
                            return
                        }
                        for (delay in longArrayOf(2000, 6000, 12000)) {
                            main.postDelayed({
                                if (result.get() != null) return@postDelayed
                                v.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                    if (result.get() == null && html != null) {
                                        val hit = LinkResolver.extract(html, isBaidu)
                                        android.util.Log.i(
                                            "WebResolver", "t=$delay len=${html.length} -> $hit"
                                        )
                                        hit?.let {
                                            result.set(it)
                                            latch.countDown()
                                        }
                                        // 调试：最后一个时点仍失败就把 DOM 落盘分析
                                        if (hit == null && delay == 12000L) {
                                            runCatching {
                                                java.io.File(ctx.filesDir, "webdump.html")
                                                    .writeText(html)
                                            }
                                        }
                                    }
                                }
                            }, delay)
                        }
                    }
                }
                w.loadUrl(url)
            }.onFailure { latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        main.post { runCatching { web?.destroy() } }
        return result.get()
    }

    /** 先走纯 HTTP（快），失败再上 WebView（稳）。 */
    fun resolveSmart(ctx: Context, text: String): Pair<Double, Double>? {
        LinkResolver.resolve(text)?.let { return it }
        val url = Regex("""https?://[^\s"'，。<>]+""").find(text)?.value ?: return null
        return resolve(ctx, url)
    }
}
