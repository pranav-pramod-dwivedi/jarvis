package com.pr4nav.jarvis.tools.catalog

import com.pr4nav.jarvis.capabilities.AudioCapability
import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object MediaAudioTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "music_play",
            description = "Requests playback or opens Spotify/YouTube search; reports launch errors and unconfirmed playback.",
            argumentSchema = schema(
                prop("query", "string", "Song title, artist, album, or playlist name"),
                prop("provider", "string", "Streaming provider ('spotify', 'youtube', 'default')")
            ),
            execute = { ctx, args ->
                com.pr4nav.jarvis.tools.MediaPlayback.play(ctx, args)
            }
        ))

        reg(CanonicalToolDef(
            name = "music_pause",
            description = "Pauses active audio playback.",
            argumentSchema = schema(),
            execute = { _, _ ->
                AudioCapability.mediaKey("pause")
                ok("⏸️ Music paused.")
            }
        ))

        reg(CanonicalToolDef(
            name = "music_resume",
            description = "Resumes paused audio playback.",
            argumentSchema = schema(),
            execute = { _, _ ->
                AudioCapability.mediaKey("play")
                ok("▶️ Music resumed.")
            }
        ))

        reg(CanonicalToolDef(
            name = "music_next",
            description = "Skips to next track.",
            argumentSchema = schema(),
            execute = { _, _ ->
                AudioCapability.mediaKey("next")
                ok("⏭️ Next track.")
            }
        ))

        reg(CanonicalToolDef(
            name = "music_previous",
            description = "Skips to previous track.",
            argumentSchema = schema(),
            execute = { _, _ ->
                AudioCapability.mediaKey("previous")
                ok("⏮️ Previous track.")
            }
        ))

        reg(CanonicalToolDef(
            name = "music_open_spotify",
            description = "Opens the Spotify music player app.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                com.pr4nav.jarvis.tools.CanonicalToolRegistry.execute(
                    ctx, "open_app", org.json.JSONObject().put("app", "Spotify")
                )
            }
        ))
    }
}
