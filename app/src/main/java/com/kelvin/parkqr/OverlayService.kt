package com.kelvin.parkqr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 车机启动时悬浮在屏幕右上角的候选码窗：最近 3 个停车场（码 + 名字），
 * 点一个放大到可扫尺寸，再点收回；× 关闭。
 *
 * 用 TYPE_APPLICATION_OVERLAY + specialUse 前台服务，Android 8~14 通用。
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var root: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        if (!Settings.canDrawOverlays(this)) {
            // 没授权画不了，别空转
            stopSelf()
            return START_NOT_STICKY
        }
        val candidates = Candidates.top(this, LotStore.get(this), 3)
        if (candidates.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        showCompact(candidates)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeView()
        super.onDestroy()
    }

    // ---------- 视图 ----------

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(12)
        y = dp(12)
    }

    private fun cardBg() = GradientDrawable().apply {
        setColor(0xEE1B1D22.toInt())   // 半透明深底
        cornerRadius = dp(12).toFloat()
    }

    private fun whiteBg() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(8).toFloat()
    }

    /** 紧凑态：三个候选横排（迷你码 + 名字 + 距离），一行操作按钮。 */
    private fun showCompact(candidates: List<Pair<Lot, Double?>>) {
        removeView()
        val store = LotStore.get(this)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((lot, dist) in candidates) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val qr = ImageView(this).apply {
                background = whiteBg()
                val pad = dp(4)
                setPadding(pad, pad, pad, pad)
                val bmp = lot.payload?.let { runCatching { QrUtil.encode(it, dp(120)) }.getOrNull() }
                    ?: lot.imageFile?.let { store.readImage(it) }
                setImageBitmap(bmp)
                layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
            }
            val name = TextView(this).apply {
                text = lot.name
                setTextColor(0xFFECEDEE.toInt())
                textSize = 12f
                maxLines = 1
                gravity = Gravity.CENTER
            }
            val meta = TextView(this).apply {
                text = dist?.let { Geo.format(it) } ?: "无坐标"
                setTextColor(0xFF8A8F98.toInt())
                textSize = 10f
                gravity = Gravity.CENTER
            }
            cell.addView(qr); cell.addView(name); cell.addView(meta)
            cell.setOnClickListener { showExpanded(lot, dist, candidates) }
            row.addView(cell)
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        actions.addView(actionText("打开App") {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            stopSelf()
        })
        actions.addView(actionText("  ✕  ") { stopSelf() })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(actions)
            addView(row)
        }
        addViewToWindow(panel)
    }

    /** 展开态：单个候选放大到可扫尺寸。 */
    private fun showExpanded(lot: Lot, dist: Double?, candidates: List<Pair<Lot, Double?>>) {
        removeView()
        val store = LotStore.get(this)
        // 按宽高较小值算：竖屏下用屏高算会超出屏宽，码被裁掉就扫不出了
        val dm = resources.displayMetrics
        val side = (minOf(dm.widthPixels, dm.heightPixels) * 0.62f).toInt()
            .coerceAtLeast(dp(240))

        val qr = ImageView(this).apply {
            background = whiteBg()
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            val bmp = lot.payload?.let { runCatching { QrUtil.encode(it, side) }.getOrNull() }
                ?: lot.imageFile?.let { store.readImage(it) }
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(side, side)
        }
        val title = TextView(this).apply {
            text = lot.name + (dist?.let { "  ·  ${Geo.format(it)}" } ?: "")
            setTextColor(0xFFECEDEE.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(actionText("返回") { showCompact(candidates) })
        actions.addView(actionText("  ✕  ") { stopSelf() })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(10))
            addView(actions)
            addView(qr)
            addView(title)
        }
        addViewToWindow(panel)
    }

    private fun actionText(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFF6B9FFF.toInt())
        textSize = 14f
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun addViewToWindow(v: View) {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm!!.addView(v, layoutParams()) }
            .onSuccess { root = v }
            .onFailure { stopSelf() }
    }

    private fun removeView() {
        root?.let { r -> runCatching { wm?.removeView(r) } }
        root = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- 前台通知 ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "悬浮码", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("停车码悬浮窗运行中")
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
