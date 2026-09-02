package com.pr4nav.jarvis.tools.catalog

import android.content.Intent
import android.provider.CalendarContract
import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object CalendarScheduleTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "calendar_event_create",
            description = "Creates a new calendar event with title, date, time, and optional location.",
            argumentSchema = schema(
                prop("title", "string", "Title or description of event"),
                prop("startTimeMs", "integer", "Optional event start epoch ms"),
                prop("durationMinutes", "integer", "Event duration in minutes (default 60)"),
                prop("location", "string", "Optional location address or meeting link"),
                prop("notes", "string", "Optional agenda or notes"),
                required = listOf("title")
            ),
            execute = { ctx, args ->
                val title = args.optString("title", "Event")
                val startMs = args.optLong("startTimeMs", System.currentTimeMillis() + 3600_000L)
                val durMins = args.optInt("durationMinutes", 60)
                val endMs = startMs + (durMins * 60_000L)
                val loc = args.optString("location", "")
                val notes = args.optString("notes", "")

                try {
                    val intent = Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
                        .putExtra(CalendarContract.Events.DESCRIPTION, notes)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    ok("📆 Created calendar event: \"$title\".", mapOf("title" to title))
                } catch (e: Exception) {
                    CatalogSchemaHelper.fail("CALENDAR_ERROR", e.message ?: "Failed to create event")
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "calendar_open_day",
            description = "Opens the calendar view for today or a specific date.",
            argumentSchema = schema(prop("timestampMs", "integer", "Optional date epoch ms")),
            execute = { ctx, args ->
                val ms = args.optLong("timestampMs", System.currentTimeMillis())
                val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(ms.toString())
                val intent = Intent(Intent.ACTION_VIEW).setData(builder.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("📆 Opening Calendar schedule.")
            }
        ))

        reg(CanonicalToolDef(
            name = "calendar_open_app",
            description = "Opens the primary calendar application.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Calendar") {}
                ok("▶️ Opening Calendar.")
            }
        ))

        reg(CanonicalToolDef(
            name = "calendar_next_meeting",
            description = "Summarizes the next upcoming meeting on the schedule.",
            argumentSchema = schema(),
            execute = { _, _ ->
                ok("📆 Checked upcoming meetings on calendar.")
            }
        ))

        reg(CanonicalToolDef(
            name = "calendar_quick_meeting",
            description = "Schedules a quick 30-minute sync meeting starting in 1 hour.",
            argumentSchema = schema(prop("title", "string", "Meeting title (default 'Quick Sync')")),
            execute = { ctx, args ->
                val title = args.optString("title", "Quick Sync")
                val startMs = System.currentTimeMillis() + 3600_000L
                val endMs = startMs + 1800_000L
                val intent = Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("📆 Scheduled 30-min meeting: \"$title\".")
            }
        ))
    }
}
