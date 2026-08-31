package com.pr4nav.jarvis.router

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import com.pr4nav.jarvis.DeviceCommandHandler
import com.pr4nav.jarvis.Shell
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * JARVIS Semantic Intent Router & Capability Orchestrator
 *
 * Rather than asking "Which app did the user mention?", JARVIS asks:
 * "What capability satisfies this?"
 *
 * Handles single and simultaneous compound capabilities (e.g.
 * "Play that YouTube video while navigating home.")
 */
object JarvisIntentRouter {

    enum class CapabilityType(val label: String, val icon: String) {
        MUSIC("Music Capability", "🎵"),
        NAVIGATION("Navigation Capability", "🗺️"),
        FILE_SEARCH("File/Cloud Capability", "📁"),
        SCHEDULE("Schedule Capability", "📅"),
        NOTES("Notes/Memory Capability", "📝"),
        MESSAGING("Messaging Capability", "💬"),
        EMAIL("Email Capability", "📧"),
        WEB("Web Capability", "🌐"),
        VIDEO("Media/Video Capability", "▶️"),
        PHOTOS("Photos Capability", "📷"),
        LENS("Visual Lookup Capability", "🔍"),
        CALL("Phone/Call Capability", "📞"),
        DEVICE("Device Control Capability", "⚡"),
        AI_QUERY("General AI Intelligence", "🤖")
    }

    data class SubIntent(
        val type: CapabilityType,
        val target: String,
        val rawPhrase: String
    )

    data class RouteResult(
        val matched: Boolean,
        val capabilities: List<CapabilityType>,
        val executionSummary: String
    )

