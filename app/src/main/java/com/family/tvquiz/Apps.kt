package com.family.tvquiz

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** 列出电视上可以打开的应用。 */
object Apps {
    data class App(val pkg: String, val label: String)

    fun launchable(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == ctx.packageName || pkg in seen) continue
                seen[pkg] = App(pkg, ri.loadLabel(pm).toString())
            }
        }
        return seen.values.sortedBy { it.label }
    }

    fun launch(ctx: Context, pkg: String) {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (i == null) { Ui.toast(ctx, "打不开这个应用"); return }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
