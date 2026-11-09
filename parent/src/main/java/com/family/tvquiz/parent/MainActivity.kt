package com.family.tvquiz.parent

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 一个标签页。 */
abstract class Page(val act: MainActivity) {
    abstract val view: View
    open fun onShow() {}
    open fun onHide() {}
    val store get() = act.store
}

/** 主界面：底部导航「首页 / 学习 / 出题 / 记录」。 */
class MainActivity : Activity() {

    lateinit var store: Store
    private lateinit var content: FrameLayout
    private val navItems = ArrayList<Pair<ImageView, TextView>>()
    private val pages = arrayOfNulls<Page>(4)
    private var current = -1

    val home get() = page(0) as HomePage
    val learn get() = page(1) as LearnPage
    val import get() = page(2) as ImportPage
    val records get() = page(3) as RecordsPage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (store.token == null) { backToConnect(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(K.BG) }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(K.MATCH, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(0xFFE3E7EF.toInt()) }, LinearLayout.LayoutParams(K.MATCH, 1))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(K.CARD) }
        listOf(
            R.drawable.ic_home to "首页", R.drawable.ic_book to "学习",
            R.drawable.ic_camera to "出题", R.drawable.ic_list to "记录",
        ).forEachIndexed { i, (icon, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = K.ripple(android.graphics.drawable.ColorDrawable(0))
                isClickable = true
                contentDescription = label
                setOnClickListener { show(i) }
            }
            val iv = K.icon(this, icon, K.MUTED, 24)
            val tv = K.text(this, label, 12f, K.MUTED).apply { gravity = Gravity.CENTER }
            item.addView(iv); item.addView(tv)
            navItems += iv to tv
            nav.addView(item, LinearLayout.LayoutParams(0, K.dp(this, 64), 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(K.MATCH, K.WRAP))
        setContentView(root)
        show(savedInstanceState?.getInt("tab") ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    private fun page(i: Int): Page = pages[i] ?: when (i) {
        0 -> HomePage(this)
        1 -> LearnPage(this)
        2 -> ImportPage(this)
        else -> RecordsPage(this)
    }.also { pages[i] = it }

    fun show(i: Int) {
        if (i == current) { page(i).onShow(); return }
        if (current >= 0) pages[current]?.onHide()
        current = i
        content.removeAllViews()
        content.addView(page(i).view, FrameLayout.LayoutParams(K.MATCH, K.MATCH))
        navItems.forEachIndexed { j, (iv, tv) ->
            val c = if (j == i) K.PRIMARY else K.MUTED
            iv.imageTintList = android.content.res.ColorStateList.valueOf(c)
            tv.setTextColor(c)
            tv.typeface = if (j == i) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        page(i).onShow()
    }

    fun openRecords(segment: Int) {
        records.segment = segment
        show(3)
    }

    override fun onResume() {
        super.onResume()
        if (current >= 0) pages[current]?.onShow()
    }

    override fun onPause() {
        super.onPause()
        if (current >= 0) pages[current]?.onHide()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        import.onResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current == 2 && import.onBack()) return
        if (current != 0) { show(0); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    fun backToConnect() {
        startActivity(Intent(this, ConnectActivity::class.java))
        finish()
    }

    /** 电视返回 401：配对已被取消。 */
    fun handleError(e: Exception) {
        if ((e as? ApiException)?.code == 401) {
            Ui.confirm(this, "需要重新配对", "电视上已取消这台手机的配对。", "去配对") { store.forget(); backToConnect() }
        } else {
            Ui.alert(this, "出错了", e.message ?: e.toString())
        }
    }

    fun askApiKey(then: () -> Unit) {
        val e = K.input(this, "sk-ant-...", store.apiKey ?: "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val wrap = FrameLayout(this).apply { setPadding(K.dp(this@MainActivity, 20), K.dp(this@MainActivity, 8), K.dp(this@MainActivity, 20), 0); addView(e) }
        android.app.AlertDialog.Builder(this).setTitle("Claude API Key")
            .setMessage("只保存在这台手机上，用于拍照出题。可在 console.anthropic.com 创建。")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val k = e.text.toString().trim()
                store.apiKey = k.ifEmpty { null }
                if (k.isNotEmpty()) then()
            }
            .setNegativeButton("取消", null).show()
    }
}
