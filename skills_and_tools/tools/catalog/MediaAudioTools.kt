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
            description = "Plays music, songs, artists, or playlists on Spotify or default player.",
            argumentSchema = schema(
                prop("query", "string", "Song title, artist, album, or playlist name"),
                prop("provider", "string", "Streaming provider ('spotify', 'youtube', 'default')")
            ),
            execute = { ctx, args ->
                val q = args.optString("query", "music")
                JarvisIntentRouter.routeAndExecute(ctx, "Play $q on Spotify") {}
                ok("🎵 Playing \"$q\".", mapOf("query" to q))
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
                JarvisIntentRouter.routeAndExecute(ctx, "Open Spotify") {}
                ok("▶️ Opening Spotify.")
            }
        ))
    }
}
