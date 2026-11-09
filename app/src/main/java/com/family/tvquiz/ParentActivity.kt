package com.family.tvquiz

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 家长页（测试版）：受管应用、守门开关、调试浮层、余额测试工具、流水查看。 */
class ParentActivity : Activity() {

    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        prefs = Prefs(this)
        body = Ui.column(this, 32)
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(body) })
        render()
    }

    private fun render() {
        body.removeAllViews()
        val b = db.balanceSeconds()
        val (n, ok) = db.todayAnswerStats()

        section("当前状态")
        val unlock = (prefs.unlockRemainingMs() / 1000).toInt()
        line(if (unlock > 0) "模式：家长模式（解锁中，还剩 ${fmt(unlock)}，不扣余额）" else "模式：答题余额模式")
        val modeRow = Ui.row(this).apply { gravity = android.view.Gravity.START }
        modeRow.addView(Ui.button(this, "解锁 2 小时", 18f) { prefs.unlockFor(120); render() }, Ui.margins(this, 8))
        modeRow.addView(Ui.button(this, "解锁 30 分钟", 18f) { prefs.unlockFor(30); render() }, Ui.margins(this, 8))
        modeRow.addView(Ui.button(this, "结束解锁", 18f) { prefs.unlockFor(0); render() }, Ui.margins(this, 8))
        body.addView(modeRow)
        line("余额 ${fmt(b)} · 今日获得 ${fmt(db.todayEarnedSeconds())} · 今日已看 ${fmt(db.todayWatchedSeconds())}")
        line("今日答题 $n 题，答对 $ok 题 · 错题 ${db.wrongCount()} 道")
        line("守门服务：" + (if (prefs.accessibilityOn()) "已开启" else "未开启（用 ADB 授权脚本开启）"))
        line("通知使用权：" + (if (prefs.notificationAccessOn()) "已开启（可用 A 级计时）" else "未开启（只能 B 级计时）"))

        section("家长手机配对")
        val ip = LanServer.localIp() ?: "未联网"
        val code = if (LanServer.pairCodeValid()) LanServer.pairCode else "—"
        line("电视地址：$ip:${LanServer.PORT}    配对码：$code（10 分钟内有效，用一次失效）", 20f)
        line("已配对手机：" + prefs.pairedDevices().values.joinToString("、").ifEmpty { "无" })
        val pairRow = Ui.row(this).apply { gravity = android.view.Gravity.START }
        pairRow.addView(Ui.button(this, "生成配对码", 18f) { LanServer.newPairCode(); render() }, Ui.margins(this, 8))
        pairRow.addView(Ui.button(this, "取消所有手机配对", 18f) { prefs.clearPaired(); render() }, Ui.margins(this, 8))
        body.addView(pairRow)

        section("开关")
        check("守门生效（关闭后不扣时、不拦截）", prefs.guardEnabled) { prefs.guardEnabled = it }
        check("显示调试浮层（会导致小米电视强行停止本应用，仅短时调试用）", prefs.overlayEnabled) { prefs.overlayEnabled = it }

        section("测试工具")
        val tools = Ui.row(this)
        tools.addView(Ui.button(this, "+20 分钟", 18f) {
            val g = db.earn(20 * 60, "parent_test", kind = "ADJUST"); Ui.toast(this, "增加 ${fmt(g)}"); render()
        }, Ui.margins(this, 8))
        tools.addView(Ui.button(this, "+1 分钟", 18f) {
            val g = db.earn(60, "parent_test", kind = "ADJUST"); Ui.toast(this, "增加 ${fmt(g)}"); render()
        }, Ui.margins(this, 8))
        tools.addView(Ui.button(this, "余额清零", 18f) { db.resetBalance(); render() }, Ui.margins(this, 8))
        tools.addView(Ui.button(this, "修改密码", 18f) { changePin() }, Ui.margins(this, 8))
        body.addView(tools)

        section("教材进度（题库：小学语数英题库收集）")
        val bookRow = Ui.row(this).apply { gravity = android.view.Gravity.START }
        bookRow.addView(Ui.text(this, "册次：", 18f))
        bookRow.addView(Ui.button(this, "${prefs.book}（按确认键切换）", 18f) {
            val i = Bank.BOOKS.indexOf(prefs.book)
            prefs.book = Bank.BOOKS[(i + 1) % Bank.BOOKS.size]
            render()
        }, Ui.margins(this, 6))
        body.addView(bookRow)
        val bookSubjects = Bank.subjects(this, prefs.book)
        val chosen = prefs.subjects.toMutableSet()
        for (sub in bookSubjects) {
            val units = Bank.units(this, prefs.book, sub)
            val cur = prefs.maxUnit(sub)
            val curName = units.lastOrNull { it.first <= cur }?.second ?: "全部"
            val count = Bank.all(this).count { it.book == prefs.book && it.q.subject == sub && it.unitIndex <= cur }
            check("$sub：学到「${if (cur == Int.MAX_VALUE) "全部单元" else curName}」，可出 $count 题", sub in chosen) { on ->
                if (on) chosen += sub else chosen -= sub
                prefs.subjects = chosen
            }
            val r = Ui.row(this).apply { gravity = android.view.Gravity.START }
            r.addView(Ui.button(this, "进度往前", 16f) {
                val idx = units.indexOfLast { it.first <= cur }
                if (cur == Int.MAX_VALUE) prefs.setMaxUnit(sub, units.last().first)
                else if (idx > 0) prefs.setMaxUnit(sub, units[idx - 1].first)
                render()
            }, Ui.margins(this, 6))
            r.addView(Ui.button(this, "进度往后", 16f) {
                val idx = units.indexOfLast { it.first <= cur }
                if (cur != Int.MAX_VALUE && idx + 1 < units.size) prefs.setMaxUnit(sub, units[idx + 1].first)
                else prefs.setMaxUnit(sub, Int.MAX_VALUE)
                render()
            }, Ui.margins(this, 6))
            r.addView(Ui.button(this, "全部单元", 16f) { prefs.setMaxUnit(sub, Int.MAX_VALUE); render() }, Ui.margins(this, 6))
            body.addView(r)
        }
        if (bookSubjects.isEmpty()) line("这一册题库里没有题，会用 20 以内加减法代替")
        val types = prefs.types.toMutableSet()
        for ((t, name) in Prefs.TYPE_NAMES) {
            check("题型：$name", t in types) { on -> if (on) types += t else types -= t; prefs.types = types }
        }
        line("拍照导入题：${db.customQuestionsJson().size} 道")

        section("受管应用（勾选后才计时和拦截）")
        val managed = prefs.managed.toMutableSet()
        for (app in Apps.launchable(this)) {
            val level = prefs.level(app.pkg)?.let { " · 实测 $it 级" } ?: ""
            check("${app.label}  (${app.pkg})$level", app.pkg in managed) { on ->
                if (on) managed += app.pkg else managed -= app.pkg
                prefs.managed = managed
            }
        }

        section("最近流水")
        val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        for (r in db.recentLedger(30)) {
            val sign = if (r.seconds >= 0) "+" else "−"
            line("${f.format(Date(r.ts))}  ${r.kind}  $sign${fmt(kotlin.math.abs(r.seconds))}  ${r.ref ?: ""}", 16f)
        }
    }

    private fun section(title: String) {
        body.addView(Ui.text(this, title, 24f, bold = true, color = Ui.ACCENT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@ParentActivity, 20) })
    }

    private fun line(s: String, size: Float = 18f) = body.addView(Ui.text(this, s, size))

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        body.addView(CheckBox(this).apply {
            text = label; textSize = 18f; setTextColor(Ui.FG); isChecked = value
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    private fun changePin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "新密码（4 位以上数字）"
        }
        AlertDialog.Builder(this).setTitle("修改家长密码").setView(input)
            .setPositiveButton("保存") { _, _ ->
                val p = input.text.toString()
                if (p.length >= 4) { prefs.pin = p; Ui.toast(this, "已保存") } else Ui.toast(this, "至少 4 位")
            }
            .setNegativeButton("取消", null).show()
    }
}
