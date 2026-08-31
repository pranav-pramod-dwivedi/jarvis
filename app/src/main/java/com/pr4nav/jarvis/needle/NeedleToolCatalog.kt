package com.pr4nav.jarvis.needle

import org.json.JSONArray
import org.json.JSONObject

/**
 * Catalog of official Needle 2 JSON schemas exposing JARVIS's registered capabilities.
 */
object NeedleToolCatalog {

    fun generateSchemasJson(): String {
        val list = JSONArray()

        list.put(tool(
            "system.battery",
            "Check device battery percentage and charging state",
            JSONObject()
        ))

        list.put(tool(
            "system.torch",
            "Turn the flashlight or torch on or off",
            obj(
                "on" to prop("boolean", "true to turn torch on, false to turn off")
            ),
            listOf("on")
        ))

        list.put(tool(
            "system.volume",
            "Adjust or set device volume for media, ring, or alarm",
            obj(
                "stream" to prop("string", "stream type: music, ring, notification, or alarm"),
                "value" to prop("integer", "target volume level 0 to 15"),
                "direction" to prop("string", "direction: up or down")
            )
        ))

        list.put(tool(
            "system.brightness",
            "Set or adjust screen brightness",
            obj(
                "level" to prop("integer", "brightness percentage 0 to 100"),
                "direction" to prop("string", "direction: up or down")
            )
        ))

        list.put(tool(
            "system.time",
            "Get the current local time",
            JSONObject()
        ))

        list.put(tool(
            "system.date",
            "Get the current date and day of week",
            JSONObject()
        ))

        list.put(tool(
            "system.alarm",
            "Set an alarm for a specific time via system clock",
            obj(
                "hour" to prop("integer", "alarm hour in 24-hour format 0-23"),
                "minute" to prop("integer", "alarm minute 0-59"),
                "label" to prop("string", "optional label for the alarm")
            ),
            listOf("hour", "minute")
        ))

        list.put(tool(
            "system.timer",
            "Start a countdown timer",
            obj(
                "seconds" to prop("integer", "countdown duration in seconds"),
                "label" to prop("string", "optional label for the timer")
            ),
            listOf("seconds")
        ))

        list.put(tool(
            "media.play",
            "Play music, songs, artists, or playlists on Spotify or YouTube Music",
            obj(
                "query" to prop("string", "song title, artist, genre, or playlist name"),
                "provider" to prop("string", "preferred provider: spotify or youtube")
            ),
            listOf("query")
        ))

        list.put(tool(
            "media.control",
            "Control media playback",
            obj(
                "action" to prop("string", "playback action: play, pause, next, previous, or stop")
            ),
            listOf("action")
        ))

        list.put(tool(
            "navigation.route",
            "Navigate to a destination or search nearby places via Google Maps",
            obj(
                "destination" to prop("string", "destination address, place name, or coordinates")
            ),
            listOf("destination")
        ))

        list.put(tool(
            "file.list",
            "List files and folders in directory",
            obj(
                "path" to prop("string", "directory path, defaults to internal storage")
            )
        ))

        list.put(tool(
            "file.search",
            "Search for files or documents by name or keyword",
            obj(
                "query" to prop("string", "search keyword or filename")
            ),
            listOf("query")
        ))

        list.put(tool(
            "file.read",
            "Read content of a text or code file",
            obj(
                "path" to prop("string", "file path to read")
            ),
            listOf("path")
        ))

        list.put(tool(
            "file.delete",
            "Delete a file or directory (destructive action)",
            obj(
                "path" to prop("string", "file path to delete")
            ),
            listOf("path")
        ))

        list.put(tool(
            "app.launch",
            "Open an installed Android application",
            obj(
                "name" to prop("string", "application name like spotify, youtube, settings, chrome, maps")
            ),
            listOf("name")
        ))

        list.put(tool(
            "termux.diag",
            "Run a safe Linux/Termux diagnostic check (uname, uptime, distro)",
            obj(
                "check" to prop("string", "diagnostic check: uname, uptime, or memory")
            )
        ))

        list.put(tool(
            "opencode.open",
            "Open OpenCode coding agent workspace or project",
            obj(
                "project" to prop("string", "optional project or workspace name")
            )
        ))

        list.put(tool(
            "gui.show_dashboard",
            "Render interactive visual GUI dashboard for CPU, RAM, and storage metrics",
            obj(
                "metric" to prop("string", "target metric: cpu, ram, storage, or all")
            )
        ))

        list.put(tool(
            "notes.create",
            "Save a note, reminder, or personal fact to memory",
            obj(
                "content" to prop("string", "note content or fact to remember")
            ),
            listOf("content")
        ))

        list.put(tool(
            "notes.view",
            "Recall saved notes, memories, or personal facts",
            obj(
                "query" to prop("string", "query to search in notes or memory")
            )
        ))

        return list.toString(2)
    }

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("description", desc)
        val params = JSONObject()
        params.put("type", "object")
        params.put("properties", props)
        if (required.isNotEmpty()) {
            val reqArr = JSONArray()
            for (r in required) reqArr.put(r)
            params.put("required", reqArr)
        }
        o.put("parameters", params)
        return o
    }

    private fun prop(type: String, desc: String): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        o.put("description", desc)
        return o
    }

    private fun obj(vararg pairs: Pair<String, JSONObject>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v)
        return o
    }
}
