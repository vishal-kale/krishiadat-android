package com.vallixo.krishiadat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    companion object {
        private const val APP_URL = "https://krishiadat-web.vercel.app"
    }

    // ── JavaScript bridges ──────────────────────────────────────────────────────

    inner class AndroidPrintBridge {
        @JavascriptInterface
        fun print(html: String) {
            runOnUiThread {
                // Load the bill HTML into a hidden WebView, then hand it to
                // Android's PrintManager — gives the user a real print/save-PDF dialog.
                val printView = WebView(this@MainActivity)
                printView.settings.javaScriptEnabled = true
                printView.loadDataWithBaseURL(
                    APP_URL, html, "text/html", "UTF-8", null,
                )
                printView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val printManager =
                            getSystemService(Context.PRINT_SERVICE) as PrintManager
                        val adapter = view.createPrintDocumentAdapter("KrishiAdat Bill")
                        val attrs = PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setResolution(
                                PrintAttributes.Resolution("default", "Default", 300, 300),
                            )
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build()
                        printManager.print("KrishiAdat Bill", adapter, attrs)
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
                    val file = File(dir, filename)
                    file.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        file,
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, title))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this)
        webView = WebView(this)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(container)

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

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

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(APP_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
