package com.launchit.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.launchit.app.LaunchItApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fullscreen activity for running a project in a WebView.
 * Provides an immersive, app-like execution experience.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LaunchItPlayer"
        const val EXTRA_PROJECT_ID = "project_id"

        fun createIntent(context: Context, projectId: String): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_ID, projectId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorContainer: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var errorDetailText: TextView
    private lateinit var container: FrameLayout

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var projectId: String? = null
    private var hasNavigated = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "PlayerActivity onCreate")

        // Get project ID from intent
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (projectId == null) {
            Log.e(TAG, "No project ID in intent!")
            Toast.makeText(this, "No project specified", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.i(TAG, "Loading project: $projectId")

        // Immersive fullscreen
        setupImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Create layout programmatically
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Loading indicator
        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }

        // Error container (hidden by default)
        errorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1C1B1F"))
            visibility = View.GONE
            setPadding(48, 48, 48, 48)
        }

        errorText = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }

        errorDetailText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#CAC4D0"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        errorContainer.addView(errorText)
        errorContainer.addView(errorDetailText)

        // WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        container.addView(webView)
        container.addView(progressBar)
        container.addView(errorContainer)
        setContentView(container)

        setupWebView()
        loadProject()
    }

    private fun setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            // Hardware acceleration is enabled via manifest, not via hidden API
        }

        // WebView Chrome client for progress and console messages
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                Log.d(TAG, "Page progress: $newProgress%")
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                    if (!hasNavigated) {
                        hasNavigated = true
                        webView.visibility = View.VISIBLE
                    }
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val level = it.messageLevel()
                    val msg = it.message()
                    val source = it.sourceId()
                    val line = it.lineNumber()
                    when (level) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "JS ERROR: $msg at $source:$line")
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS WARN: $msg at $source:$line")
                        else -> Log.d(TAG, "JS [$level]: $msg at $source:$line")
                    }
                }
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                // Could update activity title if needed
            }
        }

        // WebView client for navigation and error handling
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Keep local URLs in WebView
                if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                    return false
                }
                // Open external URLs in system browser
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not open external URL: $url", e)
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.i(TAG, "Page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "Page finished: $url")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val description = error?.description?.toString() ?: "Unknown error"
                    val errorCode = error?.errorCode ?: -1
                    Log.e(TAG, "WebView error: code=$errorCode desc=$description url=${request.url}")

                    // Show error to user if it's a main frame error
                    showError(
                        "Failed to load project",
                        "$description\n\nThe project may not be ready or the server may have stopped."
                    )
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    val statusCode = errorResponse?.statusCode ?: 0
                    Log.e(TAG, "HTTP error: status=$statusCode url=${request.url}")
                    if (statusCode >= 500) {
                        showError(
                            "Server Error",
                            "The project server returned HTTP $statusCode. The project may have encountered an error."
                        )
                    }
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                // Never bypass SSL errors - fail safely
                Log.e(TAG, "SSL error: ${error?.primaryError}")
                handler?.cancel()
            }
        }

        // Handle back key for browser history
        webView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (webView.canGoBack()) {
                    webView.goBack()
                    true
                } else {
                    false
                }
            } else false
        }
    }

    private fun loadProject() {
        val pid = projectId ?: return
        val app = try { application as LaunchItApplication } catch (e: Exception) {
            Log.e(TAG, "Failed to get application instance", e)
            showError("Initialization Error", "Could not access app services. Please try again.")
            return
        }

        scope.launch {
            try {
                // Check if project is already running (has a URL)
                var url = app.runtimeOrchestrator.getProjectUrl(pid)
                Log.i(TAG, "Existing URL: $url")

                if (url == null) {
                    // Show loading state
                    progressBar.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                    errorContainer.visibility = View.GONE

                    // Start the project runtime
                    Log.i(TAG, "Starting project runtime for $pid")
                    val result = app.runtimeOrchestrator.startProject(pid)

                    if (result.isSuccess) {
                        url = result.getOrNull()
                        Log.i(TAG, "Project started, URL: $url")
                    } else {
                        val error = result.exceptionOrNull()
                        val msg = error?.message ?: "Failed to start project"
                        Log.e(TAG, "Failed to start project: $msg", error)
                        showError("Project Failed to Start", msg)
                        return@launch
                    }
                }

                if (url != null) {
                    // Update last opened timestamp
                    try {
                        app.projectRepository.updateLastOpened(pid)
                    } catch (_: Exception) {}

                    // Load the URL in WebView
                    Log.i(TAG, "Loading URL in WebView: $url")
                    webView.visibility = View.VISIBLE
                    errorContainer.visibility = View.GONE
                    webView.loadUrl(url)
                } else {
                    showError("Project Error", "Could not determine project URL. The project may be corrupted.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading project", e)
                showError("Error", e.message ?: "An unexpected error occurred")
            }
        }
    }

    private fun showError(title: String, detail: String) {
        Log.w(TAG, "Showing error: $title - $detail")
        runOnUiThread {
            progressBar.visibility = View.GONE
            webView.visibility = View.GONE
            errorContainer.visibility = View.VISIBLE
            errorText.text = title
            errorDetailText.text = detail
        }
    }

    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            webView.onPause()
            webView.pauseTimers()
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
            webView.resumeTimers()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        Log.i(TAG, "PlayerActivity onDestroy for project $projectId")
        super.onDestroy()
        scope.cancel()
        try {
            webView.apply {
                onPause()
                removeAllViews()
                destroy()
            }
        } catch (_: Exception) {}
    }
}
