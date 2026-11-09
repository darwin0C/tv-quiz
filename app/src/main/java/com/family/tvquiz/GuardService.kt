package com.family.tvquiz

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.TextView

/**
 * 守门服务（无障碍服务）。系统会在开机后自动启动已开启的无障碍服务，
 * 所以不依赖开机广播。
 *
 * 每秒检查一次：
 * - 受管应用是否在播放（A 级：读媒体会话；B 级：在前台且有声音）
 * - 屏幕是否亮着
 * 满足条件才累计观看时间，每 15 秒写一次数据库。余额用完就暂停并打开答题页。
 */
class GuardService : AccessibilityService() {

    companion object {
        const val TAG = "TVQuiz"
        private const val TICK_MS = 1000L
        private const val FLUSH_MS = 15_000L
        private const val MAX_DELTA_MS = 3000L   // 防止休眠恢复后一次算进大段时间
        private const val BLOCK_DEBOUNCE_MS = 2500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private lateinit var power: PowerManager
    private lateinit var audio: AudioManager
    private var sessions: MediaSessionManager? = null

    private var fgPkg: String = ""
    private var lastTick = 0L
    private var pendingMs = 0L
    private var pendingPkg = ""
    private var pendingParent = false
    private var lastBlock = 0L
    private var lastStatus = ""

    private var overlay: TextView? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Log.i(TAG, "screen event: ${i.action}")
            flush(force = true)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try { onTick() } catch (t: Throwable) { Log.e(TAG, "tick failed", t) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = Db.get(this)
        prefs = Prefs(this)
        power = getSystemService(PowerManager::class.java)
        audio = getSystemService(AudioManager::class.java)
        sessions = getSystemService(MediaSessionManager::class.java)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        })
        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)
        LanServer.start(this)
        Log.i(TAG, "guard connected, balance=${db.balanceSeconds()}s")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: return
        // 忽略输入法和系统界面；其余只接受 Activity 或可启动应用发出的窗口事件
        // （云视听极光的窗口事件类名不是 Activity，只按类名判断会漏掉它）
        if (isIme(pkg) || pkg == "com.android.systemui") return
        if (!isActivity(pkg, cls) && !isLaunchable(pkg)) return
        if (pkg != fgPkg) {
            Log.i(TAG, "foreground: $fgPkg -> $pkg ($cls)")
            fgPkg = pkg
            onTick()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacks(tick)
        if (::db.isInitialized) flush(force = true)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        removeOverlay()
    }

    // ---------- 核心逻辑 ----------

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTick).coerceIn(0, MAX_DELTA_MS)
        lastTick = now

        // 兜底：云视听极光屏蔽了无障碍（不发窗口事件、读不到窗口），改从系统使用记录读最近切到前台的应用
        val active = foregroundFromUsage()
        if (active != null && active != fgPkg && active != "com.android.systemui" && !isIme(active)) {
            Log.i(TAG, "foreground(poll): $fgPkg -> $active")
            fgPkg = active
        }

        if (!prefs.guardEnabled) {
            flush(force = true)
            showStatus("守门已停用")
            return
        }

        val managed = prefs.managed
        val interactive = power.isInteractive
        val controllers = activeControllers()

        // 记录每个受管应用的计时级别
        controllers.map { it.packageName }.filter { it in managed }.forEach { prefs.setLevel(it, "A") }

        // A 级：受管应用的媒体会话正在播放
        val playingA = controllers.firstOrNull {
            it.packageName in managed && it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val fgManaged = fgPkg in managed
        // B 级：没有媒体会话的应用，在前台且有声音就算播放
        val fgHasSession = controllers.any { it.packageName == fgPkg }
        val playingB = fgManaged && !fgHasSession && prefs.level(fgPkg) != "A" && audio.isMusicActive
        if (playingB) prefs.setLevel(fgPkg, "B")

        val playingPkg = when {
            playingA != null -> playingA.packageName
            playingB -> fgPkg
            else -> null
        }
        val counting = interactive && playingPkg != null

        // 家长模式：不扣余额、不拦截，观看时间单独记录
        val unlocked = prefs.unlockRemainingMs() > 0

        if (counting) {
            if (pendingPkg != playingPkg || pendingParent != unlocked) {
                flush(force = true); pendingPkg = playingPkg!!; pendingParent = unlocked
            }
            pendingMs += delta
        }

        val balance = db.balanceSeconds() - if (pendingParent) 0 else (pendingMs / 1000).toInt()

        if (unlocked) {
            // 不拦截
        } else if (counting && balance <= 0) {
            flush(force = true)
            block(playingPkg!!, controllers, "播放中余额用完")
        } else if (interactive && fgManaged && balance <= 0) {
            block(fgPkg, controllers, "余额为 0 时打开受管应用")
        }

        if (pendingMs >= FLUSH_MS || (!counting && pendingMs >= 1000)) flush(force = false)

        val level = playingPkg?.let { if (playingA != null) "A" else "B" }
        showStatus(buildString {
            append("前台: ").append(fgPkg.ifEmpty { "-" })
            append(if (fgManaged) "（受管）" else "").append('\n')
            append("状态: ")
            append(
                when {
                    !interactive -> "屏幕关闭"
                    counting && unlocked -> "播放中，家长模式不扣时（$level 级）"
                    counting -> "播放中，扣时（$level 级）"
                    fgManaged -> "受管应用未播放"
                    else -> "不计时"
                }
            ).append('\n')
            append("余额: ").append(fmt(balance.coerceAtLeast(0)))
            append("  今日已看: ").append(fmt(db.todayWatchedSeconds() + (pendingMs / 1000).toInt()))
        })
    }

    /** 把累计的观看时间写入数据库。force=false 时只写整秒，余下的毫秒留到下次。 */
    @Synchronized
    private fun flush(force: Boolean) {
        val secs = (pendingMs / 1000).toInt()
        if (secs > 0 && pendingPkg.isNotEmpty()) {
            if (pendingParent) {
                db.parentWatch(secs, pendingPkg)
                Log.i(TAG, "parent-mode watch ${secs}s on $pendingPkg")
            } else {
                val used = db.spend(secs, pendingPkg)
                Log.i(TAG, "spend ${used}s on $pendingPkg, balance=${db.balanceSeconds()}s")
            }
        }
        pendingMs -= secs * 1000L
        if (force) pendingMs = 0
    }

    private fun block(pkg: String, controllers: List<MediaController>, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlock < BLOCK_DEBOUNCE_MS) return
        lastBlock = now
        Log.w(TAG, "block $pkg: $reason")

        // 1. 先用媒体会话暂停；2. 没有会话就模拟遥控器暂停键
        val c = controllers.firstOrNull { it.packageName == pkg }
        if (c != null) {
            c.transportControls.pause()
            Log.i(TAG, "paused via media session")
        } else {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            Log.i(TAG, "paused via media key")
        }
        // 3. 打开答题页盖住视频
        startActivity(Intent(this, QuizActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(QuizActivity.EXTRA_TIME_UP, true)
        })
    }

    private fun activeControllers(): List<MediaController> = try {
        sessions?.getActiveSessions(ComponentName(this, MediaListener::class.java)) ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()  // 没有通知使用权时读不到，自动退回 B 级
    }

    private fun isActivity(pkg: String, cls: String): Boolean = try {
        packageManager.getActivityInfo(ComponentName(pkg, cls), 0); true
    } catch (_: Exception) {
        false
    }

    private var usageCheckedAt = 0L
    private var usageFg: String? = null

    /** 从使用记录中取最近一次切到前台的应用（需要"使用情况访问"权限，由 grant.sh 授予）。 */
    private fun foregroundFromUsage(): String? {
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val from = if (usageCheckedAt == 0L) now - 60 * 60_000L else usageCheckedAt - 2000
        usageCheckedAt = now
        try {
            val events = usm.queryEvents(from, now)
            val e = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) usageFg = e.packageName
            }
        } catch (t: Throwable) {
            Log.w(TAG, "usage query failed: $t")
        }
        return usageFg
    }

    private val launchableCache = HashMap<String, Boolean>()
    private fun isLaunchable(pkg: String): Boolean = launchableCache.getOrPut(pkg) {
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null ||
            packageManager.getLaunchIntentForPackage(pkg) != null
    }

    private fun isIme(pkg: String): Boolean =
        getSystemService(InputMethodManager::class.java).inputMethodList.any { it.packageName == pkg }

    // ---------- 调试浮层 ----------

    private fun showStatus(text: String) {
        if (text != lastStatus) {
            lastStatus = text
        }
        if (!prefs.overlayEnabled) { removeOverlay(); return }
        val v = overlay ?: createOverlay() ?: return
        v.text = text
    }

    private fun createOverlay(): TextView? = try {
        val v = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            textSize = 14f
            setPadding(16, 10, 16, 10)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 24 }
        getSystemService(WindowManager::class.java).addView(v, lp)
        overlay = v
        v
    } catch (t: Throwable) {
        Log.e(TAG, "overlay failed", t); null
    }

    private fun removeOverlay() {
        overlay?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {} }
        overlay = null
    }
}

fun fmt(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
