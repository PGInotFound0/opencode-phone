package com.opencode.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import android.widget.ScrollView
import android.widget.FrameLayout

class MainActivity : Activity() {

    private lateinit var urlView: EditText
    private lateinit var userView: EditText
    private lateinit var passView: EditText
    private lateinit var keepView: CheckBox
    private lateinit var autoView: CheckBox
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var machineBox: LinearLayout
    private lateinit var sessionsBox: LinearLayout
    private lateinit var pendingBox: LinearLayout

    private val uiRcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refresh(false)
        }
    }

    private fun api(): OcApi? {
        val m = Prefs.machines(this).find { it.id == Prefs.PRIMARY_ID } ?: return null
        if (m.url.isBlank()) return null
        return OcApi(m.url, m.user, m.pass)
    }

    private fun apiFor(mid: String): OcApi? {
        val m = Prefs.machines(this).find { it.id == mid } ?: return null
        return OcApi(m.url, m.user, m.pass)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status_view)
        hintView = findViewById(R.id.hint_view)
        urlView = findViewById(R.id.url_view)
        userView = findViewById(R.id.user_view)
        passView = findViewById(R.id.pass_view)
        keepView = findViewById(R.id.keep_view)
        autoView = findViewById(R.id.auto_view)
        machineBox = findViewById(R.id.machine_box)
        sessionsBox = findViewById(R.id.sessions_box)
        pendingBox = findViewById(R.id.pending_box)

        urlView.setText(Prefs.serverUrl(this))
        userView.setText(Prefs.username(this))
        passView.setText(Prefs.password(this))
        keepView.isChecked = Prefs.keepAwake(this)
        autoView.isChecked = Prefs.autoStart(this)

        val f = IntentFilter(SseService.ACTION_UI)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(uiRcv, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiRcv, f)
        }

        findViewById<Button>(R.id.connect_view).setOnClickListener {
            val u = urlView.text.toString().trim()
            if (u.isEmpty()) {
                toast("Enter the opencode server URL first")
                return@setOnClickListener
            }
            Prefs.save(
                this,
                u,
                userView.text.toString().trim().ifBlank { "opencode" },
                passView.text.toString(),
                keepView.isChecked,
                autoView.isChecked,
            )
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
            try {
                startForegroundService(Intent(this, SseService::class.java).setAction(SseService.ACTION_START))
                toast("Connecting…")
            } catch (e: Exception) {
                toast(e.message ?: "could not start")
            }
            refresh(true)
        }

        findViewById<Button>(R.id.disconnect_view).setOnClickListener {
            startService(Intent(this, SseService::class.java).setAction(SseService.ACTION_STOP))
            stopService(Intent(this, SseService::class.java))
            refresh(true)
        }

        findViewById<Button>(R.id.add_machine_view).setOnClickListener {
            machineDialog(null)
        }

        findViewById<Button>(R.id.test_view).setOnClickListener {
            val a = api()
            if (a == null) {
                toast("URL not saved yet")
                return@setOnClickListener
            }
            Thread {
                try {
                    val h = a.health()
                    runOnUiThread { toast("OK · opencode ${h.optString("version", "?")}") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Failed: ${e.message?.take(120)}") }
                }
            }.start()
        }

        findViewById<Button>(R.id.new_session_view).setOnClickListener {
            val a = api()
            if (a == null) {
                toast("URL not saved yet")
                return@setOnClickListener
            }
            Thread {
                try {
                    val id = a.createSession("From phone ${System.currentTimeMillis() / 1000 % 100000}")
                    if (id.isEmpty()) throw Exception("no id")
                    runOnUiThread {
                        toast("Session created — sent a ping")
                        try {
                            a.prompt(id, "Reply with the single word: pong")
                        } catch (_: Exception) {
                        }
                        refresh(false)
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Failed: ${e.message?.take(120)}") }
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh(false)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(uiRcv)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun refresh(force: Boolean) {
        val svc = SseService.instance
        statusView.text = if (svc == null) "Bridge stopped — tap “Save & connect”" else svc.statusText()
        statusView.setTextColor(
            when {
                svc == null -> 0xFFF5F5F6.toInt()
                statusView.text.toString().contains("Connected") -> 0xFFDC2626.toInt()
                statusView.text.toString().contains("Reconnect") -> 0xFFEF4444.toInt()
                else -> 0xFFF5F5F6.toInt()
            },
        )
        hintView.text = "Primary: ${Prefs.serverUrl(this).ifBlank { "not set" }}"
        renderMachines()
        renderPending()
        fetchSessions(force)
    }

    private fun text(text: String, bold: Boolean, dim: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = if (bold) 15f else 12f
        t.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        t.setTextColor(if (dim) 0xFF8E8E96.toInt() else 0xFFF5F5F6.toInt())
        t.setPadding(0, dp(6), 0, dp(2))
        return t
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun chip(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 13f
        b.isAllCaps = false
        b.minWidth = 0
        b.minimumWidth = 0
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun rowv(vararg children: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        for (c in children) l.addView(c)
        return l
    }

    private fun card(vararg children: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(10), dp(8), dp(10), dp(10))
        l.setBackgroundResource(R.drawable.card_bg)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        l.layoutParams = lp
        for (c in children) l.addView(c)
        return l
    }

    // ---------- machines ----------

    private fun renderMachines() {
        machineBox.removeAllViews()
        val list = Prefs.machines(this)
        val extras = list.filter { it.id != Prefs.PRIMARY_ID }
        if (extras.isEmpty()) {
            machineBox.addView(text("Extra machines appear here (the form above is the primary).", false, true))
            return
        }
        val status = SseService.instance?.statusSnapshot() ?: emptyMap()
        for (m in extras) {
            val st = if (!m.enabled) "disabled" else (status[m.id] ?: "stopped")
            machineBox.addView(
                card(
                    text("${m.name}  ·  $st", true, false),
                    text(m.url, false, true),
                    rowv(
                        chip(if (m.enabled) "Stop" else "Start") { toggleMachine(m) },
                        chip("Edit") { machineDialog(m) },
                        chip("Remove") { removeMachine(m) },
                    ),
                ),
            )
        }
    }

    private fun toggleMachine(m: Machine) {
        val list = Prefs.machines(this).map { if (it.id == m.id) it.copy(enabled = !it.enabled) else it }
        Prefs.saveMachines(this, list)
        SseService.instance?.syncWorkers()
        refresh(false)
    }

    private fun removeMachine(m: Machine) {
        AlertDialog.Builder(this)
            .setTitle("Remove “${m.name}”?")
            .setPositiveButton("Remove") { _, _ ->
                val list = Prefs.machines(this).filter { it.id != m.id }
                Prefs.saveMachines(this, list)
                SseService.instance?.syncWorkers()
                refresh(false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun machineDialog(existing: Machine?) {
        val name = EditText(this)
        val url = EditText(this)
        val user = EditText(this)
        val pass = EditText(this)
        val en = CheckBox(this)
        name.hint = "Name (e.g. Homelab)"
        url.hint = "http://192.168.1.20:4096"
        user.hint = "user (opencode)"
        pass.hint = "password (optional)"
        en.text = "Connected"
        pass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = dp(12)
        for (w in listOf<android.view.View>(name, url, user, pass)) {
            (w as EditText).setSingleLine(true)
            w.setTextColor(0xFFF5F5F6.toInt())
            w.setBackgroundResource(R.drawable.field_bg)
            col.addView(w, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
        }
        col.addView(en)
        existing.let { m ->
            if (m != null) {
                name.setText(m.name)
                url.setText(m.url)
                user.setText(m.user)
                pass.setText(m.pass)
                en.isChecked = m.enabled
            } else {
                en.isChecked = true
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add machine" else "Edit machine")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                val list = Prefs.machines(this).toMutableList()
                val nm = name.text.toString().trim()
                val uu = url.text.toString().trim()
                if (uu.isEmpty()) {
                    toast("URL required")
                    return@setPositiveButton
                }
                val mach = Machine(
                    id = existing?.id ?: ("m" + (1000 + (System.currentTimeMillis() % 9000)).toInt()),
                    name = nm.ifBlank { "Machine" },
                    url = uu,
                    user = user.text.toString().trim().ifBlank { "opencode" },
                    pass = pass.text.toString(),
                    enabled = en.isChecked && uu.isNotBlank(),
                )
                if (existing != null) {
                    val i = list.indexOfFirst { it.id == existing.id }
                    if (i >= 0) list[i] = mach else list.add(mach)
                } else {
                    list.add(mach)
                }
                Prefs.saveMachines(this, list)
                if (existing != null) SseService.instance?.restartMachine(mach.id)
                SseService.instance?.syncWorkers()
                refresh(false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- sessions (merged across machines) ----------

    private val sessMap = HashMap<String, Pair<String, List<SessionInfo>>>()
    private val fetching = HashSet<String>()
    private val lastFetchMs = HashMap<String, Long>()

    private fun fetchSessions(force: Boolean) {
        SseService.instance ?: return
        for (m in Prefs.machines(this)) {
            if (!m.enabled || m.url.isBlank()) continue
            val now = System.currentTimeMillis()
            val last = lastFetchMs[m.id] ?: 0
            if (!force && now - last < 2000) continue
            val already = synchronized(fetching) {
                val has = fetching.contains(m.id)
                if (!has) fetching.add(m.id)
                has
            }
            if (already) continue
            lastFetchMs[m.id] = now
            Thread {
                try {
                    val list = OcApi(m.url, m.user, m.pass).sessionList()
                    synchronized(sessMap) { sessMap[m.id] = Pair(m.name, list) }
                } catch (_: Exception) {
                    synchronized(sessMap) { sessMap.remove(m.id) }
                }
                synchronized(fetching) { fetching.remove(m.id) }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) renderSessions()
                }
            }.start()
        }
    }

    private fun renderSessions() {
        val inst = SseService.instance ?: run {
            renderEmptySessions()
            return
        }
        val order = ArrayList<Pair<Machine, Pair<String, List<SessionInfo>>>>()
        for (m in Prefs.machines(this)) {
            val pair = synchronized(sessMap) { sessMap[m.id] }
            if (pair != null) order.add(Pair(m, pair))
        }
        sessionsBox.removeAllViews()
        if (order.isEmpty()) {
            renderEmptySessions()
            return
        }
        for ((m, pair) in order) {
            val (name, list) = pair
            if (list.isEmpty()) {
                sessionsBox.addView(text("$name: no sessions", false, true))
                continue
            }
            for (s in list.take(20)) {
                val title = (if (s.running) "▶ " else "") + s.title + (if (s.child) " (child)" else "")
                sessionsBox.addView(
                    card(
                        text("[$name] $title", true, false),
                        text(s.directory, false, true),
                        rowv(
                            chip("Chat") { openChat(m, s) },
                            chip("Abort") { abortOn(m, s) },
                        ),
                    ),
                )
            }
        }
    }

    private fun renderEmptySessions() {
        sessionsBox.removeAllViews()
        sessionsBox.addView(text("No sessions yet", false, true))
    }

    private fun openChat(m: Machine, s: SessionInfo) {
        val i = Intent(this, ChatActivity::class.java)
            .putExtra("mid", m.id)
            .putExtra("mname", m.name)
            .putExtra("sid", s.id)
            .putExtra("title", s.title)
        startActivity(i)
    }

    private fun abortOn(m: Machine, s: SessionInfo) {
        Thread {
            try {
                OcApi(m.url, m.user, m.pass).abort(s.id)
                runOnUiThread { toast("Requested abort for ${s.title}") }
            } catch (e: Exception) {
                runOnUiThread { toast("Failed: ${e.message?.take(120)}") }
            }
        }.start()
    }

    // ---------- pending (questions & permissions, all machines) ----------

    private fun renderPending() {
        pendingBox.removeAllViews()
        val ps = SseService.instance?.pendings() ?: emptyList()
        if (ps.isEmpty()) {
            pendingBox.addView(text("Nothing pending", false, true))
            return
        }
        for (p in ps) {
            val body = LinearLayout(this)
            body.orientation = LinearLayout.VERTICAL

            body.addView(text(p.title, true, false))
            if (p.body.isNotEmpty()) body.addView(text(p.body, false, false))

            if (p.kind == "permission") {
                body.addView(
                    rowv(
                        chip("Once") { permit(p, "once") },
                        chip("Always") { permit(p, "always") },
                        chip("Deny") { permit(p, "reject") },
                    ),
                )
            } else {
                val single = p.questions.length() == 1 && !p.questions.getJSONObject(0).optBoolean("multiple")
                val opts = if (single) p.questions.getJSONObject(0).optJSONArray("options") else null
                val optCount = opts?.length() ?: 0
                if (single && opts != null && optCount in 1..4) {
                    val btns = ArrayList<View>()
                    for (i in 0 until optCount) {
                        val label = opts.getJSONObject(i).optString("label", "Option ${i + 1}")
                        btns.add(chip(label) { answer(p, JSONArray().put(JSONArray().put(label))) })
                    }
                    body.addView(rowv(*btns.toTypedArray()))
                    val extra = ArrayList<View>()
                    if (p.questions.getJSONObject(0).optBoolean("custom", true)) {
                        extra.add(chip("Custom…") { customAnswer(p) })
                    }
                    extra.add(chip("Deny") { qReject(p) })
                    body.addView(rowv(*extra.toTypedArray()))
                } else {
                    body.addView(
                        rowv(
                            chip("Choose…") { questionDialog(p) },
                            chip("Deny") { qReject(p) },
                        ),
                    )
                }
            }
            pendingBox.addView(body.beCard())
        }
    }

    private fun View.beCard(): View {
        val c = LinearLayout(this@MainActivity)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(10), dp(8), dp(10), dp(10))
        c.setBackgroundResource(R.drawable.card_bg)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        c.layoutParams = lp
        c.addView(this)
        return c
    }

    private fun permit(p: Pending, reply: String) {
        Thread {
            try {
                val a = apiFor(p.machineId)!!
                if (p.v2) a.permit(p.sid, p.rid, reply) else a.permitV1(p.sid, p.rid, reply)
                SseService.instance?.forget(p.machineId, p.rid)
                runOnUiThread { toast("Sent: $reply") }
            } catch (e: Exception) {
                runOnUiThread { toast("Failed: ${e.message?.take(120)}") }
            }
        }.start()
    }

    private fun answer(p: Pending, answers: JSONArray) {
        Thread {
            try {
                val a = apiFor(p.machineId)!!
                if (p.v2) a.answerQuestion(p.sid, p.rid, answers) else a.answerQuestionV1(p.rid, answers)
                SseService.instance?.forget(p.machineId, p.rid)
                runOnUiThread { toast("Answer sent") }
            } catch (e: Exception) {
                runOnUiThread { toast("Failed: ${e.message?.take(120)}") }
            }
        }.start()
    }

    private fun qReject(p: Pending) {
        Thread {
            try {
                val a = apiFor(p.machineId)!!
                if (p.v2) a.rejectQuestion(p.sid, p.rid) else a.rejectQuestionV1(p.rid)
                SseService.instance?.forget(p.machineId, p.rid)
                runOnUiThread { toast("Rejected") }
            } catch (e: Exception) {
                runOnUiThread { toast("Failed: ${e.message?.take(120)}") }
            }
        }.start()
    }

    private fun customAnswer(p: Pending) {
        val input = EditText(this)
        input.hint = "Answer text"
        val wrap = FrameLayout(this).apply {
            setPadding(dp(16), dp(4), dp(16), dp(0))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom answer")
            .setView(wrap)
            .setPositiveButton("Send") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) answer(p, JSONArray().put(JSONArray().put(t)))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun questionDialog(p: Pending) {
        try {
            val qn = p.questions.length()
            val selState = ArrayList<MutableSet<String>>()
            val customEdits = ArrayList<EditText?>()

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL

            for (qi in 0 until qn) {
                val q = p.questions.getJSONObject(qi)
                col.addView(text("${q.optString("header", "Question ${qi + 1}")}", true, false))
                col.addView(text(q.optString("question", ""), false, false))
                val opts = q.optJSONArray("options") ?: JSONArray()
                val sel = HashSet<String>()
                selState.add(sel)
                customEdits.add(null)
                if (q.optBoolean("multiple", false)) {
                    for (i in 0 until opts.length()) {
                        val label = opts.getJSONObject(i).optString("label")
                        val cb = CheckBox(this)
                        cb.text = label
                        cb.textSize = 13f
                        cb.setOnCheckedChangeListener { _, check ->
                            if (check) sel.add(label) else sel.remove(label)
                        }
                        col.addView(cb)
                    }
                } else {
                    val rg = RadioGroup(this)
                    for (i in 0 until opts.length()) {
                        val label = opts.getJSONObject(i).optString("label")
                        val rb = RadioButton(this)
                        rb.text = label
                        rb.textSize = 13f
                        rb.setOnCheckedChangeListener { _, check ->
                            if (check) {
                                sel.clear()
                                sel.add(label)
                            } else {
                                sel.remove(label)
                            }
                        }
                        rg.addView(rb)
                    }
                    col.addView(rg)
                }
                if (q.optBoolean("custom", true)) {
                    val e = EditText(this)
                    e.hint = "Or type a custom answer"
                    e.textSize = 13f
                    customEdits[qi] = e
                    col.addView(e)
                }
            }

            val wrap = ScrollView(this).apply {
                setPadding(dp(8), dp(0), dp(8), dp(0))
                addView(col)
            }

            AlertDialog.Builder(this)
                .setTitle("Opencode asks")
                .setView(wrap)
                .setPositiveButton("Send") { _, _ ->
                    val answers = JSONArray()
                    for (qi in 0 until qn) {
                        val list = JSONArray()
                        for (l in selState[qi]) list.put(l)
                        val ce = customEdits[qi]
                        if (ce != null) {
                            val t = ce.text.toString().trim()
                            if (t.isNotEmpty()) list.put(t)
                        }
                        answers.put(list)
                    }
                    answer(p, answers)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            toast(e.message ?: "dialog error")
        }
    }
}
