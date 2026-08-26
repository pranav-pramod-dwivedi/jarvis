package com.pr4nav.jarvis.capabilities

import android.content.Intent
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject

object ScreenshotCapability : Capability {

    override val name = "screenshot"

    @Volatile private var resultCode: Int = Int.MIN_VALUE
    @Volatile private var resultData: Intent? = null
    @Volatile private var consentError: String? = null

    fun onConsentResult(rc: Int?, data: Intent?, error: String?) {
        if (rc != null && data != null) {
            resultCode = rc
            resultData = data
            consentError = null
        } else {
            consentError = error ?: "consent not granted"
        }
    }

    fun hasToken(): Boolean = resultCode != Int.MIN_VALUE && resultData != null

    fun invalidateToken() {
        resultCode = Int.MIN_VALUE
        resultData = null
    }

    /** Explicitly asks the user for screen-capture consent (system dialog). */
    fun prepare(fromActivity: android.app.Activity) {
        fromActivity.startActivity(Intent(fromActivity, CaptureConsentActivity::class.java))
    }

    fun capture(): CapabilityResult {
        val ctx = Capabilities.require()
        if (!hasToken()) {
            try {
                ctx.startActivity(Intent(ctx, CaptureConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            return CapabilityResult.fail(
                "Screen capture needs your one-time consent — a system dialog was opened. Ask again after approving."
            )
        }
        if (ScreenshotCaptureService.busy)
            return CapabilityResult.fail("A capture is already in progress")
        ScreenshotCaptureService.request(ctx, resultCode, resultData!!)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline && ScreenshotCaptureService.busy) {
            Thread.sleep(120)
        }
        val path = ScreenshotCaptureService.lastResultPath
        val err = ScreenshotCaptureService.lastError
        return when {
            path != null -> CapabilityResult.ok(
                JSONObject().put("screenshot", path).toString(),
                "hint" to "reference this path with file tools or vision"
            )
            err != null -> CapabilityResult.fail("Screenshot failed: $err")
            else -> CapabilityResult.fail("Screenshot timed out")
        }
    }

    override fun available(): Boolean = true
    override fun permitted(): Boolean = hasToken()
    override fun status(): String = when {
        hasToken() -> "✓ Screenshot — capture consent granted"
        else -> "○ Screenshot — consent required at first use per session"
    }

    override fun tools() = listOf(
        ToolDef(
            "screenshot.capture",
            "Capture the screen and save it; returns image path/URI. Opens a consent dialog the first time.",
            "{}", null,
            { _ ->
                if (Capabilities.app == null) JSONObject().put("ok", false).put("error", "not initialized")
                else capture().envelope()
            })
    )
}
