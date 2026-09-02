package com.pr4nav.jarvis.context

import org.json.JSONObject

/**
 * Tracks conversational context (active contact, app, file, URL, action)
 * to resolve pronouns ("him", "her", "it", "that file", "this app", "there")
 * without hallucinating context when none exists.
 */
object ConversationalContext {

    data class TurnContext(
        var lastContact: String? = null,
        var lastApp: String? = null,
        var lastFile: String? = null,
        var lastUrl: String? = null,
        var lastLocation: String? = null,
        var lastAction: String? = null,
        var timestamp: Long = System.currentTimeMillis()
    )

    data class ConversationTurn(
        val userQuery: String,
        val assistantResponse: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val sessionContext = TurnContext()
    private val turnHistory = java.util.Collections.synchronizedList(mutableListOf<ConversationTurn>())

    fun recordTurn(userQuery: String, assistantResponse: String) {
        if (userQuery.isNotBlank() && assistantResponse.isNotBlank()) {
            turnHistory.add(ConversationTurn(userQuery.trim(), assistantResponse.trim()))
            // Keep at most 10 recent turns
            while (turnHistory.size > 10) {
                turnHistory.removeAt(0)
            }
        }
    }

    fun getRecentTurns(limit: Int = 4): List<Pair<String, String>> {
        val recent = synchronized(turnHistory) {
            val valid = turnHistory.filter { System.currentTimeMillis() - it.timestamp < 10 * 60 * 1000L }
            valid.takeLast(limit)
        }
        val list = mutableListOf<Pair<String, String>>()
        for (turn in recent) {
            list.add("user" to turn.userQuery)
            list.add("assistant" to turn.assistantResponse)
        }
        return list
    }

    fun getRecentHistory(limit: Int = 4): String {
        val recent = synchronized(turnHistory) {
            val valid = turnHistory.filter { System.currentTimeMillis() - it.timestamp < 10 * 60 * 1000L }
            valid.takeLast(limit)
        }
        if (recent.isEmpty()) return ""
        return buildString {
            append("Recent dialogue context:\n")
            for (turn in recent) {
                append("User: ").append(turn.userQuery).append("\n")
                append("Jarvis: ").append(turn.assistantResponse).append("\n")
            }
        }.trim()
    }

    /**
     * Extracts a compact list of topics, key questions, and entities from the past 4-5 turns.
     * Prevents bloating message tokens while keeping the model anchored in the conversation.
     */
    fun getCompactTopicSummary(limit: Int = 5): String {
        val recent = synchronized(turnHistory) {
            val valid = turnHistory.filter { System.currentTimeMillis() - it.timestamp < 15 * 60 * 1000L }
            valid.takeLast(limit)
        }
        if (recent.isEmpty()) return ""

        val sb = StringBuilder()
        for ((idx, turn) in recent.withIndex()) {
            val qTopic = extractTopicKeywords(turn.userQuery)
            val aFact = turn.assistantResponse.lines().firstOrNull { it.isNotBlank() }?.take(80)?.trim() ?: ""
            sb.append("${idx + 1}. Topic: \"$qTopic\"")
            if (aFact.isNotBlank()) {
                sb.append(" ➔ $aFact")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun extractTopicKeywords(query: String): String {
        val cleaned = query.replace(Regex("(?i)^(what is|what's|tell me about|who is|who's|how is|how does|where is|can you|please|search for|lookup)\\s+"), "").trim()
        return if (cleaned.length > 50) cleaned.take(47) + "…" else cleaned
    }

    /**
     * Compact active context summary (active contact, app, file, location, last action)
     * for prompt grounding without bloating tokens.
     */
    fun getActiveContextSummary(): String {
        val sb = StringBuilder()
        val c = sessionContext
        if (System.currentTimeMillis() - c.timestamp < 10 * 60 * 1000L) {
            if (!c.lastContact.isNullOrBlank()) sb.append("• Active Contact: ${c.lastContact}\n")
            if (!c.lastApp.isNullOrBlank()) sb.append("• Active App: ${c.lastApp}\n")
            if (!c.lastFile.isNullOrBlank()) sb.append("• Active File/Path: ${c.lastFile}\n")
            if (!c.lastLocation.isNullOrBlank()) sb.append("• Active Location: ${c.lastLocation}\n")
            if (!c.lastAction.isNullOrBlank()) sb.append("• Last Action: ${c.lastAction}\n")
        }
        return sb.toString().trim()
    }

    fun updateContext(
        tool: String,
        args: JSONObject
    ) {
        sessionContext.timestamp = System.currentTimeMillis()
        sessionContext.lastAction = tool

        when (tool) {
            "call_contact", "call_number", "send_message", "find_contact" -> {
                val c = args.optString("number").takeIf { it.isNotBlank() }
                    ?: args.optString("recipient").takeIf { it.isNotBlank() }
                    ?: args.optString("contact").takeIf { it.isNotBlank() }
                    ?: args.optString("name").takeIf { it.isNotBlank() }
                if (c != null && !isPronoun(c)) sessionContext.lastContact = c
            }
            "open_app", "close_app" -> {
                val a = args.optString("app").takeIf { it.isNotBlank() }
                    ?: args.optString("package").takeIf { it.isNotBlank() }
                if (a != null && !isPronoun(a)) sessionContext.lastApp = a
            }
            "read_file", "write_file", "delete_file", "open_file", "search_files" -> {
                val f = args.optString("path").takeIf { it.isNotBlank() }
                    ?: args.optString("query").takeIf { it.isNotBlank() }
                if (f != null && !isPronoun(f)) sessionContext.lastFile = f
            }
            "navigate" -> {
                val d = args.optString("destination").takeIf { it.isNotBlank() }
                if (d != null && !isPronoun(d)) sessionContext.lastLocation = d
            }
            "open_url", "search_web" -> {
                val u = args.optString("url").takeIf { it.isNotBlank() }
                    ?: args.optString("query").takeIf { it.isNotBlank() }
                if (u != null) sessionContext.lastUrl = u
            }
        }
    }

    private fun isPronoun(s: String): Boolean {
        val lower = s.trim().lowercase()
        return lower in listOf("him", "her", "it", "this", "that", "there", "them", "usko", "isse", "usse", "wahan")
    }

    fun getContact(): String? = sessionContext.lastContact
    fun getApp(): String? = sessionContext.lastApp
    fun getFile(): String? = sessionContext.lastFile
    fun getLocation(): String? = sessionContext.lastLocation
    fun getUrl(): String? = sessionContext.lastUrl
    fun getLastAction(): String? = sessionContext.lastAction

    fun clear() {
        sessionContext.lastContact = null
        sessionContext.lastApp = null
        sessionContext.lastFile = null
        sessionContext.lastUrl = null
        sessionContext.lastLocation = null
        sessionContext.lastAction = null
        sessionContext.timestamp = System.currentTimeMillis()
    }

    /**
     * Resolves pronouns in input prompt using active conversational context.
     * If pronouns exist but no matching context exists, returns original prompt (does not hallucinate).
     */
    fun resolvePronouns(prompt: String): String {
        var resolved = prompt.trim()

        // 1. Contact pronouns: "him", "her", "uske", "unhe", "usse"
        val contact = sessionContext.lastContact
        if (!contact.isNullOrBlank()) {
            resolved = resolved.replace(Regex("\\b(?:call|phone|dial|ring)\\s+him\\b", RegexOption.IGNORE_CASE), "call $contact")
                .replace(Regex("\\b(?:call|phone|dial|ring)\\s+her\\b", RegexOption.IGNORE_CASE), "call $contact")
                .replace(Regex("\\b(?:message|text|sms)\\s+him\\b", RegexOption.IGNORE_CASE), "message $contact")
                .replace(Regex("\\b(?:message|text|sms)\\s+her\\b", RegexOption.IGNORE_CASE), "message $contact")
                .replace(Regex("\\bto\\s+him\\b", RegexOption.IGNORE_CASE), "to $contact")
                .replace(Regex("\\bto\\s+her\\b", RegexOption.IGNORE_CASE), "to $contact")
                .replace(Regex("\\busko\\s+(?:call|phone)\\b", RegexOption.IGNORE_CASE), "$contact ko call")
                .replace(Regex("\\busse\\s+baat\\b", RegexOption.IGNORE_CASE), "$contact se baat")
                .replace(Regex("\\busse\\s+message\\b", RegexOption.IGNORE_CASE), "$contact ko message")
        }

        // 2. App pronouns: "this app", "that app", "the app"
        val app = sessionContext.lastApp
        if (!app.isNullOrBlank()) {
            resolved = resolved.replace(Regex("\\b(?:close|stop|kill)\\s+(?:this|that|the)\\s+app\\b", RegexOption.IGNORE_CASE), "close $app")
                .replace(Regex("\\bis\\s+app\\s+ko\\s+band\\b", RegexOption.IGNORE_CASE), "$app band")
        }

        // 3. File pronouns: "that file", "this file", "the file"
        val file = sessionContext.lastFile
        if (!file.isNullOrBlank()) {
            resolved = resolved.replace(Regex("\\b(?:read|open|show|cat|view)\\s+(?:file\\s+)?(?:this|that|the)\\s+file\\b", RegexOption.IGNORE_CASE), "read file $file")
                .replace(Regex("\\b(?:delete|remove|rm)\\s+(?:file\\s+)?(?:this|that|the)\\s+file\\b", RegexOption.IGNORE_CASE), "delete file $file")
                .replace(Regex("\\b(?:this|that|the)\\s+file\\b", RegexOption.IGNORE_CASE), file)
        }

        // 4. Location pronouns: "there", "wahan"
        val loc = sessionContext.lastLocation
        if (!loc.isNullOrBlank()) {
            resolved = resolved.replace(Regex("\\b(?:take me|navigate|drive)\\s+there\\b", RegexOption.IGNORE_CASE), "take me to $loc")
                .replace(Regex("\\bwahan\\s+le\\s+chalo\\b", RegexOption.IGNORE_CASE), "$loc le chalo")
        }

        return resolved
    }
}
