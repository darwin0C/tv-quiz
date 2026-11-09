package com.family.tvquiz

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * 局域网服务：家长手机 App 通过它查看状态、修改设置、导入题目。
 *
 * - 端口 8765，HTTP + JSON，只在家庭局域网内使用
 * - 通过 mDNS 广播 _tvquiz._tcp，手机可自动发现电视
 * - 配对：电视家长页显示 6 位配对码（10 分钟有效），手机提交后拿到令牌，之后每次请求带令牌
 *
 * 接口说明见 docs/lan-api.md。
 */
object LanServer {
    const val PORT = 8765
    private const val TAG = "TVQuiz"
    private const val SERVICE_TYPE = "_tvquiz._tcp."
    private const val MAX_BODY = 2 * 1024 * 1024
    private const val CODE_TTL_MS = 10 * 60_000L

    private val rnd = SecureRandom()
    private var server: ServerSocket? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile var pairCode: String = ""; private set
    @Volatile private var pairCodeAt = 0L

    fun newPairCode(): String {
        pairCode = "%06d".format(rnd.nextInt(1_000_000))
        pairCodeAt = System.currentTimeMillis()
        return pairCode
    }

    fun pairCodeValid(): Boolean = pairCode.isNotEmpty() && System.currentTimeMillis() - pairCodeAt < CODE_TTL_MS

    @Synchronized
    fun start(ctx: Context) {
        if (server != null) return
        val app = ctx.applicationContext
        try {
            val ss = ServerSocket(PORT)
            server = ss
            Thread({
                while (!ss.isClosed) {
                    try {
                        val s = ss.accept()
                        pool.execute { handle(app, s) }
                    } catch (e: Exception) {
                        if (!ss.isClosed) Log.w(TAG, "accept failed: $e")
                    }
                }
            }, "lan-server").start()
            registerNsd(app)
            Log.i(TAG, "LAN server on ${localIp() ?: "?"}:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "LAN server start failed", e)
            server = null
        }
    }

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) { null }

