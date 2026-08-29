package com.kelvin.parkqr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * OTA：查 GitHub Releases 最新版，比对版本号，下载 APK 拉起系统安装器。
 * 前提：release 的 tag 是 vX.Y.Z，资产里有 .apk，且签名与已装版本一致（都是本地 debug keystore）。
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/darksun113/parkqr/releases/latest"
    private const val PREFS = "ota"
    private const val K_LAST = "lastCheck"
    private const val AUTO_INTERVAL_MS = 24 * 3600_000L

    fun currentVersion(ctx: Context): String =
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"

    /** vX.Y.Z 比较；解析失败按 0。返回正数表示 a 更新。 */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").split(".", "-")
            .mapNotNull { it.toIntOrNull() }
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /** 启动时静默检查（24h 一次）；手动检查（manual=true）总是执行且总有反馈。 */
    fun check(activity: Activity, manual: Boolean) {
        val sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!manual) {
            val last = sp.getLong(K_LAST, 0L)
            if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return
        }
        thread {
            val result = runCatching { fetchLatest() }
            sp.edit().putLong(K_LAST, System.currentTimeMillis()).apply()
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                result.fold(
                    onSuccess = { (tag, notes, apkUrl, apkName) ->
                        val cur = currentVersion(activity)
                        if (compare(tag, cur) > 0 && apkUrl != null) {
                            AlertDialog.Builder(activity)
                                .setTitle("发现新版本 $tag（当前 v$cur）")
                                .setMessage(notes.take(600))
                                .setPositiveButton("下载并安装") { _, _ ->
                                    download(activity, apkUrl, apkName ?: "update.apk")
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        } else if (manual) {
                            toast(activity, "已是最新版本（v$cur）")
                        }
                    },
                    onFailure = {
                        if (manual) toast(activity, "检查失败：${it.message}（车机网络能访问 GitHub 吗？）")
                    }
                )
            }
        }
    }

    private data class Latest(
        val tag: String, val notes: String, val apkUrl: String?, val apkName: String?
    )

    private fun fetchLatest(): Latest {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val body = conn.inputStream.bufferedReader().readText()
        val o = JSONObject(body)
        var apkUrl: String? = null
        var apkName: String? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    apkName = a.optString("name")
                    break
                }
            }
        }
        return Latest(o.optString("tag_name"), o.optString("body"), apkUrl, apkName)
    }

    private fun download(activity: Activity, url: String, name: String) {
        val dlg = AlertDialog.Builder(activity)
            .setTitle("正在下载…")
            .setMessage(name)
            .setCancelable(false)
            .show()
        thread {
            val result = runCatching {
                val dir = File(activity.cacheDir, "ota").apply { mkdirs() }
                val f = File(dir, name)
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                f
            }
            activity.runOnUiThread {
                dlg.dismiss()
                if (activity.isFinishing) return@runOnUiThread
                result.fold(
                    onSuccess = { install(activity, it) },
                    onFailure = { toast(activity, "下载失败：${it.message}") }
                )
            }
        }
    }

    private fun install(activity: Activity, apk: File) {
        // 首次需要用户给"安装未知应用"授权，引导过去，装完回来再点一次更新即可
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, "请先允许本应用安装未知应用，然后再点一次检查更新")
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
}
