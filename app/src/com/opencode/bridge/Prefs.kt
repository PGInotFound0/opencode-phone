package com.opencode.bridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Machine(
    val id: String,
    val name: String,
    val url: String,
    val user: String,
    val pass: String,
    val enabled: Boolean,
)

object Prefs {
    const val PRIMARY_ID = "m0"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    fun serverUrl(c: Context): String = sp(c).getString("url", "") ?: ""
    fun username(c: Context): String = sp(c).getString("user", "opencode") ?: "opencode"
    fun password(c: Context): String = sp(c).getString("pass", "") ?: ""
    fun keepAwake(c: Context): Boolean = sp(c).getBoolean("keepAwake", true)
    fun autoStart(c: Context): Boolean = sp(c).getBoolean("autoStart", false)

    fun machines(c: Context): MutableList<Machine> {
        val raw = sp(c).getString("machines", null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                val out = ArrayList<Machine>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isEmpty()) continue
                    out.add(
                        Machine(
                            id = id,
                            name = o.optString("name", "").ifBlank { "Machine" },
                            url = o.optString("url", ""),
                            user = o.optString("user", "opencode"),
                            pass = o.optString("pass", ""),
                            enabled = o.optBoolean("enabled", true),
                        ),
                    )
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
            }
        }
        val u = serverUrl(c)
        if (u.isNotBlank()) {
            return mutableListOf(Machine(PRIMARY_ID, "Primary", u, username(c), password(c), true))
        }
        return mutableListOf(Machine(PRIMARY_ID, "Primary", "", username(c), "", false))
    }

    fun hasAny(c: Context): Boolean = machines(c).any { it.url.isNotBlank() }

    fun saveMachines(c: Context, list: List<Machine>) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("url", m.url)
                    .put("user", m.user)
                    .put("pass", m.pass)
                    .put("enabled", m.enabled),
            )
        }
        sp(c).edit().putString("machines", arr.toString()).apply()
        val p = list.find { it.id == PRIMARY_ID }
        if (p != null) {
            sp(c).edit()
                .putString("url", p.url)
                .putString("user", p.user)
                .putString("pass", p.pass)
                .putBoolean("keepAwake", keepAwake(c))
                .putBoolean("autoStart", autoStart(c))
                .apply()
        }
    }

    fun savePrimary(c: Context, url: String, user: String, pass: String) {
        val list = machines(c)
        val idx = list.indexOfFirst { it.id == PRIMARY_ID }
        if (idx < 0) {
            list.add(Machine(PRIMARY_ID, "Primary", url, user, pass, url.isNotBlank()))
        } else {
            list[idx] = list[idx].copy(url = url, user = user, pass = pass, enabled = url.isNotBlank())
        }
        saveMachines(c, list)
    }

    fun save(c: Context, url: String, user: String, pass: String, keep: Boolean, auto: Boolean) {
        sp(c).edit()
            .putString("url", url)
            .putString("user", user)
            .putString("pass", pass)
            .putBoolean("keepAwake", keep)
            .putBoolean("autoStart", auto)
            .apply()
        savePrimary(c, url, user, pass)
    }
}
