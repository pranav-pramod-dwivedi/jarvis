package com.pr4nav.jarvis

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.opencode.OpenCodeConfig
import com.pr4nav.jarvis.opencode.OpenCodeSessionStore
import com.pr4nav.jarvis.opencode.PrefsKvStore

/**
 * WebView that embeds the *real* opencode web UI (GET /) OR the custom assets/opencode.html.
 * - Loads http://127.0.0.1:PORT/ directly inside the app — no Chrome external browser.
 * - Handles Basic Auth inside WebView (no 401 popup loop).
 * - Query param ?mode=custom forces the file:// asset; default is server UI (easier, no CORS).
 */
class OpenCodeWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var baseUrl: String = OpenCodeConfig().baseUrl
    private var username: String = "opencode"
    private var password: String = ""

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        val kv = try { PrefsKvStore(this) } catch (_: Exception) { null }
        baseUrl = kv?.getString(OpenCodeSessionStore.KEY_SERVER_BASE_URL)
            ?: intent.getStringExtra("baseUrl")
            ?: OpenCodeConfig().baseUrl
        username = kv?.getString(OpenCodeSessionStore.KEY_SERVER_USERNAME) ?: "opencode"
        password = kv?.getString(OpenCodeSessionStore.KEY_SERVER_PASSWORD) ?: intent.getStringExtra("password") ?: ""
        // Intent may carry explicit creds
        intent.getStringExtra("username")?.let { username = it }

        val mode = intent.getStringExtra("mode") // "custom" -> load asset, else server UI
        val useCustom = mode == "custom"

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        webView.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        webView.settings.allowUniversalAccessFromFileURLs = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.addJavascriptInterface(JsBridge(), "Jarvis")

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                // Supply Basic Auth silently — prevents 401 -> "not found" confusion
                if (password.isNotBlank()) handler.proceed(username, password) else handler.cancel()
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Inject auth header for fetch() in custom asset via JS variable
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.startsWith("file://")) {
                    val js = "window.__jarvisSetConfig && window.__jarvisSetConfig(" +
                        "'${jsEscape(baseUrl)}','${jsEscape(username)}','${jsEscape(password)}')"
                    view.evaluateJavascript(js, null)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                // Stay in-app for opencode host; only external domains go to Chrome
                val isLocal = url.contains("127.0.0.1") || url.contains("localhost") || url.startsWith(baseUrl) || url.startsWith("file://")
                if (isLocal) return false
                // external link -> Chrome is fine
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                return true
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                super.onReceivedError(view, req, err)
                if (req.isForMainFrame) {
                    // Show hint but keep WebView (don't jump to Chrome)
                    Toast.makeText(this@OpenCodeWebActivity, "Load failed: ${err.description} — is opencode running on $baseUrl ?", Toast.LENGTH_LONG).show()
                }
            }
        }

        if (useCustom) {
            webView.loadUrl("file:///android_asset/opencode.html")
        } else {
            // Direct server web UI — most reliable, no CORS. Add trailing / to avoid redirect.
            val target = baseUrl.trimEnd('/') + "/"
            // WebView will call onReceivedHttpAuthRequest for 401; no need to embed creds in URL
            webView.loadUrl(target)
        }

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

    private fun jsEscape(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    inner class JsBridge {
        @JavascriptInterface fun openWeb(url: String?) {
            val t = (url?.takeIf { it.isNotBlank() } ?: baseUrl).trimEnd('/') + "/"
            runOnUiThread { webView.loadUrl(t) }
        }
        @JavascriptInterface fun openCustom() {
            runOnUiThread { webView.loadUrl("file:///android_asset/opencode.html") }
        }
        @JavascriptInterface fun openExternal(url: String?) {
            val t = url ?: baseUrl
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t))) } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@OpenCodeWebActivity, e.message, Toast.LENGTH_SHORT).show() }
            }
        }
        @JavascriptInterface fun toast(msg: String) {
            runOnUiThread { Toast.makeText(this@OpenCodeWebActivity, msg, Toast.LENGTH_SHORT).show() }
        }
        @JavascriptInterface fun getBaseUrl(): String = baseUrl
    }
}
