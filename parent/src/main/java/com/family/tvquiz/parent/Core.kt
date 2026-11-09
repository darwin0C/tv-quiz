package com.family.tvquiz.parent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 本机保存的连接信息和 Claude API Key。 */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("parent", Context.MODE_PRIVATE)
    var host: String? get() = sp.getString("host", null); set(v) = sp.edit().putString("host", v).apply()
    var token: String? get() = sp.getString("token", null); set(v) = sp.edit().putString("token", v).apply()
    var tvName: String? get() = sp.getString("tv", null); set(v) = sp.edit().putString("tv", v).apply()
    var apiKey: String? get() = sp.getString("api_key", null); set(v) = sp.edit().putString("api_key", v).apply()
    fun api() = Api(host ?: "", token)
    fun forget() = sp.edit().remove("host").remove("token").remove("tv").apply()
}

class ApiException(msg: String, val code: Int = 0) : Exception(msg)

/** 电视局域网接口客户端（见 docs/lan-api.md）。 */
class Api(private val host: String, private val token: String?) {
    companion object { const val PORT = 8765 }

    fun get(path: String) = call("GET", path, null)
    fun post(path: String, body: JSONObject) = call("POST", path, body)
    fun put(path: String, body: JSONObject) = call("PUT", path, body)
    fun delete(path: String) = call("DELETE", path, null)

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        val c = URL("http://$host:$PORT$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5000
            c.readTimeout = 10000
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (code !in 200..299) throw ApiException(json.optString("error", "电视返回错误 $code"), code)
            return json
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("连不上电视（$host）：${e.message ?: e.javaClass.simpleName}")
        } finally {
            c.disconnect()
        }
    }
}

// ---------- 后台任务 ----------

private val pool = Executors.newFixedThreadPool(3)
private val main = Handler(Looper.getMainLooper())

/** 在后台执行 work，完成后回到主线程调用 done；出错时弹窗（401 时提示重新配对）。 */
fun <T> Activity.task(work: () -> T, fail: ((Exception) -> Unit)? = null, done: (T) -> Unit) {
    pool.execute {
        val r = runCatching(work)
        main.post {
            if (isFinishing || isDestroyed) return@post
            r.onSuccess(done).onFailure { e ->
                val ex = e as? Exception ?: Exception(e)
                if (fail != null) fail(ex) else Ui.alert(this, "出错了", ex.message ?: ex.toString())
            }
        }
    }
}

// ---------- 界面小工具 ----------

object Ui {
    const val ACCENT = 0xFF2E4A7D.toInt()
    const val WARN = 0xFFC62828.toInt()
    const val MUTED = 0xFF666666.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /** 把页面设置为可滚动的纵向列表，返回列表容器。 */
    fun page(act: Activity): LinearLayout {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(act, 16); setPadding(p, p, p, p * 3)
        }
        act.setContentView(ScrollView(act).apply { setBackgroundColor(Color.WHITE); addView(col) })
        return col
    }

    fun text(ctx: Context, s: CharSequence, size: Float = 16f, bold: Boolean = false, color: Int = Color.BLACK) =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }

    fun section(parent: LinearLayout, title: String) {
        parent.addView(text(parent.context, title, 18f, bold = true, color = ACCENT).apply {
            setPadding(0, dp(context, 18), 0, dp(context, 6))
        })
    }

    fun button(ctx: Context, s: String, onClick: () -> Unit) = Button(ctx).apply {
        text = s; isAllCaps = false; setOnClickListener { onClick() }
    }

    /** 一行多个按钮，平均分宽。 */
    fun buttons(parent: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, fn) in items) {
            row.addView(button(parent.context, label, fn), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        parent.addView(row)
    }

    fun edit(ctx: Context, hint: String, value: String = "", number: Boolean = false, lines: Int = 1) = EditText(ctx).apply {
        this.hint = hint; setText(value)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER
        else if (lines > 1) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else InputType.TYPE_CLASS_TEXT
        if (lines > 1) { minLines = lines; maxLines = lines + 6 }
    }

    fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun alert(act: Activity, title: String, msg: String) {
        AlertDialog.Builder(act).setTitle(title).setMessage(msg).setPositiveButton("知道了", null).show()
    }

    /** 输入数字的对话框。 */
    fun askNumber(act: Activity, title: String, hint: String, onOk: (Int) -> Unit) {
        val e = edit(act, hint, number = true)
        AlertDialog.Builder(act).setTitle(title).setView(e)
            .setPositiveButton("确定") { _, _ -> e.text.toString().toIntOrNull()?.let(onOk) }
            .setNegativeButton("取消", null).show()
    }

    fun confirm(act: Activity, title: String, msg: String, ok: String = "确定", onOk: () -> Unit) {
        AlertDialog.Builder(act).setTitle(title).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> onOk() }.setNegativeButton("取消", null).show()
    }
}

fun fmtSec(s: Int): String = if (s >= 3600) "%d 小时 %d 分".format(s / 3600, s % 3600 / 60)
else "%d 分 %02d 秒".format(s / 60, s % 60)
