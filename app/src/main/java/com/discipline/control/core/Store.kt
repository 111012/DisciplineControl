package com.discipline.control.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PendingChange(
    val id: String,
    /** "rule" 或 "config" */
    val type: String,
    val payload: JSONObject,
    val effectiveAt: Long,
    val summary: String
)

/**
 * 本地存储：所有规则、全局配置、当日用量、待生效修改都放在 SharedPreferences 里（纯本地，无网络）。
 */
object Store {
    private const val PREF = "dc_store"
    private lateinit var sp: SharedPreferences
    private var cfgCache: GlobalConfig? = null
    private var ruleCache: MutableList<AppRule>? = null
    private var dayCache: DayUsage? = null
    private var pendingCache: MutableList<PendingChange>? = null

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    // ---------------- 全局配置 ----------------

    fun config(): GlobalConfig {
        var c = cfgCache
        if (c == null) {
            c = runCatching {
                GlobalConfig.fromJson(JSONObject(sp.getString("config", "{}") ?: "{}"))
            }.getOrElse { GlobalConfig() }
            cfgCache = c
        }
        return c
    }

    fun saveConfig(c: GlobalConfig) {
        cfgCache = c
        sp.edit().putString("config", c.toJson().toString()).apply()
    }

    // ---------------- 应用规则 ----------------

    fun rules(): MutableList<AppRule> {
        var list = ruleCache
        if (list == null) {
            list = ArrayList()
            runCatching {
                val arr = JSONArray(sp.getString("rules", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val r = AppRule.fromJson(o)
                    if (r.pkg.isNotEmpty()) list.add(r)
                }
            }
            ruleCache = list
        }
        return list
    }

    fun rule(pkg: String): AppRule? = rules().firstOrNull { it.pkg == pkg }

    fun saveRules() {
        val arr = JSONArray()
        rules().forEach { arr.put(it.toJson()) }
        sp.edit().putString("rules", arr.toString()).apply()
    }

    fun upsertRule(rule: AppRule) {
        val list = rules()
        val idx = list.indexOfFirst { it.pkg == rule.pkg }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        saveRules()
    }

    fun removeRule(pkg: String) {
        rules().removeAll { it.pkg == pkg }
        saveRules()
    }

    // ---------------- 当日用量 ----------------

    fun logicalDate(nowMs: Long = System.currentTimeMillis()): LocalDate {
        val dt = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        var d = dt.toLocalDate()
        if (dt.hour < config().resetHour) d = d.minusDays(1)
        return d
    }

    fun day(): DayUsage {
        val key = logicalDate().toString()
        val cached = dayCache
        if (cached != null && cached.date == key) return cached
        val text = sp.getString("day_$key", null)
        val d = DayUsage.fromJson(text) ?: DayUsage(key)
        dayCache = d
        return d
    }

    fun saveDay(d: DayUsage) {
        dayCache = d
        sp.edit().putString("day_${d.date}", d.toJson().toString()).apply()
        // 顺手清理 30 天前的记录
        runCatching {
            val cutoff = logicalDate().minusDays(30).toString()
            sp.all.keys.filter { it.startsWith("day_") && it.removePrefix("day_") < cutoff }
                .forEach { sp.edit().remove(it).apply() }
        }
    }

    /** 读取历史某天的用量（用于统计页） */
    fun dayOf(date: LocalDate): DayUsage? {
        val key = date.toString()
        val cached = dayCache
        if (cached != null && cached.date == key) return cached
        return DayUsage.fromJson(sp.getString("day_$key", null))
    }

    // ---------------- 待生效修改 ----------------

    fun pending(): MutableList<PendingChange> {
        var list = pendingCache
        if (list == null) {
            list = ArrayList()
            runCatching {
                val arr = JSONArray(sp.getString("pending", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        PendingChange(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            type = o.optString("type", "rule"),
                            payload = o.optJSONObject("payload") ?: JSONObject(),
                            effectiveAt = o.optLong("at", 0L),
                            summary = o.optString("summary", "")
                        )
                    )
                }
            }
            pendingCache = list
        }
        return list
    }

    private fun savePending() {
        val arr = JSONArray()
        pending().forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("type", it.type)
                    .put("payload", it.payload)
                    .put("at", it.effectiveAt)
                    .put("summary", it.summary)
            )
        }
        sp.edit().putString("pending", arr.toString()).apply()
    }

    fun addPending(change: PendingChange) {
        pending().add(change)
        savePending()
    }

    fun removePending(id: String) {
        pending().removeAll { it.id == id }
        savePending()
    }

    /** 应用所有到期的待生效修改，返回被应用项的说明 */
    fun applyDuePending(nowMs: Long = System.currentTimeMillis()): List<String> {
        val due = pending().filter { it.effectiveAt <= nowMs }
        if (due.isEmpty()) return emptyList()
        val applied = ArrayList<String>()
        for (p in due) {
            runCatching {
                when (p.type) {
                    "rule" -> {
                        val r = AppRule.fromJson(p.payload)
                        if (r.pkg.isNotEmpty()) upsertRule(r)
                    }

                    "config" -> {
                        val cur = config()
                        val incoming = GlobalConfig.fromJson(p.payload)
                        saveConfig(incoming.copy(pinSalt = cur.pinSalt, pinHash = cur.pinHash))
                    }

                    "remove" -> {
                        val pkg = p.payload.optString("pkg")
                        if (pkg.isNotEmpty()) removeRule(pkg)
                    }
                }
                applied.add(p.summary)
            }
            removePending(p.id)
        }
        return applied
    }

    // ---------------- 其他 ----------------

    fun clearAll() {
        sp.edit().clear().apply()
        cfgCache = null
        ruleCache = null
        dayCache = null
        pendingCache = null
    }
}
