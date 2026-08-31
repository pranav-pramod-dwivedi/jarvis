package com.pr4nav.jarvis

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.agy.AgyConfig

class AgyWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var baseUrl: String = AgyConfig.DEFAULT_BASE_URL

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseUrl = intent.getStringExtra("baseUrl") ?: AgyConfig.DEFAULT_BASE_URL

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0B0F17"))
        }

        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#151D2A"))
            setPadding(12, 8, 12, 8)
        }

        val btnLocal = android.widget.Button(this).apply {
            text = "AGY (5050)"
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2563EB"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 80, 1f).apply { setMargins(0, 0, 6, 0) }
            setOnClickListener { webView.loadUrl(baseUrl.trimEnd('/') + "/") }
        }

        val btnCloud = android.widget.Button(this).apply {
            text = "CLOUD WEB"
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#38BDF8"))
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E293B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 80, 1f).apply { setMargins(0, 0, 6, 0) }
            setOnClickListener { webView.loadUrl("https://antigravity.google/") }
        }

        val btnBrowser = android.widget.Button(this).apply {
            text = "CHROME ↗"
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#334155"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, 80, 1f).apply { setMargins(0, 0, 6, 0) }
            setOnClickListener {
                val current = webView.url ?: (baseUrl.trimEnd('/') + "/")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current)))
                } catch (_: Exception) {}
            }
        }

        val btnReload = android.widget.Button(this).apply {
            text = "⟳"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E293B"))
            layoutParams = android.widget.LinearLayout.LayoutParams(80, 80)
            setOnClickListener { webView.reload() }
        }

        bar.addView(btnLocal)
        bar.addView(btnCloud)
        bar.addView(btnBrowser)
        bar.addView(btnReload)
        root.addView(bar)

        webView = WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(webView)
        setContentView(root)

        val customUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = customUa
        }

        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val popup = WebView(this@AgyWebActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = customUa
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            val u = req.url.toString()
                            if (u.contains("localhost") || u.contains("127.0.0.1")) {
                                webView.loadUrl(baseUrl.trimEnd('/') + "/")
                                view.destroy()
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            if (url.contains("callback") || url.contains("success") || url.contains("auth")) {
                                view.postDelayed({
                                    webView.loadUrl(baseUrl.trimEnd('/') + "/")
                                    try { view.destroy() } catch (_: Exception) {}
                                }, 1000)
                            }
                        }
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popup
                resultMsg?.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (url.contains("127.0.0.1") || url.contains("localhost") ||
                    url.contains("google.com") || url.contains("antigravity.google")) {
                    return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {}
                return true
            }

            private var redirected = false

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (!redirected && (url.contains("callback") || url.contains("success"))) {
                    redirected = true
                    view.postDelayed({
                        webView.loadUrl(baseUrl.trimEnd('/') + "/")
                    }, 1200)
                }
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                super.onReceivedError(view, req, err)
                if (req.isForMainFrame && !req.url.toString().contains("antigravity.google")) {
                    Toast.makeText(
                        this@AgyWebActivity,
                        "Local Web UI unreachable on $baseUrl",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        webView.loadUrl(baseUrl.trimEnd('/') + "/")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
