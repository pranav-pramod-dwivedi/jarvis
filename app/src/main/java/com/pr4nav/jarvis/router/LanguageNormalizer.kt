package com.pr4nav.jarvis.router

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Normalization result containing canonical tool name and arguments.
 */
data class NormalizedToolCall(
    val tool: String,
    val args: JSONObject,
    val confidence: Float = 1.0f,
    val matchedPhrase: String = ""
)

/**
 * Natural language normalization layer handling English, Hindi, and Hinglish.
 * Maps user queries directly to canonical tools with high confidence (>0.95),
 * ensuring the user never needs to remember internal tool keywords.
 * Also filters informational queries ("how do batteries work?") from actionable device operations.
 */
object LanguageNormalizer {

    private data class PatternRule(
        val regexes: List<Pattern>,
        val mapper: (java.util.regex.Matcher) -> NormalizedToolCall
    )

    private val rules = ArrayList<PatternRule>()

    // Informational negative patterns: questions asking for explanations, definitions, or trivia
    // These should NOT trigger phone actions!
    private val informationalPatterns = listOf(
        Pattern.compile("^(?:how\\s+do(?:es)?|why\\s+do(?:es)?)\\s+.*?(?:work|function|operate).*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:explain|describe|tell\\s+me\\s+about)\\s+(?:how\\s+)?(?:phone\\s+calls?|wi-?fi|bluetooth|batter(?:y|ies)|android\\s+settings?|cellular).*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:what\\s+is|define)\\s+(?:a\\s+|the\\s+concept\\s+of\\s+)?(?:battery\\s+percentage|wi-?fi|bluetooth|android\\s+settings?|phone\\s+call).*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^(?:battery|wifi|bluetooth|phone\\s+call)\\s+(?:kya\\s+hota\\s+hai|kaise\\s+kaam\\s+karta\\s+hai|ke\\s+bare\\s+mein\\s+batao).*$", Pattern.CASE_INSENSITIVE)
    )

