package com.family.tvquiz.parent

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

/** 首页：大字余额、加时间、家长解锁、今天的情况。 */
class HomePage(act: MainActivity) : Page(act) {

    private val col = K.vbox(act, 14)
    override val view = K.page(act, col)

    private val connPill = K.pill(act, "连接中…", K.MUTED, K.SEG)
    private val heroLabel = K.text(act, "还可以看", 15f, Color.WHITE)
    private val modePill = K.pill(act, "答题余额模式", Color.WHITE, 0x2EFFFFFF)
    private val heroTime = K.text(act, "--:--", 60f, Color.WHITE, bold = true)
    private val heroBar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 3600
        progressTintList = ColorStateList.valueOf(K.AMBER)
        progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
    }
    private val heroCaption = K.text(act, "余额上限 60 分钟 · 答对一题加 1 分钟", 13f, 0xE6FFFFFF.toInt())

    private val unlockChips = ArrayList<Pair<Button, Int>>()
    private var unlockMinutes = 120
    private lateinit var unlockBtn: Button
    private lateinit var endUnlockBtn: Button

    private val statViews = List(4) { K.text(act, "-", 22f, bold = true) }
    private val wrongRow = K.linkRow(act, "错题 - 道") { act.openRecords(1) }
    private val warnCard = K.card(act, 6).apply { background = K.shape(act, K.WARN_BG, 16) }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 10_000) } }

    init { build() }

    private fun build() {
        // 标题行
        val top = K.hbox(act, 8)
        top.addView(K.h1(act, "答题看电视"), K.weight())
        top.addView(connPill)
        col.addView(top, K.full())

        // 大字余额
        val hero = K.vbox(act, 8).apply {
            background = K.shape(act, K.PRIMARY, 20)
            val p = K.dp(act, 20); setPadding(p, p, p, p)
        }
        val heroTop = K.hbox(act)
        heroTop.addView(heroLabel, K.weight()); heroTop.addView(modePill)
        hero.addView(heroTop, K.full())
        hero.addView(heroTime)
        hero.addView(heroBar, LinearLayout.LayoutParams(K.MATCH, K.dp(act, 8)))
        hero.addView(heroCaption)
        col.addView(hero, K.full())

        warnCard.visibility = android.view.View.GONE
        col.addView(warnCard, K.full())

        // 加时间
        val add = K.card(act)
        val addHead = K.hbox(act)
        addHead.addView(K.h2(act, "加时间"), K.weight())
        addHead.addView(K.text(act, "清零", 14f, K.PRIMARY).apply {
            setPadding(K.dp(act, 8), K.dp(act, 8), 0, K.dp(act, 8))
            setOnClickListener { Ui.confirm(act, "清零余额", "孩子需要重新答题才能看电视。") { balance(JSONObject().put("set_minutes", 0)) } }
        })
        add.addView(addHead, K.full())
        add.addView(K.grid(act, 3, listOf(
            K.chip(act, "+10 分钟") { balance(JSONObject().put("add_minutes", 10)) },
            K.chip(act, "+30 分钟") { balance(JSONObject().put("add_minutes", 30)) },
            K.chip(act, "设定…") { Ui.askNumber(act, "把余额设为多少分钟？", "0 到 60") { balance(JSONObject().put("set_minutes", it)) } },
        )), K.full())
        col.addView(add, K.full())

        // 家长解锁
        val unlock = K.card(act)
        val head = K.vbox(act, 2)
        head.addView(K.h2(act, "家长解锁"))
        head.addView(K.muted(act, "解锁期间直接看，不扣答题余额"))
        unlock.addView(head)
        val options = listOf("30 分" to 30, "1 小时" to 60, "2 小时" to 120, "自定义" to -1)
        val chips = options.map { (label, m) ->
            K.chip(act, label, m == unlockMinutes) {
                if (m > 0) selectUnlock(m)
                else Ui.askNumber(act, "解锁多少分钟？", "例如 45") { if (it > 0) selectUnlock(it) }
            }.also { unlockChips += it to m }
        }
        unlock.addView(K.grid(act, 4, chips), K.full())
        unlockBtn = K.primary(act, "") { doUnlock(unlockMinutes) }
        unlock.addView(unlockBtn)
        endUnlockBtn = K.ghost(act, "结束解锁") { doUnlock(0) }.apply { visibility = android.view.View.GONE }
        unlock.addView(endUnlockBtn)
        col.addView(unlock, K.full())
        selectUnlock(unlockMinutes)

        // 今天
        val today = K.card(act)
        today.addView(K.h2(act, "今天"))
        val labels = listOf("已看电视", "已答题", "答对", "答题赚到")
        val stats = statViews.mapIndexed { i, v ->
            K.vbox(act, 2).apply {
                background = K.shape(act, K.BG, 12)
                val p = K.dp(act, 12); setPadding(p, p, p, p)
                addView(v); addView(K.muted(act, labels[i]))
            }
        }
        today.addView(K.grid(act, 2, stats, heightDp = 0), K.full())
        today.addView(wrongRow, K.full())
        col.addView(today, K.full())

        // 设置
        val settings = K.card(act, 0)
        settings.addView(K.linkRow(act, "Claude API Key（拍照出题用）") { act.askApiKey {} }, K.full())
        settings.addView(K.linkRow(act, "断开这台电视", K.ERR) {
            Ui.confirm(act, "断开电视", "断开后需要在电视上重新生成配对码才能连接。") { store.forget(); act.backToConnect() }
        }, K.full())
        col.addView(settings, K.full())
    }

    private fun selectUnlock(m: Int) {
        unlockMinutes = m
        for ((b, v) in unlockChips) K.setChip(b, v == m || (v == -1 && m !in listOf(30, 60, 120)))
        unlockBtn.text = "解锁电视 " + if (m % 60 == 0) "${m / 60} 小时" else "$m 分钟"
    }

    override fun onShow() { handler.removeCallbacks(poll); handler.post(poll) }
    override fun onHide() { handler.removeCallbacks(poll) }

    private fun refresh() = act.task({ store.api().get("/api/status") }, fail = { e ->
        connPill.text = "连不上电视"; connPill.setTextColor(K.ERR); connPill.background = K.shape(act, K.ERR_BG, 999)
        if ((e as? ApiException)?.code == 401) act.handleError(e)
    }) { show(it) }

    private fun balance(body: JSONObject) = act.task({ store.api().post("/api/balance", body) }, fail = act::handleError) {
        show(it); Ui.toast(act, "已更新")
    }

    private fun doUnlock(minutes: Int) = act.task({ store.api().post("/api/unlock", JSONObject().put("minutes", minutes)) }, fail = act::handleError) {
        show(it); Ui.toast(act, if (minutes > 0) "已解锁" else "已结束解锁")
    }

    private fun show(s: JSONObject) {
        connPill.text = "● ${store.tvName ?: "电视"} 已连接"
        connPill.setTextColor(K.OK); connPill.background = K.shape(act, K.OK_BG, 999)

        val parent = s.optString("mode") == "parent"
        val bal = s.optInt("balance_seconds")
        if (parent) {
            val left = s.optInt("unlock_remaining_seconds")
            heroLabel.text = "家长解锁中，还剩"
            modePill.text = "家长模式"
            heroTime.text = clock(left)
            heroBar.progress = 0
            heroCaption.text = "解锁期间不扣答题余额 · 答题余额 ${clock(bal)}"
            endUnlockBtn.visibility = android.view.View.VISIBLE
        } else {
            heroLabel.text = "还可以看"
            modePill.text = "答题余额模式"
            heroTime.text = clock(bal)
            heroBar.progress = bal.coerceAtMost(3600)
            heroCaption.text = "余额上限 60 分钟 · 答对一题加 1 分钟"
            endUnlockBtn.visibility = android.view.View.GONE
        }

        statViews[0].text = "${s.optInt("today_watched_seconds") / 60} 分钟"
        statViews[1].text = "${s.optInt("today_answers")} 题"
        statViews[2].text = "${s.optInt("today_correct")} / ${s.optInt("today_answers")}"
        statViews[3].text = "${s.optInt("today_earned_seconds") / 60} 分钟"
        (wrongRow.getChildAt(0) as TextView).text = "错题 ${s.optInt("wrong_count")} 道"

        val warns = buildList {
            if (!s.optBoolean("guard_enabled")) add("守门已停用，电视不会扣时和拦截。")
            if (!s.optBoolean("accessibility_on")) add("电视的守门服务没开启，需要用电脑运行 grant.sh 重新授权。")
            if (!s.optBoolean("notification_access_on")) add("电视的通知使用权没开启，播放状态识别会变粗略。")
        }
        warnCard.removeAllViews()
        warnCard.visibility = if (warns.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        for (w in warns) {
            val row = K.hbox(act, 8).apply { gravity = Gravity.TOP }
            row.addView(K.icon(act, R.drawable.ic_warn, K.WARN, 18))
            row.addView(K.text(act, w, 14f, K.WARN), K.weight())
            warnCard.addView(row, K.full())
        }
    }

    private fun clock(sec: Int): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
}
