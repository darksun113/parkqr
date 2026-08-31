package com.kelvin.parkqr

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView

/**
 * 把车牌渲染成中国车牌的样子。
 *
 * 按"省+字母之后的位数"判断：5 位是普通蓝牌，6 位是新能源渐变绿牌
 * （即总长 7 位=蓝、8 位=绿）。判断不了就按蓝牌走。
 */
object PlateStyle {

    fun apply(tv: TextView, plate: String?) {
        val p = plate?.trim()?.uppercase().orEmpty()
        if (p.isBlank() || p == "未设置") {
            tv.text = "未设置"
            tv.background = null
            tv.setTextColor(0xFF8A8F98.toInt())
            tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            tv.letterSpacing = 0.02f
            tv.setPadding(0, 0, 0, 0)
            return
        }

        val newEnergy = p.length >= 8            // 省1 + 字母1 + 6位
        val density = tv.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        tv.background = GradientDrawable().apply {
            cornerRadius = 6f * density
            if (newEnergy) {
                // 新能源小型车：白→绿 渐变，黑字
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFFF4FBF0.toInt(), 0xFF7BC46A.toInt())
                setStroke(dp(2), 0xFF2E7D32.toInt())
            } else {
                setColor(0xFF1B4B9E.toInt())     // 普通蓝牌
                setStroke(dp(2), Color.WHITE)
            }
        }
        tv.setTextColor(if (newEnergy) 0xFF10240C.toInt() else Color.WHITE)
        tv.setTypeface(Typeface.DEFAULT_BOLD)
        tv.letterSpacing = 0.10f
        tv.setPadding(dp(12), dp(4), dp(12), dp(6))
        // 真车牌上省份+字母后面有个圆点分隔
        tv.text = if (p.length > 2) "${p.substring(0, 2)}·${p.substring(2)}" else p
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (newEnergy) 26f else 28f)
    }
}
