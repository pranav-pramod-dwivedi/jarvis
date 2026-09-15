package com.pr4nav.jarvis.pin

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pr4nav.jarvis.AgentActivity
import com.pr4nav.jarvis.MainActivity
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.voice.JarvisVoiceEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * JARVIS Sticky Human Pin & Anti-Ignore Engine.
 *
 * Core Principles:
 * 1. PINS ARE STICKY: Persistent, non-swipeable Android notifications with actions (done / snooze 15m / talk to me).
 * 2. NOCTURNAL RHYTHM: Pranav sleeps ~6 AM to ~1-3 PM, studies overnight. NEVER schedule daytime assumptions.
 *    - ~15:30: Kickoff pin ("day started, what's the plan") + Daily Ignored Digest
 *    - ~23:00: Mid-session pin ("halfway check")
 *    - ~05:00: Pre-sleep review ("what got done")
 * 3. ANTI-IGNORE LADDER:
 *    - Step 0 (0m): Silent sticky pin.
 *    - Step 1 (20m): Buzz once + rephrase wording (more direct, slightly annoyed co-pilot tone, lowercase).
 *    - Step 2 (40m): Voice drop via Kira TTS / local voice engine ("hey, you awake?").
 *    - Step 3 (60m): Full-screen HUD / alarm alert for JEE study blocks & critical items.
 * 4. NAG BUDGET: Max 6 proactive pins per waking period. If exceeded, merge into 15:30 Ignored Digest.
 * 5. PROACTIVE MONITORS: Battery warning at 20%, escalate at 13%; missed calls (2x from same caller -> pin).
 */
object StickyPinManager {

    private const val TAG = "StickyPinManager"
    const val CHANNEL_ID = "jarvis_sticky_human_pins"
    private const val PREFS_NAME = "jarvis_sticky_pins_prefs"

    private const val KEY_ACTIVE_PINS = "active_pins_json"
    private const val KEY_NAG_COUNT = "nag_count"
    private const val KEY_NAG_DATE = "nag_date"
    private const val KEY_IGNORED_DIGEST = "ignored_digest_items"

    const val MAX_NAG_BUDGET_PER_DAY = 6

