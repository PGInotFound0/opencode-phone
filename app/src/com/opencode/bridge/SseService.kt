package com.opencode.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class Pending(
    val v2: Boolean,
    val kind: String,
    val machineId: String,
    val machineName: String,
    val sid: String,
    val rid: String,
    val title: String,
    val body: String,
    val questions: JSONArray,
)

class SseService : Service() {

    companion object {
        const val EXTRA_SID = "sid"
        const val EXTRA_RID = "rid"
        const val EXTRA_MID = "mid"
        const val EXTRA_REPLY = "reply"
        const val EXTRA_V2 = "v2"
        const val EXTRA_LABELS = "labels"

        const val ACTION_START = "com.opencode.bridge.START"
        const val ACTION_STOP = "com.opencode.bridge.STOP"
        const val ACTION_PERMIT = "com.opencode.bridge.PERMIT"
        const val ACTION_ANSWER = "com.opencode.bridge.ANSWER"
        const val ACTION_QREJECT = "com.opencode.bridge.QREJECT"
        const val ACTION_UI = "com.opencode.bridge.UI"

        @Volatile var instance: SseService? = null
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>() // key: "mid|rid"
    private val notifIds = LinkedHashMap<String, Int>()
    private val titles = HashMap<String, String>() // key: "mid|sid"
    private val notifSeq = AtomicInteger(100)
    private val status = ConcurrentHashMap<String, String>()

    private val workers = HashMap<String, Thread>() // mid -> thread
    private val stopFlags = ConcurrentHashMap<String, Boolean>()
    private val conns = ConcurrentHashMap<String, HttpURLConnection>() // mid -> live SSE socket

    private var wake: PowerManager.WakeLock? = null
    @Volatile private var stopFlag = false

    private fun mkApi(url: String, user: String, pass: String): OcApi = OcApi(url, user, pass)

    private fun apiFor(mid: String): OcApi? {
        val m = Prefs.machines(this).find { it.id == mid } ?: return null
        return mkApi(m.url, m.user, m.pass)
    }

    fun statusSnapshot(): Map<String, String> = status.toMap()

    fun statusText(): String {
        val ms = Prefs.machines(this)
        val enabled = ms.filter { it.enabled }
        if (instance == null) return "Bridge stopped"
        if (enabled.isEmpty()) return "No machines configured"
        val up = enabled.count { status[it.id]?.startsWith("Connected") == true }
        if (up == enabled.size) return "Connected · ${enabled.size} machine(s)"
        if (up == 0) {
            val any = enabled.any { (status[it.id] ?: "").startsWith("Reconnect") }
            return if (any) "Reconnecting…" else "Connecting…"
        }
        return "$up/${enabled.size} connected · retrying rest"
    }

    private fun toForeground(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, n)
        }
    }

    private fun updateStatusNotification() {
        try {
            toForeground(1, Notifications.status(this, statusText()))
        } catch (_: Exception) {
        }
    }

    private fun setStatus(mid: String, text: String) {
        status[mid] = text
        updateStatusNotification()
    }

    private fun uiRefresh(evt: String = "") = sendBroadcast(Intent(ACTION_UI).setPackage(packageName).putExtra("t", evt))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.ensureChannels(this)
        val f = IntentFilter()
        f.addAction(ACTION_PERMIT)
        f.addAction(ACTION_ANSWER)
        f.addAction(ACTION_QREJECT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
    }

    override fun onDestroy() {
        stopFlag = true
        synchronized(workers) {
            for ((id, _) in workers.toList()) {
                stopFlags[id] = true
                conns.remove(id)?.let {
                    try { it.disconnect() } catch (_: Exception) { }
                }
            }
            for ((_, t) in workers.toList()) {
                try { t.interrupt() } catch (_: Exception) { }
            }
            workers.clear()
        }
        clearPending()
        if (wake?.isHeld == true) wake?.release()
        instance = null
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopFlag = false
                Notifications.ensureChannels(this)
                updateStatusNotification()
                syncWorkers()
                val pm = getSystemService(PowerManager::class.java)
                if (wake == null) {
                    wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode-bridge:net")
                    wake?.setReferenceCounted(false)
                }
                if (Prefs.keepAwake(this) && wake?.isHeld != true) {
                    try { wake?.acquire() } catch (_: Exception) { }
                } else if (!Prefs.keepAwake(this) && wake?.isHeld == true) {
                    wake?.release()
                }
                return START_STICKY
            }
        }
    }

    private fun stopServiceSelf() {
        stopFlag = true
        synchronized(workers) {
            for ((id, _) in workers.toList()) {
                stopFlags[id] = true
                conns.remove(id)?.disconnect()
            }
            for ((_, t) in workers.toList()) {
                try { t.interrupt() } catch (_: Exception) { }
            }
            workers.clear()
        }
        clearPending()
        if (wake?.isHeld == true) wake?.release()
        status.clear()
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    fun syncWorkers() {
        val list = Prefs.machines(this)
        synchronized(workers) {
            val itr = workers.entries.iterator()
            while (itr.hasNext()) {
                val (id, t) = itr.next()
                val m = list.find { it.id == id }
                if (m == null || !m.enabled || m.url.isBlank()) {
                    stopFlags[id] = true
                    conns.remove(id)?.disconnect()
                    try { t.interrupt() } catch (_: Exception) { }
                    itr.remove()
                    status[id] = "disabled"
                }
            }
            for (m in list) {
                if (m.enabled && m.url.isNotBlank() && workers[m.id] == null) {
                    stopFlags[m.id] = false
                    status[m.id] = "connecting"
                    val t = Thread(machineLoop(m.id)).apply { name = "oc-${m.id}" }
                    workers[m.id] = t
                    t.start()
                }
            }
        }
        updateStatusNotification()
        uiRefresh()
    }

    fun restartMachine(mid: String) {
        synchronized(workers) {
            stopFlags[mid] = true
            conns.remove(mid)?.disconnect()
            val t = workers.remove(mid)
            t?.interrupt()
        }
        syncWorkers()
    }

    private fun machineLoop(mid: String): Runnable = Runnable {
        var attempt = 0
        while (!stopFlag && (stopFlags[mid] ?: false) == false) {
            var hc: HttpURLConnection? = null
            try {
                val m = Prefs.machines(this).find { it.id == mid } ?: break
                setStatus(mid, "connecting…")
                hc = mkApi(m.url, m.user, m.pass).sseConnect()
                conns[mid] = hc
                val code = hc.responseCode
                if (code != 200) {
                    throw IOException("HTTP $code ${drainQuiet(hc.errorStream).take(60)}")
                }
                setStatus(mid, "Connected · watching")
                attempt = 0
                val br = BufferedReader(InputStreamReader(hc.inputStream, Charsets.UTF_8))
                var line: String?
                while (!stopFlag && (stopFlags[mid] ?: false) == false) {
                    line = br.readLine()
                    if (line == null) throw IOException("stream closed")
                    if (line.length < 6 || !line.startsWith("data:")) continue
                    val raw = line.substring(5).trim()
                    if (raw.isEmpty()) continue
                    try {
                        val payload = JSONObject(raw)
                        val mm = Prefs.machines(this).find { it.id == mid } ?: break
                        handle(payload.optString("type"), payload.optJSONObject("properties") ?: JSONObject(), mm)
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                if (stopFlag || (stopFlags[mid] ?: false)) break
                attempt++
                val d = (5 + attempt * 5).coerceAtMost(30).toLong()
                setStatus(mid, "Reconnecting in ${d}s (${e.message?.take(60) ?: "network error"})")
                try {
                    Thread.sleep(d * 1000)
                } catch (_: InterruptedException) {
                    break
                }
            } finally {
                try { hc?.disconnect() } catch (_: Exception) { }
                conns.remove(mid, hc)
            }
        }
        status[mid] = "stopped"
        updateStatusNotification()
        uiRefresh()
    }

    private fun drainQuiet(s: InputStream?): String {
        if (s == null) return ""
        return try {
            val br = BufferedReader(InputStreamReader(s, Charsets.UTF_8))
            br.readLine() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun titleOf(m: Machine, sid: String): String? {
        val list = try { mkApi(m.url, m.user, m.pass).sessions() } catch (_: Exception) { return null }
        for (s in list) if (s.id == sid) return s.title
        return null
    }

    private fun sessionTitle(m: Machine, sid: String): String {
        synchronized(lock) {
            titles["${m.id}|$sid"]?.let { return it }
        }
        Thread {
            try {
                titleOf(m, sid)?.let { t ->
                    synchronized(lock) { titles["${m.id}|$sid"] = t }
                }
            } catch (_: Exception) {
            }
        }.start()
        return "Session ${sid.takeLast(6)}"
    }

    private fun cacheTitles(m: Machine) {
        Thread {
            try {
                val list = mkApi(m.url, m.user, m.pass).sessions()
                val map = HashMap<String, String>()
                for (s in list) map["${m.id}|${s.id}"] = s.title
                synchronized(lock) { titles.putAll(map) }
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Pending-notification ids live in [100..4999] and wrap; session lifecycle ids use disjoint ranges. */
    private fun nextPendingId(): Int {
        while (true) {
            val cur = notifSeq.get()
            val next = if (cur >= 4999) 100 else cur + 1
            if (notifSeq.compareAndSet(cur, next)) return next
        }
    }

    private fun notifKeyUnlocked(mid: String, rid: String): Int {
        val key = "$mid|$rid"
        val old = notifIds[key]
        if (old != null) return old
        val nid = nextPendingId()
        notifIds[key] = nid
        return nid
    }

    private fun notifKey(mid: String, rid: String): Int = synchronized(lock) { notifKeyUnlocked(mid, rid) }

    private fun addPending(p: Pending) {
        synchronized(lock) {
            notifKeyUnlocked(p.machineId, p.rid)
            pending[pendingKey(p.machineId, p.rid)] = p
        }
        uiRefresh()
    }

    private fun pendingKey(mid: String, rid: String): String = "$mid|$rid"

    fun forget(mid: String, rid: String) {
        val key = pendingKey(mid, rid)
        var nid: Int? = null
        synchronized(lock) {
            nid = notifIds.remove(key)
            pending.remove(key)
        }
        nid?.let { getSystemService(NotificationManager::class.java)?.cancel(it) }
        uiRefresh()
    }

    private fun clearPending() {
        val nm = getSystemService(NotificationManager::class.java)
        synchronized(lock) {
            for (id in notifIds.values) nm?.cancel(id)
            notifIds.clear()
            pending.clear()
        }
    }

    fun pendings(): List<Pending> = synchronized(lock) { pending.values.toList() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val mid = i.getStringExtra(EXTRA_MID) ?: ""
            val sid = i.getStringExtra(EXTRA_SID) ?: ""
            val rid = i.getStringExtra(EXTRA_RID) ?: ""
            val m = Prefs.machines(applicationContext).find { it.id == mid }
            if (m == null) return
            when (i.action) {
                ACTION_PERMIT -> {
                    val reply = i.getStringExtra(EXTRA_REPLY) ?: "once"
                    val v2 = i.getBooleanExtra(EXTRA_V2, true)
                    Thread {
                        try {
                            val api = mkApi(m.url, m.user, m.pass)
                            if (v2) api.permit(sid, rid, reply) else api.permitV1(sid, rid, reply)
                            forget(mid, rid)
                        } catch (e: Exception) {
                            setStatus(mid, "Reply failed: ${e.message?.take(50) ?: "?"}")
                        }
                        uiRefresh()
                    }.start()
                }
                ACTION_ANSWER -> {
                    val labelsStr = i.getStringExtra(EXTRA_LABELS) ?: "[]"
                    val v2 = i.getBooleanExtra(EXTRA_V2, true)
                    Thread {
                        try {
                            val api = mkApi(m.url, m.user, m.pass)
                            val answers = JSONArray(labelsStr)
                            if (v2) api.answerQuestion(sid, rid, answers) else api.answerQuestionV1(rid, answers)
                            forget(mid, rid)
                        } catch (e: Exception) {
                            setStatus(mid, "Answer failed: ${e.message?.take(50) ?: "?"}")
                        }
                        uiRefresh()
                    }.start()
                }
                ACTION_QREJECT -> {
                    val v2 = i.getBooleanExtra(EXTRA_V2, true)
                    Thread {
                        try {
                            val api = mkApi(m.url, m.user, m.pass)
                            if (v2) api.rejectQuestion(sid, rid) else api.rejectQuestionV1(rid)
                            forget(mid, rid)
                        } catch (e: Exception) {
                            setStatus(mid, "Reject failed: ${e.message?.take(50) ?: "?"}")
                        }
                        uiRefresh()
                    }.start()
                }
            }
        }
    }

    private fun handle(type: String, props: JSONObject, m: Machine) {
        when {
            type == "server.connected" -> {
                cacheTitles(m)
                uiRefresh(type)
            }
            type == "session.created" || type == "session.updated" -> {
                val sid = props.optString("id")
                val t = props.optString("title")
                if (sid.isNotEmpty() && t.isNotEmpty()) {
                    synchronized(lock) { titles["${m.id}|$sid"] = t }
                }
                uiRefresh(type)
            }
            type == "session.deleted" -> {
                uiRefresh(type)
            }
            type == "session.idle" -> {
                val sid = props.optString("sessionID")
                if (sid.isEmpty()) return
                synchronized(lock) {
                    val stale = pending.values.filter { it.machineId == m.id && it.sid == sid }.map { it.rid }
                    val nm = getSystemService(NotificationManager::class.java)
                    for (r in stale) {
                        val k = pendingKey(m.id, r)
                        notifIds.remove(k)?.let { nm?.cancel(it) }
                        pending.remove(k)
                    }
                }
                Thread {
                    val title = sessionTitle(m, sid)
                    Thread.sleep(300)
                    val text = try { mkApi(m.url, m.user, m.pass).lastAssistantText(sid) } catch (_: Exception) { "" }
                    if (stopFlag || (stopFlags[m.id] ?: false)) return@Thread
                    getSystemService(NotificationManager::class.java)?.notify(
                        ((sid.hashCode() and 0x3FF) + 5000),
                        Notifications.buildFinished(this@SseService, "${m.name} · $title", text),
                    )
                    uiRefresh(type)
                }.start()
            }
            type == "session.error" -> {
                val sid = props.optString("sessionID")
                val err = props.optJSONObject("error") ?: JSONObject()
                val name = err.optString("name", "error")
                var msg = err.optString("message", "")
                if (msg.isEmpty()) msg = err.optJSONObject("data")?.optString("message", "") ?: ""
                if (msg.isEmpty()) msg = err.toString().take(200)
                val title = sessionTitle(m, sid)
                getSystemService(NotificationManager::class.java)?.notify(
                    ((sid.hashCode() and 0x3FF) + 6000),
                    Notifications.buildError(this, "${m.name} · $title", "$name: $msg"),
                )
            }
            type == "permission.v2.asked" || type == "permission.asked" -> {
                val v2 = type.startsWith("permission.v2")
                val rid = props.optString("id")
                val sid = props.optString("sessionID")
                if (rid.isEmpty() || sid.isEmpty()) return
                val st = sessionTitle(m, sid)
                var detail = if (v2) props.optString("action", "permission") else "Type: ${props.optString("permission", "permission")}"
                val res = props.optJSONArray(if (v2) "resources" else "patterns")
                if (res != null && res.length() > 0) {
                    val join = StringBuilder()
                    for (i in 0 until res.length()) {
                        join.append(res.optString(i))
                        if (i + 1 < res.length()) join.append(", ")
                    }
                    detail += ": $join"
                }
                addPending(Pending(v2, "permission", m.id, m.name, sid, rid, "${m.name} · Permission · $st", detail, JSONArray()))
                val acts = ArrayList<Notification.Action>()
                for (pair in listOf("once" to "Allow once", "always" to "Always", "reject" to "Deny")) {
                    acts.add(
                        Notifications.action(
                            this,
                            Intent(ACTION_PERMIT)
                                .putExtra(EXTRA_MID, m.id)
                                .putExtra(EXTRA_SID, sid)
                                .putExtra(EXTRA_RID, rid)
                                .putExtra(EXTRA_V2, v2)
                                .putExtra(EXTRA_REPLY, pair.first),
                            pair.second,
                        ),
                    )
                }
                getSystemService(NotificationManager::class.java)
                    ?.notify(notifKey(m.id, rid), Notifications.buildAttention(this, "${m.name}: Permission needed", "$st\n$detail", acts))
            }
            type == "question.v2.asked" || type == "question.asked" -> {
                val v2 = type.startsWith("question.v2")
                val rid = props.optString("id")
                val sid = props.optString("sessionID")
                if (rid.isEmpty()) return
                val st = sessionTitle(m, sid)
                val qs = props.optJSONArray("questions") ?: JSONArray()
                var head = ""
                var body = ""
                if (qs.length() > 0) {
                    head = qs.getJSONObject(0).optString("header", "")
                    body = qs.getJSONObject(0).optString("question", "")
                    if (qs.length() > 1) body += " (+${qs.length() - 1} more)"
                }
                addPending(Pending(v2, "question", m.id, m.name, sid, rid, "${m.name} · Question · $st", body, qs))
                val acts = ArrayList<Notification.Action>()
                val single = qs.length() == 1 && !qs.getJSONObject(0).optBoolean("multiple")
                val opts = if (single) qs.getJSONObject(0).optJSONArray("options") else null
                if (single && opts != null && opts.length() in 1..3) {
                    for (i in 0 until opts.length()) {
                        val label = opts.getJSONObject(i).optString("label", "Option ${i + 1}")
                        val answers = JSONArray().put(JSONArray().put(label))
                        acts.add(
                            Notifications.action(
                                this,
                                Intent(ACTION_ANSWER)
                                    .putExtra(EXTRA_MID, m.id)
                                    .putExtra(EXTRA_SID, sid)
                                    .putExtra(EXTRA_RID, rid)
                                    .putExtra(EXTRA_V2, v2)
                                    .putExtra(EXTRA_LABELS, answers.toString()),
                                label,
                            ),
                        )
                    }
                    if (opts.length() < 3) {
                        acts.add(
                            Notifications.action(
                                this,
                                Intent(ACTION_QREJECT)
                                    .putExtra(EXTRA_MID, m.id)
                                    .putExtra(EXTRA_SID, sid)
                                    .putExtra(EXTRA_RID, rid)
                                    .putExtra(EXTRA_V2, v2),
                                "Reject",
                            ),
                        )
                    }
                }
                val ntitle = if (head.isEmpty()) "Question" else head
                getSystemService(NotificationManager::class.java)
                    ?.notify(notifKey(m.id, rid), Notifications.buildAttention(this, "${m.name}: $ntitle · $st", body, acts))
            }
            type.startsWith("permission.replied") || type.startsWith("permission.v2.replied") ||
                type.startsWith("question.replied") || type.startsWith("question.v2.replied") ||
                type.startsWith("question.rejected") || type.startsWith("question.v2.rejected") -> {
                val rid = props.optString("id").ifBlank { props.optString("permissionID") }
                if (rid.isNotEmpty()) forget(m.id, rid)
            }
        }
    }
}
