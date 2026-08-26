package com.pr4nav.jarvis.capabilities

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject

object AudioCapability : Capability {

    override val name = "audio"

    val STREAMS = mapOf(
        "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "alarm" to AudioManager.STREAM_ALARM,
        "system" to AudioManager.STREAM_SYSTEM,
        "voicecall" to AudioManager.STREAM_VOICE_CALL,
        "dtmf" to AudioManager.STREAM_DTMF
    )

    private fun am(ctx: Context = Capabilities.require()): AudioManager =
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun volume(streamName: String): CapabilityResult {
        val st = STREAMS[streamName.lowercase()]
            ?: return CapabilityResult.fail("Unknown stream '$streamName'. Streams: ${STREAMS.keys}")
        val a = am()
        return CapabilityResult.ok(
            JSONObject().put("stream", streamName)
                .put("current", a.getStreamVolume(st))
                .put("max", a.getStreamMaxVolume(st))
                .put("muteSupported", true)
                .toString()
        )
    }

    fun setVolume(streamName: String, value: Int): CapabilityResult {
        val st = STREAMS[streamName.lowercase()]
            ?: return CapabilityResult.fail("Unknown stream '$streamName'. Streams: ${STREAMS.keys}")
        val a = am()
        val v = value.coerceIn(0, a.getStreamMaxVolume(st))
        a.setStreamVolume(st, v, 0)
        return CapabilityResult.ok(
            JSONObject().put("stream", streamName).put("volume", v)
                .put("max", a.getStreamMaxVolume(st)).toString()
        )
    }

    fun adjustVolume(streamName: String, delta: Int): CapabilityResult {
        val st = STREAMS[streamName.lowercase()]
            ?: return CapabilityResult.fail("Unknown stream '$streamName'")
        val before = am().getStreamVolume(st)
        return setVolume(streamName, before + delta)
    }

    fun mute(streamName: String, mute: Boolean): CapabilityResult {
        val st = STREAMS[streamName.lowercase()]
            ?: return CapabilityResult.fail("Unknown stream '$streamName'. Streams: ${STREAMS.keys}")
        val a = am()
        if (mute) {
            a.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
        } else {
            a.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
        }
        val muted = try {
            a.isStreamMute(st)
        } catch (_: Exception) { a.getStreamVolume(st) == 0 }
        return CapabilityResult.ok(
            JSONObject().put("stream", streamName).put("muted", muted).toString()
        )
    }

    fun mediaKey(action: String): CapabilityResult {
        val code = when (action.lowercase().trim()) {
            "play", "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return CapabilityResult.fail(
                "Unknown media action '$action' (play|pause|next|previous|stop)"
            )
        }
        val a = am()
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return CapabilityResult.ok(
            JSONObject().put("dispatched", action).put("musicActive", a.isMusicActive).toString()
        )
    }

    override fun available(): Boolean = true
    override fun permitted(): Boolean = true
    override fun status() = "✓ Audio — volumes & media control via AudioManager"

    override fun tools() = listOf(
        ToolDef("audio.volume", "Read stream volume", """{"stream":"music"}""", null,
            { a -> volume(a.optString("stream", "music")).envelope() }),
        ToolDef("audio.setvolume", "Set stream volume (0..max)", """{"stream":"music","value":7}""", null,
            { a -> setVolume(a.getString("stream"), a.optInt("value", -1)).envelope() }),
        ToolDef("audio.mute", "Mute/unmute a stream", """{"stream":"ring","mute":true}""", null,
            { a -> mute(a.getString("stream"), a.optBoolean("mute", true)).envelope() }),
        ToolDef("audio.media", "Media control: play/pause/next/previous/stop", """{"action":"next"}""",
            { if (Capabilities.app == null) "not initialized" else null },
            { a -> mediaKey(a.getString("action")).envelope() })
    )
}
