package com.pr4nav.jarvis.registry

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pr4nav.jarvis.capabilities.AudioCapability
import com.pr4nav.jarvis.router.JarvisIntentRouter

object MediaDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        CapabilityDef(
            id = "media.play",
            category = "media",
            name = "Play Media",
            description = "Start or resume media playback",
            aliases = listOf("play", "resume music", "play music", "start playing", "resume"),
            execute = { _, _ ->
                AudioCapability.mediaKey("play")
                CapabilityExecutionResult.ok("▶️ Media playback started.")
            }
        ),

        CapabilityDef(
            id = "media.pause",
            category = "media",
            name = "Pause Media",
            description = "Pause current media playback",
            aliases = listOf("pause", "pause music", "stop music", "pause song", "hold playback"),
            execute = { _, _ ->
                AudioCapability.mediaKey("pause")
                CapabilityExecutionResult.ok("⏸️ Media paused.")
            }
        ),

        CapabilityDef(
            id = "media.playpause",
            category = "media",
            name = "Toggle Media Playback",
            description = "Toggle between play and pause states",
            aliases = listOf("toggle music", "toggle playback", "play pause"),
            execute = { _, _ ->
                AudioCapability.mediaKey("playpause")
                CapabilityExecutionResult.ok("⏯️ Media playback toggled.")
            }
        ),

        CapabilityDef(
            id = "media.stop",
            category = "media",
            name = "Stop Media",
            description = "Stop active media playback",
            aliases = listOf("stop playback", "stop playing"),
            execute = { _, _ ->
                AudioCapability.mediaKey("stop")
                CapabilityExecutionResult.ok("⏹️ Media playback stopped.")
            }
        ),

        CapabilityDef(
            id = "media.next",
            category = "media",
            name = "Next Track",
            description = "Skip to the next audio track",
            aliases = listOf("next song", "next track", "skip song", "skip", "next"),
            execute = { _, _ ->
                AudioCapability.mediaKey("next")
                CapabilityExecutionResult.ok("⏭️ Skipped to next track.")
            }
        ),

        CapabilityDef(
            id = "media.previous",
            category = "media",
            name = "Previous Track",
            description = "Return to previous audio track",
            aliases = listOf("previous song", "previous track", "last song", "prev song", "go back song"),
            execute = { _, _ ->
                AudioCapability.mediaKey("previous")
                CapabilityExecutionResult.ok("⏮️ Returned to previous track.")
            }
        ),

        CapabilityDef(
            id = "media.spotify.open",
            category = "media",
            name = "Open Spotify",
            description = "Launch the Spotify music application",
            aliases = listOf("open spotify", "launch spotify", "start spotify"),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Spotify") { _ -> }
                CapabilityExecutionResult.ok("🎵 Spotify launched.")
            }
        ),

        CapabilityDef(
            id = "media.spotify.play",
            category = "media",
            name = "Play on Spotify",
            description = "Search and play a song or artist on Spotify",
            aliases = listOf("play on spotify", "spotify play"),
            optionalParams = listOf("query"),
            execute = { ctx, params ->
                val query = (params["query"] as? String) ?: "music"
                JarvisIntentRouter.routeAndExecute(ctx, "Play $query on Spotify") { _ -> }
                CapabilityExecutionResult.ok("🎵 Playing \"$query\" on Spotify.")
            }
        ),

        CapabilityDef(
            id = "media.youtube.open",
            category = "media",
            name = "Open YouTube",
            description = "Launch the YouTube application",
            aliases = listOf("open youtube", "launch youtube", "start youtube"),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open YouTube") { _ -> }
                CapabilityExecutionResult.ok("▶️ YouTube launched.")
            }
        ),

        CapabilityDef(
            id = "media.youtube.search",
            category = "media",
            name = "Search YouTube",
            description = "Search YouTube for a specific query or video",
            aliases = listOf("search youtube for", "youtube search"),
            optionalParams = listOf("query"),
            execute = { ctx, params ->
                val q = (params["query"] as? String) ?: ""
                val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("▶️ Searching YouTube for \"$q\".")
            }
        ),

        CapabilityDef(
            id = "media.youtubemusic.open",
            category = "media",
            name = "Open YouTube Music",
            description = "Launch the YouTube Music app",
            aliases = listOf("open youtube music", "launch yt music", "open yt music"),
            execute = { ctx, _ ->
                val pm = ctx.packageManager
                val intent = pm.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
                if (intent != null) {
                    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    CapabilityExecutionResult.ok("🎧 YouTube Music opened.")
                } else {
                    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(web)
                    CapabilityExecutionResult.ok("🎧 Opening YouTube Music on web.")
                }
            }
        )
    )
}
