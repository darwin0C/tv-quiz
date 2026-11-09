package com.family.tvquiz

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** 孩子端首页：显示"还可以看多久"，两个主入口。 */
class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private lateinit var balanceView: TextView
    private lateinit var infoView: TextView
    private lateinit var warnView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        prefs = Prefs(this)
        Bank.preload(this)
        LanServer.start(this)

        val root = Ui.column(this).apply { gravity = Gravity.CENTER }
        root.addView(Ui.text(this, "还可以看", 30f).apply { gravity = Gravity.CENTER })
        balanceView = Ui.text(this, "", 72f, bold = true, color = Ui.ACCENT).apply { gravity = Gravity.CENTER }
        root.addView(balanceView)
        infoView = Ui.text(this, "", 20f).apply { gravity = Gravity.CENTER }
        root.addView(infoView)

        val buttons = Ui.row(this)
        val quiz = Ui.button(this, "答题赚时间", 32f) { startActivity(Intent(this, QuizActivity::class.java)) }
        buttons.addView(quiz, Ui.margins(this, 20))
        buttons.addView(Ui.button(this, "开始看电视", 32f) { pickApp() }, Ui.margins(this, 20))
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 24) })

        warnView = Ui.text(this, "", 18f, color = 0xFFFF8A80.toInt()).apply { gravity = Gravity.CENTER }
        root.addView(warnView)

        root.addView(Ui.button(this, "家长", 18f) {
            Ui.askPin(this) { startActivity(Intent(this, ParentActivity::class.java)) }
        }, Ui.margins(this, 24).apply { gravity = Gravity.END })

        setContentView(root)
        quiz.requestFocus()
    }

    override fun onResume() { super.onResume(); handler.post(refresher) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresher) }

    private fun refresh() {
        val b = db.balanceSeconds()
        val unlock = (prefs.unlockRemainingMs() / 1000).toInt()
        balanceView.text = if (unlock > 0) "家长解锁中 ${fmt(unlock)}" else "${b / 60} 分 ${b % 60} 秒"
        val (n, ok) = db.todayAnswerStats()
        infoView.text = "今天答了 $n 题，答对 $ok 题 · 今天已看 ${db.todayWatchedSeconds() / 60} 分钟" +
            if (b >= Db.CAP_SECONDS) "\n余额已满 60 分钟，先去看吧" else ""
        val warns = mutableListOf<String>()
        if (!prefs.accessibilityOn()) warns += "守门服务未开启"
        if (!prefs.notificationAccessOn()) warns += "通知使用权未开启（只能用 B 级计时）"
        if (prefs.managed.isEmpty()) warns += "家长还没选择受管应用"
        if (!prefs.guardEnabled) warns += "家长已停用守门"
        warnView.text = warns.joinToString("  ·  ")
    }

    private fun pickApp() {
        if (db.balanceSeconds() <= 0 && prefs.unlockRemainingMs() <= 0) {
            Ui.toast(this, "时间用完了，先答题赚时间吧")
            startActivity(Intent(this, QuizActivity::class.java))
            return
        }
        val apps = Apps.launchable(this).filter { it.pkg in prefs.managed }
        if (apps.isEmpty()) { Ui.toast(this, "家长还没选择受管应用"); return }
        AlertDialog.Builder(this)
            .setTitle("看哪个？")
            .setItems(apps.map { it.label }.toTypedArray()) { _, i -> Apps.launch(this, apps[i].pkg) }
            .show()
    }
}
