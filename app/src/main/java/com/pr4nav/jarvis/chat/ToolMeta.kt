package com.pr4nav.jarvis.chat

import com.pr4nav.jarvis.R

/**
 * Human-friendly labels + SVG icon mapping for every tool the agent can run.
 * This is what turns "execute_shell_command" into a "Run" card with a terminal icon
 * instead of dumped raw text.
 */
object ToolMeta {

    data class Meta(val label: String, val iconRes: Int, val accent: String)

    private const val ORANGE = "#FF7A00"
    private const val BLUE = "#38BDF8"
    private const val GREEN = "#10B981"
    private const val VIOLET = "#A78BFA"
    private const val SLATE = "#94A3B8"
    private const val RED = "#EF4444"

    fun of(toolName: String, argsSummary: String = ""): Meta {
        return when (toolName.trim().lowercase()) {
            "execute_shell_command" -> Meta("Run", R.drawable.ic_terminal, ORANGE)
            "execute_root_command" -> Meta("Run as root", R.drawable.ic_terminal, RED)
            "execute_batch_commands" -> Meta("Batch run", R.drawable.ic_terminal, ORANGE)
            "read_file" -> Meta("Read", R.drawable.ic_file, BLUE)
            "write_file" -> Meta("Write", R.drawable.ic_pencil, GREEN)
            "edit_file" -> Meta("Edit", R.drawable.ic_pencil, GREEN)
            "list_directory" -> Meta("List", R.drawable.ic_folder, BLUE)
            "grep_search" -> Meta("Search", R.drawable.ic_search, VIOLET)
            "find_files" -> Meta("Find", R.drawable.ic_search, VIOLET)
            "remember" -> Meta("Remember", R.drawable.ic_brain, VIOLET)
            "browser_render_app" -> Meta("Render app", R.drawable.ic_window, GREEN)
            "execute_device_tool" -> {
                val inner = humanize(argsSummary)
                if (inner.isNotBlank()) Meta(inner, R.drawable.ic_phone, BLUE)
                else Meta("Device tool", R.drawable.ic_phone, BLUE)
            }
            else -> {
                val label = humanize(toolName)
                Meta(if (label.isNotBlank()) label else "Tool", R.drawable.ic_tool, SLATE)
            }
        }
    }

    /** Turns snake_case / camelCase tool names into Title Case labels. */
    fun humanize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        s = s.replace('_', ' ').replace('-', ' ')
        s = s.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        return s.split(' ').filter { it.isNotBlank() }.joinToString(" ") { w ->
            if (w.all { it.isUpperCase() || it.isDigit() }) w
            else w.replaceFirstChar { it.uppercase() }
        }
    }

    /** One-line summary of tool arguments for the card subtitle. */
    fun summarizeArgs(toolName: String, keys: Map<String, String>): String {
        val t = toolName.trim().lowercase()
        fun g(vararg ks: String): String {
            for (k in ks) {
                val v = keys[k]?.trim()
                if (!v.isNullOrBlank()) return v
            }
            return ""
        }
        return when (t) {
            "execute_shell_command", "execute_root_command" -> g("command", "cmd")
            "execute_batch_commands" -> {
                val c = g("commands", "command")
                if (c.length > 90) c.take(90) + "…" else c
            }
            "read_file", "write_file", "edit_file", "list_directory", "find_files" -> g("path", "file", "dir", "query")
            "grep_search" -> {
                val q = g("pattern", "query")
                val p = g("path")
                if (q.isNotBlank() && p.isNotBlank()) "$q · $p" else q.ifBlank { p }
            }
            "remember" -> g("fact", "key", "text", "memory")
            "browser_render_app" -> {
                val title = g("title")
                val id = g("app_id", "appId")
                listOf(title, id).filter { it.isNotBlank() }.joinToString(" · ")
            }
            "execute_device_tool" -> g("tool_name", "tool", "name")
            else -> g("command", "path", "query", "text", "tool_name", "app_id", "title", "pattern")
        }
    }
}
