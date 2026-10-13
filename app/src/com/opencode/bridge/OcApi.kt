package com.opencode.bridge

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SessionInfo(
    val id: String,
    val title: String,
    val directory: String,
    val child: Boolean,
    val running: Boolean,
)

data class ChatMsg(
    val role: String,
    val kind: String,
    val text: String,
)

class ApiException(val code: Int, message: String) : Exception(message)

open class OcApi(baseUrlRaw: String, private val username: String, private val password: String) {

    val baseUrl: String

    init {
        var u = baseUrlRaw.trim()
        if (u.isEmpty()) u = "http://127.0.0.1:4096"
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        while (u.endsWith("/")) u = u.dropLast(1)
        baseUrl = u
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun open(path: String, method: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 12000
        c.readTimeout = readTimeoutMs
        if (password.isNotEmpty()) {
            val tok = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
            c.setRequestProperty("Authorization", "Basic $tok")
        }
        c.setRequestProperty("User-Agent", "opencode-bridge-android/1.0")
        return c
    }

    private fun drain(s: java.io.InputStream?): String {
        if (s == null) return ""
        val sb = StringBuilder()
        try {
            val br = BufferedReader(InputStreamReader(s, Charsets.UTF_8))
            var line: String?
            while (br.readLine().also { line = it } != null) sb.append(line).append('\n')
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    private fun http(path: String, method: String, body: String? = null): String {
        val c = open(path, method, 30000)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                val b = body.toByteArray()
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            }
            val code = c.responseCode
            if (code >= 400) {
                val err = drain(c.errorStream)
                throw ApiException(code, if (err.isBlank()) "$method $path -> HTTP $code" else err.take(300))
            }
            if (code == 204) return ""
            return drain(c.inputStream)
        } finally {
            c.disconnect()
        }
    }

    fun health(): JSONObject = JSONObject(http("/global/health", "GET"))

    fun sessions(): List<SessionInfo> {
        val raw = http("/session", "GET")
        val arr = if (raw.isBlank()) JSONArray() else JSONArray(raw)
        val out = ArrayList<SessionInfo>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("id")
            if (id.isEmpty()) continue
            out.add(
                SessionInfo(
                    id = id,
                    title = s.optString("title", "").ifBlank { "Untitled" },
                    directory = s.optString("directory", ""),
                    child = !s.optString("parentID", "").isBlank(),
                    running = false,
                ),
            )
        }
        return out
    }

    private fun runningIds(): HashSet<String> {
        val out = HashSet<String>()
        try {
            val raw = http("/session/status", "GET")
            val obj = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val t = obj.optJSONObject(k)?.optString("type", "idle") ?: "idle"
                if (t != "idle") out.add(k)
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun sessionList(): List<SessionInfo> {
        val busy = runningIds()
        return sessions().map { it.copy(running = busy.contains(it.id)) }
    }

    fun createSession(title: String): String =
        JSONObject(http("/session", "POST", JSONObject().put("title", title).toString()))
            .optString("id", "")

    fun prompt(sid: String, text: String) {
        val body = JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        http("/session/${enc(sid)}/prompt_async", "POST", body.toString())
    }

    fun abort(sid: String) {
        http("/session/${enc(sid)}/abort", "POST", "{}")
    }

    fun permit(sid: String, rid: String, reply: String) {
        http("/api/session/${enc(sid)}/permission/${enc(rid)}/reply", "POST", JSONObject().put("reply", reply).toString())
    }

    fun permitV1(sid: String, rid: String, reply: String) {
        http("/session/${enc(sid)}/permissions/${enc(rid)}", "POST", JSONObject().put("response", reply).toString())
    }

    fun answerQuestion(sid: String, rid: String, answers: JSONArray) {
        http("/api/session/${enc(sid)}/question/${enc(rid)}/reply", "POST", JSONObject().put("answers", answers).toString())
    }

    fun answerQuestionV1(rid: String, answers: JSONArray) {
        http("/question/${enc(rid)}/reply", "POST", JSONObject().put("answers", answers).toString())
    }

    fun rejectQuestion(sid: String, rid: String) {
        http("/api/session/${enc(sid)}/question/${enc(rid)}/reject", "POST", "{}")
    }

    fun rejectQuestionV1(rid: String) {
        http("/question/${enc(rid)}/reject", "POST", "{}")
    }

    fun messages(sid: String): List<ChatMsg> {
        val raw = http("/session/${enc(sid)}/message", "GET")
        val arr = JSONArray(raw)
        val out = ArrayList<ChatMsg>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val role = item.optJSONObject("info")?.optString("role", "") ?: ""
            val parts = item.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                when (p.optString("type")) {
                    "text" -> {
                        val t = p.optString("text", "")
                        if (t.isNotBlank()) out.add(ChatMsg(role, "text", t))
                    }
                    "tool" -> {
                        val st = p.optJSONObject("state")
                        val sb = StringBuilder("\uD83D\uDEE0 ").append(p.optString("tool", "tool"))
                        val status = st?.optString("status", "") ?: ""
                        if (status.isNotBlank()) sb.append(" · ").append(status)
                        val title = st?.optString("title", "") ?: ""
                        if (title.isNotBlank()) sb.append(": ").append(title)
                        out.add(ChatMsg(role, "tool", sb.toString()))
                    }
                }
            }
        }
        return out
    }

    fun lastAssistantText(sid: String): String {
        return try {
            val raw = http("/session/${enc(sid)}/message", "GET")
            val arr = JSONArray(raw)
            for (i in arr.length() - 1 downTo 0) {
                val item = arr.optJSONObject(i) ?: continue
                val info = item.optJSONObject("info") ?: continue
                if (info.optString("role") != "assistant") continue
                val parts = item.optJSONArray("parts") ?: continue
                val sb = StringBuilder()
                for (j in 0 until parts.length()) {
                    val p = parts.optJSONObject(j) ?: continue
                    if (p.optString("type") == "text") sb.append(p.optString("text")).append(' ')
                }
                val t = sb.toString().trim()
                if (t.isNotEmpty()) return t
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    fun sseConnect(): HttpURLConnection {
        val c = open("/global/event", "GET", 90000)
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }
}
