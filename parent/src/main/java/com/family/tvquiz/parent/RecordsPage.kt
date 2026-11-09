package com.family.tvquiz.parent

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 记录：答题 / 错题 / 导入题。 */
class RecordsPage(act: MainActivity) : Page(act) {

    var segment = 0
    private val col = K.vbox(act, 14)
    override val view = K.page(act, col)
    private var wrongCount = -1
    private var customCount = -1
    private val hm = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val mdhm = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onShow() = load()

    private fun header() {
        col.removeAllViews()
        col.addView(K.h1(act, "记录"))
        val labels = listOf("答题",
            "错题" + if (wrongCount >= 0) " · $wrongCount" else "",
            "导入题" + if (customCount >= 0) " · $customCount" else "")
        col.addView(K.segmented(act, labels, segment) { segment = it; load() }, K.full())
    }

    private fun load() {
        header()
        col.addView(K.muted(act, "读取中…", 15f))
        val seg = segment
        act.task({
            val status = store.api().get("/api/status")
            val data: JSONArray = when (seg) {
                0 -> store.api().get("/api/answers?limit=200").getJSONArray("answers")
                1 -> store.api().get("/api/wrong").getJSONArray("wrong")
                else -> store.api().get("/api/custom").getJSONArray("questions")
            }
            status to data
        }, fail = act::handleError) { (status, data) ->
            if (seg != segment) return@task
            wrongCount = status.optInt("wrong_count"); customCount = status.optInt("custom_count")
            header()
            when (seg) {
                0 -> answers(data)
                1 -> wrong(data)
                else -> custom(data)
            }
        }
    }

    private fun when_(ts: Long): String {
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        return if (ts >= today) hm.format(Date(ts)) else mdhm.format(Date(ts))
    }

    private fun list(): LinearLayout = K.vbox(act).apply {
        background = K.shape(act, K.CARD, 16)
        elevation = K.dp(act, 1).toFloat()
        clipToOutline = true
    }

    private fun item(ok: Boolean?, title: String, meta: String, trailing: android.view.View? = null) = K.hbox(act, 12).apply {
        gravity = Gravity.TOP
        setPadding(K.dp(act, 16), K.dp(act, 14), K.dp(act, 16), K.dp(act, 14))
        if (ok != null) addView(K.mark(act, ok))
        val t = K.vbox(act, 2)
        t.addView(K.text(act, title, 15f))
        t.addView(K.muted(act, meta))
        addView(t, K.weight())
        if (trailing != null) addView(trailing)
    }

    private fun divider() = android.view.View(act).apply { setBackgroundColor(K.LINE) }

    private fun empty(s: String) = col.addView(K.card(act).apply { addView(K.muted(act, s, 15f)) }, K.full())

    private fun answers(a: JSONArray) {
        if (a.length() == 0) return empty("还没有答题记录")
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val rows = List(a.length()) { a.getJSONObject(it) }
        val t = rows.filter { it.getLong("ts") >= today }
        col.addView(K.text(act, "今天 · 答 ${t.size} 题，对 ${t.count { it.getBoolean("correct") }} 题", 13f, K.MUTED, bold = true))
        val box = list()
        rows.forEachIndexed { i, r ->
            if (i > 0) box.addView(divider(), LinearLayout.LayoutParams(K.MATCH, 1))
            val ok = r.getBoolean("correct")
            val tail = if (ok) (if (r.getBoolean("rewarded")) "+1 分钟" else "未加时间") else "已记入错题"
            box.addView(item(ok, r.getString("question"), "答 ${r.getString("answer")} · ${when_(r.getLong("ts"))} · $tail"), K.full())
        }
        col.addView(box, K.full())
    }

    private fun wrong(a: JSONArray) {
        col.addView(K.muted(act, "「之后答对」是错后又做对的次数，可作为掌握度参考。", 14f))
        if (a.length() == 0) return empty("还没有错题")
        val box = list()
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            if (i > 0) box.addView(divider(), LinearLayout.LayoutParams(K.MATCH, 1))
            val fixed = r.getInt("later_correct") > 0
            box.addView(item(fixed, r.getString("question"),
                "错 ${r.getInt("wrong")} 次 · 之后答对 ${r.getInt("later_correct")} 次 · 最近 ${when_(r.getLong("last_ts"))}"), K.full())
        }
        col.addView(box, K.full())
    }

    private fun custom(a: JSONArray) {
        if (a.length() == 0) return empty("还没有拍照导入的题目。去「出题」拍一张试试。")
        val box = list()
        for (i in a.length() - 1 downTo 0) {
            val q = a.getJSONObject(i)
            if (i < a.length() - 1) box.addView(divider(), LinearLayout.LayoutParams(K.MATCH, 1))
            val del = FrameLayout(act).apply {
                isClickable = true
                contentDescription = "删除这道题"
                background = K.ripple(android.graphics.drawable.ColorDrawable(0))
                setOnClickListener { delete(q) }
                addView(K.icon(act, R.drawable.ic_trash, K.MUTED, 20), FrameLayout.LayoutParams(K.dp(act, 20), K.dp(act, 20), Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(K.dp(act, 44), K.dp(act, 44))
            }
            box.addView(item(null, q.getString("q"),
                "${q.getString("book")} · ${q.getString("sub")} · 答案 ${q.getString("a")}", del), K.full())
        }
        col.addView(box, K.full())
    }

    private fun delete(q: JSONObject) {
        Ui.confirm(act, "删除这道题？", q.getString("q"), "删除") {
            act.task({ store.api().delete("/api/custom?id=" + java.net.URLEncoder.encode(q.getString("id"), "UTF-8")) }, fail = act::handleError) {
                act.learn.invalidate(); load()
            }
        }
    }
}
