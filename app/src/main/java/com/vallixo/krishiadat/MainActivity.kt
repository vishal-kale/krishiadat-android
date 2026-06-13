package com.vallixo.krishiadat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private lateinit var offlineView: LinearLayout

    private var backPressedTime = 0L
    private val BACK_PRESS_INTERVAL = 2000L

    companion object {
        private const val APP_URL = "https://krishiadat-web.vercel.app"
    }

    // ── Connectivity ────────────────────────────────────────────────────────────

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (offlineView.visibility == View.VISIBLE) {
                    showLoading()
                    loadApp()
                }
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                if (!isOnline()) showOffline()
            }
        }
    }

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun loadApp() {
        if (webView.url.isNullOrEmpty()) {
            webView.loadUrl(APP_URL)
        } else {
            webView.reload()
        }
    }

    // ── JavaScript Bridges ─────────────────────────────────────────────────────

    inner class AndroidPrintBridge {
        @JavascriptInterface
        fun print(html: String) {
            runOnUiThread {
                val printView = WebView(this@MainActivity)
                printView.settings.javaScriptEnabled = true
                printView.loadDataWithBaseURL(APP_URL, html, "text/html", "UTF-8", null)
                printView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                        val adapter = view.createPrintDocumentAdapter("KrishiAdat Bill")
                        val attrs = PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setResolution(
                                PrintAttributes.Resolution("default", "Default", 300, 300),
                            )
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build()
                        pm.print("KrishiAdat Bill", adapter, attrs)
                    }
                }
            }
        }
    }

    inner class AndroidShareBridge {
        @JavascriptInterface
        fun shareImage(base64Png: String, filename: String, title: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Png, Base64.DEFAULT)
                    val dir = File(cacheDir, "shared_images").also { it.mkdirs() }
                    val file = File(dir, filename).also { it.writeBytes(bytes) }
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        file,
                    )
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, title)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                            title,
                        ),
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingView = findViewById(R.id.loadingView)
        offlineView = findViewById(R.id.offlineView)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container)) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()
        setupBackPress()

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isOnline()) {
                showLoading()
                loadApp()
            } else {
                Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            showWebView()
        } else if (isOnline()) {
            showLoading()
            webView.loadUrl(APP_URL)
        } else {
            showOffline()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // ── WebView Setup ──────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
        }

        webView.addJavascriptInterface(AndroidPrintBridge(), "AndroidPrintBridge")
        webView.addJavascriptInterface(AndroidShareBridge(), "AndroidShareBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                view.loadUrl(request.url.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Only reveal the WebView if we aren't showing the offline screen
                if (offlineView.visibility != View.VISIBLE) {
                    showWebView()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame && !isOnline()) {
                    showOffline()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                filePathCallback.onReceiveValue(null)
                return false
            }
        }
    }

    // ── Back Press ─────────────────────────────────────────────────────────────

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webView.canGoBack() -> webView.goBack()
                    System.currentTimeMillis() - backPressedTime < BACK_PRESS_INTERVAL -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    else -> {
                        backPressedTime = System.currentTimeMillis()
                        Toast.makeText(
                            this@MainActivity,
                            R.string.press_back_to_exit,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        })
    }

    // ── UI State ───────────────────────────────────────────────────────────────

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        offlineView.visibility = View.GONE
        webView.visibility = View.INVISIBLE
    }

    private fun showOffline() {
        offlineView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        webView.visibility = View.INVISIBLE
    }

    private fun showWebView() {
        webView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        offlineView.visibility = View.GONE
    }
}
