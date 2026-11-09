package com.family.tvquiz

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

/**
 * 本地数据库。时间余额按流水记账：收入为正、消费为负，余额 = 全部流水之和。
 * 只存流水不存单个数字，这样断电时最多丢最后一次未写入的消费。
 */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "tvquiz.db", null, 2) {

    companion object {
        const val CAP_SECONDS = 60 * 60          // 余额上限 60 分钟
        const val REWARD_SECONDS = 60            // 每答对一题 1 分钟
        const val REWARD_INTERVAL_MS = 3L * 24 * 3600 * 1000  // 同一题 3 天内只奖励一次

        @Volatile private var instance: Db? = null
        fun get(ctx: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(ctx).also { instance = it } }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE ledger(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                kind TEXT NOT NULL,
                seconds INTEGER NOT NULL,
                ref TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE answers(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                qid TEXT NOT NULL,
                question TEXT NOT NULL,
                answer TEXT NOT NULL,
                correct INTEGER NOT NULL,
                rewarded INTEGER NOT NULL,
                hint INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_answers_qid ON answers(qid)")
        createCustom(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCustom(db)
    }

    /** 家长拍照导入、审核通过的题目。 */
    private fun createCustom(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS custom_questions(
                id TEXT PRIMARY KEY,
                created INTEGER NOT NULL,
                json TEXT NOT NULL)"""
        )
    }

    // ---------- 时间账户 ----------

    // PWATCH = 家长模式下的观看记录，只用于统计，不计入余额
    fun balanceSeconds(): Int =
        sumLong("SELECT COALESCE(SUM(seconds),0) FROM ledger WHERE kind!='PWATCH'").toInt()

    /** 增加时间，受 60 分钟上限限制。返回实际增加的秒数。 */
    @Synchronized
    fun earn(seconds: Int, ref: String, kind: String = "EARN"): Int {
        val granted = minOf(seconds, CAP_SECONDS - balanceSeconds()).coerceAtLeast(0)
        if (granted > 0) insertLedger(kind, granted, ref)
        return granted
    }

    /** 记录观看消费。不会把余额扣成负数。返回实际扣除的秒数。 */
    @Synchronized
    fun spend(seconds: Int, pkg: String): Int {
        val used = minOf(seconds, balanceSeconds()).coerceAtLeast(0)
        if (used > 0) insertLedger("SPEND", -used, pkg)
        return used
    }

    /** 测试用：清零余额。 */
    @Synchronized
    fun resetBalance() = setBalance(0, "parent_reset")

    /** 家长手动设定余额（0 到上限）。 */
    @Synchronized
    fun setBalance(seconds: Int, ref: String = "parent_set") {
        val target = seconds.coerceIn(0, CAP_SECONDS)
        val diff = target - balanceSeconds()
        if (diff != 0) insertLedger("ADJUST", diff, ref)
    }

    /** 家长模式观看记录（不影响余额）。 */
    @Synchronized
    fun parentWatch(seconds: Int, pkg: String) {
        if (seconds > 0) insertLedger("PWATCH", -seconds, pkg)
    }

    fun todayWatchedSeconds(): Int = sumLong(
        "SELECT COALESCE(-SUM(seconds),0) FROM ledger WHERE kind IN ('SPEND','PWATCH') AND ts>=?",
        startOfToday().toString()
    ).toInt()

    fun todayParentWatchSeconds(): Int = sumLong(
        "SELECT COALESCE(-SUM(seconds),0) FROM ledger WHERE kind='PWATCH' AND ts>=?",
        startOfToday().toString()
    ).toInt()

    fun todayEarnedSeconds(): Int = sumLong(
        "SELECT COALESCE(SUM(seconds),0) FROM ledger WHERE kind='EARN' AND ts>=?",
        startOfToday().toString()
    ).toInt()

    data class LedgerRow(val ts: Long, val kind: String, val seconds: Int, val ref: String?)

    fun recentLedger(limit: Int = 20): List<LedgerRow> {
        val out = ArrayList<LedgerRow>()
        readableDatabase.rawQuery(
            "SELECT ts,kind,seconds,ref FROM ledger ORDER BY id DESC LIMIT $limit", null
        ).use { c ->
            while (c.moveToNext()) out += LedgerRow(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3))
        }
        return out
    }

    private fun insertLedger(kind: String, seconds: Int, ref: String) {
        writableDatabase.insert("ledger", null, ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("kind", kind)
            put("seconds", seconds)
            put("ref", ref)
        })
    }

    // ---------- 答题记录 ----------

    /** 这道题最近 3 天内是否已经领过奖励。 */
    fun rewardedRecently(qid: String): Boolean = sumLong(
        "SELECT COUNT(*) FROM answers WHERE qid=? AND rewarded=1 AND ts>=?",
        qid, (System.currentTimeMillis() - REWARD_INTERVAL_MS).toString()
    ) > 0

    fun recentlyRewardedIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT qid FROM answers WHERE rewarded=1 AND ts>=?",
            arrayOf((System.currentTimeMillis() - REWARD_INTERVAL_MS).toString())
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun recordAnswer(qid: String, question: String, answer: String, correct: Boolean, rewarded: Boolean) {
        writableDatabase.insert("answers", null, ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("qid", qid)
            put("question", question)
            put("answer", answer)
            put("correct", if (correct) 1 else 0)
            put("rewarded", if (rewarded) 1 else 0)
        })
    }

    fun todayAnswerStats(): Pair<Int, Int> {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(correct),0) FROM answers WHERE ts>=?",
            arrayOf(startOfToday().toString())
        ).use { c -> c.moveToFirst(); return c.getInt(0) to c.getInt(1) }
    }

    data class AnswerRow(val ts: Long, val qid: String, val question: String, val answer: String, val correct: Boolean, val rewarded: Boolean)

    fun recentAnswers(limit: Int = 100): List<AnswerRow> {
        val out = ArrayList<AnswerRow>()
        readableDatabase.rawQuery(
            "SELECT ts,qid,question,answer,correct,rewarded FROM answers ORDER BY id DESC LIMIT $limit", null
        ).use { c ->
            while (c.moveToNext()) out += AnswerRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getInt(5) == 1)
        }
        return out
    }

    /** 错题：答错过的题，附答错次数和之后是否答对过。 */
    data class WrongRow(val qid: String, val question: String, val wrong: Int, val laterCorrect: Int, val lastTs: Long)

    fun wrongQuestions(limit: Int = 100): List<WrongRow> {
        val out = ArrayList<WrongRow>()
        readableDatabase.rawQuery(
            """SELECT qid, MAX(question), SUM(correct=0), SUM(correct=1), MAX(ts) FROM answers
               GROUP BY qid HAVING SUM(correct=0)>0 ORDER BY MAX(ts) DESC LIMIT $limit""", null
        ).use { c ->
            while (c.moveToNext()) out += WrongRow(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4))
        }
        return out
    }

    // ---------- 自建题库 ----------

    fun customQuestionsJson(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT json FROM custom_questions ORDER BY created", null)
            .use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun putCustomQuestion(id: String, json: String) {
        writableDatabase.insertWithOnConflict("custom_questions", null, ContentValues().apply {
            put("id", id); put("created", System.currentTimeMillis()); put("json", json)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteCustomQuestion(id: String): Boolean =
        writableDatabase.delete("custom_questions", "id=?", arrayOf(id)) > 0

    fun wrongCount(): Int = sumLong(
        "SELECT COUNT(DISTINCT qid) FROM answers WHERE correct=0"
    ).toInt()

    // ---------- 工具 ----------

    private fun sumLong(sql: String, vararg args: String): Long =
        readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