    private val activePins = ConcurrentHashMap<String, StickyPin>()
    private var isBatteryMonitorRegistered = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        createNotificationChannel(context)
        loadActivePins(context)
        scheduleDailyRhythms(context)
        registerBatteryMonitor(context)
        Log.i(TAG, "StickyPinManager initialized with ${activePins.size} active pins.")
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Sticky Human Pins",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Persistent, non-swipeable check-ins and action pins"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250)
                setSound(null, null) // Silent by default; vibration/audio triggered dynamically by ladder
                setShowBadge(true)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    /**
     * Checks if current time is within Pranav's sleep window (~6 AM to ~2 PM / 14:00).
     */
    fun isInSleepWindow(calendar: Calendar = Calendar.getInstance()): Boolean {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return hour in 6..13 // 06:00 to 13:59
    }

    private fun getTodayDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    /**
     * Verifies if nag budget permits posting a new proactive pin.
     */
    @Synchronized
    private fun checkAndIncrementNagBudget(context: Context, itemSummary: String): Boolean {
        val prefs = getPrefs(context)
        val today = getTodayDateKey()
        val savedDate = prefs.getString(KEY_NAG_DATE, "")
        var count = if (savedDate == today) prefs.getInt(KEY_NAG_COUNT, 0) else 0

        if (count >= MAX_NAG_BUDGET_PER_DAY) {
            Log.w(TAG, "Nag budget reached ($count/$MAX_NAG_BUDGET_PER_DAY). Merging '$itemSummary' into Ignored Digest.")
            addToIgnoredDigest(context, itemSummary)
            return false
        }

        count++
        prefs.edit().putString(KEY_NAG_DATE, today).putInt(KEY_NAG_COUNT, count).apply()
        return true
    }

    private fun addToIgnoredDigest(context: Context, item: String) {
        val prefs = getPrefs(context)
        val current = getIgnoredDigest(context).toMutableList()
        if (!current.contains(item)) {
            current.add(item)
            val arr = JSONArray(current)
            prefs.edit().putString(KEY_IGNORED_DIGEST, arr.toString()).apply()
        }
    }

    fun getIgnoredDigest(context: Context): List<String> {
        val jsonStr = getPrefs(context).getString(KEY_IGNORED_DIGEST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearIgnoredDigest(context: Context) {
        getPrefs(context).edit().remove(KEY_IGNORED_DIGEST).apply()
    }

    /**
     * Posts a persistent, non-swipeable sticky pin.
     */
    fun postPin(
        context: Context,
        id: String,
        title: String,
        body: String,
        priority: PinPriority = PinPriority.NORMAL,
        isProactive: Boolean = false,
        originalPrompt: String? = null
    ): StickyPin? {
        // Enforce nocturnal clock rules
        if (isProactive && isInSleepWindow()) {
            Log.i(TAG, "Inhibiting proactive pin during sleep window (~6 AM - ~2 PM): $title")
            return null
        }

        // Enforce nag budget for proactive prompts
        if (isProactive && !checkAndIncrementNagBudget(context, "$title: $body")) {
            return null
        }

        val pin = StickyPin(
            id = id,
            title = title,
            body = body,
            createdAt = System.currentTimeMillis(),
            priority = priority,
            escalationStep = 0,
            lastEscalatedAt = System.currentTimeMillis(),
            isProactive = isProactive,
            acknowledged = false
        )

        activePins[id] = pin
        saveActivePins(context)
        renderNotification(context, pin, vibrate = false)

        // Schedule 20-minute anti-ignore escalation
        scheduleEscalationAlarm(context, id, 20 * 60 * 1000L)

        Log.i(TAG, "Sticky pin posted: [$id] $title")
        return pin
    }

    /**
     * Renders or updates the ongoing sticky notification.
     */
    private fun renderNotification(context: Context, pin: StickyPin, vibrate: Boolean = false) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Tap content -> Open Agent / Main
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_pin_id", pin.id)
            putExtra("prompt", pin.title)
        }
        val openPi = PendingIntent.getActivity(
            context,
            pin.notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action 1: done
        val doneIntent = Intent(context, PinActionReceiver::class.java).apply {
            action = PinActionReceiver.ACTION_PIN_DONE
            putExtra(PinActionReceiver.EXTRA_PIN_ID, pin.id)
        }
        val donePi = PendingIntent.getBroadcast(
            context,
            pin.notificationId + 1,
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action 2: snooze 15m
        val snoozeIntent = Intent(context, PinActionReceiver::class.java).apply {
            action = PinActionReceiver.ACTION_PIN_SNOOZE
            putExtra(PinActionReceiver.EXTRA_PIN_ID, pin.id)
            putExtra(PinActionReceiver.EXTRA_MINUTES, 15)
        }
        val snoozePi = PendingIntent.getBroadcast(
            context,
            pin.notificationId + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action 3: talk to me
        val talkIntent = Intent(context, PinActionReceiver::class.java).apply {
            action = PinActionReceiver.ACTION_PIN_TALK
            putExtra(PinActionReceiver.EXTRA_PIN_ID, pin.id)
            putExtra(PinActionReceiver.EXTRA_PROMPT, pin.title)
        }
        val talkPi = PendingIntent.getBroadcast(
            context,
            pin.notificationId + 3,
            talkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.bg_dot)
            .setContentTitle(pin.title)
            .setContentText(pin.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pin.body))
            .setContentIntent(openPi)
            .setOngoing(true) // STICKY: Non-swipeable
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .addAction(0, "done", donePi)
            .addAction(0, "snooze 15m", snoozePi)
            .addAction(0, "talk to me", talkPi)

        if (vibrate) {
            builder.setVibrate(longArrayOf(0, 250, 100, 250))
        }

        nm.notify(pin.notificationId, builder.build())
    }

    /**
     * Anti-Ignore Escalation Ladder.
     */
    fun escalatePin(context: Context, pinId: String) {
        val pin = activePins[pinId] ?: return
        if (pin.acknowledged) return

        val nextStep = pin.escalationStep + 1
        Log.i(TAG, "Escalating pin [${pin.id}] to step $nextStep")

        when (nextStep) {
            1 -> {
                // Step 2: Buzz once + reword title/body to direct, slightly annoyed co-pilot tone (lowercase)
                vibrateDevice(context, 250)
                val newBody = when {
                    pin.priority == PinPriority.JEE_STUDY || pin.id.contains("kickoff") ->
                        "20 min in. what are we hitting tonight: physics, chem, or math? don't stall."
                    pin.id.contains("mid") ->
                        "23:20. how are the problems looking? don't slip."
                    pin.id.contains("sleep") ->
                        "session wrap-up. log hours before you crash."
                    pin.id.contains("battery") ->
                        "plug in right now. phone dying soon."
                    else ->
                        "still waiting on this. 20 min in. tap done or snooze."
                }

                val updatedPin = pin.copy(
                    body = newBody,
                    escalationStep = 1,
                    lastEscalatedAt = System.currentTimeMillis()
                )
                activePins[pinId] = updatedPin
                saveActivePins(context)
                renderNotification(context, updatedPin, vibrate = true)

                // Next escalation in 20 min (40 min total)
                scheduleEscalationAlarm(context, pinId, 20 * 60 * 1000L)
            }
            2 -> {
                // Step 3: Short voice drop via Kira TTS / voice engine
                val voiceText = when {
                    pin.priority == PinPriority.JEE_STUDY ->
                        "hey, you awake? drop the phone and get back to physics."
                    pin.id.contains("battery") ->
                        "battery is critical. plug it in."
                    else ->
                        "hey, you awake?"
                }
                triggerVoiceDrop(context, voiceText)

                val updatedPin = pin.copy(
                    body = "voice drop sent · wake up, don't drop the study block.",
                    escalationStep = 2,
                    lastEscalatedAt = System.currentTimeMillis()
                )
                activePins[pinId] = updatedPin
                saveActivePins(context)
                renderNotification(context, updatedPin, vibrate = true)

                // Next escalation in 20 min (60 min total)
                scheduleEscalationAlarm(context, pinId, 20 * 60 * 1000L)
            }
            3 -> {
                // Step 4: Full-screen HUD or alarm alert for critical study blocks
                if (pin.priority == PinPriority.JEE_STUDY || pin.priority == PinPriority.CRITICAL) {
                    try {
                        com.pr4nav.jarvis.companion.JarvisOverlayService.showHud(context)
                    } catch (_: Exception) {}
                    vibrateAlarm(context)
                }

                val updatedPin = pin.copy(
                    body = "critical alert · study block slipping. acknowledge now.",
                    escalationStep = 3,
                    lastEscalatedAt = System.currentTimeMillis()
                )
                activePins[pinId] = updatedPin
                saveActivePins(context)
                renderNotification(context, updatedPin, vibrate = true)
            }
        }
    }

    fun acknowledgePin(context: Context, pinId: String) {
        val pin = activePins.remove(pinId)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        pin?.let { nm?.cancel(it.notificationId) }
        cancelEscalationAlarm(context, pinId)
        saveActivePins(context)
        Log.i(TAG, "Pin [$pinId] acknowledged and dismissed.")
    }

    fun snoozePin(context: Context, pinId: String, minutes: Int = 15) {
        val pin = activePins[pinId] ?: return
        val updated = pin.copy(
            body = "snoozed for ${minutes}m.",
            lastEscalatedAt = System.currentTimeMillis()
        )
        activePins[pinId] = updated
        saveActivePins(context)
        renderNotification(context, updated, vibrate = false)

        scheduleEscalationAlarm(context, pinId, minutes * 60 * 1000L)
        Log.i(TAG, "Pin [$pinId] snoozed for ${minutes}m.")
    }

    /**
     * Nocturnal Rhythm Handlers
     */
    fun handleKickoffCheckin(context: Context) {
        val today = getTodayDateKey()
        val pinId = "checkin_kickoff_$today"
        if (activePins.containsKey(pinId)) return

        val digest = getIgnoredDigest(context)
        val body = if (digest.isNotEmpty()) {
            val digestStr = digest.joinToString(", ")
            clearIgnoredDigest(context)
            "day started, what's the plan\n[yesterday's skipped: $digestStr]\nslate reset."
        } else {
            "woke up. what are we hitting tonight: physics, chem, or math?"
        }

        postPin(
            context = context,
            id = pinId,
            title = "day started, what's the plan",
            body = body,
            priority = PinPriority.JEE_STUDY,
            isProactive = true
        )
    }

    fun handleMidSessionCheckin(context: Context) {
        val today = getTodayDateKey()
        val pinId = "checkin_mid_$today"
        if (activePins.containsKey(pinId)) return

        postPin(
            context = context,
            id = pinId,
            title = "halfway check",
            body = "23:00. how's the problem set looking? stay on track.",
            priority = PinPriority.JEE_STUDY,
            isProactive = true
        )
    }

    fun handlePreSleepCheckin(context: Context) {
        val today = getTodayDateKey()
        val pinId = "checkin_sleep_$today"
        if (activePins.containsKey(pinId)) return

        postPin(
            context = context,
            id = pinId,
            title = "pre-sleep review",
            body = "05:00 wrap up. what got done? log before crashing.",
            priority = PinPriority.JEE_STUDY,
            isProactive = true
        )
    }

    /**
     * Proactive Battery Monitor Handler
     */
    fun onBatteryChanged(context: Context, level: Int, isCharging: Boolean) {
        if (isCharging) {
            // Dismiss battery warning pins if plugged in
            if (activePins.containsKey("sys_battery")) acknowledgePin(context, "sys_battery")
            if (activePins.containsKey("sys_battery_crit")) acknowledgePin(context, "sys_battery_crit")
            return
        }

        if (level <= 13) {
            if (!activePins.containsKey("sys_battery_crit")) {
                postPin(
                    context = context,
                    id = "sys_battery_crit",
                    title = "battery 13% critical",
                    body = "plug in right now. phone about to shut down.",
                    priority = PinPriority.CRITICAL,
                    isProactive = true
                )
                vibrateAlarm(context)
            }
        } else if (level <= 20) {
            if (!activePins.containsKey("sys_battery") && !activePins.containsKey("sys_battery_crit")) {
                postPin(
                    context = context,
                    id = "sys_battery",
                    title = "battery 20%",
                    body = "plug in or overnight session dies",
                    priority = PinPriority.SYSTEM,
                    isProactive = true
                )
            }
        }
    }

    private fun registerBatteryMonitor(context: Context) {
        if (isBatteryMonitorRegistered) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (c == null || intent == null) return
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    val pct = if (level >= 0 && scale > 0) (level * 100) / scale else level
                    if (pct in 0..100) {
                        onBatteryChanged(c, pct, isCharging)
                    }
                }
            }
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            isBatteryMonitorRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery monitor receiver: ${e.message}")
        }
    }

    /**
     * Schedule Daily Alarms for 15:30, 23:00, and 05:00
     */
    private fun scheduleDailyRhythms(context: Context) {
        scheduleRecurringAlarm(context, PinActionReceiver.ACTION_CHECKIN_KICKOFF, 15, 30)
        scheduleRecurringAlarm(context, PinActionReceiver.ACTION_CHECKIN_MID, 23, 0)
        scheduleRecurringAlarm(context, PinActionReceiver.ACTION_CHECKIN_PRE_SLEEP, 5, 0)
    }

    private fun scheduleRecurringAlarm(context: Context, action: String, hour: Int, minute: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, PinActionReceiver::class.java).apply { this.action = action }
        val pi = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule exact alarm for $action: ${e.message}")
        }
    }

    private fun scheduleEscalationAlarm(context: Context, pinId: String, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, PinActionReceiver::class.java).apply {
            action = PinActionReceiver.ACTION_ESCALATE_TICK
            putExtra(PinActionReceiver.EXTRA_PIN_ID, pinId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            pinId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val triggerAt = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {}
    }

    private fun cancelEscalationAlarm(context: Context, pinId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, PinActionReceiver::class.java).apply {
            action = PinActionReceiver.ACTION_ESCALATE_TICK
            putExtra(PinActionReceiver.EXTRA_PIN_ID, pinId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            pinId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        if (pi != null) am.cancel(pi)
    }

    private fun vibrateDevice(context: Context, durationMs: Long) {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun vibrateAlarm(context: Context) {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            val pattern = longArrayOf(0, 300, 200, 300, 200, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }

    private fun triggerVoiceDrop(context: Context, text: String) {
        try {
            JarvisVoiceEngine.getInstance(context).speak(text, interrupt = true)
        } catch (e: Exception) {
            Log.w(TAG, "Voice drop failed: ${e.message}")
        }
    }

    private fun saveActivePins(context: Context) {
        val arr = JSONArray()
        for (p in activePins.values) {
            arr.put(p.toJson())
        }
        getPrefs(context).edit().putString(KEY_ACTIVE_PINS, arr.toString()).apply()
    }

    private fun loadActivePins(context: Context) {
        activePins.clear()
        val jsonStr = getPrefs(context).getString(KEY_ACTIVE_PINS, "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val p = StickyPin.fromJson(arr.getJSONObject(i))
                activePins[p.id] = p
            }
        } catch (_: Exception) {}
    }
}
