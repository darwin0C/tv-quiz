package com.family.tvquiz.parent

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import org.json.JSONArray
import org.json.JSONObject

/** 学习设置：册次、每个学科学到哪个单元、题型。 */
class LearnPage(act: MainActivity) : Page(act) {

    private val col = K.vbox(act, 14)
    override val view = K.page(act, col)

    private var catalog: JSONObject? = null
    private var book = ""
    private val subjects = HashSet<String>()
    private val maxUnit = HashMap<String, Int?>()     // null = 全部单元
    private val types = HashSet<String>()
    private var savedBook = ""

    override fun onShow() {
        if (catalog != null) return
        col.removeAllViews()
        col.addView(K.h1(act, "学习设置"))
        col.addView(K.muted(act, "读取中…", 15f))
        act.task({ store.api().get("/api/catalog") to store.api().get("/api/settings") }, fail = act::handleError) { (c, s) ->
            catalog = c
            book = s.optString("book"); savedBook = book
            subjects.clear(); subjects += s.getJSONArray("subjects").strings()
            types.clear(); types += s.getJSONArray("types").strings()
            maxUnit.clear()
            val m = s.optJSONObject("max_unit") ?: JSONObject()
            for (k in m.keys()) maxUnit[k] = if (m.isNull(k)) null else m.getInt(k)
            render()
        }
    }

    private fun bookObj(): JSONObject? {
        val books = catalog?.getJSONArray("books") ?: return null
        for (i in 0 until books.length()) if (books.getJSONObject(i).getString("book") == book) return books.getJSONObject(i)
        return null
    }

    private fun render() {
        val c = catalog ?: return
        val keepY = view.scrollY
        view.post { view.scrollTo(0, keepY) }
        col.removeAllViews()
        col.addView(K.h1(act, "学习设置"))

        // 册次
        val bookCard = K.card(act)
        bookCard.addView(K.text(act, "当前册次", 15f, K.MUTED, bold = true))
        val books = c.getJSONArray("books").let { a -> List(a.length()) { a.getJSONObject(it).getString("book") } }
        bookCard.addView(K.grid(act, 3, books.map { b ->
            K.chip(act, b.removeSuffix("册"), b == book) {
                if (b != book) {
                    book = b
                    if (book != savedBook) maxUnit.clear()  // 进度按册次分别保存
                    render()
                }
            }
        }), K.full())
        col.addView(bookCard, K.full())

        // 学科
        val subs = bookObj()?.getJSONArray("subjects") ?: JSONArray()
        if (subs.length() == 0) col.addView(K.card(act).apply { addView(K.text(act, "这一册题库里没有题，电视会用 20 以内加减法代替。", 14f, K.WARN)) }, K.full())
        for (i in 0 until subs.length()) col.addView(subjectCard(subs.getJSONObject(i)), K.full())

        // 题型
        val typeCard = K.card(act)
        typeCard.addView(K.text(act, "题型", 15f, K.MUTED, bold = true))
        val names = c.getJSONObject("types")
        val flow = K.vbox(act, 8)
        for (k in names.keys()) {
            val label = names.getString(k) + if (k == "custom") " · ${c.optInt("custom_count")}" else ""
            flow.addView(K.chip(act, label, k in types) {
                if (k in types) types -= k else types += k
                render()
            }, LinearLayout.LayoutParams(K.MATCH, K.dp(act, 44)))
        }
        typeCard.addView(flow, K.full())
        col.addView(typeCard, K.full())

        col.addView(K.primary(act, "保存到电视") { save() })
    }

    private fun subjectCard(s: JSONObject): LinearLayout {
        val name = s.getString("subject")
        val units = s.getJSONArray("units").let { a -> List(a.length()) { a.getJSONObject(it) } }
        val cur = maxUnit[name]
        val curIdx = if (cur == null) units.size - 1 else units.indexOfLast { it.getInt("ui") <= cur }
        val count = units.filterIndexed { i, _ -> i <= curIdx }.sumOf { it.getInt("count") }
        val on = name in subjects

        val card = K.card(act, 4)
        val head = K.hbox(act, 8)
        val titles = K.vbox(act, 2)
        titles.addView(K.h2(act, name))
        titles.addView(K.muted(act, if (!on) "不参与出题"
            else if (cur == null) "全部单元，可出 $count 题"
            else "学到「${units.getOrNull(curIdx)?.getString("name") ?: "-"}」，可出 $count 题"))
        head.addView(titles, K.weight())
        head.addView(Switch(act).apply {
            isChecked = on
            contentDescription = "$name 出题"
            setOnCheckedChangeListener { _, v -> if (v) subjects += name else subjects -= name; render() }
        })
        card.addView(head, K.full())
        if (!on) return card

        units.forEachIndexed { i, u ->
            val done = i <= curIdx
            val isCur = i == curIdx && cur != null
            card.addView(stepRow(u.getString("name") + if (isCur) "（学到这里）" else "", done, isCur) {
                maxUnit[name] = u.getInt("ui"); render()
            }, K.full())
        }
        card.addView(stepRow("不限，全部单元都出题", cur == null, cur == null, dot = false) {
            maxUnit[name] = null; render()
        }, K.full())
        return card
    }

    private fun stepRow(label: String, done: Boolean, current: Boolean, dot: Boolean = true, onClick: () -> Unit) =
        K.hbox(act, 10).apply {
            minimumHeight = K.dp(act, 40)
            isClickable = true
            background = K.ripple(android.graphics.drawable.ColorDrawable(0))
            setOnClickListener { onClick() }
            val d = FrameLayout(act)
            if (dot) {
                if (done) {
                    d.background = K.shape(act, K.PRIMARY, 10)
                    d.addView(K.icon(act, R.drawable.ic_check, android.graphics.Color.WHITE, 12),
                        FrameLayout.LayoutParams(K.dp(act, 12), K.dp(act, 12), Gravity.CENTER))
                } else d.background = K.shape(act, 0, 10, 0xFFC9D0DE.toInt(), 2)
            }
            addView(d, LinearLayout.LayoutParams(K.dp(act, 20), K.dp(act, 20)))
            addView(K.text(act, label, 14f, if (current) K.PRIMARY else if (done) K.TEXT else K.MUTED, bold = current), K.weight())
        }

    private fun save() {
        if (types.isEmpty()) { Ui.toast(act, "至少选一种题型"); return }
        val max = JSONObject()
        val subs = bookObj()?.getJSONArray("subjects") ?: JSONArray()
        for (i in 0 until subs.length()) {
            val n = subs.getJSONObject(i).getString("subject")
            max.put(n, maxUnit[n] ?: JSONObject.NULL)
        }
        val body = JSONObject().put("book", book).put("subjects", JSONArray(subjects.toList()))
            .put("types", JSONArray(types.toList())).put("max_unit", max)
        act.task({ store.api().put("/api/settings", body) }, fail = act::handleError) {
            savedBook = book
            Ui.toast(act, "已保存，下一轮答题生效")
        }
    }

    /** 下次进入时重新从电视读取。 */
    fun invalidate() { catalog = null }
}

fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
