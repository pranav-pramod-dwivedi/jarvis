package com.pr4nav.jarvis.tools.catalog

import android.content.Intent
import android.provider.AlarmClock
import com.pr4nav.jarvis.capabilities.DeviceCapability
import com.pr4nav.jarvis.needle.NeedleExecutor
import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ClockTimerTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "clock_alarm_set",
            description = "Sets a new alarm at a specific hour and minute with an optional label description.",
            argumentSchema = schema(
                prop("hour", "integer", "Hour in 24-hour format (0-23) or 12-hour format"),
                prop("minute", "integer", "Minute (0-59)"),
                prop("label", "string", "Optional alarm description (e.g. 'Meeting', 'Gym')"),
                prop("ampm", "string", "Optional 'am' or 'pm'"),
                required = listOf("hour")
            ),
            execute = { _, args ->
                var h = args.optInt("hour", 7)
                val m = args.optInt("minute", 0)
                val ampm = args.optString("ampm", "").lowercase(Locale.ROOT)
                if (ampm == "pm" && h < 12) h += 12
                if (ampm == "am" && h == 12) h = 0
                val label = args.optString("label", "Alarm")
                val res = DeviceCapability.setAlarm(h, m, label)
                val period = if (h < 12) "AM" else "PM"
                val displayH = if (h == 0) 12 else if (h > 12) h - 12 else h
                val timeStr = "%d:%02d %s".format(displayH, m, period)
                if (res.success) ok("⏰ Alarm set for $timeStr ($label).", mapOf("time" to timeStr, "label" to label))
                else CatalogSchemaHelper.fail("ALARM_ERROR", res.error ?: "Failed to set alarm")
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_alarm_list",
            description = "Opens the Clock application to view all configured alarms.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                try {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    ok("⏰ Opening alarm list in Clock.")
                } catch (e: Exception) {
                    CatalogSchemaHelper.fail("CLOCK_ERROR", e.message ?: "Failed to open alarms")
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_alarm_snooze",
            description = "Snoozes an active alarm.",
            argumentSchema = schema(prop("minutes", "integer", "Snooze duration in minutes (default 9)")),
            execute = { ctx, args ->
                val mins = args.optInt("minutes", 9)
                try {
                    val intent = Intent(AlarmClock.ACTION_SNOOZE_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    ok("⏰ Alarm snoozed for $mins minutes.")
                } catch (_: Exception) {
                    ok("⏰ Snooze command sent.")
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_alarm_dismiss",
            description = "Dismisses an active sounding alarm.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                try {
                    val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    ok("⏰ Alarm dismissed.")
                } catch (_: Exception) {
                    ok("⏰ Dismiss command sent.")
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_timer_start",
            description = "Starts a countdown timer for a specified duration in seconds or minutes.",
            argumentSchema = schema(
                prop("seconds", "integer", "Total seconds for the timer"),
                prop("minutes", "integer", "Minutes for the timer (converted if seconds omitted)"),
                prop("label", "string", "Timer name or label (e.g. 'Eggs', 'Tea', 'Break')")
            ),
            execute = { _, args ->
                var sec = args.optInt("seconds", 0)
                val min = args.optInt("minutes", 0)
                if (sec <= 0 && min > 0) sec = min * 60
                if (sec <= 0) sec = 300
                val label = args.optString("label", "Timer")
                val res = DeviceCapability.setTimer(sec, label)
                val durationStr = if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
                if (res.success) ok("⏱️ Timer started for $durationStr ($label).", mapOf("seconds" to sec, "label" to label))
                else CatalogSchemaHelper.fail("TIMER_ERROR", res.error ?: "Failed to start timer")
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_timer_list",
            description = "Opens the active countdown timers in the clock app.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                try {
                    val intent = Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    ok("⏱️ Opening timers in Clock.")
                } catch (e: Exception) {
                    CatalogSchemaHelper.fail("CLOCK_ERROR", e.message ?: "Failed to open timers")
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_stopwatch_start",
            description = "Opens the stopwatch tab in the clock app.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open stopwatch") {}
                ok("⏱️ Opening Stopwatch.")
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_world_time",
            description = "Looks up the current time, date, and timezone for any city or country in the world.",
            argumentSchema = schema(
                prop("location", "string", "City or country name (e.g. 'Tokyo', 'London', 'New York')"),
                required = listOf("location")
            ),
            execute = { _, args ->
                val loc = args.optString("location", "London")
                val res = NeedleExecutor.resolveWorldTime(loc)
                ok(res, mapOf("location" to loc))
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_time_current",
            description = "Returns the local system time formatted naturally.",
            argumentSchema = schema(),
            execute = { _, _ ->
                val t = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                ok("⏰ It is currently $t.", mapOf("time" to t))
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_date_current",
            description = "Returns the current local date with day of week, month, day, and year.",
            argumentSchema = schema(),
            execute = { _, _ ->
                val d = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
                ok("📅 Today is $d.", mapOf("date" to d))
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_time_difference",
            description = "Calculates the time difference in hours between the local device and a target city.",
            argumentSchema = schema(
                prop("location", "string", "Remote city or country name"),
                required = listOf("location")
            ),
            execute = { _, args ->
                val loc = args.optString("location", "London")
                val localOffset = TimeZone.getDefault().rawOffset
                val targetTz = TimeZone.getTimeZone(loc)
                val diffHours = (targetTz.rawOffset - localOffset) / (1000 * 60 * 60)
                val aheadBehind = if (diffHours >= 0) "$diffHours hours ahead of" else "${-diffHours} hours behind"
                ok("⏰ $loc is $aheadBehind your local time.", mapOf("diffHours" to diffHours))
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_open_app",
            description = "Launches the default system clock application.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Clock") {}
                ok("▶️ Opening Clock.")
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_timer_preset_tea",
            description = "Starts a quick 3-minute timer preset for tea brewing.",
            argumentSchema = schema(),
            execute = { _, _ ->
                DeviceCapability.setTimer(180, "Tea")
                ok("⏱️ Tea timer started for 3 minutes.")
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_timer_preset_pasta",
            description = "Starts a quick 10-minute timer preset for pasta cooking.",
            argumentSchema = schema(),
            execute = { _, _ ->
                DeviceCapability.setTimer(600, "Pasta")
                ok("⏱️ Pasta timer started for 10 minutes.")
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_timer_preset_workout",
            description = "Starts a quick 45-minute timer preset for a workout session.",
            argumentSchema = schema(),
            execute = { _, _ ->
                DeviceCapability.setTimer(2700, "Workout")
                ok("⏱️ Workout timer started for 45 minutes.")
            }
        ))

        reg(CanonicalToolDef(
            name = "clock_day_of_week",
            description = "Returns the day of the week for today or an offset date.",
            argumentSchema = schema(prop("daysOffset", "integer", "Days offset from today (e.g. 1 for tomorrow, -1 for yesterday)")),
            execute = { _, args ->
                val offset = args.optInt("daysOffset", 0)
                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
                val day = SimpleDateFormat("EEEE", Locale.US).format(cal.time)
                ok("📅 It ${if (offset == 0) "is" else "will be"} $day.", mapOf("day" to day))
            }
        ))
    }
}
