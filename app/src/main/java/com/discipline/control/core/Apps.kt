package com.discipline.control.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object Apps {

    private var cachedAt = 0L
    private var cachedList: List<AppEntry>? = null
    private val iconCache = HashMap<String, Drawable?>()
    private val labelCache = HashMap<String, String>()

    /** 桌面上可见的已安装应用（有 LAUNCHER 入口的），排除自己。带 30 秒缓存。 */
    fun launchable(ctx: Context): List<AppEntry> {
        val now = System.currentTimeMillis()
        val cached = cachedList
        if (cached != null && now - cachedAt < 30_000L) return cached

        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrElse { emptyList() }
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName) continue
            if (!seen.add(pkg)) continue
            val label = labelCache.getOrPut(pkg) {
                runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            }
            val icon = if (iconCache.containsKey(pkg)) {
                iconCache[pkg]
            } else {
                val d = runCatching { ri.loadIcon(pm) }.getOrNull()
                iconCache[pkg] = d
                d
            }
            out.add(AppEntry(pkg, label, icon))
        }
        out.sortBy { it.label.lowercase() }
        cachedList = out
        cachedAt = now
        return out
    }

    fun invalidate() {
        cachedList = null
        cachedAt = 0L
    }

    fun label(ctx: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg)
    }

    fun icon(ctx: Context, pkg: String): Drawable? = iconCache.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun hasLauncherEntry(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return runCatching { pm.queryIntentActivities(intent, 0).isNotEmpty() }.getOrDefault(false)
    }

    @Suppress("unused")
    private fun pmOf(ctx: Context): PackageManager = ctx.packageManager
}
