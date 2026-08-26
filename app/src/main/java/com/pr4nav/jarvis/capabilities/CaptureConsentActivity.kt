package com.pr4nav.jarvis.capabilities

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class CaptureConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), 9100)
        } catch (e: Exception) {
            ScreenshotCapability.onConsentResult(null, null, e.message ?: "consent unavailable")
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 9100) return
        when {
            resultCode == RESULT_OK && data != null ->
                ScreenshotCapability.onConsentResult(resultCode, data, null)
            else -> ScreenshotCapability.onConsentResult(null, null, "screen capture denied by user")
        }
        finish()
    }
}