    private fun registerNsd(ctx: Context) {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "答题看电视-${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "NSD registered: ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD failed: $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsdRegistration = l }
        catch (e: Exception) { Log.w(TAG, "NSD register error: $e") }
    }

    // ---------- HTTP ----------

    private class Req(val method: String, val path: String, val query: Map<String, String>,
                      val headers: Map<String, String>, val body: String) {
        fun json(): JSONObject = if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private class HttpError(val code: Int, msg: String) : Exception(msg)

    private fun handle(ctx: Context, s: Socket) {
        s.use { sock ->
            sock.soTimeout = 15_000
            val out = sock.getOutputStream()
            val (code, body) = try {
                val req = read(BufferedInputStream(sock.getInputStream()))
                200 to route(ctx, req)
            } catch (e: HttpError) {
                e.code to JSONObject().put("error", e.message)
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
                400 to JSONObject().put("error", e.message ?: e.toString())
            }
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            val status = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found")[code] ?: "Error"
            out.write(("HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    private fun read(inp: BufferedInputStream): Req {
        fun line(): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = inp.read()
                if (c == -1 || c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
                if (b.size() > 8192) throw HttpError(400, "header too long")
            }
            return b.toString("UTF-8")
        }
        val first = line().split(' ')
        if (first.size < 2) throw HttpError(400, "bad request line")
        val headers = HashMap<String, String>()
        while (true) {
            val l = line()
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) throw HttpError(400, "body too large")
        val body = ByteArray(len)
        var n = 0
        while (n < len) { val r = inp.read(body, n, len - n); if (r < 0) break; n += r }
        val full = first[1]
        val q = full.substringAfter('?', "")
        val query = q.split('&').filter { it.contains('=') }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Req(first[0].uppercase(), full.substringBefore('?'), query, headers, String(body, 0, n, Charsets.UTF_8))
    }

    // ---------- 路由 ----------

    private fun route(ctx: Context, r: Req): JSONObject {
        val prefs = Prefs(ctx)
        val db = Db.get(ctx)

        // 不需要令牌的接口
        when ("${r.method} ${r.path}") {
            "GET /api/info" -> return JSONObject()
                .put("app", "tvquiz").put("version", 1).put("device", Build.MODEL)
                .put("pairing", pairCodeValid())
            "POST /api/pair" -> {
                val b = r.json()
                if (!pairCodeValid() || b.optString("code") != pairCode)
                    throw HttpError(403, "配对码不对或已过期，请在电视家长页重新生成")
                val token = ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                prefs.addPaired(token, b.optString("device", "手机").take(40))
                pairCode = ""  // 一次性
                Log.i(TAG, "paired: ${b.optString("device")}")
                return JSONObject().put("token", token).put("device", Build.MODEL)
            }
        }

        val token = r.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        if (token == null || token !in prefs.pairedDevices()) throw HttpError(401, "未配对或令牌失效")

        return when ("${r.method} ${r.path}") {
            "GET /api/status" -> status(ctx, prefs, db)

            "POST /api/balance" -> {
                val b = r.json()
                when {
                    b.has("set_minutes") -> db.setBalance((b.getDouble("set_minutes") * 60).toInt(), "phone_set")
                    b.has("add_minutes") -> {
                        val add = (b.getDouble("add_minutes") * 60).toInt()
                        db.setBalance(db.balanceSeconds() + add, "phone_add")
                    }
                    else -> throw HttpError(400, "需要 set_minutes 或 add_minutes")
                }
                status(ctx, prefs, db)
            }

            "POST /api/unlock" -> {
                prefs.unlockFor(r.json().optInt("minutes", 0).coerceIn(0, 24 * 60))
                status(ctx, prefs, db)
            }

            "GET /api/settings" -> settings(prefs)
            "PUT /api/settings" -> {
                val b = r.json()
                if (b.has("book")) {
                    val book = b.getString("book")
                    if (book !in Bank.BOOKS) throw HttpError(400, "未知册次")
                    prefs.book = book
                }
                if (b.has("subjects")) prefs.subjects = b.getJSONArray("subjects").strings().toSet()
                if (b.has("types")) prefs.types = b.getJSONArray("types").strings().filter { it in Prefs.ALL_TYPES }.toSet()
                if (b.has("max_unit")) {
                    val m = b.getJSONObject("max_unit")
                    for (k in m.keys()) prefs.setMaxUnit(k, if (m.isNull(k)) Int.MAX_VALUE else m.getInt(k))
                }
                if (b.has("guard_enabled")) prefs.guardEnabled = b.getBoolean("guard_enabled")
                settings(prefs)
            }

            "GET /api/catalog" -> catalog(ctx, db)

            "GET /api/apps" -> {
                val managed = prefs.managed
                JSONObject().put("apps", JSONArray(Apps.launchable(ctx).map {
                    JSONObject().put("pkg", it.pkg).put("label", it.label)
                        .put("managed", it.pkg in managed).put("level", prefs.level(it.pkg) ?: JSONObject.NULL)
                }))
            }
            "PUT /api/apps" -> {
                prefs.managed = r.json().getJSONArray("managed").strings().toSet()
                JSONObject().put("ok", true)
            }

            "GET /api/answers" -> {
                val limit = r.query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                JSONObject().put("answers", JSONArray(db.recentAnswers(limit).map {
                    JSONObject().put("ts", it.ts).put("qid", it.qid).put("question", it.question)
                        .put("answer", it.answer).put("correct", it.correct).put("rewarded", it.rewarded)
                }))
            }
            "GET /api/wrong" -> JSONObject().put("wrong", JSONArray(db.wrongQuestions().map {
                JSONObject().put("qid", it.qid).put("question", it.question).put("wrong", it.wrong)
                    .put("later_correct", it.laterCorrect).put("last_ts", it.lastTs)
            }))

            "GET /api/custom" -> JSONObject().put("questions", JSONArray(db.customQuestionsJson().map { JSONObject(it) }))
            "POST /api/custom" -> {
                val arr = r.json().getJSONArray("questions")
                var added = 0
                val errors = JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val err = validateCustom(o)
                    if (err != null) { errors.put("第 ${i + 1} 题：$err"); continue }
                    if (!o.has("id")) o.put("id", "custom-${System.currentTimeMillis()}-$i")
                    o.put("t", "custom")
                    db.putCustomQuestion(o.getString("id"), o.toString())
                    added++
                }
                JSONObject().put("added", added).put("errors", errors)
            }
            "DELETE /api/custom" -> {
                val id = r.query["id"] ?: throw HttpError(400, "需要 id")
                JSONObject().put("deleted", db.deleteCustomQuestion(id))
            }

            else -> throw HttpError(404, "没有这个接口：${r.method} ${r.path}")
        }
    }

    private fun validateCustom(o: JSONObject): String? {
        for (k in listOf("sub", "book", "q", "a", "o")) if (!o.has(k)) return "缺少 $k"
        if (o.getString("book") !in Bank.BOOKS) return "册次不对"
        val opts = o.getJSONArray("o").strings()
        if (opts.size !in 2..6) return "选项要 2 到 6 个"
        if (opts.toSet().size != opts.size) return "选项有重复"
        if (o.getString("a") !in opts) return "答案不在选项里"
        return null
    }

    private fun status(ctx: Context, prefs: Prefs, db: Db): JSONObject {
        val (n, ok) = db.todayAnswerStats()
        return JSONObject()
            .put("balance_seconds", db.balanceSeconds())
            .put("cap_seconds", Db.CAP_SECONDS)
            .put("today_watched_seconds", db.todayWatchedSeconds())
            .put("today_parent_watch_seconds", db.todayParentWatchSeconds())
            .put("today_earned_seconds", db.todayEarnedSeconds())
            .put("today_answers", n).put("today_correct", ok)
            .put("wrong_count", db.wrongCount())
            .put("mode", if (prefs.unlockRemainingMs() > 0) "parent" else "quiz")
            .put("unlock_remaining_seconds", prefs.unlockRemainingMs() / 1000)
            .put("guard_enabled", prefs.guardEnabled)
            .put("accessibility_on", prefs.accessibilityOn())
            .put("notification_access_on", prefs.notificationAccessOn())
            .put("custom_count", db.customQuestionsJson().size)
    }

    private fun settings(prefs: Prefs): JSONObject {
        val max = JSONObject()
        for (sub in listOf("语文", "数学", "英语")) {
            val v = prefs.maxUnit(sub)
            max.put(sub, if (v == Int.MAX_VALUE) JSONObject.NULL else v)
        }
        return JSONObject()
            .put("book", prefs.book)
            .put("subjects", JSONArray(prefs.subjects.toList()))
            .put("types", JSONArray(prefs.types.toList()))
            .put("max_unit", max)
            .put("guard_enabled", prefs.guardEnabled)
    }

    private fun catalog(ctx: Context, db: Db): JSONObject {
        val all = Bank.all(ctx)
        val books = JSONArray()
        for (book in Bank.BOOKS) {
            val subs = JSONArray()
            for (sub in all.filter { it.book == book }.map { it.q.subject }.distinct()) {
                val units = JSONArray()
                all.filter { it.book == book && it.q.subject == sub }
                    .groupBy { it.unitIndex to it.unit }.toSortedMap(compareBy { it.first })
                    .forEach { (k, v) ->
                        units.put(JSONObject().put("ui", k.first).put("name", k.second).put("count", v.size)
                            .put("choice", v.count { it.q.type == "choice" }).put("fill", v.count { it.q.type == "fill" }))
                    }
                subs.put(JSONObject().put("subject", sub).put("units", units))
            }
            books.put(JSONObject().put("book", book).put("subjects", subs))
        }
        return JSONObject().put("books", books)
            .put("types", JSONObject(Prefs.TYPE_NAMES))
            .put("custom_count", db.customQuestionsJson().size)
    }

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
}
