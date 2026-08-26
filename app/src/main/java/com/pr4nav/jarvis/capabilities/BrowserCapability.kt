package com.pr4nav.jarvis.capabilities

import android.content.Intent
import android.net.Uri
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject

object BrowserCapability : Capability {

    override val name = "browser"

    fun openUrl(rawUrl: String): CapabilityResult {
        var url = rawUrl.trim()
        if (url.isEmpty()) return CapabilityResult.fail("No URL given")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return try {
            val ctx = Capabilities.require()
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CapabilityResult.ok(JSONObject().put("opened", url).toString())
        } catch (e: Exception) {
            CapabilityResult.fail("No browser available for $url (${e.message})")
        }
    }

    fun webSearch(query: String): CapabilityResult {
        if (query.isBlank()) return CapabilityResult.fail("Empty search")
        return openUrl("https://www.google.com/search?q=" + android.net.Uri.encode(query))
    }

    override fun available(): Boolean = true
    override fun permitted(): Boolean = true
    override fun status() = "✓ Browser — URL / search intents ready"

    override fun tools() = listOf(
        ToolDef("browser.open", "Open a URL in the browser", """{"url":"example.com"}""",
            null, { a -> openUrl(a.getString("url")).envelope() }),
        ToolDef("browser.search", "Run a web search", """{"query":"..."}""",
            null, { a -> webSearch(a.getString("query")).envelope() })
    )
}
