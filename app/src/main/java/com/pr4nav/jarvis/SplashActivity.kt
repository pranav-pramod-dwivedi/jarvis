package com.pr4nav.jarvis

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Full-screen 16:9 video splash (4.5-5s animation) with an on-demand DM Sans metallic Skip button.
 *
 * Rules:
 * 1. Default state: Hidden (not pre-revealed without touch).
 * 2. Tapping screen reveals the DM Sans metallic Skip text with smooth fade-in.
 * 3. Clicking the Skip button once immediately skips (no double tap required).
 * 4. Auto fades out in the last 1s of the animation (at 4.0s).
 * 5. Plays full 4.5-5.0s cinematic animation before smooth exit transition into MainActivity.
 */
@UnstableApi
class SplashActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var rotContainer: View
    private lateinit var btnSkip: View
    private var videoEnded = false
    private var mainLaunched = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val autoHideRunnable = Runnable {
        if (!isFinishing && !isDestroyed && !videoEnded && btnSkip.visibility == View.VISIBLE) {
            btnSkip.isClickable = false
            btnSkip.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(400)
                .withEndAction { btnSkip.visibility = View.INVISIBLE }
                .start()
        }
    }

    private val autoFadeOutLastSecondRunnable = Runnable {
        if (!isFinishing && !isDestroyed && !videoEnded && btnSkip.visibility == View.VISIBLE) {
            btnSkip.isClickable = false
            btnSkip.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(500)
                .withEndAction { btnSkip.visibility = View.INVISIBLE }
                .start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        rotContainer = findViewById(R.id.rot_container)
        playerView = findViewById(R.id.player_view)
        btnSkip = findViewById(R.id.btn_skip_splash)

        // Initial state: Hidden until tapped by user
        btnSkip.alpha = 0f
        btnSkip.visibility = View.INVISIBLE
        btnSkip.isClickable = false

        // Match black bars for seamless transition into MainActivity
        window.decorView.setBackgroundColor(Color.BLACK)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLACK

        // Back button navigation: skip immediately
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!videoEnded) {
                    videoEnded = true
                    mainHandler.removeCallbacksAndMessages(null)
                launchNext()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        rotContainer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        rotContainer.rotation = 0f

        // 1. Single click on Skip button: immediately skips
        btnSkip.setOnClickListener {
            if (!videoEnded) {
                videoEnded = true
                mainHandler.removeCallbacksAndMessages(null)
                btnSkip.isClickable = false
                btnSkip.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(200).start()
                runExitAnimation()
            }
        }

        // 2. Touch screen overlay to reveal or toggle Skip button
        val toggleSkipAction = View.OnClickListener {
            if (!videoEnded) {
                if (btnSkip.visibility != View.VISIBLE || btnSkip.alpha < 0.1f) {
                    // Fade in on user tap
                    btnSkip.visibility = View.VISIBLE
                    btnSkip.isClickable = true
                    btnSkip.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(250)
                        .start()

                    // Auto hide after 2.5s idle
                    mainHandler.removeCallbacks(autoHideRunnable)
                    mainHandler.postDelayed(autoHideRunnable, 2500L)
                } else {
                    // Toggle off if tapped again
                    btnSkip.isClickable = false
                    btnSkip.animate()
                        .alpha(0f)
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(250)
                        .withEndAction { btnSkip.visibility = View.INVISIBLE }
                        .start()
                }
            }
        }

        findViewById<View>(R.id.touch_overlay)?.setOnClickListener(toggleSkipAction)
        findViewById<View>(R.id.splash_root)?.setOnClickListener(toggleSkipAction)

        // 3. Auto fade-out in last 1 second of the 4.5-5.0s animation (at 4000ms)
        val autoFadeOutDelay = maxOf(MAX_SPLASH_MS - 1000L, 1000L)
        mainHandler.postDelayed(autoFadeOutLastSecondRunnable, autoFadeOutDelay)

        // Initialize ExoPlayer
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(SPLASH_VIDEO_URL))
            volume = 1.0f
            playWhenReady = true
            prepare()
        }
        playerView.player = player
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !videoEnded) {
                    videoEnded = true
                    player.pause()
                    runExitAnimation()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!videoEnded) {
                    videoEnded = true
                    runExitAnimation()
                }
            }
        })

        // 4. Fallback: Full 5.0s animation duration
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed && !videoEnded) {
                videoEnded = true
                runExitAnimation()
            }
        }, MAX_SPLASH_MS)
    }

    private fun runExitAnimation() {
        if (isFinishing || isDestroyed) return
        mainHandler.removeCallbacksAndMessages(null)
        btnSkip.animate().alpha(0f).setDuration(250).start()

        val duration = 800L
        val scale = ValueAnimator.ofFloat(1.0f, 1.6f).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val s = it.animatedValue as Float
                rotContainer.scaleX = s
                rotContainer.scaleY = s
            }
        }
        val alpha = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { rotContainer.alpha = it.animatedValue as Float }
        }
        val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ValueAnimator.ofFloat(0f, 30f).apply {
                this.duration = duration
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val radius = it.animatedValue as Float
                    if (radius > 0f) {
                        rotContainer.setRenderEffect(
                            android.graphics.RenderEffect.createBlurEffect(
                                radius, radius, android.graphics.Shader.TileMode.CLAMP
                            )
                        )
                    }
                }
            }
        } else null

        scale.start()
        alpha.start()
        blur?.start()

        alpha.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                launchNext()
            }
        })
    }

    private fun launchNext() {
        if (mainLaunched || isFinishing || isDestroyed) return
        mainLaunched = true
        val isFirstTime = !com.pr4nav.jarvis.setup.SetupManager.isSetupCompleted(this)
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

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) player.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized && !videoEnded) player.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }

    companion object {
        // Video MP4 (1440x1080, ~5s animation duration)
        private const val SPLASH_VIDEO_URL = "https://cdn.dribbble.com/userupload/47340679/file/c83afa93214b2db991e6da9e1fd41be4.mp4"
        private const val MAX_SPLASH_MS = 5_000L
    }
}
