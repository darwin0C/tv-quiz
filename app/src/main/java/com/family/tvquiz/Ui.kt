package com.family.tvquiz

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 遥控器友好的界面小工具：大字号、焦点高亮。 */
object Ui {
    const val BG = 0xFF14213D.toInt()
    const val FG = Color.WHITE
    const val ACCENT = 0xFFFCA311.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun column(ctx: Context, pad: Int = 48): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        val p = dp(ctx, pad); setPadding(p, p, p, p)
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    fun text(ctx: Context, s: String, size: Float = 22f, bold: Boolean = false, color: Int = FG) =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun button(ctx: Context, s: String, size: Float = 26f, onClick: () -> Unit) = Button(ctx).apply {
        text = s; textSize = size; isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundResource(R.drawable.btn_bg)
        val h = dp(ctx, 20); val v = dp(ctx, 12)
        setPadding(h, v, h, v)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun margins(ctx: Context, all: Int = 12): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { val m = dp(ctx, all); setMargins(m, m, m, m) }

    fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    /** 家长 PIN 验证。 */
    fun askPin(act: Activity, onOk: () -> Unit) {
        val input = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "家长密码"
            textSize = 24f
        }
        AlertDialog.Builder(act)
            .setTitle("家长验证")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                if (input.text.toString() == Prefs(act).pin) onOk() else toast(act, "密码不对")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
