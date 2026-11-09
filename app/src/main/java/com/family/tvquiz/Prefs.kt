package com.family.tvquiz

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/** 家长设置。 */
class Prefs(ctx: Context) {
    companion object {
        val ALL_TYPES = setOf("choice", "fill", "custom")
        val TYPE_NAMES = mapOf("choice" to "选择题", "fill" to "填空题（转选择）", "custom" to "拍照导入题")
    }

    private val ctx = ctx.applicationContext
    private val sp: SharedPreferences = this.ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var guardEnabled: Boolean
        get() = sp.getBoolean("guard_enabled", true)
        set(v) = sp.edit().putBoolean("guard_enabled", v).apply()

    var overlayEnabled: Boolean
        // 默认关闭：小米电视会强行停止带悬浮窗的应用（日志"has system window, so kill it"），
        // 强行停止后无障碍权限也会被系统移除。
        get() = sp.getBoolean("overlay_enabled", false)
        set(v) = sp.edit().putBoolean("overlay_enabled", v).apply()

    var pin: String
        get() = sp.getString("pin", "0000") ?: "0000"
        set(v) = sp.edit().putString("pin", v).apply()

    var managed: Set<String>
        get() = sp.getStringSet("managed", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("managed", v.toSet()).apply()

    // ---------- 家长模式 ----------

    /** 家长解锁截止时间（墙上时间毫秒）。解锁期间不扣余额、不拦截。 */
    var unlockUntil: Long
        get() = sp.getLong("unlock_until", 0L)
        set(v) = sp.edit().putLong("unlock_until", v).apply()

    fun unlockRemainingMs(): Long = (unlockUntil - System.currentTimeMillis()).coerceAtLeast(0)

    fun unlockFor(minutes: Int) {
        unlockUntil = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
    }

    // ---------- 题型 ----------

    /** 参与出题的题型：choice = 原选择题，fill = 原填空题（已转选择），custom = 拍照导入题。 */
    var types: Set<String>
        get() = sp.getStringSet("types", ALL_TYPES)?.toSet() ?: ALL_TYPES
        set(v) = sp.edit().putStringSet("types", v.toSet()).apply()

    // ---------- 手机配对 ----------

    /** 已配对手机：token -> 设备名。 */
    fun pairedDevices(): Map<String, String> =
        (sp.getStringSet("paired", emptySet()) ?: emptySet()).mapNotNull {
            val i = it.indexOf('|'); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()

    fun addPaired(token: String, name: String) {
        val set = (sp.getStringSet("paired", emptySet()) ?: emptySet()).toMutableSet()
        set += "$token|${name.replace('|', ' ')}"
        sp.edit().putStringSet("paired", set).apply()
    }

    fun clearPaired() = sp.edit().remove("paired").apply()

    // ---------- 教材进度 ----------

    /** 当前册次，例如"一年级上册"。 */
    var book: String
        get() = sp.getString("book", "一年级上册") ?: "一年级上册"
        set(v) = sp.edit().putString("book", v).apply()

    /** 参与出题的学科。 */
    var subjects: Set<String>
        get() = sp.getStringSet("subjects", setOf("语文", "数学", "英语"))?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("subjects", v.toSet()).apply()

    /** 某学科只出到第几个单元（按题库的单元序号），默认不限。 */
    fun maxUnit(subject: String): Int = sp.getInt("max_unit_${book}_$subject", Int.MAX_VALUE)
    fun setMaxUnit(subject: String, v: Int) = sp.edit().putInt("max_unit_${book}_$subject", v).apply()

    /** 守门服务实测到的计时级别：A = 能读到播放状态，B = 前台 + 有声音。 */
    fun level(pkg: String): String? = sp.getString("level_$pkg", null)
    fun setLevel(pkg: String, level: String) {
        if (level(pkg) != level) sp.edit().putString("level_$pkg", level).apply()
    }

    fun accessibilityOn(): Boolean {
        val want = ComponentName(ctx, GuardService::class.java).flattenToString()
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.split(':').any { it.equals(want, true) || it.equals(ComponentName(ctx, GuardService::class.java).flattenToShortString(), true) }
    }

    fun notificationAccessOn(): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        return list.contains(ctx.packageName + "/")
    }
}
