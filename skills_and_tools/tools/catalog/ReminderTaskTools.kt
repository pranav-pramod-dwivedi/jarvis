package com.pr4nav.jarvis.tools.catalog

import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object ReminderTaskTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "reminder_create",
            description = "Creates a new reminder or to-do task with title and optional due time.",
            argumentSchema = schema(
                prop("title", "string", "Reminder task text (e.g. 'Buy milk', 'Pay electric bill')"),
                prop("dueTime", "string", "Optional due time (e.g. 'tomorrow at 5pm')"),
                required = listOf("title")
            ),
            execute = { ctx, args ->
                val title = args.optString("title", "Reminder")
                val due = args.optString("dueTime", "")
                JarvisIntentRouter.routeAndExecute(ctx, "Remind me to $title $due") {}
                ok("📝 Reminder created: \"$title\".", mapOf("title" to title))
            }
        ))

        reg(CanonicalToolDef(
            name = "reminder_list_today",
            description = "Lists all reminders scheduled for today.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Show my reminders") {}
                ok("📝 Opened Reminders list.")
            }
        ))

        reg(CanonicalToolDef(
            name = "reminder_open_app",
            description = "Opens Google Tasks or the primary Reminders application.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Tasks") {}
                ok("▶️ Opening Reminders/Tasks.")
            }
        ))

        reg(CanonicalToolDef(
            name = "reminder_water_log",
            description = "Sets a periodic drinking water reminder.",
            argumentSchema = schema(prop("intervalMinutes", "integer", "Interval in minutes (default 60)")),
            execute = { ctx, args ->
                val mins = args.optInt("intervalMinutes", 60)
                JarvisIntentRouter.routeAndExecute(ctx, "Remind me to drink water every $mins minutes") {}
                ok("💧 Set water reminder every $mins minutes.")
            }
        ))

        reg(CanonicalToolDef(
            name = "reminder_medicine_set",
            description = "Sets a daily reminder to take medicine.",
            argumentSchema = schema(
                prop("medicineName", "string", "Medicine name"),
                prop("time", "string", "Time of day (e.g. '9:00 AM')"),
                required = listOf("medicineName")
            ),
            execute = { ctx, args ->
                val med = args.optString("medicineName", "medicine")
                val time = args.optString("time", "9:00 AM")
                JarvisIntentRouter.routeAndExecute(ctx, "Remind me to take $med at $time") {}
                ok("💊 Set reminder to take $med at $time.")
            }
        ))
    }
}
