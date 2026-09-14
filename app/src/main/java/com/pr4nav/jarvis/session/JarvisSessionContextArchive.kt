package com.pr4nav.jarvis.session

import android.content.Context
import android.os.Environment
import android.util.Log
import com.pr4nav.jarvis.artifacts.JarvisArtifactManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured Session Context & Historical Memory Archive.
 *
 * Requirements:
 * 1. Saves formatted, well-structured past session context in a text file (`jarvis_sessions_context.txt`).
 * 2. Uses Topics and Markdown Tables (JEE study plans, nocturnal rhythms, historical session index, generated artifacts/UI).
 * 3. Keeps system prompt compact by pointing to this file.
 * 4. Stores deep recent turns (last messages and conclusions, not just first 3 prompts) so context is never lost.
 * 5. Powers the `remember` / `search_context` tool to retrieve precise context on demand.
 */
object JarvisSessionContextArchive {

    private const val TAG = "SessionContextArchive"
    const val ARCHIVE_FILENAME = "jarvis_sessions_context.txt"

    private fun getInternalFile(context: Context): File {
        return File(context.filesDir, ARCHIVE_FILENAME)
    }

    private fun getExternalDir(): File? {
        return try {
            val sd = Environment.getExternalStorageDirectory()
            if (sd != null && sd.exists() && sd.canWrite()) {
                val dir = File(sd, "JARVIS")
                if (!dir.exists()) dir.mkdirs()
                dir
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generates or refreshes the structured archive from all stored sessions and artifacts.
     */
    @Synchronized
    fun updateArchive(context: Context): String {
        val sessions = JarvisSessionManager.listSessions(context)
        val sb = StringBuilder()
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        sb.append("# JARVIS PERSISTENT MEMORY & SESSION ARCHIVE\n")
        sb.append("Last Synchronized: $nowStr\n\n")

        // ── TOPIC 1: USER PROFILE & JEE 2027 ROADMAP ──
        sb.append("## TOPIC: USER PROFILE & JEE ROADMAP\n")
        sb.append("| Field | Value | Notes |\n")
        sb.append("|---|---|---|\n")
        sb.append("| Name | Pranav | 19, JEE drop year (2026-27) |\n")
        sb.append("| Primary Target | JEE Main / Advanced Jan 2027 | Highest priority focus |\n")
        sb.append("| Secondary Target | JEE Apr 2027 | Backup window |\n")
        sb.append("| Nocturnal Rhythm | Sleep ~6 AM to ~2 PM | Core study window: 11 PM to 5 AM |\n")
        sb.append("| Communication | Terse, short lowercase bursts | No throat-clearing, no motivational fluff |\n")
        sb.append("| Co-pilot Mode | Human Pin / Anti-Ignore | Action-first, non-swipeable sticky pins |\n\n")

        // ── TOPIC 2: JEE SUBJECT TOPICS & TRACKING ──
        sb.append("## TOPIC: JEE SUBJECT MODULES & TARGETS\n")
        sb.append("| Subject | Core Focus Topics | Priority | Target Status |\n")
        sb.append("|---|---|---|---|\n")
        sb.append("| Physics | Mechanics, Rotational Dynamics, Electromagnetism, Modern Physics | Critical | Active Problem Sets |\n")
        sb.append("| Chemistry | Organic Mechanisms, Thermodynamics, Chemical Bonding, Coordination | High | Active Revision |\n")
        sb.append("| Mathematics | Calculus, Coordinate Geometry, Vectors & 3D, Probability | Critical | Daily Timed Drills |\n\n")

        // ── TOPIC 3: NOCTURNAL CLOCK & CHECK-IN RHYTHMS ──
        sb.append("## TOPIC: NOCTURNAL SCHEDULE & CHECK-IN RHYTHMS\n")
        sb.append("| Routine | Time (24H) | Purpose | Escalation Rule |\n")
        sb.append("|---|---|---|---|\n")
        sb.append("| Sleep Window | 06:00 - 14:00 | Sleep period | Strictly zero proactive pings |\n")
        sb.append("| Kickoff Pin | 15:30 | Day start check-in | Sticky pin + yesterday's ignored digest |\n")
        sb.append("| Mid-Session Check | 23:00 | Overnight study progress | Sticky pin, 20m buzz escalation |\n")
        sb.append("| Pre-Sleep Review | 05:00 | Session summary logging | Sticky pin before sleep |\n")
        sb.append("| Battery Monitor | Dynamic (20% & 13%) | Prevent overnight phone death | 20% sticky warning, 13% critical buzzing |\n\n")

        // ── TOPIC 4: GENERATED ARTIFACTS & UI APPS ──
        sb.append("## TOPIC: GENERATED ARTIFACTS & UI APPS DIRECTORY\n")
        try {
            sb.append(JarvisArtifactManager.getArtifactsMarkdownTable(context))
        } catch (e: Exception) {
            sb.append("_Error generating artifact index: ${e.message}_\n")
        }
        sb.append("\n")

        // ── TOPIC 5: HISTORICAL SESSIONS TABLE ──
        sb.append("## TOPIC: HISTORICAL SESSIONS DIRECTORY\n")
        sb.append("| Date / Time | Session Title | Session ID | Messages | Initial Goal | Last Status / Conclusion |\n")
        sb.append("|---|---|---|---|---|---|\n")

        for (s in sessions.take(40)) {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.createdAtMs))
            val userMsgs = s.messages.filter { it.sender == "user" }
            val firstPrompt = userMsgs.firstOrNull()?.text?.replace("|", "/")?.take(45) ?: "(No prompt)"
            val lastAgentMsg = s.messages.lastOrNull { it.sender == "agent" }?.text?.replace("|", "/")?.take(55) ?: "Active"
            sb.append("| $dateStr | ${s.title.replace("|", "/")} | `${s.id}` | ${s.messages.size} | $firstPrompt | $lastAgentMsg |\n")
        }
        sb.append("\n")

        // ── TOPIC 6: DEEP RECENT SESSIONS & LAST CONVERSATION TURNS ──
        sb.append("## TOPIC: DEEP RECENT SESSIONS & LAST CONVERSATION TURNS\n")
        for (s in sessions.take(10)) {
            if (s.messages.isEmpty()) continue
            val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(s.createdAtMs))
            val lastActiveStr = SimpleDateFormat("HH:mm", Locale.US).format(Date(s.lastUsedMs))

            sb.append("### Session: ${s.title} (Created: $dateStr, Last Active: $lastActiveStr)\n")
            sb.append("- **Session ID**: `${s.id}` | **Type**: `${s.type.name}` | **Model**: `${s.modelUsed}`\n")

            // Grab the last 6 messages of this session to ensure recent turns, context, and decisions are captured
            val recentTurns = s.messages.takeLast(6)
            for (m in recentTurns) {
                val role = if (m.sender == "user") "User" else "JARVIS"
                val cleanText = m.text.replace("\n", " ").take(280)
                sb.append("- **$role**: $cleanText\n")
                if (!m.steps.isNullOrEmpty()) {
                    val stepSummary = m.steps.take(2).joinToString(" | ") { it.take(80).replace("\n", " ") }
                    sb.append("  _Steps: ${stepSummary}_\n")
                }
            }
            sb.append("\n")
        }

        val fullText = sb.toString()

        // 1. Write internal file
        try {
            getInternalFile(context).writeText(fullText)
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing internal archive: ${e.message}")
        }

        // 2. Sync to external /sdcard/JARVIS/jarvis_sessions_context.txt
        try {
            val extDir = getExternalDir()
            if (extDir != null) {
                File(extDir, ARCHIVE_FILENAME).writeText(fullText)
                // Also copy to root /sdcard/jarvis_sessions_context.txt for convenience
                val rootSd = Environment.getExternalStorageDirectory()
                if (rootSd != null && rootSd.canWrite()) {
                    File(rootSd, ARCHIVE_FILENAME).writeText(fullText)
                }
            }
        } catch (_: Exception) {}

        return fullText
    }

