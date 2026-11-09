package com.family.tvquiz.parent

import android.app.Activity
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 连接电视：自动在局域网里查找电视（mDNS _tvquiz._tcp），也可手动输入地址；
 * 输入电视家长页显示的 6 位配对码完成配对。
 */
class ConnectActivity : Activity() {

    private lateinit var store: Store
    private lateinit var foundBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var ipEdit: EditText
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private val found = LinkedHashMap<String, String>()   // ip -> 名称
    private var chosen: String? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (store.token != null && store.host != null) { openMain(); return }

        val col = K.vbox(this, 16)
        val head = K.vbox(this, 6)
        head.addView(K.text(this, "连接电视", 28f, bold = true))
        head.addView(K.text(this, "手机和电视连同一个 Wi-Fi，在电视「答题看电视 → 家长」里生成配对码。", 15f, K.MUTED))
        col.addView(head, K.full())

        val foundCard = K.card(this, 10)
        foundCard.addView(K.muted(this, "已找到"))
        status = K.text(this, "正在查找…", 14f, K.MUTED)
        foundCard.addView(status)
        foundBox = K.vbox(this, 8)
        foundCard.addView(foundBox, K.full())
        col.addView(foundCard, K.full())

        val codeCard = K.card(this, 10)
        codeCard.addView(K.muted(this, "配对码（电视上显示的 6 位数字）"))
        val codeEdit = K.input(this, "······", number = true).apply {
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 26f
            letterSpacing = 0.5f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        codeCard.addView(codeEdit, LinearLayout.LayoutParams(K.MATCH, K.dp(this, 60)))
        codeCard.addView(K.muted(this, "10 分钟内有效，用一次就失效。", 12f))
        col.addView(codeCard, K.full())

        val ipCard = K.card(this, 6)
        ipCard.addView(K.muted(this, "电视地址（没找到时手动输入，电视家长页里有显示）"))
        ipEdit = K.input(this, "例如 192.168.50.171", store.host ?: "")
        ipCard.addView(ipEdit, K.full())
        col.addView(ipCard, K.full())

        col.addView(K.primary(this, "配对") {
            val host = (chosen ?: ipEdit.text.toString()).trim()
            val code = codeEdit.text.toString().trim()
            if (host.isEmpty()) { Ui.toast(this, "请选择或输入电视地址"); return@primary }
            if (code.length != 6) { Ui.toast(this, "请输入 6 位配对码"); return@primary }
            task({
                val info = Api(host, null).get("/api/info")
                if (info.optString("app") != "tvquiz") throw ApiException("这个地址不是「答题看电视」")
                Api(host, null).post("/api/pair", JSONObject().put("code", code).put("device", "${Build.MANUFACTURER} ${Build.MODEL}"))
            }) { r ->
                store.host = host
                store.token = r.getString("token")
                store.tvName = r.optString("device")
                Ui.toast(this, "配对成功")
                openMain()
            }
        })

        setContentView(K.page(this, col).apply { setPadding(0, K.dp(this@ConnectActivity, 28), 0, 0) })
        window.statusBarColor = K.BG
    }

    override fun onResume() {
        super.onResume()
        if (::foundBox.isInitialized) startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        stopDiscovery()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun renderFound() {
        foundBox.removeAllViews()
        status.visibility = if (found.isEmpty()) TextView.VISIBLE else TextView.GONE
        for ((ip, name) in found) {
            val on = ip == chosen
            val row = K.hbox(this, 12).apply {
                background = K.ripple(if (on) K.shape(context, K.PRIMARY_SOFT, 12, K.PRIMARY, 2) else K.shape(context, K.CARD, 12, K.BORDER, 1))
                setPadding(K.dp(context, 12), K.dp(context, 10), K.dp(context, 12), K.dp(context, 10))
                minimumHeight = K.dp(context, 64)
                isClickable = true
                setOnClickListener { chosen = ip; ipEdit.setText(ip); renderFound() }
            }
            row.addView(K.icon(this, R.drawable.ic_tv, K.PRIMARY, 30))
            val t = K.vbox(this, 2)
            t.addView(K.text(this, name.removePrefix("答题看电视-"), 16f, bold = true))
            t.addView(K.muted(this, ip))
            row.addView(t, K.weight())
            if (on) row.addView(K.icon(this, R.drawable.ic_check, K.PRIMARY, 22))
            foundBox.addView(row, K.full())
        }
    }

    // ---------- mDNS 查找 ----------

    private fun startDiscovery() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        lock = wifi?.createMulticastLock("tvquiz")?.apply { setReferenceCounted(false); acquire() }
        nsd = getSystemService(NsdManager::class.java)
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { runOnUiThread { status.text = "自动查找失败，请手动输入地址" } }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                synchronized(resolveQueue) { resolveQueue.addLast(s) }
                resolveNext()
            }
        }
        discovery = l
        try { nsd?.discoverServices("_tvquiz._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
        catch (e: Exception) { status.text = "自动查找失败，请手动输入地址" }
        foundBox.postDelayed({ if (found.isEmpty() && !isFinishing) status.text = "没找到电视，请在下面手动输入地址" }, 8000)
    }

    /** NsdManager 一次只能解析一个服务，所以排队解析。 */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            if (resolving) return
            resolveQueue.removeFirstOrNull()?.also { resolving = true }
        } ?: return
        nsd?.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) { resolving = false; resolveNext() }
            override fun onServiceResolved(s: NsdServiceInfo) {
                resolving = false
                val ip = s.host?.hostAddress
                if (ip != null && ip.contains('.')) runOnUiThread {
                    if (ip !in found) {
                        found[ip] = s.serviceName
                        if (chosen == null) { chosen = ip; ipEdit.setText(ip) }
                        renderFound()
                    }
                }
                resolveNext()
            }
        })
    }

    private fun stopDiscovery() {
        discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }
}