    init {
        // --- 0. open_settings & structured subpage settings ---
        // English: "open settings", "open wifi settings", "show wifi settings", "go to wifi settings", "open device wifi settings"
        // Hindi / Hinglish: "settings kholo", "wifi settings kholo", "wifi setting kholo", "wifi settings open karo"
        registerRule(
            listOf(
                "^(?:open|show|launch|kholo|go to|view)\\s+(?:the\\s+|device\\s+|android\\s+)?([a-zA-Z0-9_]+)\\s+settings?(?:\\s+screen|\\s+page)?(?:\\s+pls|\\s+please)?$",
                "^(?:open|show|launch)\\s+(?:the\\s+)?settings?$",
                "^([a-zA-Z0-9_]+)\\s+settings?\\s+(?:kholo|khol do|dikhao|par jao|open karo|open kar do)$",
                "^([a-zA-Z0-9_]+)\\s+(?:configuration|setting)\\s+kholo$",
                "^settings?\\s+me\\s+([a-zA-Z0-9_]+)\\s+kholo$",
                "^settings?\\s+(?:kholo|khol do|open karo)$",
                "^kholo\\s+([a-zA-Z0-9_]+)\\s+settings?$"
            )
        ) { m ->
            val sub = if (m.groupCount() >= 1) m.group(1)?.trim()?.lowercase() ?: "" else ""
            NormalizedToolCall("open_settings", JSONObject().put("subpage", sub), 0.98f, m.group(0) ?: "")
        }

        // --- 1. open_app ---
        // English: "open chrome", "launch whatsapp", "start youtube", "fire up spotify", "can you open telegram"
        // Hindi / Hinglish: "chrome kholo", "whatsapp open karo", "youtube chalao", "spotify start kar"
        registerRule(
            listOf(
                "^(?:can you |please |bhai )?(?:open|launch|start|fire up)\\s+(?:the\\s+)?([a-zA-Z0-9_\\s]+?)(?:\\s+app)?(?:\\s+pls|\\s+please)?$",
                "^([a-zA-Z0-9_\\s]+?)\\s+(?:kholo|khol do|kholna|chalao|chala do|chalu karo)$",
                "^([a-zA-Z0-9_\\s]+?)\\s+(?:open\\s+karo|open\\s+kar do|start karo)$",
                "^kholo\\s+([a-zA-Z0-9_\\s]+)$"
            )
        ) { m ->
            val appRaw = m.group(1)?.trim() ?: ""
            val app = cleanAppTarget(appRaw)
            NormalizedToolCall("open_app", JSONObject().put("app", app), 0.98f, m.group(0) ?: "")
        }

        // --- 2. close_app ---
        // English: "close chrome", "stop spotify", "kill whatsapp", "shut down youtube"
        // Hindi / Hinglish: "chrome band karo", "spotify band kar do", "whatsapp hatao"
        registerRule(
            listOf(
                "^(?:can you |please )?(?:close|stop|kill|shut down)\\s+(?:the\\s+)?([a-zA-Z0-9_\\s]+?)(?:\\s+app)?(?:\\s+pls|\\s+please)?$",
                "^([a-zA-Z0-9_\\s]+?)\\s+(?:band\\s+karo|band\\s+kar do|rok do|hatao)$"
            )
        ) { m ->
            val appRaw = m.group(1)?.trim() ?: ""
            val app = cleanAppTarget(appRaw)
            NormalizedToolCall("close_app", JSONObject().put("package", app), 0.96f, m.group(0) ?: "")
        }

        // --- 3. navigate ---
        // English: "take me home", "navigate to pune", "directions to airport", "get me back home", "I wanna go home", "let's head home"
        // Hindi / Hinglish: "ghar ka rasta bata", "mujhe ghar le chalo", "home le chalo", "mujhe ghar pahucha do", "delhi ka rasta batao"
        registerRule(
            listOf(
                "^(?:take me (?:to |back )?|navigate (?:me )?(?:to |back )?|directions to |drive to |go to |head (?:to |towards )?)(.+)$",
                "^(?:get me back |i (?:wanna|want to) go )(?:to )?(.+)$",
                "^(?:mujhe )?(.+?)(?: le chalo| le jao| pahucha do)$",
                "^(.+?)\\s+(?:ka rasta batao|ka rasta bata|ka route dikhao)$",
                "^(.+?)\\s+chalo$"
            )
        ) { m ->
            var dest = m.group(1)?.trim() ?: "home"
            if (dest.lowercase() in listOf("ghar", "ghar ka", "my house", "home")) {
                dest = "home"
            }
            NormalizedToolCall("navigate", JSONObject().put("destination", dest), 0.99f, m.group(0) ?: "")
        }

        // --- 4. call_contact ---
        // English: "call Akhil", "can you ring Akhil", "dial Akhil", "get Akhil on the phone", "phone Akhil", "ring my friend Akhil"
        // Hindi / Hinglish: "Akhil ko call karo", "Akhil ko phone lagao", "bhai Akhil ko phone kar", "mujhe Akhil se baat karni hai", "Akhil se connect karo"
        registerRule(
            listOf(
                "^(?:can you |please |bhai )?(?:call|dial|phone|ring)\\s+(?:up\\s+|my friend\\s+)?(.+?)(?:\\s+for me|\\s+pls|\\s+please)?$",
                "^(?:get|connect)\\s+(.+?)\\s+(?:on the phone|on call|on line)$",
                "^i (?:need|want) to (?:talk|speak) to\\s+(.+)$",
                "^(.+?)\\s+ko\\s+(?:call lagao|call karo|phone karo|phone lagao|phone milao|phone kar)$",
                "^(?:mujhe\\s+)?(.+?)\\s+se\\s+(?:baat karni hai|connect karo)$",
                "^call\\s+karo\\s+(.+)$"
            )
        ) { m ->
            val target = m.group(1)?.trim() ?: ""
            NormalizedToolCall("call_contact", JSONObject().put("number", target), 0.97f, m.group(0) ?: "")
        }

        // --- 5. send_message & draft_message ---
        // English: "send message to Rahul hello", "message Aman I will be late", "sms Priya ok", "text Rahul I'm outside"
        // Hindi / Hinglish: "Rahul ko message bhejo hello", "Priya ko bol do late hunga"
        registerRule(
            listOf(
                "^(?:send (?:a )?message|send sms|text)\\s+(?:to\\s+)?([a-zA-Z0-9_]+)\\s+(?:saying\\s+)?(.+)$",
                "^message\\s+([a-zA-Z0-9_]+)\\s+(?:saying\\s+)?(.+)$",
                "^([a-zA-Z0-9_]+)\\s+ko\\s+(?:message|sms)\\s+(?:bhejo|kar do|bhej do)\\s+(.+)$",
                "^([a-zA-Z0-9_]+)\\s+ko\\s+bol do\\s+(.+)$"
            )
        ) { m ->
            val rec = m.group(1)?.trim() ?: ""
            val msg = m.group(2)?.trim() ?: ""
            NormalizedToolCall("send_message", JSONObject().put("recipient", rec).put("message", msg), 0.95f, m.group(0) ?: "")
        }

        // --- 6. get_battery ---
        // English: "what is my battery", "battery status", "how much battery do i have", "check battery", "battery level"
        // Hindi / Hinglish: "battery kitni hai", "charge kitna hai", "battery check karo", "mera phone kitna charge hai"
        registerRule(
            listOf(
                "^(?:what(?:'s| is) (?:the |my )?battery(?: level| status| percentage)?|battery|how much battery (?:do i have|is left)|check battery|battery level)$",
                "^(?:battery kitni hai|battery check karo|charge kitna hai|kitna charge hai|battery status batao|mera phone kitna charge hai|battery kitni bachi hai)$"
            )
        ) { m ->
            NormalizedToolCall("get_battery", JSONObject(), 0.99f, m.group(0) ?: "")
        }

        // --- 7. get_location ---
        // English: "where am I", "my current location", "get location", "what's my location", "where am i right now"
        // Hindi / Hinglish: "main kahan hoon", "meri location kya hai", "location batao", "hum kahan hain", "abhi hum kahan hain"
        registerRule(
            listOf(
                "^(?:where am i(?: right now| located)?|my location|current location|get (?:my )?location|what(?:'s| is) my location|current coordinates batao)$",
                "^(?:main kahan hoon|hum kahan hain|meri location kya hai|location batao|meri current location kya hai|abhi hum kahan hain|apni location batao|batao main kahan hoon|meri location check karo)$"
            )
        ) { m ->
            NormalizedToolCall("get_location", JSONObject(), 0.99f, m.group(0) ?: "")
        }

        // --- 8. get_wifi ---
        // English: "wifi status", "is wifi connected", "what wifi am i on", "check wifi", "wifi connection status"
        // Hindi / Hinglish: "wifi chal raha hai kya", "wifi check karo", "wifi connected hai", "wifi status batao"
        registerRule(
            listOf(
                "^(?:wifi status|is wifi connected|wifi connected|get wifi(?: status)?|check (?:my )?wifi|what wifi am i on|what wifi is this|is wifi on|wifi connection status|wifi check kar|is wifi active)$",
                "^(?:wifi chal raha hai(?: kya)?|wifi check karo|wifi connected hai(?: kya)?|wifi ka status batao|wifi ki speed ya status)$"
            )
        ) { m ->
            NormalizedToolCall("get_wifi", JSONObject(), 0.98f, m.group(0) ?: "")
        }

        // --- 9. get_bluetooth ---
        // English: "bluetooth status", "is bluetooth on", "get bluetooth", "check bluetooth", "is bluetooth enabled"
        // Hindi / Hinglish: "bluetooth check karo", "bluetooth on hai kya", "bluetooth status batao"
        registerRule(
            listOf(
                "^(?:bluetooth status|is bluetooth on|bluetooth on|get bluetooth(?: status)?|check (?:my )?bluetooth|is bluetooth enabled|bluetooth toggle state|bluetooth check kar|is my bluetooth on|check if bluetooth is on)$",
                "^(?:bluetooth check karo|bluetooth on hai(?: kya)?|bluetooth chalu hai kya|bluetooth ka status kya hai|bluetooth active hai kya|bluetooth connected hai kya)$"
            )
        ) { m ->
            NormalizedToolCall("get_bluetooth", JSONObject(), 0.98f, m.group(0) ?: "")
        }

        // --- 10. take_screenshot ---
        // English: "take screenshot", "capture screen", "screenshot", "take a screenshot", "capture the screen"
        // Hindi / Hinglish: "screenshot lo", "screen capture karo", "screenshot khicho", "screen grab karo"
        registerRule(
            listOf(
                "^(?:can you )?(?:please )?(?:take (?:a )?screenshot|capture (?:the )?screen(?: display)?|screenshot|screen grab karo|take screen snapshot|display capture karo)(?: please)?$",
                "^(?:screenshot (?:lo|khicho|le lo|khich lo|nikalo)(?: please)?|screen capture karo|screen ka screenshot lo|ek screenshot khicho)$"
            )
        ) { m ->
            NormalizedToolCall("take_screenshot", JSONObject(), 0.99f, m.group(0) ?: "")
        }

        // --- 11. search_files & find_downloads ---
        // English: "search files pdf", "find file notes.txt", "find downloads pdf"
        // Hindi / Hinglish: "notes file dhundho", "pdf files khojo"
        registerRule(
            listOf(
                "^(?:find downloads|downloads me (?:pdf|files?) dhundho)\\s*(.*)$"
            )
        ) { m ->
            val ext = m.group(1)?.trim() ?: ""
            NormalizedToolCall("find_downloads", JSONObject().put("extension", ext), 0.96f, m.group(0) ?: "")
        }
        registerRule(
            listOf(
                "^(?:search (?:for )?files?|find (?:files? )?)(.+)$",
                "^(.+?)\\s+(?:file|files)?\\s*(?:dhundho|khojo|search karo)$"
            )
        ) { m ->
            val q = m.group(1)?.trim() ?: ""
            NormalizedToolCall("search_files", JSONObject().put("query", q), 0.95f, m.group(0) ?: "")
        }

        // --- 12. read_file ---
        // English: "read file /sdcard/notes.txt", "show file /sdcard/abc.txt"
        // Hindi / Hinglish: "/sdcard/notes.txt padho", "file dekho /sdcard/a.txt"
        registerRule(
            listOf(
                "^(?:read (?:the )?file|cat|show file)\\s+(.+)$",
                "^(.+?)\\s+(?:padho|read karo)$"
            )
        ) { m ->
            val path = m.group(1)?.trim() ?: ""
            NormalizedToolCall("read_file", JSONObject().put("path", path), 0.96f, m.group(0) ?: "")
        }

        // --- 13. delete_file ---
        registerRule(
            listOf(
                "^(?:delete file|remove file|rm)\\s+(.+)$",
                "^(.+?)\\s+(?:file\\s+)?(?:delete karo|hata do|mita do)$"
            )
        ) { m ->
            val path = m.group(1)?.trim() ?: ""
            NormalizedToolCall("delete_file", JSONObject().put("path", path), 0.96f, m.group(0) ?: "")
        }

        // --- 14. clipboard_get / clipboard_set ---
        registerRule(
            listOf("^(?:get clipboard|read clipboard|what is on clipboard|clipboard dekho)$")
        ) { m ->
            NormalizedToolCall("clipboard_get", JSONObject(), 0.99f, m.group(0) ?: "")
        }
        registerRule(
            listOf("^(?:copy to clipboard|set clipboard|clipboard par copy karo)\\s+(.+)$")
        ) { m ->
            val text = m.group(1)?.trim() ?: ""
            NormalizedToolCall("clipboard_set", JSONObject().put("text", text), 0.97f, m.group(0) ?: "")
        }

        // --- 15. web search & open_url ---
        registerRule(
            listOf(
                "^(?:search (?:the )?web (?:for )?|google|search for )(.+)$",
                "^(.+?)\\s+(?:google karo|search karo)$"
            )
        ) { m ->
            val q = m.group(1)?.trim() ?: ""
            NormalizedToolCall("search_web", JSONObject().put("query", q), 0.96f, m.group(0) ?: "")
        }
        registerRule(
            listOf(
                "^(?:open url|browse to|open website)\\s+(.+)$"
            )
        ) { m ->
            val url = m.group(1)?.trim() ?: ""
            NormalizedToolCall("open_url", JSONObject().put("url", url), 0.96f, m.group(0) ?: "")
        }

        // --- 16. call_history & find_contact ---
        registerRule(
            listOf(
                "^(?:show call history|recent calls|call log|who called me|call history dekho)$"
            )
        ) { m ->
            NormalizedToolCall("call_history", JSONObject().put("limit", 10), 0.98f, m.group(0) ?: "")
        }
        registerRule(
            listOf(
                "^(?:find contact|contact details for|get number for)\\s+(.+)$",
                "^(.+?)\\s+(?:ka number batao|ka contact dhundho)$"
            )
        ) { m ->
            val name = m.group(1)?.trim() ?: ""
            NormalizedToolCall("find_contact", JSONObject().put("name", name), 0.96f, m.group(0) ?: "")
        }

        // --- 17. UI interactions: click, scroll, type_text ---
        registerRule(
            listOf(
                "^(?:click|tap|press)\\s+(?:on\\s+)?(.+)$",
                "^(.+?)\\s+(?:par click karo|par tap karo|dabao)$"
            )
        ) { m ->
            val target = m.group(1)?.trim() ?: ""
            NormalizedToolCall("click", JSONObject().put("text", target), 0.95f, m.group(0) ?: "")
        }
        registerRule(
            listOf(
                "^(?:scroll down|niche scroll karo)$"
            )
        ) { m ->
            NormalizedToolCall("scroll", JSONObject().put("direction", "forward"), 0.98f, m.group(0) ?: "")
        }
        registerRule(
            listOf(
                "^(?:scroll up|upar scroll karo)$"
            )
        ) { m ->
            NormalizedToolCall("scroll", JSONObject().put("direction", "backward"), 0.98f, m.group(0) ?: "")
        }
        registerRule(
            listOf(
                "^(?:type|enter|write text)\\s+(.+)$",
                "^(.+?)\\s+(?:type karo|likho)$"
            )
        ) { m ->
            val text = m.group(1)?.trim() ?: ""
            NormalizedToolCall("type_text", JSONObject().put("text", text), 0.95f, m.group(0) ?: "")
        }
    }

