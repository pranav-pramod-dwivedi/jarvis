package com.pr4nav.jarvis.tools.catalog

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object WebKnowledgeTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "web_google_search",
            description = "Performs a Google web search for any query.",
            argumentSchema = schema(
                prop("query", "string", "Search query"),
                required = listOf("query")
            ),
            execute = { ctx, args ->
                val q = args.optString("query", "")
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, q)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                ok("🔍 Searching web for \"$q\".", mapOf("query" to q))
            }
        ))

        reg(CanonicalToolDef(
            name = "web_open_url",
            description = "Opens a web link in browser.",
            argumentSchema = schema(
                prop("url", "string", "Website URL"),
                required = listOf("url")
            ),
            execute = { ctx, args ->
                var url = args.optString("url", "")
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("🌐 Opening $url.", mapOf("url" to url))
            }
        ))

        reg(CanonicalToolDef(
            name = "web_wikipedia_lookup",
            description = "Opens Wikipedia page for any topic or person.",
            argumentSchema = schema(
                prop("topic", "string", "Article topic"),
                required = listOf("topic")
            ),
            execute = { ctx, args ->
                val topic = args.optString("topic", "")
                val url = "https://en.wikipedia.org/wiki/${Uri.encode(topic)}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("📖 Opening Wikipedia for \"$topic\".")
            }
        ))
    }
}
