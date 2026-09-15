package com.pr4nav.jarvis

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.setup.SetupManager

/**
 * Fast, clean launcher splash screen.
 * Transitions smoothly into MainActivity (or SetupLoadingActivity if first run)
 * without artificial video streaming or network delays.
 */
class SplashActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        window.decorView.setBackgroundColor(Color.parseColor("#090B0E"))
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.parseColor("#090B0E")
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.parseColor("#090B0E")

        val content = findViewById<View>(R.id.splash_content)
        content?.alpha = 0f
        content?.animate()?.alpha(1f)?.setDuration(150)?.start()

        mainHandler.postDelayed({
            launchNext()
        }, 250L)
    }

    private fun launchNext() {
        if (launched || isFinishing || isDestroyed) return
        launched = true

        val isFirstTime = !SetupManager.isSetupCompleted(this)
        val targetClass = if (isFirstTime) {
            com.pr4nav.jarvis.setup.SetupLoadingActivity::class.java
        } else {
            MainActivity::class.java
        }
        val intent = Intent(this, targetClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
