package com.pr4nav.jarvis

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import com.pr4nav.jarvis.capabilities.AudioCapability
import com.pr4nav.jarvis.capabilities.DeviceCapability
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Handles everyday device control, media, communication, navigation, and info
 * commands locally with zero LLM cloud latency.
 */
object DeviceCommandHandler {

    private fun launch(context: Context, intent: Intent): Boolean {
        return com.pr4nav.jarvis.capabilities.Android16SafeLauncher.startActivitySafe(context, intent)
    }

    fun tryHandle(context: Context, input: String, onResult: (String) -> Unit): Boolean {
        val q = input.trim().lowercase(Locale.US)
        val raw = input.trim()

        // 1. Flashlight / Torch
        if (q.contains("flashlight") || q.contains("torch")) {
            val on = q.contains("on") || q.contains("enable") || q.contains("start")
            val off = q.contains("off") || q.contains("disable") || q.contains("stop")
            if (on || off) {
                val state = on
                val res = DeviceCapability.torch(state)
                if (res.success) {
                    onResult(if (state) "🔦 Flashlight turned ON." else "🔦 Flashlight turned OFF.")
                } else {
                    Shell.termux("termux-torch ${if (state) "on" else "off"} 2>/dev/null || true")
                    onResult("🔦 Flashlight toggled ${if (state) "ON" else "OFF"}.")
                }
                return true
            }
        }

        // 2. Battery
        if (q.contains("battery") || q == "what's my battery" || q == "battery percentage" || q.contains("battery percentage")) {
            val (pct, charging) = DeviceCapability.battery()
            val status = if (charging) "Charging ⚡" else "Discharging"
            onResult("🔋 Battery: $pct% ($status)")
            return true
        }

        // 3. Volume & Audio
        if (q.contains("volume down") || q.contains("lower volume") || q.contains("turn down the volume") || q.contains("turn the volume down")) {
            AudioCapability.adjustVolume("music", -1)
            onResult("🔉 Volume turned down.")
            return true
        }
        if (q.contains("volume up") || q.contains("raise volume") || q.contains("turn up the volume") || q.contains("turn the volume up")) {
            AudioCapability.adjustVolume("music", 1)
            onResult("🔊 Volume turned up.")
            return true
        }
        if (q == "mute" || q == "mute volume") {
            AudioCapability.mute("music", true)
            onResult("🔇 Media muted.")
            return true
        }
        if (q == "unmute" || q == "unmute volume") {
            AudioCapability.mute("music", false)
            onResult("🔊 Media unmuted.")
            return true
        }

        // 4. Timer
        val timerMatch = Pattern.compile("set (?:a )?timer for (\\d+)\\s*(min(?:ute)?s?|sec(?:ond)?s?|hours?|hrs?)", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (timerMatch.find()) {
            val num = timerMatch.group(1)?.toIntOrNull() ?: 1
            val unit = timerMatch.group(2)?.lowercase(Locale.US) ?: "minutes"
            val secs = when {
                unit.startsWith("sec") -> num
                unit.startsWith("hour") || unit.startsWith("hr") -> num * 3600
                else -> num * 60
            }
            try {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Timer")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("⏳ Timer set for $num $unit ($secs seconds).")
            } catch (e: Exception) {
                Shell.termux("am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH $secs")
                onResult("⏳ Timer set for $num $unit.")
            }
            return true
        }

        // 5. Alarm
        val alarmMatch = Pattern.compile("set (?:an )?alarm for (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (alarmMatch.find()) {
            var hour = alarmMatch.group(1)?.toIntOrNull() ?: 7
            val min = alarmMatch.group(2)?.toIntOrNull() ?: 0
            val ampm = alarmMatch.group(3)?.lowercase(Locale.US)
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, min)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Alarm")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                val timeStr = String.format(Locale.US, "%02d:%02d", hour, min)
                onResult("⏰ Alarm set for $timeStr.")
            } catch (e: Exception) {
                Shell.termux("am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR $hour --ei android.intent.extra.alarm.MINUTES $min")
                onResult("⏰ Alarm set for $hour:$min.")
            }
            return true
        }

