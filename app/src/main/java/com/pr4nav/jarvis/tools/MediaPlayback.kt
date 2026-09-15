package com.pr4nav.jarvis.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Shared media launch path. An accepted intent is not proof of playback. */
object MediaPlayback {
    internal data class Request(val query: String, val provider: String, val uri: String?)

    internal fun plan(query: String, provider: String): Request {
        var q = query.trim()
        require(q.isNotEmpty()) { "Specify a song, artist, or video to play." }
        var target = provider.trim().lowercase(Locale.ROOT).ifBlank { "default" }
        if (target == "default") {
            val suffix = Regex("\\s+on\\s+(youtube|spotify)$", RegexOption.IGNORE_CASE).find(q)
            if (suffix != null) {
                target = suffix.groupValues[1].lowercase(Locale.ROOT)
                q = q.substring(0, suffix.range.first).trim()
                require(q.isNotEmpty()) { "Specify a song, artist, or video to play." }
            }
        }
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val uri = when (target) {
            "youtube" -> "https://www.youtube.com/results?search_query=$encoded"
            "spotify" -> "spotify:search:$encoded"
            "default" -> null
            else -> throw IllegalArgumentException("Unsupported media provider: $target")
        }
        return Request(q, target, uri)
    }

    fun play(context: Context, args: JSONObject): ToolResult {
        val request = try {
            plan(args.optString("query", ""), args.optString("provider", "default"))
        } catch (e: IllegalArgumentException) {
            return ToolResult.invalidArguments(e.message ?: "Invalid media request")
        }
        return try {
            val intent = if (request.uri != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse(request.uri))
            } else {
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            }
            if (request.provider == "spotify") intent.setPackage("com.spotify.music")
            if (request.uri == null) {
                intent.putExtra(SearchManager.QUERY, request.query)
                intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val note = when (request.provider) {
                "youtube" -> "Opened YouTube search for \"${request.query}\". Select a result to play; playback is not confirmed."
                "spotify" -> "Opened Spotify search for \"${request.query}\". Select a result to play; playback is not confirmed."
                else -> "Sent the player a playback request for \"${request.query}\". Playback is not confirmed."
            }
            Log.i("MediaPlayback", "provider=${request.provider} outcome=INTENT_ACCEPTED playbackConfirmed=false")
            ToolResult.requiresUser("Check the media player", note).copy(data = JSONObject().apply {
                put("query", request.query)
                put("provider", request.provider)
                put("action", if (request.uri == null) "PLAYBACK_REQUESTED" else "MEDIA_SEARCH_OPENED")
                put("playbackConfirmed", false)
            })
        } catch (e: Exception) {
            Log.w("MediaPlayback", "provider=${request.provider} outcome=LAUNCH_FAILED", e)
            ToolResult.failure("MEDIA_LAUNCH_FAILED", "Could not open the media player: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
