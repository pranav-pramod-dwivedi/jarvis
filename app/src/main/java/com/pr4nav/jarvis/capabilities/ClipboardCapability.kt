package com.pr4nav.jarvis.capabilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject

object ClipboardCapability : Capability {

    override val name = "clipboard"

    private fun cm(ctx: Context = Capabilities.require()): ClipboardManager =
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun read(): CapabilityResult = try {
        val clip = cm().primaryClip
        when {
            !cm().hasPrimaryClip() || clip == null || clip.itemCount == 0 ->
                CapabilityResult.fail(
                    if (Build.VERSION.SDK_INT >= 29)
                        "Clipboard empty or restricted — Android only lets the focused app read the clipboard"
                    else "Clipboard is empty"
                )
            else -> {
                val item = clip.getItemAt(0)
                val text = item.text?.toString()
                val uri = item.uri?.toString()
                val intent = item.intent?.toUri(0)
                CapabilityResult.ok(
                    JSONObject().apply {
                        put("text", text ?: "")
                        uri?.let { put("uri", it) }
                        intent?.let { put("intent", it) }
                        put("mime", clip.description?.let { d ->
                            if (d.mimeTypeCount > 0) d.getMimeType(0) else ""
                        } ?: "")
                    }.toString()
                )
            }
        }
    } catch (e: Exception) {
        CapabilityResult.fail("Clipboard read failed: ${e.message}")
    }

    fun write(text: String): CapabilityResult = try {
        if (text.isEmpty()) CapabilityResult.fail("Nothing to copy")
        else {
            cm().setPrimaryClip(ClipData.newPlainText("jarvis", text))
            CapabilityResult.ok(JSONObject().put("copied", text.take(200)).put("chars", text.length).toString())
        }
    } catch (e: Exception) {
        CapabilityResult.fail("Clipboard write failed: ${e.message}")
    }

    override fun available(): Boolean = true
    override fun permitted(): Boolean = true
    override fun status() = "✓ Clipboard — read/write (Android focus rules apply)"

    override fun tools() = listOf(
        ToolDef("clipboard.read", "Read current clipboard content", "{}", null,
            { _ -> read().envelope() }),
        ToolDef("clipboard.write", "Put text on the clipboard", """{"text":"..."}""", null,
            { a -> write(a.optString("text", "")).envelope() })
    )
}
