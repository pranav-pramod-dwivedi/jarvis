package com.pr4nav.jarvis.capabilities

import android.content.Intent
import android.os.Build
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object ScreenshotCapability : Capability {

    override val name = "screenshot"

    @Volatile private var resultCode: Int = Int.MIN_VALUE
    @Volatile private var resultData: Intent? = null
    @Volatile private var consentError: String? = null
    private val captureLock = ReentrantLock()

    fun onConsentResult(rc: Int?, data: Intent?, error: String?) {
        if (rc != null && data != null) {
            resultCode = rc
            resultData = data
            consentError = null
        } else {
            resultCode = Int.MIN_VALUE
            resultData = null
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

    fun capture(): CapabilityResult = captureLock.withLock {
        val ctx = Capabilities.require()
        if (!hasToken()) {
            try {
                ctx.startActivity(
                    Intent(ctx, CaptureConsentActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
            return CapabilityResult.fail(
                "Screen capture needs your one-time consent — a system dialog was opened. Ask again after approving."
            )
        }
        if (ScreenshotCaptureService.busy) {
            return CapabilityResult.fail("A capture is already in progress")
        }

        val tokenRc = resultCode
        val tokenData = resultData!!

        // On Android 14+ (API 34-36), MediaProjection consent intents are strictly single-use.
        // Invalidate cached token immediately so subsequent captures obtain a fresh session.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            invalidateToken()
        }

        ScreenshotCaptureService.request(ctx, tokenRc, tokenData)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline && ScreenshotCaptureService.busy) {
            Thread.sleep(100)
        }
        val path = ScreenshotCaptureService.lastResultPath
        val err = ScreenshotCaptureService.lastError
        return when {
            path != null -> CapabilityResult.ok(
                JSONObject().put("screenshot", path).toString(),
                "hint" to "reference this path with file tools or vision"
            )
            err != null -> {
                invalidateToken()
                CapabilityResult.fail("Screenshot failed: $err")
            }
            else -> {
                invalidateToken()
                CapabilityResult.fail("Screenshot timed out")
            }
        }
    }

    override fun available(): Boolean = true
    override fun permitted(): Boolean = hasToken()
    override fun status(): String = when {
        hasToken() -> "✓ Screenshot — capture consent ready"
        else -> "○ Screenshot — consent required per session on modern Android"
    }

    override fun tools() = listOf(
        ToolDef(
            "screenshot.capture",
            "Capture the screen and save it; returns image path/URI. Opens a consent dialog when needed.",
            "{}", null,
            { _ ->
                if (Capabilities.app == null) JSONObject().put("ok", false).put("error", "not initialized")
                else capture().envelope()
            })
    )
}
