package com.pr4nav.jarvis.tools.catalog

import com.pr4nav.jarvis.memory.JarvisMemoryStore
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object NotesListsTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "notes_create",
            description = "Creates and saves a quick note into memory.",
            argumentSchema = schema(
                prop("text", "string", "Note text content"),
                required = listOf("text")
            ),
            execute = { ctx, args ->
                val text = args.optString("text", "")
                JarvisMemoryStore.remember(ctx, "note_${System.currentTimeMillis()}", text)
                ok("📝 Saved note: \"$text\".", mapOf("content" to text))
            }
        ))

        reg(CanonicalToolDef(
            name = "notes_list",
            description = "Retrieves all saved notes.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                val notes = JarvisMemoryStore.getAll(ctx)
                val summary = notes.map { it.value }.take(5).joinToString("; ")
                ok("📝 Notes: ${if (summary.isNotBlank()) summary else "No notes saved."}")
            }
        ))

        reg(CanonicalToolDef(
            name = "list_add_item",
            description = "Adds an item to a named shopping or to-do list.",
            argumentSchema = schema(
                prop("listName", "string", "List name (e.g. 'groceries', 'todo')"),
                prop("item", "string", "Item to add"),
                required = listOf("listName", "item")
            ),
            execute = { ctx, args ->
                val list = args.optString("listName", "general")
                val item = args.optString("item", "")
                val key = "list_$list"
                val existing = JarvisMemoryStore.recall(ctx, key).firstOrNull()?.value ?: ""
                val updated = if (existing.isBlank()) item else "$existing, $item"
                JarvisMemoryStore.remember(ctx, key, updated)
                ok("📋 Added \"$item\" to $list list.")
            }
        ))

        reg(CanonicalToolDef(
            name = "list_view",
            description = "Views all items in a named list.",
            argumentSchema = schema(
                prop("listName", "string", "List name (e.g. 'groceries', 'todo')"),
                required = listOf("listName")
            ),
            execute = { ctx, args ->
                val list = args.optString("listName", "general")
                val items = JarvisMemoryStore.recall(ctx, "list_$list").firstOrNull()?.value ?: "List is empty."
                ok("📋 $list list: $items")
            }
        ))
    }
}
