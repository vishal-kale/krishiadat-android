package com.vallixo.krishiadat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.app.AlertDialog
import android.graphics.Bitmap
import android.provider.MediaStore
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private lateinit var offlineView: LinearLayout
    private lateinit var lockView: LinearLayout

    private var backPressedTime = 0L
    private var pendingDeepPath: String? = null

    // ── Camera / File Chooser ──────────────────────────────────────────────────

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        if (result.resultCode == RESULT_OK) {
            // Camera capture (with EXTRA_OUTPUT) returns null data — use cameraPhotoUri
            val uri = result.data?.data ?: cameraPhotoUri
            cb.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        } else {
            cb.onReceiveValue(null)
        }
        cameraPhotoUri = null
    }

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val cb = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        cb.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    // ── App Update ─────────────────────────────────────────────────────────────

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(this) }

    private val updateResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* user can dismiss — flexible update continues in background */ }

    private val installStateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            runOnUiThread { notifyUpdateDownloaded() }
        }
    }

    companion object {
        private const val APP_URL = "https://krishiadat-web.vercel.app"
        private const val ACTION_NEW_BILL = "com.vallixo.krishiadat.ACTION_NEW_BILL"
        private const val ACTION_NEW_CREDIT = "com.vallixo.krishiadat.ACTION_NEW_CREDIT"
        private const val BACK_PRESS_INTERVAL = 2000L

        // Survives rotation; resets when process is killed — re-auth on cold launch
        var isSessionAuthenticated = false
        var userHasLoggedIn = false
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

    private fun loadApp(path: String? = null) {
        val url = if (path != null) "$APP_URL$path" else APP_URL
        if (webView.url.isNullOrEmpty() || path != null) {
            webView.loadUrl(url)
        } else {
            webView.reload()
        }
    }

    // ── JavaScript Bridges ─────────────────────────────────────────────────────

    inner class AndroidPrintBridge {
        @JavascriptInterface
        fun print(html: String, size: String = "a4") {
            runOnUiThread {
                val printView = WebView(this@MainActivity)
                printView.settings.javaScriptEnabled = true
                printView.loadDataWithBaseURL(APP_URL, html, "text/html", "UTF-8", null)
                printView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                        val adapter = view.createPrintDocumentAdapter("KrishiAdat Bill")
                        val mediaSize = PrintAttributes.MediaSize.ISO_A4
                        val attrs = PrintAttributes.Builder()
                            .setMediaSize(mediaSize)
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

        // Fast path: receive HTML, render natively, share — no html2canvas roundtrip
        @JavascriptInterface
        fun shareHtml(html: String, cssWidthPx: Int, filename: String, title: String) {
            runOnUiThread {
                val density = resources.displayMetrics.density
                val renderWidth = (cssWidthPx * minOf(density, 2.5f)).toInt()

                val rootView = findViewById<ViewGroup>(R.id.container)
                val container = FrameLayout(this@MainActivity)
                container.visibility = View.INVISIBLE
                rootView.addView(container, ViewGroup.LayoutParams(renderWidth, ViewGroup.LayoutParams.WRAP_CONTENT))

                val renderView = WebView(this@MainActivity)
                renderView.settings.javaScriptEnabled = true
                container.addView(renderView, ViewGroup.LayoutParams(renderWidth, ViewGroup.LayoutParams.WRAP_CONTENT))

                renderView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        fun captureAndShare() {
                            try {
                                val contentH = (view.contentHeight * view.scale).toInt()
                                val height = maxOf(contentH, 100)
                                view.measure(
                                    View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                                )
                                view.layout(0, 0, renderWidth, height)
                                val bm = Bitmap.createBitmap(renderWidth, height, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(bm)
                                canvas.drawColor(Color.WHITE)
                                view.draw(canvas)
                                rootView.removeView(container)

                                val dir = java.io.File(cacheDir, "shared_images").also { it.mkdirs() }
                                val shareFile = java.io.File(dir, filename)
                                shareFile.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 85, it) }
                                bm.recycle()

                                val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", shareFile)
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
                                rootView.removeView(container)
                            }
                            // Dismiss the web-side loader
                            webView.post {
                                webView.evaluateJavascript("window.__ka_hideShareLoader?.()", null)
                            }
                        }

                        fun waitAndCapture(attemptsLeft: Int) {
                            view.evaluateJavascript("""
                                (function() {
                                    var imgs = document.querySelectorAll('img');
                                    if (imgs.length === 0) return true;
                                    return Array.from(imgs).every(function(img) {
                                        return img.complete && img.naturalWidth > 0;
                                    });
                                })()
                            """.trimIndent()) { result ->
                                if (result == "true" || attemptsLeft <= 0) {
                                    captureAndShare()
                                } else {
                                    view.postDelayed({ waitAndCapture(attemptsLeft - 1) }, 150)
                                }
                            }
                        }

                        // Initial settle delay, then poll until all <img> tags are loaded
                        view.postDelayed({ waitAndCapture(15) }, 200)
                    }
                }
                // Software layer required: hardware-accelerated WebView draws blank on a software Canvas
                renderView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                renderView.loadDataWithBaseURL(APP_URL, html, "text/html", "UTF-8", null)
            }
        }

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
        lockView = findViewById(R.id.lockView)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.container)) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime  = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            // When the keyboard is open, ime.bottom = keyboard height — use it so the
            // WebView shrinks and the focused field stays visible above the keyboard.
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
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

        findViewById<Button>(R.id.authenticateButton).setOnClickListener {
            showBiometricPrompt()
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Capture shortcut deep-link; applied after auth succeeds
        pendingDeepPath = resolveShortcutPath(intent)

        if (isSessionAuthenticated) {
            onAuthSuccess(savedInstanceState)
        } else {
            authenticate(savedInstanceState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = resolveShortcutPath(intent) ?: return
        if (isSessionAuthenticated) {
            navigateTo(path)
        } else {
            pendingDeepPath = path
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.registerListener(installStateListener)
        // Show banner if update finished downloading while app was in background
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                notifyUpdateDownloaded()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        appUpdateManager.unregisterListener(installStateListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // ── Biometric Auth ─────────────────────────────────────────────────────────

    private fun authenticate(savedInstanceState: Bundle?) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            showLock()
            showBiometricPrompt()
        } else {
            // No screen lock set up — proceed without auth
            onAuthSuccess(savedInstanceState)
        }
    }

    private fun showBiometricPrompt() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val promptInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_name))
                .setSubtitle(getString(R.string.biometric_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build()
        } else {
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_name))
                .setSubtitle(getString(R.string.biometric_subtitle))
                .setNegativeButtonText(getString(R.string.biometric_cancel))
                .build()
        }

        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isSessionAuthenticated = true
                    onAuthSuccess(null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User dismissed — keep lock screen so they can retry via button
                    showLock()
                }

                override fun onAuthenticationFailed() {
                    // Wrong biometric — system handles retries automatically
                }
            },
        ).authenticate(promptInfo)
    }

    private fun onAuthSuccess(savedInstanceState: Bundle?) {
        isSessionAuthenticated = true
        checkForUpdate()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            showWebView()
        } else if (isOnline()) {
            showLoading()
            val path = pendingDeepPath.also { pendingDeepPath = null }
            loadApp(path)
        } else {
            showOffline()
        }
    }

    // ── App Shortcuts ──────────────────────────────────────────────────────────

    private fun resolveShortcutPath(intent: Intent?): String? = when (intent?.action) {
        ACTION_NEW_BILL -> "/en/purchases/new"
        ACTION_NEW_CREDIT -> "/en/credits/new"
        else -> null
    }

    private fun navigateTo(path: String) {
        webView.loadUrl("$APP_URL$path")
        showWebView()
    }

    // ── App Update ─────────────────────────────────────────────────────────────

    private fun checkForUpdate() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        updateResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                    )
                }
            }
    }

    private fun notifyUpdateDownloaded() {
        Toast.makeText(this, R.string.update_downloaded, Toast.LENGTH_LONG).show()
    }

    // ── Camera helpers ─────────────────────────────────────────────────────────

    private fun launchCameraCapture() {
        try {
            val dir = File(cacheDir, "camera_photos").also { it.mkdirs() }
            val file = File.createTempFile("photo_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            cameraPhotoUri = uri
            fileChooserLauncher.launch(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            )
        } catch (e: Exception) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private fun launchImageChooser() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_image))
            .setItems(
                arrayOf(getString(R.string.take_photo), getString(R.string.choose_from_gallery))
            ) { _, which ->
                when (which) {
                    0 -> {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            launchCameraCapture()
                        } else {
                            requestCameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    }
                    1 -> photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            .setOnCancelListener {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
            .show()
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

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Session timeout: detect redirect to /login after the user was on a dashboard page
                val isLoginPage = url.contains("/login")
                if (isLoginPage && userHasLoggedIn) {
                    Toast.makeText(this@MainActivity, R.string.session_expired, Toast.LENGTH_LONG).show()
                    userHasLoggedIn = false
                } else if (!isLoginPage) {
                    userHasLoggedIn = true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (offlineView.visibility != View.VISIBLE) {
                    showWebView()
                }
                // Scroll focused input into view when the keyboard resizes the viewport
                view.evaluateJavascript("""
                    (function() {
                        if (!window.__kaKeyboardListenerAdded && window.visualViewport) {
                            window.__kaKeyboardListenerAdded = true;
                            window.visualViewport.addEventListener('resize', function() {
                                var el = document.activeElement;
                                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT')) {
                                    setTimeout(function() {
                                        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                    }, 100);
                                }
                            });
                        }
                    })();
                """.trimIndent(), null)
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
                // Cancel any pending callback from a previously abandoned chooser
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                if (fileChooserParams.isCaptureEnabled) {
                    // Direct camera capture — request runtime permission if not yet granted
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        launchCameraCapture()
                    } else {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                } else {
                    // General file picker — offer camera + gallery chooser
                    launchImageChooser()
                }
                return true
            }
        }
    }

    // ── Back Press ─────────────────────────────────────────────────────────────

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // Never allow back to bypass the lock screen
                    lockView.visibility == View.VISIBLE -> Unit
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

    // ── UI States ──────────────────────────────────────────────────────────────

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        offlineView.visibility = View.GONE
        lockView.visibility = View.GONE
        webView.visibility = View.INVISIBLE
    }

    private fun showOffline() {
        offlineView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        lockView.visibility = View.GONE
        webView.visibility = View.INVISIBLE
    }

    private fun showWebView() {
        webView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        offlineView.visibility = View.GONE
        lockView.visibility = View.GONE
    }

    private fun showLock() {
        lockView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        offlineView.visibility = View.GONE
        webView.visibility = View.INVISIBLE
    }
}
