package com.pr4nav.jarvis.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.voice.JarvisVoiceEngine
import java.io.File

/**
 * Fullscreen, hardware-accelerated dynamic UI surface for JarvisBrowser applications.
 * Can wake the screen and display over the lockscreen if requested by voice.
 */
class JarvisBrowserActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JarvisBrowserAct"
        const val EXTRA_APP_ID = "extra_app_id"
        const val EXTRA_EXPLANATION_SPEECH = "extra_explanation_speech"
        const val EXTRA_INITIAL_DATA = "extra_initial_data"

        fun launch(
            context: Context,
            appId: String,
            explanationSpeech: String? = null,
            initialDataJson: String? = null
        ) {
            val intent = Intent(context, JarvisBrowserActivity::class.java).apply {
                putExtra(EXTRA_APP_ID, appId)
                putExtra(EXTRA_EXPLANATION_SPEECH, explanationSpeech)
                putExtra(EXTRA_INITIAL_DATA, initialDataJson)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var iconView: TextView
    private lateinit var btnSave: Button
    private lateinit var btnClose: ImageButton
    private lateinit var btnRefresh: ImageButton

    private var voiceEngine: JarvisVoiceEngine? = null
    private var currentApp: JarvisBrowserApp? = null
    private var explanationSpeech: String? = null
    private var initialDataJson: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow display over lock screen if phone is locked/sleeping
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_jarvis_browser)

        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        explanationSpeech = intent.getStringExtra(EXTRA_EXPLANATION_SPEECH)
        initialDataJson = intent.getStringExtra(EXTRA_INITIAL_DATA)

        currentApp = JarvisBrowserAppManager.getApp(this, appId)
        if (currentApp == null) {
            Toast.makeText(this, "JarvisBrowser app not found: $appId", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        voiceEngine = JarvisVoiceEngine.getInstance(applicationContext)

        initViews()
        setupWebView()
        loadApp()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun initViews() {
        webView = findViewById(R.id.browser_webview)
        progressBar = findViewById(R.id.browser_progress)
        titleView = findViewById(R.id.txt_browser_title)
        subtitleView = findViewById(R.id.txt_browser_subtitle)
        iconView = findViewById(R.id.txt_browser_icon)
        btnSave = findViewById(R.id.btn_browser_save)
        btnClose = findViewById(R.id.btn_browser_close)
        btnRefresh = findViewById(R.id.btn_browser_refresh)

        currentApp?.let { app ->
            titleView.text = app.title
            subtitleView.text = if (app.isTemporary) "Preview • Temporary UI" else "JarvisBrowser App"
            iconView.text = app.icon

            if (app.isTemporary) {
                btnSave.visibility = View.VISIBLE
                btnSave.setOnClickListener {
                    JarvisBrowserAppManager.saveTemporaryApp(this, app.id)
                    btnSave.text = "Saved ✓"
                    btnSave.isEnabled = false
                    subtitleView.text = "Saved App"
                    Toast.makeText(this, "App saved to library!", Toast.LENGTH_SHORT).show()
                }
            } else {
                btnSave.visibility = View.GONE
            }
        }

        btnClose.setOnClickListener { finish() }
        btnRefresh.setOnClickListener { webView.reload() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        val bridge = JarvisBrowserBridge(
            context = this,
            webView = webView,
            currentApp = currentApp!!,
            voiceEngine = voiceEngine,
            onSaveRequested = { _, _ ->
                runOnUiThread {
                    btnSave.text = "Saved ✓"
                    btnSave.isEnabled = false
                    subtitleView.text = "Saved App"
                }
            },
            onCloseRequested = { finish() }
        )
        webView.addJavascriptInterface(bridge, "JarvisNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.d(TAG, "[WebView Console] ${msg?.message()} (line ${msg?.lineNumber()})")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE

                // Inject initial data if provided
                if (!initialDataJson.isNullOrBlank()) {
                    val safeJson = escapeJs(initialDataJson!!)
                    view?.evaluateJavascript("window.__JARVIS_INITIAL_DATA__ = JSON.parse('$safeJson');", null)
                }

                // Voice narration while visual is on screen
                if (!explanationSpeech.isNullOrBlank()) {
                    val speech = explanationSpeech
                    explanationSpeech = null // only speak once on initial load
                    voiceEngine?.speak(speech!!, interrupt = false)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("file://")) {
                    return false // Keep local app files in-app
                }
                // Open external links in default browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot open external URL: $url (${e.message})")
                }
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "JarvisBrowser failed loading: ${error?.description}")
                }
            }
        }
    }

    private fun loadApp() {
        val app = currentApp ?: return
        val entryFile = File(app.entryPath)
        if (entryFile.exists()) {
            val fileUrl = "file://${entryFile.absolutePath}"
            Log.i(TAG, "Loading JarvisBrowser entry point: $fileUrl")
            webView.loadUrl(fileUrl)
        } else {
            Toast.makeText(this, "App entry file does not exist", Toast.LENGTH_SHORT).show()
        }
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine?.destroy()
        voiceEngine = null
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        } catch (_: Exception) {}
    }
}
