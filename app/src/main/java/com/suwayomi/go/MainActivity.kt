package com.suwayomi.go


import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout


class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var prefs: SharedPreferences


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 默认进入沉浸式全屏
        hideSystemUI()

        prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

        // 检查配置，如果没有 URL，则弹出设置
        val savedUrl = prefs.getString("url", "")
        if (savedUrl.isNullOrEmpty()) {
            showConfigDialog()
        } else {
            webView.loadUrl(savedUrl)
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // 兼容性微调
        settings.useWideViewPort = true       
        settings.loadWithOverviewMode = true 
        settings.displayZoomControls = false 
        settings.builtInZoomControls = false 

        // 伪装 UA
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.viewTreeObserver.addOnScrollChangedListener {
            swipeRefresh.isEnabled = webView.scrollY == 0
        }

        webView.webViewClient = object : WebViewClient() {
            
            // 解决旧版内核不支持 Object.hasOwn 的问题
            private fun injectFixes(view: WebView?) {
                val js = """
                    (function() {
                        if (!Object.hasOwn) {
                            Object.hasOwn = function(object, property) {
                                return Object.prototype.hasOwnProperty.call(object, property);
                            };
                        }
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectFixes(view)
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                val user = prefs.getString("user", "")
                val pass = prefs.getString("pass", "")

                if (view?.tag == "auth_failed") {
                    view.tag = null
                    handler?.cancel()
                    if (!user.isNullOrEmpty()) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "验证失败，请检查账号密码", Toast.LENGTH_LONG).show()
                            showConfigDialog()
                        }
                    }
                } else {
                    if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                        view?.tag = "auth_failed"
                        handler?.proceed(user, pass)
                    } else {
                        showConfigDialog()
                        handler?.cancel()
                    }
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                view?.tag = null
                injectFixes(view)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Toast.makeText(this@MainActivity, "连接失败，请检查配置", Toast.LENGTH_LONG).show()
                    showConfigDialog()
                }
            }
        }

        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            if (result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val imageUrl = result.extra
                if (imageUrl != null) {
                    AlertDialog.Builder(this)
                        .setTitle("保存图片")
                        .setMessage("要下载这张漫画页面吗？")
                        .setPositiveButton("下载") { _, _ -> saveImageToGallery(imageUrl) }
                        .setNegativeButton("取消", null)
                        .show()
                }
                true
            } else false
        }
    }

    // --- 核心修复：点击返回键不再直接退出，而是弹出配置对话框 ---
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack() // 网页内回退
                } else {
                    // 当处于主页无法回退时，弹出服务器配置对话框
                    showConfigDialog()
                }
            }
        })
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
    }

    private fun saveImageToGallery(url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val pageTitle = webView.title ?: "Image"
            val cleanTitle = pageTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val originalFileName = URLUtil.guessFileName(url, null, "image/jpeg")
            val fileName = "${cleanTitle}_$originalFileName"

            val user = prefs.getString("user", "")
            val pass = prefs.getString("pass", "")
            if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                val auth = "$user:$pass"
                val base64Auth = Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)
                request.addRequestHeader("Authorization", "Basic $base64Auth")
            }

            request.setTitle("漫画下载")
            request.setDescription("正在下载: $fileName")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "Suwayomi/$fileName")

            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "已加入下载队列: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载异常: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showConfigDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_config, null)
        val editUrl = view.findViewById<EditText>(R.id.editUrl)
        val editUser = view.findViewById<EditText>(R.id.editUser)
        val editPass = view.findViewById<EditText>(R.id.editPass)

        val savedUrl = prefs.getString("url", "")
        editUrl.setText(if (savedUrl.isNullOrEmpty()) "https://" else savedUrl)
        editUser.setText(prefs.getString("user", ""))
        editPass.setText(prefs.getString("pass", ""))

        AlertDialog.Builder(this)
            .setTitle("服务器配置")
            .setView(view)
            // 如果已有配置，则允许点击外部或返回键取消（回到网页）
            .setCancelable(savedUrl.isNullOrEmpty().not())
            .setPositiveButton("保存并进入") { _, _ ->
                val url = editUrl.text.toString()
                val user = editUser.text.toString()
                val pass = editPass.text.toString()

                if (url.isNotEmpty()) {
                    prefs.edit()
                        .putString("url", url)
                        .putString("user", user)
                        .putString("pass", pass)
                        .apply()

                    WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
                    webView.clearCache(true)
                    webView.tag = null
                    webView.loadUrl(url)
                }
            }
            // 添加“退出应用”按钮
            .setNegativeButton("退出应用") { _, _ ->
                finish()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                simulateKey("ArrowRight", 39)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                simulateKey("ArrowLeft", 37)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun simulateKey(keyName: String, keyCode: Int) {
        val jsCode = """
        var event = new KeyboardEvent('keydown', {
            key: '$keyName',
            code: '$keyName',
            keyCode: $keyCode,
            which: $keyCode,
            bubbles: true,
            cancelable: true
        });
        document.dispatchEvent(event);
    """.trimIndent()
        webView.evaluateJavascript(jsCode, null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }
}