    private fun registerRule(patterns: List<String>, mapper: (java.util.regex.Matcher) -> NormalizedToolCall) {
        val compiled = patterns.map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }
        rules.add(PatternRule(compiled, mapper))
    }

    private fun cleanAppTarget(raw: String): String {
        var clean = raw.trim()
        val removePrefixes = listOf("app ", "the ")
        for (p in removePrefixes) {
            if (clean.lowercase().startsWith(p)) clean = clean.substring(p.length).trim()
        }
        return when (clean.lowercase()) {
            "chrome" -> "Chrome"
            "youtube" -> "YouTube"
            "whatsapp" -> "WhatsApp"
            "spotify" -> "Spotify"
            "settings" -> "Settings"
            "camera" -> "Camera"
            "gallery" -> "Gallery"
            "maps", "google maps" -> "Maps"
            "telegram" -> "Telegram"
            else -> clean
        }
    }

    /**
     * Checks if a prompt is an informational question rather than an actionable command.
     * Prevents accidental tool triggering (e.g. "how do phone calls work?").
     */
    fun isInformational(prompt: String): Boolean {
        val clean = prompt.trim()
        for (pattern in informationalPatterns) {
            if (pattern.matcher(clean).matches()) return true
        }
        return false
    }

    /**
     * Attempts to normalize the raw user prompt into a canonical tool invocation.
     * Returns NormalizedToolCall if matched with high confidence, null otherwise.
     */
    fun normalize(input: String): NormalizedToolCall? {
        val clean = input.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return null

        // Negative check: informational queries must not trigger tools!
        if (isInformational(clean)) return null

        for (rule in rules) {
            for (pattern in rule.regexes) {
                val m = pattern.matcher(clean)
                if (m.matches()) {
                    return rule.mapper(m)
                }
            }
        }
        return null
    }
}
