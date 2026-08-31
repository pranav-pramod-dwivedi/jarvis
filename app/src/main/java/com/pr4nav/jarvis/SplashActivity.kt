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
 * Full-screen 16:9 video splash rotated to fit 9:16 portrait.
 * Plays once → zoom + fade + motion-blur transition → starts MainActivity.
 */
@UnstableApi
class SplashActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var rotContainer: View
    private var videoEnded = false
    private var mainLaunched = false
    private val fallbackHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        rotContainer = findViewById(R.id.rot_container)
        playerView = findViewById(R.id.player_view)

        // Black bars for the duration of the splash to match the target MainActivity theme.
        window.decorView.setBackgroundColor(Color.BLACK)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLACK

        // Handle back navigation: skip splash smoothly
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!videoEnded) {
                    videoEnded = true
                    fallbackHandler.removeCallbacksAndMessages(null)
                    launchMain()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Natural upright orientation, full screen match parent
        rotContainer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        rotContainer.rotation = 0f

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

        // Fallback: if the video never reaches ENDED (network fail), skip after MAX_SPLASH_MS.
        fallbackHandler.postDelayed({
            if (!isFinishing && !isDestroyed && !videoEnded) {
                videoEnded = true
                runExitAnimation()
            }
        }, MAX_SPLASH_MS)
    }

    private fun runExitAnimation() {
        if (isFinishing || isDestroyed) return
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
                launchMain()
            }
        })
    }

    private fun launchMain() {
        if (mainLaunched || isFinishing || isDestroyed) return
        mainLaunched = true
        val intent = Intent(this, MainActivity::class.java).apply {
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
        fallbackHandler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
    }

    companion object {
        // Video MP4 (1440x1080, 11s), displayed fullscreen upright without distortion
        private const val SPLASH_VIDEO_URL = "https://cdn.dribbble.com/userupload/47340679/file/c83afa93214b2db991e6da9e1fd41be4.mp4"
        private const val MAX_SPLASH_MS = 12_000L
    }
}
