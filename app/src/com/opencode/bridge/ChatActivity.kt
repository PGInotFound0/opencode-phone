package com.opencode.bridge

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class ChatActivity : Activity() {

    private var mid = ""
    private var machineName = ""
    private var sid = ""
    private var tuiMode = false

    @Volatile private var alive = false

    private lateinit var listView: ListView
    private lateinit var input: EditText
    private lateinit var send: Button
    private val rows = ArrayList<ChatMsg>()
    private val adapter = ChatAdapter()
    private var api: OcApi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        setContentView(R.layout.chat)

        mid = intent.getStringExtra("mid") ?: Prefs.PRIMARY_ID
        machineName = intent.getStringExtra("mname") ?: "Machine"
        sid = intent.getStringExtra("sid") ?: ""

        val m = Prefs.machines(this).find { it.id == mid }
        if (m != null) {
            api = OcApi(m.url, m.user, m.pass)
            tuiMode = m.tui
        }

        listView = findViewById(R.id.chat_msgs)
        listView.adapter = adapter

        input = findViewById(R.id.chat_input)
        send = findViewById(R.id.chat_send)

        findViewById<TextView>(R.id.chat_title).text =
            (intent.getStringExtra("title") ?: "Session").ifBlank { "Session" }
        findViewById<TextView>(R.id.chat_machine).text =
            machineName + if (tuiMode) " · sends go to the live opencode window" else ""

        findViewById<Button>(R.id.chat_abort).setOnClickListener {
            val a = api ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Abort this run?")
                .setPositiveButton("Abort") { _, _ ->
                    Thread {
                        val ok = try { a.abort(sid); true } catch (e: Exception) { toastErr(e); false }
                        if (ok) runOnUiThread { toast("Abort requested") }
                    }.start()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        send.setOnClickListener {
            val a = api ?: return@setOnClickListener
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            send.isEnabled = false
            Thread {
                var ok = false
                val err = try {
                    if (tuiMode) a.tuiPrompt(text) else a.prompt(sid, text)
                    ok = true; ""
                } catch (e: Exception) {
                    e.message?.take(120) ?: "failed"
                }
                runOnUiThread {
                    if (ok) {
                        input.setText("")
                        toast(if (tuiMode) "Typed into the live window" else "Sent")
                    } else {
                        toast("Failed: $err")
                    }
                    send.isEnabled = true
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        alive = true
        reload()
        Thread {
            while (alive) {
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
                if (!alive) break
                reload()
            }
        }.apply { name = "chat-poll"; isDaemon = true }.start()
    }

    override fun onPause() {
        alive = false
        super.onPause()
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    private fun reload() {
        val a = api ?: return
        Thread {
            try {
                val msgs = a.messages(sid)
                val busy = a.sessionList().any { it.id == sid && it.running }
                runOnUiThread {
                    if (!alive || isFinishing || isDestroyed) return@runOnUiThread
                    rows.clear()
                    rows.addAll(msgs)
                    adapter.notifyDataSetChanged()
                    updateBusy(busy)
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private var titleBase: String? = null

    private fun updateBusy(busy: Boolean) {
        val t = findViewById<TextView>(R.id.chat_title)
        val base = titleBase ?: t.text.toString().trimEnd(' ', '●', '○').also { titleBase = it }
        t.text = if (busy) "$base ●" else base
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun toastErr(e: Exception) {
        Toast.makeText(this, "Failed: ${e.message?.take(120)}", Toast.LENGTH_SHORT).show()
    }

    private inner class ChatAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val msg = rows[position]
            val tag = msg.kind + ":" + msg.role
            return when {
                msg.kind == "tool" -> buildToolRow(msg.text, convertView, tag)
                else -> buildBubbleRow(msg.text, msg.role == "user", convertView, tag)
            }
        }
    }

    private fun buildBubbleRow(text: String, isUser: Boolean, convertView: View?, tag: String): View {
        if (convertView != null && convertView.tag == tag && convertView is FrameLayout) {
            (convertView.getChildAt(0) as? TextView)?.text = text
            return convertView
        }
        val wrap = FrameLayout(this)
        wrap.tag = tag
        val v = TextView(this)
        v.text = text
        v.textSize = 14f
        v.setTextColor(if (isUser) 0xFFFFFFFF.toInt() else 0xFFF5F5F6.toInt())
        v.setPadding(dp(12), dp(10), dp(12), dp(10))
        v.setBackgroundResource(if (isUser) R.drawable.bubble_user else R.drawable.bubble_assistant)
        wrap.addView(
            v,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (isUser) (Gravity.END or Gravity.TOP) else (Gravity.START or Gravity.TOP),
            ).apply { topMargin = dp(6) },
        )
        wrap.layoutParams = AbsListView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return wrap
    }

    private fun buildToolRow(text: String, convertView: View?, tag: String): View {
        if (convertView != null && convertView.tag == tag && convertView is TextView) {
            convertView.text = text
            return convertView
        }
        val v = TextView(this)
        v.text = text
        v.textSize = 12f
        v.setTypeface(null, Typeface.ITALIC)
        v.setTextColor(0xFF8E8E96.toInt())
        v.setPadding(dp(6), dp(3), dp(6), dp(3))
        v.tag = tag
        return v
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
