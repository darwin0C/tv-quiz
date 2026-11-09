package com.family.tvquiz.parent

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 家长端新版界面的配色和组件（对应预览画布里的"新设计稿"）。 */
object K {
    const val BG = 0xFFF4F6FA.toInt()
    const val CARD = Color.WHITE
    const val PRIMARY = 0xFF2F4FC0.toInt()
    const val PRIMARY_SOFT = 0xFFEEF2FD.toInt()
    const val TEXT = 0xFF141A2E.toInt()
    const val MUTED = 0xFF5B6478.toInt()
    const val BORDER = 0xFFD5DAE5.toInt()
    const val SEG = 0xFFEAEEF5.toInt()
    const val LINE = 0xFFEEF1F6.toInt()
    const val OK = 0xFF1B6E3D.toInt()
    const val OK_BG = 0xFFE6F4EC.toInt()
    const val ERR = 0xFFB3261E.toInt()
    const val ERR_BG = 0xFFFBE9E7.toInt()
    const val WARN = 0xFF7A4A00.toInt()
    const val WARN_BG = 0xFFFFF4E2.toInt()
    const val WARN_BORDER = 0xFFE8A13A.toInt()
    const val AMBER = 0xFFFFC24B.toInt()
    const val PURPLE = 0xFF6B35A8.toInt()
    const val PURPLE_BG = 0xFFF3ECFB.toInt()

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun shape(ctx: Context, color: Int, radius: Int, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radius).toFloat()
        if (strokeW > 0) setStroke(dp(ctx, strokeW), stroke)
    }

    fun ripple(d: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x22000000), d, null)

    private fun gap(ctx: Context, size: Int, vertical: Boolean) = GradientDrawable().apply {
        if (vertical) setSize(0, dp(ctx, size)) else setSize(dp(ctx, size), 0)
        setColor(Color.TRANSPARENT)
    }

    fun vbox(ctx: Context, gapDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) { dividerDrawable = gap(ctx, gapDp, true); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
    }

    fun hbox(ctx: Context, gapDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) { dividerDrawable = gap(ctx, gapDp, false); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
    }

    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, WRAP, w)
    fun full() = LinearLayout.LayoutParams(MATCH, WRAP)

    fun card(ctx: Context, gapDp: Int = 10) = vbox(ctx, gapDp).apply {
        background = shape(ctx, CARD, 16)
        elevation = dp(ctx, 1).toFloat()
        val p = dp(ctx, 16); setPadding(p, p, p, p)
    }

    /** 整页：可滚动，浅灰底，左右 20dp。 */
    fun page(ctx: Context, content: LinearLayout, bottomPad: Int = 24): ScrollView {
        content.setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, bottomPad))
        return ScrollView(ctx).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        }
    }

    fun text(ctx: Context, s: CharSequence, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    fun h1(ctx: Context, s: String) = text(ctx, s, 22f, bold = true)
    fun h2(ctx: Context, s: String) = text(ctx, s, 17f, bold = true)
    fun muted(ctx: Context, s: CharSequence, size: Float = 13f) = text(ctx, s, size, MUTED)

    private fun baseButton(ctx: Context, s: String, onClick: () -> Unit) = Button(ctx).apply {
        text = s
        isAllCaps = false
        stateListAnimator = null
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        setOnClickListener { onClick() }
    }

    fun primary(ctx: Context, s: String, onClick: () -> Unit) = baseButton(ctx, s, onClick).apply {
        background = ripple(shape(ctx, PRIMARY, 12))
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(ctx, 52))
    }

    fun ghost(ctx: Context, s: String, color: Int = TEXT, onClick: () -> Unit) = baseButton(ctx, s, onClick).apply {
        background = ripple(shape(ctx, SEG, 12))
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(ctx, 48))
    }

    fun chip(ctx: Context, s: String, on: Boolean = false, onClick: () -> Unit) = baseButton(ctx, s, onClick).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setChip(this, on)
    }

    fun setChip(b: Button, on: Boolean) {
        val ctx = b.context
        b.background = ripple(if (on) shape(ctx, PRIMARY_SOFT, 12, PRIMARY, 2) else shape(ctx, CARD, 12, BORDER, 1))
        b.setTextColor(if (on) PRIMARY else TEXT)
        b.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        b.isSelected = on
    }

    /** 等宽网格：每行 cols 个，最后一行不足的用空位补齐。 */
    fun grid(ctx: Context, cols: Int, views: List<View>, gapDp: Int = 8, heightDp: Int = 44): LinearLayout {
        val g = vbox(ctx, gapDp)
        for (rowViews in views.chunked(cols)) {
            val row = hbox(ctx, gapDp)
            for (v in rowViews) row.addView(v, LinearLayout.LayoutParams(0, if (heightDp > 0) dp(ctx, heightDp) else WRAP, 1f))
            repeat(cols - rowViews.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) }
            g.addView(row, full())
        }
        return g
    }

    fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int = 20) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
    }

    fun pill(ctx: Context, s: String, fg: Int, bg: Int) = text(ctx, s, 13f, fg).apply {
        background = shape(ctx, bg, 999)
        setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
    }

    fun tag(ctx: Context, s: String, fg: Int, bg: Int) = text(ctx, s, 12f, fg, bold = true).apply {
        background = shape(ctx, bg, 6)
        setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
    }

    /** 分段选择（如"课本 / 作业"）。 */
    fun segmented(ctx: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = hbox(ctx, 4).apply {
            background = shape(ctx, SEG, 12)
            val p = dp(ctx, 4); setPadding(p, p, p, p)
        }
        labels.forEachIndexed { i, s ->
            val b = baseButton(ctx, s) { onSelect(i) }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                if (i == selected) {
                    background = shape(ctx, CARD, 9); elevation = dp(ctx, 1).toFloat()
                    setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
                } else {
                    background = ripple(ColorDrawable(Color.TRANSPARENT)); setTextColor(MUTED)
                }
            }
            box.addView(b, LinearLayout.LayoutParams(0, dp(ctx, 44), 1f))
        }
        return box
    }

    fun input(ctx: Context, hint: String, value: String = "", number: Boolean = false, lines: Int = 1) = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(TEXT)
        setHintTextColor(0xFF8A93A6.toInt())
        background = shape(ctx, CARD, 10, BORDER, 1)
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        inputType = when {
            number -> InputType.TYPE_CLASS_NUMBER
            lines > 1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START }
    }

    /** 下拉选择框（点开弹出列表）。 */
    fun select(ctx: Context, value: String, onClick: () -> Unit) = hbox(ctx, 8).apply {
        background = ripple(shape(ctx, CARD, 10, BORDER, 1))
        setPadding(dp(ctx, 14), 0, dp(ctx, 12), 0)
        minimumHeight = dp(ctx, 48)
        isClickable = true
        setOnClickListener { onClick() }
        addView(text(ctx, value, 15f).apply { maxLines = 2 }, weight())
        addView(icon(ctx, R.drawable.ic_down, MUTED, 18))
    }

    /** 可点击的一行（文字 + 右箭头）。 */
    fun linkRow(ctx: Context, label: CharSequence, color: Int = TEXT, onClick: () -> Unit) = hbox(ctx, 8).apply {
        minimumHeight = dp(ctx, 44)
        background = ripple(ColorDrawable(Color.TRANSPARENT))
        isClickable = true
        setOnClickListener { onClick() }
        addView(text(ctx, label, 15f, color), weight())
        addView(icon(ctx, R.drawable.ic_chevron, MUTED, 18))
    }

    /** 从底部弹出的面板。 */
    fun bottomSheet(act: Activity, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = vbox(act, 16).apply {
            background = GradientDrawable().apply {
                setColor(CARD)
                val r = dp(act, 24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(act, 20), dp(act, 12), dp(act, 20), dp(act, 28))
        }
        box.addView(View(act).apply { background = shape(act, BORDER, 2) },
            LinearLayout.LayoutParams(dp(act, 40), dp(act, 4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        build(box, d)
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(MATCH, WRAP)
        }
        d.show()
        return d
    }

    /** 圆形勾 / 叉标记。 */
    fun mark(ctx: Context, ok: Boolean) = android.widget.FrameLayout(ctx).apply {
        background = shape(ctx, if (ok) OK_BG else ERR_BG, 14)
        addView(icon(ctx, if (ok) R.drawable.ic_check else R.drawable.ic_close, if (ok) OK else ERR, 16),
            android.widget.FrameLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28))
    }
}
