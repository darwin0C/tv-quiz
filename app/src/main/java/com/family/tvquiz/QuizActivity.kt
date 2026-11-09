package com.family.tvquiz

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 答题页。每答对一题 +1 分钟（余额上限 60 分钟）。
 * 答错不加不扣，记入答题记录。同一题 3 天内只奖励一次；一题只能提交一次。
 */
class QuizActivity : Activity() {

    companion object { const val EXTRA_TIME_UP = "time_up" }

    private lateinit var db: Db
    private lateinit var questions: List<Question>
    private var index = 0
    private var correctCount = 0
    private var earnedSeconds = 0

    private lateinit var header: TextView
    private lateinit var questionView: TextView
    private lateinit var choiceBox: LinearLayout
    private lateinit var feedback: TextView
    private lateinit var nextBtn: Button
    private val choiceButtons = ArrayList<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        questions = Questions.round(this, db)

        val root = Ui.column(this, 32).apply { gravity = Gravity.CENTER_HORIZONTAL }
        if (intent.getBooleanExtra(EXTRA_TIME_UP, false)) {
            root.addView(Ui.text(this, "时间用完了，答题赚时间吧", 28f, bold = true, color = Ui.ACCENT))
        }
        header = Ui.text(this, "", 20f)
        root.addView(header)
        questionView = Ui.text(this, "", 56f, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(questionView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@QuizActivity, 12) })
        choiceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        root.addView(choiceBox, LinearLayout.LayoutParams(-1, -2))
        feedback = Ui.text(this, "", 26f).apply { gravity = Gravity.CENTER }
        root.addView(feedback, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@QuizActivity, 8) })

        val bottom = Ui.row(this)
        nextBtn = Ui.button(this, "下一题") { show() }
        bottom.addView(nextBtn, Ui.margins(this))
        bottom.addView(Ui.button(this, "去看电视", 22f) { finishToHome() }, Ui.margins(this))
        bottom.addView(Ui.button(this, "家长解锁", 18f) {
            Ui.askPin(this) { startActivity(Intent(this, ParentActivity::class.java)) }
        }, Ui.margins(this))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        show()
    }

    private fun show() {
        updateHeader()
        feedback.text = ""
        nextBtn.visibility = Button.INVISIBLE
        choiceBox.removeAllViews()
        choiceButtons.clear()
        if (index >= questions.size) {
            questionView.textSize = 56f
            questionView.text = "本轮完成"
            feedback.setTextColor(Ui.FG)
            feedback.text = "答对 $correctCount 题，赚到 ${fmt(earnedSeconds)}"
            val again = Ui.button(this, "再来一轮", 30f) {
                questions = Questions.round(this, db); index = 0; correctCount = 0; earnedSeconds = 0; show()
            }
            choiceBox.addView(again, Ui.margins(this, 16))
            again.post { again.requestFocus() }
            return
        }
        val q = questions[index]
        questionView.textSize = when {
            q.text.length > 24 -> 34f
            q.text.length > 12 -> 44f
            else -> 60f
        }
        questionView.text = q.text

        // 选项 2×2 排列；按最长选项调整字号
        val longest = q.choices.maxOf { it.length }
        val size = when { longest <= 4 -> 40f; longest <= 8 -> 30f; else -> 24f }
        q.choices.chunked(2).forEach { pair ->
            val row = Ui.row(this)
            pair.forEach { c ->
                val b = Ui.button(this, c, size) { submit(q, c) }
                b.minWidth = Ui.dp(this, 200)
                row.addView(b, Ui.margins(this, 10))
                choiceButtons += b
            }
            choiceBox.addView(row)
        }
        choiceButtons.first().post { choiceButtons.first().requestFocus() }
    }

    private fun submit(q: Question, chosen: String) {
        // 锁定选项，防止同一题重复提交
        choiceButtons.forEach { it.isEnabled = false }

        val correct = chosen == q.answer
        var granted = 0
        if (correct) {
            correctCount++
            if (!db.rewardedRecently(q.id)) granted = db.earn(Db.REWARD_SECONDS, "q:${q.id}")
        }
        db.recordAnswer(q.id, q.text, chosen, correct, granted > 0)
        earnedSeconds += granted

        feedback.setTextColor(if (correct) 0xFF8BC34A.toInt() else 0xFFFF8A80.toInt())
        feedback.text = when {
            !correct -> "再想想哦，正确答案是「${q.answer}」（已记入错题）" + (q.explain?.let { "\n$it" } ?: "")
            granted >= Db.REWARD_SECONDS -> "答对了！增加 1 分钟"
            granted > 0 -> "答对了！增加 $granted 秒，余额已满 60 分钟"
            db.balanceSeconds() >= Db.CAP_SECONDS -> "答对了！余额已满 60 分钟，这题不再加时间"
            else -> "答对了！这题最近已经奖励过，不重复加时间"
        }
        updateHeader()
        index++
        nextBtn.text = if (index >= questions.size) "看结果" else "下一题"
        nextBtn.visibility = Button.VISIBLE
        nextBtn.requestFocus()
    }

    private fun updateHeader() {
        val b = db.balanceSeconds()
        val q = questions.getOrNull(minOf(index, questions.size - 1))
        val where = q?.let { "${it.subject} · ${it.lesson} · " } ?: ""
        header.text = "${where}第 ${minOf(index + 1, questions.size)}/${questions.size} 题 · 余额 ${fmt(b)}" +
            if (b >= Db.CAP_SECONDS) "（已满）" else ""
    }

    private fun finishToHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = finishToHome()
}