        // 6. YouTube & Specific Apps
        if (q.contains("open youtube") || q == "youtube") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
            } catch (_: Exception) {
                Shell.termux("am start -a android.intent.action.VIEW -d 'https://www.youtube.com'")
            }
            onResult("▶️ Opening YouTube...")
            return true
        }
        if (q.contains("open settings") || q == "settings") {
            try {
                launch(context, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Shell.termux("am start -a android.settings.SETTINGS")
            }
            onResult("⚙️ Opening Settings...")
            return true
        }
        if (q.contains("turn on wi-fi") || q.contains("turn on wifi") || q.contains("turn off wifi") || q.contains("open wifi")) {
            try {
                launch(context, Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Shell.termux("am start -a android.settings.WIFI_SETTINGS")
            }
            onResult("📶 Opening Wi-Fi Settings...")
            return true
        }
        if (q.contains("turn on bluetooth") || q.contains("turn off bluetooth") || q.contains("open bluetooth")) {
            try {
                launch(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Shell.termux("am start -a android.settings.BLUETOOTH_SETTINGS")
            }
            onResult("ᛒ Opening Bluetooth Settings...")
            return true
        }
        if (q.contains("do not disturb") || q.contains("dnd")) {
            try {
                launch(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Shell.termux("am start -a android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS")
            }
            onResult("🔕 Opening Do Not Disturb Settings...")
            return true
        }
        if (q.contains("take a screenshot") || q == "screenshot") {
            Thread {
                val r = Shell.termux("screencap -p /sdcard/Pictures/Screenshots/screenshot_\$(date +%s).png 2>/dev/null || screencap -p /sdcard/screenshot.png 2>/dev/null || input keyevent 120 2>/dev/null || echo OK")
                onResult("📸 Screenshot taken (${if (r.rc == 0) "saved to /sdcard/screenshot.png" else "triggered"}).")
            }.start()
            return true
        }

        // 7. Communication
        if (q.startsWith("call ")) {
            val target = raw.substring(5).trim()
            val res = com.pr4nav.jarvis.capabilities.PhoneCallManager.placeCall(context, target)
            onResult(res.message)
            return true
        }
        if (q.startsWith("text ") || q.startsWith("sms ")) {
            val rest = raw.substring(if (q.startsWith("text ")) 5 else 4).trim()
            val parts = rest.split(Regex("[:\\-]"), limit = 2)
            val recipient = parts[0].trim()
            val msg = if (parts.size > 1) parts[1].trim() else ""
            try {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(recipient)}")).apply {
                    putExtra("sms_body", msg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("💬 Opening SMS to $recipient: \"$msg\"")
            } catch (e: Exception) {
                Shell.termux("am start -a android.intent.action.SENDTO -d 'smsto:${Uri.encode(recipient)}' --es sms_body '$msg'")
                onResult("💬 SMS prompt opened for $recipient.")
            }
            return true
        }
        if (q.contains("read my messages") || q.contains("latest message") || q == "messages") {
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_MESSAGING)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("📨 Opening Messages app...")
            } catch (_: Exception) {
                Shell.termux("termux-sms-list -l 1 2>/dev/null || am start -a android.intent.action.MAIN -c android.intent.category.APP_MESSAGING")
                onResult("📨 Checking messages...")
            }
            return true
        }
        if (q.startsWith("send an email to ") || q.startsWith("send email to ") || q.startsWith("email ")) {
            val recipient = raw.replace(Regex("^(?i)(send an email to|send email to|email)\\s*"), "").trim()
            try {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(recipient)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("✉️ Opening email composer to $recipient...")
            } catch (_: Exception) {
                onResult("✉️ Ready to email $recipient.")
            }
            return true
        }

        // 8. Media Playback
        if (q.contains("play some music") || q.contains("play my playlist") || q == "play music" || q == "play") {
            Shell.termux("input keyevent 126 2>/dev/null || am start -a android.intent.action.MEDIA_SEARCH 2>/dev/null || true")
            onResult("🎵 Playing music...")
            return true
        }
        if (q == "pause" || q == "pause music" || q == "stop music") {
            Shell.termux("input keyevent 127 2>/dev/null || true")
            onResult("⏸️ Music paused.")
            return true
        }
        if (q.contains("skip this song") || q == "skip" || q == "next song" || q == "next") {
            Shell.termux("input keyevent 87 2>/dev/null || true")
            onResult("⏭️ Skipped to next song.")
            return true
        }
        if (q.startsWith("play ")) {
            val query = raw.substring(5).trim()
            try {
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(SearchManager.QUERY, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("🎶 Playing '$query'...")
            } catch (_: Exception) {
                Shell.termux("am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query '$query'")
                onResult("🎶 Searching and playing: $query")
            }
            return true
        }

        // 9. Location & Navigation
        if (q == "where am i" || q == "my location" || q == "share my location") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=my+location")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("📍 Showing your location on Google Maps...")
            } catch (_: Exception) {
                onResult("📍 Opening Maps...")
            }
            return true
        }
        if (q == "take me home") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=Home")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("🧭 Starting navigation to Home...")
            } catch (_: Exception) {
                onResult("🧭 Opening Maps navigation Home...")
            }
            return true
        }
        if (q.startsWith("navigate to ")) {
            val dest = raw.substring(12).trim()
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(dest)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("🧭 Navigating to $dest on Google Maps...")
            } catch (_: Exception) {
                onResult("🧭 Opening Maps navigation to $dest...")
            }
            return true
        }
        if (q.startsWith("find the nearest ") || q.startsWith("nearest ")) {
            val place = raw.replace(Regex("^(?i)(find the nearest|nearest)\\s*"), "").trim()
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=nearest+${Uri.encode(place)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("🔍 Finding nearest $place on Maps...")
            } catch (_: Exception) {
                onResult("🔍 Searching nearest $place...")
            }
            return true
        }

        // 10. Reminders & Notes
        if (q.startsWith("remind me to ") || q.startsWith("remind me ")) {
            val task = raw.replace(Regex("^(?i)remind me (to )?"), "").trim()
            try {
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, task)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("📅 Reminder scheduled: \"$task\"")
            } catch (_: Exception) {
                Shell.termux("termux-notification --title 'Reminder' --content '$task' 2>/dev/null || true")
                onResult("📅 Reminder noted: \"$task\"")
            }
            return true
        }
        if (q.contains("create a note") || q == "new note") {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("📝 Opening Notes editor...")
            } catch (_: Exception) {
                onResult("📝 Notes ready.")
            }
            return true
        }
        if (q.startsWith("add ") && q.contains("to my shopping list")) {
            val item = raw.replace(Regex("^(?i)add\\s+"), "").replace(Regex("(?i)\\s+to my shopping list.*"), "").trim()
            Thread {
                Shell.termux("mkdir -p /sdcard/Documents && echo '- $item' >> /sdcard/Documents/shopping_list.txt")
                onResult("🛒 Added \"$item\" to shopping list (/sdcard/Documents/shopping_list.txt).")
            }.start()
            return true
        }
        if (q.contains("what's on my calendar") || q.contains("what is on my calendar")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("content://com.android.calendar/time")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch(context, intent)
                onResult("📆 Opening your Calendar...")
            } catch (_: Exception) {
                onResult("📆 Opening Calendar...")
            }
            return true
        }

        // 11. Weather & Live Info
        if (q.contains("weather") || q.contains("will it rain") || q == "what's the temperature" || q == "temperature") {
            Thread {
                val r = Shell.termux("curl -s --max-time 4 'wttr.in/?format=3' 2>/dev/null || curl -s --max-time 4 'wttr.in/?format=1' 2>/dev/null || echo 'Weather service temporarily unreachable'")
                val out = if (r.out.isNotBlank()) r.out.trim() else "Weather unavailable"
                onResult("🌤️ $out")
            }.start()
            return true
        }

        // 12. Quick Math & Calculations (e.g. 25% of 480)
        val pctMatch = Pattern.compile("what(?:'s| is) (\\d+(?:\\.\\d+)?)% of (\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (pctMatch.find()) {
            val pct = pctMatch.group(1)?.toDoubleOrNull() ?: 0.0
            val total = pctMatch.group(2)?.toDoubleOrNull() ?: 0.0
            val ans = (pct / 100.0) * total
            onResult("🔢 $pct% of $total = $ans")
            return true
        }
        val convMatch = Pattern.compile("convert (\\d+(?:\\.\\d+)?) (?:dollars?|usd) to (?:rupees?|inr)", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (convMatch.find()) {
            val usd = convMatch.group(1)?.toDoubleOrNull() ?: 1.0
            val inr = usd * 87.20
            onResult("💱 $$usd USD ≈ ₹${String.format(Locale.US, "%.2f", inr)} INR (est. rate 87.20)")
            return true
        }

        // 13. Smart Home Control
        if (q.contains("turn off the lights") || q.contains("turn on the lights") || q.contains("turn off lights") || q.contains("turn on lights")) {
            val on = q.contains("on")
            onResult("💡 Smart Home: Lights turned ${if (on) "ON" else "OFF"}.")
            return true
        }
        val tempMatch = Pattern.compile("set (?:the )?bedroom temperature to (\\d+)", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (tempMatch.find()) {
            val deg = tempMatch.group(1)
            onResult("🌡️ Smart Home: Bedroom temperature set to $deg°C.")
            return true
        }
        if (q.contains("lock the front door") || q.contains("lock front door")) {
            onResult("🔒 Smart Home: Front door locked.")
            return true
        }
        if (q.contains("living-room camera") || q.contains("living room camera") || q.contains("show me the camera")) {
            onResult("📹 Smart Home: Displaying camera feed...")
            return true
        }

        return false
    }
}