    /**
     * Reads or searches the archive text for a query or topic.
     */
    fun searchContext(context: Context, query: String = "", topic: String = ""): String {
        var content = try {
            val f = getInternalFile(context)
            if (f.exists() && f.length() > 0) f.readText() else updateArchive(context)
        } catch (_: Exception) {
            updateArchive(context)
        }

        val q = query.trim().lowercase()
        val t = topic.trim().lowercase()

        if (q.isBlank() && t.isBlank()) {
            return content.take(6000) // Return top topics, tables, and recent turns
        }

        val lines = content.lines()
        val matches = mutableListOf<String>()
        var currentHeader = ""
        var inMatchingSection = false

        for (line in lines) {
            if (line.startsWith("#")) {
                currentHeader = line
                inMatchingSection = (t.isNotBlank() && line.lowercase().contains(t)) ||
                        (q.isNotBlank() && line.lowercase().contains(q))
                if (inMatchingSection) {
                    matches.add("\n$line")
                }
                continue
            }

            val lineMatches = (q.isNotBlank() && line.lowercase().contains(q)) ||
                    (t.isNotBlank() && currentHeader.lowercase().contains(t))

            if (inMatchingSection || lineMatches) {
                if (!matches.contains(currentHeader) && currentHeader.isNotBlank()) {
                    matches.add("\n$currentHeader")
                }
                matches.add(line)
            }
        }

        return if (matches.isNotEmpty()) {
            matches.take(120).joinToString("\n").trim()
        } else {
            "No specific past context found for '$query'. Topics available: USER PROFILE, JEE SUBJECTS, NOCTURNAL SCHEDULE, GENERATED ARTIFACTS, HISTORICAL SESSIONS."
        }
    }
}
