package com.pr4nav.jarvis.tools.catalog

import android.content.Intent
import android.net.Uri
import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object NavigationTravelTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "maps_navigate_to",
            description = "Opens turn-by-turn navigation in Google Maps to any destination address, business, or landmark.",
            argumentSchema = schema(
                prop("destination", "string", "Target address, city, place, or business name"),
                prop("mode", "string", "Optional travel mode: 'd' (driving), 'w' (walking), 'b' (bicycling), 'r' (transit)"),
                required = listOf("destination")
            ),
            execute = { ctx, args ->
                val dest = args.optString("destination", "")
                val mode = args.optString("mode", "d")
                val uri = Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode")
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(intent)
                    ok("🗺️ Starting navigation to $dest.", mapOf("destination" to dest))
                } catch (_: Exception) {
                    val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(dest)}")
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ok("🗺️ Opening Maps route to $dest.")
                }
            }
        ))

        reg(CanonicalToolDef(
            name = "maps_search_nearby",
            description = "Searches for nearby points of interest like restaurants, gas stations, pharmacies, ATMs.",
            argumentSchema = schema(
                prop("query", "string", "Category or business (e.g. 'coffee', 'gas station', 'hospital')"),
                required = listOf("query")
            ),
            execute = { ctx, args ->
                val q = args.optString("query", "food")
                val uri = Uri.parse("geo:0,0?q=${Uri.encode(q)}")
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ok("🗺️ Searching nearby for $q.", mapOf("query" to q))
            }
        ))

        reg(CanonicalToolDef(
            name = "maps_navigate_home",
            description = "Starts navigation to your home address.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                val uri = Uri.parse("google.navigation:q=Home")
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("🗺️ Starting navigation Home.")
            }
        ))

        reg(CanonicalToolDef(
            name = "maps_open_app",
            description = "Opens Google Maps application.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Maps") {}
                ok("▶️ Opening Google Maps.")
            }
        ))
    }
}