    /**
     * Inspects input, detects single or compound capabilities, resolves providers,
     * and executes them.
     */
    fun routeAndExecute(context: Context, input: String, onResult: (RouteResult) -> Unit): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.US)

        // 1. Level 0: Pure Local Clock & Date (< 5ms)
        if (lower == "what's the time" || lower == "what is the time" || lower == "what time is it" || lower == "time") {
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            onResult(RouteResult(true, listOf(CapabilityType.DEVICE), "⏰ The current time is $time."))
            return true
        }
        if (lower == "what's the date" || lower == "what is today's date" || lower == "what's today's date" || lower == "date") {
            val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
            onResult(RouteResult(true, listOf(CapabilityType.DEVICE), "📅 Today is $date."))
            return true
        }

        // 2. Level 2: GUI Visualization Tasks (CPU, RAM, Hardware Dashboard)
        if (lower.contains("cpu usage") || lower.contains("ram usage") || lower.contains("system metrics") ||
            lower.contains("show dashboard") || lower.contains("graph of my ram") || lower.contains("show my cpu")) {
            val summary = com.pr4nav.jarvis.gui.JarvisGuiRenderer.showSystemDashboard(context)
            onResult(RouteResult(true, listOf(CapabilityType.DEVICE), "📊 [GUI Renderer] $summary"))
            return true
        }

        // 3. Long-term Memory: Forget, Remember, Recall (< 5ms)
        val forgetMsg = com.pr4nav.jarvis.memory.JarvisMemoryStore.tryForget(context, trimmed)
        if (forgetMsg != null) {
            onResult(RouteResult(true, listOf(CapabilityType.NOTES), forgetMsg))
            return true
        }
        val remembered = com.pr4nav.jarvis.memory.JarvisMemoryStore.tryExtractAndRemember(context, trimmed)
        if (remembered != null) {
            onResult(RouteResult(true, listOf(CapabilityType.NOTES), "🧠 Memory Saved: Remembered that your ${remembered.key} is \"${remembered.value}\"."))
            return true
        }
        val recallMsg = com.pr4nav.jarvis.memory.JarvisMemoryStore.tryRetrieve(context, trimmed)
        if (recallMsg != null) {
            onResult(RouteResult(true, listOf(CapabilityType.NOTES), recallMsg))
            return true
        }

        // 4. Proactive Automation: Event → Condition → Action
        val autoMsg = com.pr4nav.jarvis.automation.JarvisAutomationEngine.tryCreateAutomation(context, trimmed)
        if (autoMsg != null) {
            onResult(RouteResult(true, listOf(CapabilityType.DEVICE), autoMsg))
            return true
        }

        // 5. Check for compound intents (e.g. "X while Y", "X and navigate to Y")
        val subPhrases = splitCompoundPhrases(trimmed)
        val intents = subPhrases.mapNotNull { resolveCapability(it) }

        if (intents.isEmpty()) {
            // Check everyday device command handler
            val handled = DeviceCommandHandler.tryHandle(context, trimmed) { msg ->
                onResult(RouteResult(true, listOf(CapabilityType.DEVICE), msg))
            }
            return handled
        }

        // Execute all identified capabilities
        val summaryLines = mutableListOf<String>()
        val types = mutableListOf<CapabilityType>()

        for (intent in intents) {
            types.add(intent.type)
            val desc = executeIntent(context, intent)
            summaryLines.add("${intent.type.icon} [${intent.type.label}] → $desc")
        }

        val fullSummary = summaryLines.joinToString("\n")
        onResult(RouteResult(true, types, fullSummary))
        return true
    }

    private fun splitCompoundPhrases(input: String): List<String> {
        val lower = input.lowercase(Locale.US)
        val connectors = listOf(" while ", " and also ", " along with ", " and navigate to ", " while playing ", " while watching ")
        for (c in connectors) {
            if (lower.contains(c)) {
                val idx = lower.indexOf(c)
                val part1 = input.substring(0, idx).trim()
                val part2 = input.substring(idx + c.length).trim()
                if (part1.isNotEmpty() && part2.isNotEmpty()) {
                    val p2 = if (c.contains("navigate") && !part2.lowercase().startsWith("navigate")) "navigate to $part2"
                             else if (c.contains("playing") && !part2.lowercase().startsWith("play")) "play $part2"
                             else part2
                    return listOf(part1, p2)
                }
            }
        }
        return listOf(input)
    }

    private fun resolveCapability(phrase: String): SubIntent? {
        val p = phrase.trim()
        val q = p.lowercase(Locale.US)

        // 1. Music Capability (Spotify / YT Music / Local)
        if (q.contains("play my liked songs") || q.contains("play something chill") ||
            q.contains("play some music") || q.contains("play my playlist") ||
            q.startsWith("play music") || q.contains("play lo-fi") || q.contains("play jazz") ||
            (q.startsWith("play ") && !q.contains("video") && !q.contains("youtube"))) {
            val query = if (q.startsWith("play ")) p.substring(5).trim() else "chill"
            return SubIntent(CapabilityType.MUSIC, query, p)
        }

        // 2. Media / Video Capability (YouTube)
        if (q.contains("youtube") || q.contains("video") || q.startsWith("watch ") || q.contains("mrbeast")) {
            val query = p.replace(Regex("^(?i)(play that|play the|play|watch)\\s*"), "")
                         .replace(Regex("(?i)\\s*video.*"), "").trim()
            return SubIntent(CapabilityType.VIDEO, query.ifEmpty { "trending" }, p)
        }

        // 3. Navigation Capability (Google Maps)
        if (q.contains("navigate to") || q.contains("take me to") || q.contains("take me home") ||
            q.contains("directions to") || q.contains("navigating home") || q.contains("find the nearest") ||
            q.contains("nearest petrol") || q.contains("nearest gas") || q.contains("where am i")) {
            val dest = when {
                q.contains("home") -> "Home"
                q.contains("nearest") -> p.substring(p.lowercase().indexOf("nearest")).trim()
                q.contains("take me to ") -> p.substring(p.lowercase().indexOf("take me to ") + 11).trim()
                q.contains("navigate to ") -> p.substring(p.lowercase().indexOf("navigate to ") + 12).trim()
                else -> p
            }
            return SubIntent(CapabilityType.NAVIGATION, dest, p)
        }

        // 4. File / Cloud Search Capability (Google Drive + Local Storage + Termux)
        if (q.contains("find my ") || q.contains("find the pdf") || q.contains("notes") && q.contains("find") ||
            q.contains("find document") || q.contains("search file") || q.contains("jee notes")) {
            val query = p.replace(Regex("^(?i)(find my|find the|find|search file|search)\\s*"), "").trim()
            return SubIntent(CapabilityType.FILE_SEARCH, query, p)
        }

        // 5. Schedule Capability (Google Calendar)
        if (q.contains("what do i have tomorrow") || q.contains("what's on my calendar") ||
            q.contains("schedule a meeting") || q.contains("my calendar") || q.contains("calendar today")) {
            return SubIntent(CapabilityType.SCHEDULE, p, p)
        }

        // 6. Notes / Memory Capability (Google Keep)
        if (q.contains("remember this") || q.contains("create a note") || q.contains("take a note") ||
            q.contains("add ") && q.contains("shopping list") || q.contains("shopping list")) {
            return SubIntent(CapabilityType.NOTES, p, p)
        }

        // 7. Messaging Capability (WhatsApp / SMS)
        if (q.startsWith("message ") || q.startsWith("whatsapp ") || q.startsWith("text ") || q.contains("reply to that message")) {
            return SubIntent(CapabilityType.MESSAGING, p, p)
        }

        // 8. Email Capability (Gmail)
        if (q.contains("read my latest email") || q.contains("latest email") || q.startsWith("send an email") || q.startsWith("email ")) {
            return SubIntent(CapabilityType.EMAIL, p, p)
        }

        // 9. Photos & Visual Lookup (Google Photos / Google Lens)
        if (q.contains("find my photos") || q.contains("open photos") || q.contains("show me photos")) {
            return SubIntent(CapabilityType.PHOTOS, p, p)
        }
        if (q.contains("google lens") || q.contains("lens") || q.contains("visual lookup") || q.contains("scan this")) {
            return SubIntent(CapabilityType.LENS, p, p)
        }

        // 10. Phone / Call Capability
        if (q.startsWith("call ") || q.contains("facetime ")) {
            return SubIntent(CapabilityType.CALL, p.substring(p.indexOf(" ") + 1).trim(), p)
        }

        // 11. Web Capability (Chrome)
        if (q.startsWith("search ") || q.startsWith("google ") || q.contains("search this")) {
            val target = p.replace(Regex("^(?i)(search this|search|google)\\s*"), "").trim()
            return SubIntent(CapabilityType.WEB, target, p)
        }

        return null
    }

    private fun executeIntent(context: Context, intent: SubIntent): String {
        return when (intent.type) {
            CapabilityType.MUSIC -> executeMusic(context, intent.target)
            CapabilityType.NAVIGATION -> executeNavigation(context, intent.target)
            CapabilityType.FILE_SEARCH -> executeFileSearch(context, intent.target)
            CapabilityType.SCHEDULE -> executeSchedule(context, intent.target)
            CapabilityType.NOTES -> executeNotes(context, intent.target)
            CapabilityType.MESSAGING -> executeMessaging(context, intent.target)
            CapabilityType.EMAIL -> executeEmail(context, intent.target)
            CapabilityType.WEB -> executeWeb(context, intent.target)
            CapabilityType.VIDEO -> executeVideo(context, intent.target)
            CapabilityType.PHOTOS -> executePhotos(context, intent.target)
            CapabilityType.LENS -> executeLens(context)
            CapabilityType.CALL -> executeCall(context, intent.target)
            CapabilityType.DEVICE -> "Device control executed."
            CapabilityType.AI_QUERY -> "Sent to Antigravity AI."
        }
    }

    // ================= CAPABILITY IMPLEMENTATIONS =================

    private fun executeMusic(context: Context, query: String): String {
        // Preference: Spotify -> YouTube Music -> Native Media Search
        val isSpotify = isPackageInstalled(context, "com.spotify.music")
        val isYtMusic = isPackageInstalled(context, "com.google.android.apps.youtube.music")

        return try {
            if (isSpotify) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Spotify → Playing \"$query\""
            } else if (isYtMusic) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")).apply {
                    setPackage("com.google.android.apps.youtube.music")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to YouTube Music → Playing \"$query\""
            } else {
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Media Provider → Searching \"$query\""
            }
        } catch (e: Exception) {
            Shell.termux("am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query '${query}' 2>/dev/null || true")
            "Triggered Media Playback: $query"
        }
    }

    private fun executeNavigation(context: Context, destination: String): String {
        return try {
            if (destination.equals("home", ignoreCase = true)) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=Home")).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Google Maps → Navigating Home"
            } else {
                val uri = if (destination.startsWith("nearest")) "geo:0,0?q=${Uri.encode(destination)}"
                          else "google.navigation:q=${Uri.encode(destination)}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Google Maps → Navigating to \"$destination\""
            }
        } catch (e: Exception) {
            Shell.termux("am start -a android.intent.action.VIEW -d 'google.navigation:q=${Uri.encode(destination)}' 2>/dev/null || true")
            "Navigating via Maps: $destination"
        }
    }

    private fun executeFileSearch(context: Context, query: String): String {
        // Searches local storage + Drive search intent
        Thread {
            Shell.termux("find /sdcard/Download /sdcard/Documents ~/ -iname '*${query}*' 2>/dev/null | head -n 10")
        }.start()

        return try {
            val driveIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/search?q=${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(driveIntent)
            "Searching Local Storage & Google Drive for \"$query\""
        } catch (e: Exception) {
            "Searching files for \"$query\""
        }
    }

    private fun executeSchedule(context: Context, query: String): String {
        return try {
            if (query.lowercase().contains("schedule") || query.lowercase().contains("meeting")) {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, "Meeting")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Google Calendar → Opening Event Scheduler"
            } else {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("content://com.android.calendar/time")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Google Calendar → Opening Schedule"
            }
        } catch (e: Exception) {
            "Opening Calendar..."
        }
    }

    private fun executeNotes(context: Context, query: String): String {
        val isKeep = isPackageInstalled(context, "com.google.android.keep")
        return try {
            if (query.lowercase().contains("shopping list")) {
                val item = query.replace(Regex("^(?i)add\\s+"), "").replace(Regex("(?i)\\s+to my shopping list.*"), "").trim()
                Thread {
                    Shell.termux("mkdir -p /sdcard/Documents && echo '- $item' >> /sdcard/Documents/shopping_list.txt")
                }.start()
                "Added \"$item\" to shopping list (/sdcard/Documents/shopping_list.txt)"
            } else {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, query.replace(Regex("^(?i)(remember this|create a note|take a note)\\s*"), ""))
                    if (isKeep) setPackage("com.google.android.keep")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Google Keep → Creating Note"
            }
        } catch (e: Exception) {
            "Note captured."
        }
    }

    private fun executeMessaging(context: Context, query: String): String {
        val isWhatsApp = isPackageInstalled(context, "com.whatsapp")
        val clean = query.replace(Regex("^(?i)(message|whatsapp|text)\\s*"), "").trim()
        val parts = clean.split(Regex("[:\\-]"), limit = 2)
        val target = parts[0].trim()
        val msg = if (parts.size > 1) parts[1].trim() else ""

        return try {
            if (isWhatsApp && (query.lowercase().contains("whatsapp") || !target.all { it.isDigit() })) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?text=${Uri.encode(msg)}")).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to WhatsApp → Messaging $target"
            } else {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(target)}")).apply {
                    putExtra("sms_body", msg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Messages (SMS) → Sending to $target"
            }
        } catch (e: Exception) {
            "Opening messaging for $target..."
        }
    }

    private fun executeEmail(context: Context, query: String): String {
        return try {
            if (query.lowercase().contains("read") || query.lowercase().contains("latest")) {
                val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.gm") ?:
                             Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Resolved to Gmail → Opening Inbox"
            } else {
                val target = query.replace(Regex("^(?i)(send an email to|send email to|email)\\s*"), "").trim()
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(target)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Resolved to Gmail → Drafting email to $target"
            }
        } catch (e: Exception) {
            "Opening Gmail..."
        }
    }

    private fun executeWeb(context: Context, query: String): String {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Resolved to Google Chrome → Searching \"$query\""
        } catch (e: Exception) {
            "Searching web: $query"
        }
    }

    private fun executeVideo(context: Context, query: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Resolved to YouTube → Playing video \"$query\""
        } catch (e: Exception) {
            Shell.termux("am start -a android.intent.action.VIEW -d 'https://www.youtube.com/results?search_query=${Uri.encode(query)}'")
            "Launching YouTube: $query"
        }
    }

    private fun executePhotos(context: Context, query: String): String {
        val isPhotos = isPackageInstalled(context, "com.google.android.apps.photos")
        return try {
            val intent = if (isPhotos) {
                context.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos") ?:
                Intent(Intent.ACTION_VIEW, Uri.parse("content://media/internal/images/media"))
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("content://media/internal/images/media"))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Resolved to Google Photos → Opening photo gallery"
        } catch (e: Exception) {
            "Opening Photos..."
        }
    }

    private fun executeLens(context: Context): String {
        val isLens = isPackageInstalled(context, "com.google.ar.lens")
        return try {
            val intent = if (isLens) {
                context.packageManager.getLaunchIntentForPackage("com.google.ar.lens") ?:
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            } else {
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Resolved to Google Lens → Opening visual lookup"
        } catch (e: Exception) {
            "Opening Camera/Lens..."
        }
    }

    private fun executeCall(context: Context, target: String): String {
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(target)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Resolved to Phone Dialer → Calling $target"
        } catch (e: Exception) {
            "Dialing $target..."
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
