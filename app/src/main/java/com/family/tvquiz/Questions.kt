package com.family.tvquiz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** 一道选择题。题库里的填空题在打包时已转换成选择题（见 tools/build_bank.py）。 */
data class Question(
    val id: String,
    val text: String,
    val answer: String,
    val choices: List<String>,
    val subject: String = "数学",
    val lesson: String = "",
    val explain: String? = null,
    val type: String = "choice",   // choice / fill / custom
)

/** 题库：app/src/main/assets/bank.json，来自"小学语数英题库收集"项目。 */
object Bank {
    data class Item(val q: Question, val book: String, val unitIndex: Int, val unit: String)

    @Volatile private var items: List<Item>? = null

    fun all(ctx: Context): List<Item> = items ?: synchronized(this) {
        items ?: load(ctx).also { items = it }
    }

    /** 提前在后台加载，避免第一次进答题页卡顿。 */
    fun preload(ctx: Context) {
        val app = ctx.applicationContext
        Thread { all(app) }.start()
    }

    private fun load(ctx: Context): List<Item> {
        val text = ctx.assets.open("bank.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) out += parse(arr.getJSONObject(i))
        return out
    }

    /** 题目 JSON 格式（题库和拍照导入题共用）：
     *  {id, sub, book, ui, u, l, t, q, a, o:[...], x?} */
    fun parse(o: JSONObject, typeOverride: String? = null): Item {
        val opts = o.getJSONArray("o")
        return Item(
            q = Question(
                id = o.getString("id"),
                text = o.getString("q"),
                answer = o.getString("a"),
                choices = List(opts.length()) { opts.getString(it) },
                subject = o.getString("sub"),
                lesson = o.optString("l"),
                explain = o.optString("x").ifEmpty { null },
                type = typeOverride ?: o.optString("t", "choice"),
            ),
            book = o.getString("book"),
            unitIndex = o.optInt("ui", 0),
            unit = o.optString("u"),
        )
    }

    /** 拍照导入并审核通过的题目（存在本地数据库）。 */
    fun custom(db: Db): List<Item> = db.customQuestionsJson().mapNotNull {
        try { parse(JSONObject(it), "custom") } catch (_: Exception) { null }
    }

    val BOOKS = listOf("一年级上册", "一年级下册", "二年级上册", "二年级下册", "三年级上册", "三年级下册")

    fun subjects(ctx: Context, book: String): List<String> =
        all(ctx).filter { it.book == book }.map { it.q.subject }.distinct()

    /** 某册某学科的单元列表（序号, 名称）。 */
    fun units(ctx: Context, book: String, subject: String): List<Pair<Int, String>> =
        all(ctx).filter { it.book == book && it.q.subject == subject }
            .map { it.unitIndex to it.unit }.distinct().sortedBy { it.first }
}

object Questions {

    /**
     * 生成一轮题目：按家长设置的册次、学科、各学科进度筛选，
     * 各学科轮流出题，尽量避开 3 天内已领过奖励的题。
     */
    fun round(ctx: Context, db: Db, count: Int = 10): List<Question> {
        val prefs = Prefs(ctx)
        val book = prefs.book
        val recent = db.recentlyRewardedIds()
        val types = prefs.types
        val bySubject = (Bank.all(ctx) + Bank.custom(db))
            .filter {
                it.book == book && it.q.subject in prefs.subjects && it.q.type in types &&
                    (it.q.type == "custom" || it.unitIndex <= prefs.maxUnit(it.q.subject))
            }
            .groupBy { it.q.subject }
            .mapValues { (_, list) ->
                val fresh = list.filter { it.q.id !in recent }.shuffled()
                val used = list.filter { it.q.id in recent }.shuffled()
                ArrayDeque((fresh + used).map { it.q })
            }
        if (bySubject.isEmpty()) return arithmetic(count)

        val out = ArrayList<Question>()
        val subjects = bySubject.keys.shuffled()
        var i = 0
        while (out.size < count && bySubject.values.any { it.isNotEmpty() }) {
            bySubject[subjects[i % subjects.size]]?.removeFirstOrNull()?.let { out += it }
            i++
        }
        return out
    }

    /** 题库为空时的后备题：20 以内加减法。 */
    private fun arithmetic(count: Int, rnd: Random = Random.Default): List<Question> {
        val out = LinkedHashMap<String, Question>()
        while (out.size < count) {
            val plus = rnd.nextBoolean()
            val a = if (plus) rnd.nextInt(0, 11) else rnd.nextInt(1, 21)
            val b = if (plus) rnd.nextInt(0, 21 - a) else rnd.nextInt(0, a + 1)
            val ans = if (plus) a + b else a - b
            val op = if (plus) "+" else "−"
            val choices = mutableSetOf(ans)
            while (choices.size < 4) (ans + rnd.nextInt(-3, 4)).takeIf { it in 0..20 }?.let { choices += it }
            val id = "arith:$a$op$b"
            out[id] = Question(id, "$a $op $b = ?", ans.toString(), choices.shuffled(rnd).map { it.toString() })
        }
        return out.values.toList()
    }
}
