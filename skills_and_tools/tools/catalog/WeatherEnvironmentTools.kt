package com.pr4nav.jarvis.tools.catalog

import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object WeatherEnvironmentTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "weather_current",
            description = "Retrieves current weather conditions, temperature, and summary for a location.",
            argumentSchema = schema(prop("location", "string", "Target city or location name")),
            execute = { ctx, args ->
                val loc = args.optString("location", "local")
                JarvisIntentRouter.routeAndExecute(ctx, "Weather in $loc") {}
                ok("⛅ Checking current weather for $loc.", mapOf("location" to loc))
            }
        ))

        reg(CanonicalToolDef(
            name = "weather_rain_check",
            description = "Checks if rain or precipitation is expected today in a location.",
            argumentSchema = schema(prop("location", "string", "Target city or location name")),
            execute = { ctx, args ->
                val loc = args.optString("location", "local")
                JarvisIntentRouter.routeAndExecute(ctx, "Is it going to rain in $loc") {}
                ok("🌧️ Checking rain forecast for $loc.")
            }
        ))

        reg(CanonicalToolDef(
            name = "weather_forecast_weekly",
            description = "Retrieves the 7-day extended weather forecast for a location.",
            argumentSchema = schema(prop("location", "string", "Target city or location name")),
            execute = { ctx, args ->
                val loc = args.optString("location", "local")
                JarvisIntentRouter.routeAndExecute(ctx, "Weekly forecast for $loc") {}
                ok("⛅ Checking 7-day weather forecast for $loc.")
            }
        ))
    }
}
