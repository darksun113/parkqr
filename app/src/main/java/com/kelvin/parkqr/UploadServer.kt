package com.kelvin.parkqr

import android.content.Context
import android.net.wifi.WifiManager
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 车机上跑的极简上传服务。手机连同一个 WiFi(或车机热点)，浏览器打开就能传物料码照片。
 *
 * 为什么不是蓝牙：车机 ROM 的蓝牙 API 限制多、BLE 传图慢、配对麻烦，而 HTTP 这条路
 * 手机端一行代码都不用写。
 *
 * 注意页面上不采集手机 GPS —— http 明文页在 Chrome 里是非安全上下文，Geolocation API
 * 直接被禁。位置由车机自己在停车场按一下「记录当前位置」来采，反而更准。
 */
class UploadServer(
    private val ctx: Context,
    private val store: LotStore,
    private val onChanged: () -> Unit
) : NanoHTTPD("0.0.0.0", PORT) {

    /** 车机端刚新建的场：上传页把它置顶并预选中（新建的场多半没坐标，按距离排序会沉底） */
    @Volatile
    var preselectId: String? = null   // 显式绑通配地址：手机从局域网连进来，不能只监听回环

    companion object {
        const val PORT = 8765

        /**
         * 找一个"手机在局域网里真能连上"的车机地址。
         *
         * 不能随便拿第一个非回环 IPv4：带 SIM 的车机第一个往往是蜂窝网卡(rmnet)的
         * 运营商内网地址，手机根本到不了；VPN(tun)、链路本地(169.254)同样没用。
         * 所以按接口名打分：WiFi/热点(wlan/ap/swlan/softap) > 以太网/USB 网络共享 >
         * 其它；蜂窝和 VPN 直接排除。同分时偏向常见私网段。
         */
        fun localAddress(ctx: Context): String? {
            // WifiManager 只在"车机作为 WiFi 客户端"时有值；热点模式下拿不到，走网卡遍历
            runCatching {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ip = wm.connectionInfo.ipAddress
                if (ip != 0) {
                    return "%d.%d.%d.%d".format(
                        ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
                    )
                }
            }

            data class Cand(val ip: String, val score: Int)
            val cands = mutableListOf<Cand>()
            runCatching {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    val name = nif.name.lowercase()
                    // 蜂窝/VPN/点对点：手机连不进来，直接跳过
                    if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                        name.startsWith("pdp") || name.startsWith("ppp") ||
                        name.startsWith("tun") || name.startsWith("ipsec") ||
                        name.startsWith("dummy")
                    ) continue

                    val ifScore = when {
                        name.startsWith("wlan") || name.startsWith("ap") ||
                        name.startsWith("swlan") || name.startsWith("softap") -> 100
                        name.startsWith("eth") || name.startsWith("usb") ||
                        name.startsWith("rndis") || name.startsWith("ncm") -> 50
                        else -> 10
                    }
                    for (addr in nif.inetAddresses) {
                        if (addr !is Inet4Address) continue
                        if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                        val ip = addr.hostAddress ?: continue
                        // 私网段加分：这才是手机通常所在的网段
                        val privBonus = if (addr.isSiteLocalAddress) 5 else 0
                        cands.add(Cand(ip, ifScore + privBonus))
                    }
                }
            }
            return cands.maxByOrNull { it.score }?.ip
        }
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.POST && session.uri == "/upload" -> handleUpload(session)
        session.uri == "/lots" -> json(lotsJson().toString())
        else -> html(page())
    }

    /** 微信内置浏览器缓存极其激进，所有响应一律禁缓存 */
    private fun noCache(r: Response): Response = r.apply {
        addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        addHeader("Pragma", "no-cache")
        addHeader("Expires", "0")
    }

    private fun json(body: String) = noCache(
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
    )

    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
            .onFailure { return html(result("上传失败：${it.message}", false)) }

        val params = session.parameters
        val lotId = params["lotId"]?.firstOrNull().orEmpty()
        // NanoHTTPD 用 US-ASCII 解 multipart 的文本字段，非 ASCII 会被打成一串 U+FFFD 且不可逆，
        // 所以名称和粘贴的码内容都由前端 base64 编码后再传，这里解回来。
        val newName = decodeB64(params["newNameB64"]?.firstOrNull())
            .ifBlank { params["newName"]?.firstOrNull().orEmpty() }
            .trim()
        val pasted = decodeB64(params["payloadB64"]?.firstOrNull()).trim()
        val locRaw = decodeB64(params["locB64"]?.firstOrNull()).trim()
        val tmpPath = files["photo"]
        val bytes = tmpPath?.let { runCatching { File(it).readBytes() }.getOrNull() }

        if (pasted.isBlank() && (bytes == null || bytes.isEmpty()) && locRaw.isBlank()) {
            return html(result("码（粘贴内容或照片）和位置至少填一样。", false))
        }

        val lot = when {
            lotId.isNotBlank() && lotId != "__new__" ->
                store.byId(lotId) ?: return html(result("停车场不存在", false))
            newName.isNotBlank() -> store.newLot(newName)
            else -> return html(result("请选择停车场，或填写新停车场名称", false))
        }

        var msg = ""
        if (pasted.isNotBlank()) {
            // 粘贴优先：这就是码的原文，不需要任何识别
            lot.payload = pasted
            lot.imageFile?.let { store.deleteImage(it) }
            lot.imageFile = null
            msg = "已保存粘贴的内容（车机端会重新绘制成二维码）：\n$pasted"
        } else if (bytes != null && bytes.isNotEmpty()) {
            val text = QrUtil.decode(bytes)
            if (text != null) {
                lot.payload = text
                lot.imageFile?.let { store.deleteImage(it) }
                lot.imageFile = null
                msg = "已识别为标准二维码，存为文本（车机端会重新绘制，更清晰）：\n$text"
            } else {
                lot.payload = null
                lot.imageFile = runCatching { store.writeImage(lot.id, bytes) }
                    .getOrElse { return html(result("图片存不下来：${it.message}", false)) }
                msg = "解不出二维码内容（多半是微信小程序码，不是标准 QR），已按原图保存。"
            }
        }
        // 位置（可选）：分享链接就解析（高德/腾讯 GCJ、百度 BD 自动转 WGS），
        // 裸的"纬度,经度"按 GCJ-02 处理（手机地图上看到的都是 GCJ 系）
        var locMsg = ""
        if (locRaw.isNotBlank()) {
            val r = if (locRaw.contains("http")) {
                WebResolver.resolveSmart(ctx, locRaw)
            } else {
                val parts = locRaw.replace("，", ",").replace(" ", "").split(",")
                val a = parts.getOrNull(0)?.toDoubleOrNull()
                val b = parts.getOrNull(1)?.toDoubleOrNull()
                if (a != null && b != null) {
                    val (la, ln) = if (a in 3.0..54.0) a to b else b to a
                    CoordConv.gcjToWgs(la, ln)
                } else null
            }
            if (r != null) {
                lot.lat = r.first
                lot.lng = r.second
                locMsg = "\n位置已更新：%.6f, %.6f（已转 WGS-84）".format(r.first, r.second)
            } else {
                locMsg = "\n⚠ 位置没解析出来（链接打不开或没坐标），其余已保存。"
            }
        }
        store.save(lot)
        onChanged()
        return html(result("「${lot.name}」保存成功。$locMsg\n\n$msg", true))
    }

    private fun decodeB64(v: String?): String {
        if (v.isNullOrBlank()) return ""
        return runCatching {
            String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun html(body: String) = noCache(
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
    )

    /** 列表数据：按距离排序（预选置顶），页面初始渲染和 /lots 实时接口共用 */
    private fun lotsJson(): org.json.JSONObject {
        val loc = Geo.lastKnown(ctx)
        val ranked = store.all().map { lot ->
            val d = if (loc != null && lot.hasLocation) {
                Geo.distance(loc.latitude, loc.longitude, lot.lat!!, lot.lng!!)
            } else null
            lot to d
        }.sortedWith(compareBy({ it.second == null }, { it.second ?: 0.0 }))

        val pre = preselectId
        val ordered = if (pre != null) {
            ranked.sortedBy { if (it.first.id == pre) -1.0 else (it.second ?: Double.MAX_VALUE / 2) }
        } else ranked
        val arr = org.json.JSONArray()
        ordered.forEach { (lot, d) ->
            arr.put(
                org.json.JSONObject()
                    .put("id", lot.id)
                    .put("label", buildString {
                        append(lot.name)
                        d?.let { append(" · ${Geo.format(it)}") }
                        append(if (lot.hasCode) " ✓已有码" else " ▲缺码")
                    })
            )
        }
        return org.json.JSONObject().put("lots", arr).put("preselect", pre ?: "")
    }

    private fun page(): String {
        val data = lotsJson()
        val lotsJson = data.getJSONArray("lots")
        val pre = preselectId
        // 服务端先把 option 渲染出来：微信内置浏览器可能拦截/延迟 JS，
        // 纯 JS 渲染会让下拉整个空掉（v1.5.2 的真实故障）。JS 只做增强过滤。
        val serverOptions = buildString {
            for (i in 0 until lotsJson.length()) {
                val o = lotsJson.getJSONObject(i)
                val id = o.getString("id")
                val sel = if (id == pre) " selected" else ""
                append("""<option value="${esc(id)}"$sel>${esc(o.getString("label"))}</option>""")
            }
            append("""<option value="__new__">＋ 新建停车场…</option>""")
        }
        val lotCount = lotsJson.length()
        return """
<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>传物料码到车机</title>
<style>
 body{font:16px/1.6 -apple-system,system-ui,sans-serif;margin:0;padding:24px;background:#111;color:#eee}
 h1{font-size:20px;margin:0 0 4px}
 p.sub{color:#888;margin:0 0 24px;font-size:14px}
 label{display:block;margin:18px 0 6px;color:#aaa;font-size:14px}
 select,input[type=text],textarea{width:100%;padding:12px;font-size:16px;border-radius:8px;
   border:1px solid #333;background:#1c1c1c;color:#eee;box-sizing:border-box;font-family:inherit}
 input[type=file]{width:100%;padding:12px 0;color:#eee}
 button{width:100%;margin-top:24px;padding:16px;font-size:17px;border:0;border-radius:8px;
   background:#2f6fed;color:#fff;font-weight:600}
 .hint{margin-top:20px;color:#777;font-size:13px}
</style></head><body>
<h1>传物料码到车机</h1>
<p class="sub">拍停车场缴费码那张贴纸，选好对应停车场，上传即可。</p>
<form method="post" action="/upload" enctype="multipart/form-data" onsubmit="return prep()">
  <label>停车场（可搜索）</label>
  <input type="text" id="lotFilter" placeholder="输名字过滤，例：海雅">
  <select name="lotId" id="lotId" size="1" style="margin-top:8px"
    onchange="document.getElementById('nn').style.display=this.value==='__new__'?'block':'none'">
    $serverOptions
  </select>
  <div class="hint" style="margin-top:6px">
    共 <span id="lotCount">$lotCount</span> 个停车场 · <a href="#" onclick="refreshLots();return false" style="color:#2f6fed">刷新列表</a>
  </div>
  <div id="nn" style="display:${if (store.all().isEmpty()) "block" else "none"}">
    <label>新停车场名称</label>
    <input type="text" id="newName" placeholder="例如：万象天地 B2">
    <input type="hidden" name="newNameB64" id="newNameB64">
  </div>
  <label>方式一：粘贴二维码内容（推荐）</label>
  <textarea id="payloadText" rows="3"
    placeholder="用微信「扫一扫」扫停车场物料码，复制打开的链接，粘到这里"></textarea>
  <input type="hidden" name="payloadB64" id="payloadB64">
  <label>方式二：或拍一张物料码照片</label>
  <input type="file" name="photo" accept="image/*">
  <label>位置（可选）：粘高德/百度「分享」链接，或输 纬度,经度</label>
  <textarea id="locText" rows="2"
    placeholder="高德/百度 App 里对着停车场点「分享→复制链接」，粘到这里，坐标自动解析换算"></textarea>
  <input type="hidden" name="locB64" id="locB64">
  <button type="submit">上传</button>
</form>
<script>
var LOTS = $lotsJson;
var PRESELECT = ${pre?.let { "\"${esc(it)}\"" } ?: "null"};
function renderOptions(filter) {
  var sel = document.getElementById('lotId');
  if (!LOTS || !LOTS.length) return;   // 没数据别把服务端渲染的选项清掉
  var cur = sel.value;
  sel.innerHTML = '';
  var q = (filter || '').toLowerCase();
  LOTS.forEach(function(l) {
    if (q && l.label.toLowerCase().indexOf(q) < 0) return;
    var o = document.createElement('option');
    o.value = l.id; o.textContent = l.label;
    sel.appendChild(o);
  });
  var nn = document.createElement('option');
  nn.value = '__new__'; nn.textContent = '＋ 新建停车场…';
  sel.appendChild(nn);
  // 优先恢复：预选 > 之前选的 > 第一项
  if (PRESELECT && !q) { sel.value = PRESELECT; }
  else if (cur) {
    sel.value = cur;
    if (sel.value !== cur) sel.selectedIndex = 0;
  }
  document.getElementById('nn').style.display = sel.value === '__new__' ? 'block' : 'none';
}
function refreshLots() {
  // HTML 可能被微信缓存，列表数据必须现拉——带时间戳绕缓存
  var xhr = new XMLHttpRequest();
  xhr.open('GET', '/lots?_=' + Date.now(), true);
  xhr.onload = function() {
    try {
      var d = JSON.parse(xhr.responseText);
      LOTS = d.lots;
      PRESELECT = d.preselect || null;
      renderOptions(document.getElementById('lotFilter').value);
      var c = document.getElementById('lotCount');
      if (c) c.textContent = LOTS.length;
    } catch (e) {}
  };
  xhr.send();
}
document.addEventListener('DOMContentLoaded', function() {
  renderOptions('');
  refreshLots();
  document.getElementById('lotFilter').addEventListener('input', function() {
    renderOptions(this.value);
  });
});
function b64(str){
  // 先转 UTF-8 字节再 base64，btoa 不能直接吃中文
  return str ? btoa(String.fromCharCode.apply(null, new TextEncoder().encode(str))) : '';
}
function prep(){
  document.getElementById('newNameB64').value = b64(document.getElementById('newName').value);
  document.getElementById('payloadB64').value = b64(document.getElementById('payloadText').value.trim());
  document.getElementById('locB64').value = b64(document.getElementById('locText').value.trim());
  return true;
}
</script>
<p class="hint">位置三选一：上面粘地图分享链接（最准，自动换算坐标系）；
或到了停车场在车机上点「记录当前位置」；或在车机上用「地图选点」。
浏览器在明文 http 下拿不到定位，所以这里不自动取。</p>
</body></html>
""".trimIndent()
    }

    private fun result(msg: String, ok: Boolean) = """
<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${if (ok) "已保存" else "失败"}</title>
<style>
 body{font:16px/1.7 -apple-system,system-ui,sans-serif;margin:0;padding:32px 24px;background:#111;color:#eee}
 .icon{font-size:48px}
 pre{white-space:pre-wrap;word-break:break-all;background:#1c1c1c;padding:16px;
   border-radius:8px;color:#bbb;font-size:13px}
 a{display:block;margin-top:28px;padding:16px;text-align:center;background:#2f6fed;
   color:#fff;text-decoration:none;border-radius:8px;font-weight:600}
</style></head><body>
<div class="icon">${if (ok) "✅" else "⚠️"}</div>
<pre>${esc(msg)}</pre>
<a href="/">继续上传</a>
</body></html>
""".trimIndent()

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
