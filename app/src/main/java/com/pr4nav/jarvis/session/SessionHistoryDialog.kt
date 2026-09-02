package com.pr4nav.jarvis.session

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.pr4nav.jarvis.R

/**
 * Clean Stark HUD Dialog for browsing, switching, and creating sessions.
 * Lists sessions sorted Last Used First, named by creation Date & Time only.
 */
class SessionHistoryDialog(
    context: Context,
    private val filterType: SessionType? = null,
    private val currentSessionId: String? = null,
    private val onSessionSelected: (JarvisSession) -> Unit,
    private val onNewSessionRequested: () -> Unit
) : Dialog(context) {

    private lateinit var listView: ListView
    private lateinit var btnNewSession: Button
    private lateinit var txtHeaderTitle: TextView
    private var sessionList = listOf<JarvisSession>()
    private var adapter: SessionAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setContentView(R.layout.dialog_session_history)

        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.80).toInt()
        )

        listView = findViewById(R.id.list_sessions)
        btnNewSession = findViewById(R.id.btn_dialog_new_session)
        txtHeaderTitle = findViewById(R.id.txt_dialog_header_title)
        findViewById<View>(R.id.btn_dialog_close)?.setOnClickListener { dismiss() }

        val typeLabel = when (filterType) {
            SessionType.AGENT_CHAT -> "Agent Chat Sessions"
            SessionType.VOICE_CHAT -> "Voice Chat Sessions"
            SessionType.AGY_CODING -> "AGY Coding Sessions"
            null -> "All Sessions"
        }
        txtHeaderTitle.text = typeLabel

        btnNewSession.setOnClickListener {
            dismiss()
            onNewSessionRequested()
        }

        refreshList()
    }

    private fun refreshList() {
        sessionList = JarvisSessionManager.listSessions(context, filterType)
        adapter = SessionAdapter()
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val session = sessionList[position]
            JarvisSessionManager.setActiveSessionId(context, session.type, session.id)
            dismiss()
            onSessionSelected(session)
        }
    }

    private inner class SessionAdapter : BaseAdapter() {
        override fun getCount(): Int = sessionList.size
        override fun getItem(position: Int): JarvisSession = sessionList[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_session_card, parent, false)
            val session = getItem(position)

            val txtTitle = view.findViewById<TextView>(R.id.item_session_title)
            val txtSubtitle = view.findViewById<TextView>(R.id.item_session_subtitle)
            val badgeType = view.findViewById<TextView>(R.id.item_session_badge)
            val txtActive = view.findViewById<TextView>(R.id.item_session_active_tag)
            val btnDelete = view.findViewById<View>(R.id.item_session_btn_delete)

            // Named by Date and Time of creation
            txtTitle.text = session.title

            val msgCount = session.messages.size
            val lastActiveDiff = System.currentTimeMillis() - session.lastUsedMs
            val lastActiveStr = when {
                lastActiveDiff < 60_000 -> "Just now"
                lastActiveDiff < 3600_000 -> "${lastActiveDiff / 60_000}m ago"
                lastActiveDiff < 86400_000 -> "${lastActiveDiff / 3600_000}h ago"
                else -> "${lastActiveDiff / 86400_000}d ago"
            }
            txtSubtitle.text = "$msgCount turns • Active $lastActiveStr • ${session.workingDir}"

            when (session.type) {
                SessionType.AGENT_CHAT -> {
                    badgeType.text = "💬 CHAT"
                    badgeType.setTextColor(Color.parseColor("#38BDF8"))
                }
                SessionType.VOICE_CHAT -> {
                    badgeType.text = "🎙️ VOICE"
                    badgeType.setTextColor(Color.parseColor("#10B981"))
                }
                SessionType.AGY_CODING -> {
                    badgeType.text = "⚡ AGY CODE"
                    badgeType.setTextColor(Color.parseColor("#F59E0B"))
                }
            }

            if (session.id == currentSessionId) {
                txtActive.visibility = View.VISIBLE
                view.setBackgroundResource(R.drawable.bg_agent_input_bar)
            } else {
                txtActive.visibility = View.GONE
                view.setBackgroundResource(R.drawable.bg_step_card)
            }

            btnDelete.setOnClickListener {
                JarvisSessionManager.deleteSession(context, session.id)
                Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                refreshList()
            }

            return view
        }
    }
}
