package com.suwayomi.go


import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.view.isVisible
import androidx.core.graphics.toColorInt
import com.suwayomi.go.widget.WiperView


@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingView: ImageView
    private lateinit var wiperView: WiperView
    private lateinit var prefs: SharedPreferences


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 默认进入沉浸式全面屏，并适配状态栏 (Default immersive edge-to-edge)
        hideSystemUI()

        prefs = getSharedPreferences("AppConfig", MODE_PRIVATE)
        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingView = findViewById(R.id.loadingProgress)
        wiperView = findViewById(R.id.wiperView)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()

        // 检查配置，如果没有 URL，则弹出设置
        val savedUrl = prefs.getString("url", "")
        if (savedUrl.isNullOrEmpty()) {
            showConfigDialog()
        } else {
            // 核心修复：在应用启动准备加载 URL 时立即显示加载动画，防止 cold start 时的短暂空白 (Ensure loading visibility on startup)
            loadingView.visibility = View.VISIBLE
            val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse_animation)
            loadingView.startAnimation(pulse)

            webView.loadUrl(savedUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // 兼容性微调
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.displayZoomControls = false
        settings.builtInZoomControls = false

        // 伪装 UA
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.viewTreeObserver.addOnScrollChangedListener {
            // 核心修改：仅在非章节页面（URL 不含 "chapter"）且滚动到顶部时启用下拉刷新 (Enable swipe refresh only on non-chapter pages at scroll top)
            val isChapterPage = webView.url?.contains("chapter") == true
            swipeRefresh.isEnabled = webView.scrollY == 0 && !isChapterPage
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // 当进度达到 100% 且加载视图可见时，启动延迟隐藏逻辑
                // 优化：增加 tag 判定，防止重复提交延迟任务 (Avoid multiple delayed tasks)
                if (newProgress == 100 && loadingView.isVisible && loadingView.tag == null) {
                    loadingView.tag = "is_ending" // 标记正在处理结束逻辑
                    // 实现要求：webview加载完成，加载动画任继续运行1秒 (Keep animation for 1s after load)
                    webView.postDelayed({
                        // 再次检查进度，防止延迟期间用户又触发了新的刷新
                        if (webView.progress == 100) {
                            webView.visibility = View.VISIBLE
                            loadingView.clearAnimation() // 停止呼吸灯动画 (Stop Pulse Animation)
                            loadingView.visibility = View.GONE
                            swipeRefresh.isRefreshing = false
                        }
                        loadingView.tag = null // 重置标记
                    }, 1000)
                }
            }
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
                hideSystemUI(url)

                // 核心修改：根据当前 URL 状态同步下拉刷新的启用状态 (Sync swipe refresh state based on URL)
                val isChapterPage = url?.contains("chapter") == true
                swipeRefresh.isEnabled = !isChapterPage

                // 核心逻辑：页面刷新（或开始加载新页面）时触发动画并隐藏内容
                // 确保“首次冷启动”和“页面刷新”都能看到加载效果 (Ensure load visibility on cold start/refresh)
                webView.visibility = View.INVISIBLE
                
                // 每次开始加载新页面时，重置 tag 为 null，允许正常的“加载完成逻辑”触发 (Reset tag for new load)
                loadingView.tag = null 

                if (loadingView.visibility != View.VISIBLE) {
                    loadingView.visibility = View.VISIBLE
                    val pulse = AnimationUtils.loadAnimation(this@MainActivity, R.anim.pulse_animation)
                    loadingView.startAnimation(pulse)
                }
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
                    
                    // 核心修改：在验证失败时，立即在 UI 线程隐藏 WebView 并显示加载动画，遮盖即将出现的错误页面
                    // 同时通过设置特定的 tag，拦截 onProgressChanged 的“自动显示”逻辑
                    // (Hide WebView and show loading on auth failure, lock tag to prevent reveal)
                    runOnUiThread {
                        webView.visibility = View.INVISIBLE
                        loadingView.visibility = View.VISIBLE
                        loadingView.tag = "error_lock" // 锁定加载状态，遮盖错误页

                        if (loadingView.animation == null) {
                            val pulse = AnimationUtils.loadAnimation(this@MainActivity, R.anim.pulse_animation)
                            loadingView.startAnimation(pulse)
                        }

                        if (!user.isNullOrEmpty()) {
                            Toast.makeText(this@MainActivity, "验证失败，请检查账号密码", Toast.LENGTH_LONG).show()
                        }
                        showConfigDialog()
                    }
                } else {
                    if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                        view?.tag = "auth_failed"
                        handler?.proceed(user, pass)
                    } else {
                        // 没有任何配置时也进行遮盖并弹出设置
                        // (Cover and show config if no credentials)
                        runOnUiThread {
                            webView.visibility = View.INVISIBLE
                            loadingView.visibility = View.VISIBLE
                            loadingView.tag = "no_auth_lock"

                            if (loadingView.animation == null) {
                                val pulse = AnimationUtils.loadAnimation(this@MainActivity, R.anim.pulse_animation)
                                loadingView.startAnimation(pulse)
                            }
                            showConfigDialog()
                        }
                        handler?.cancel()
                    }
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.tag = null
                injectFixes(view)
                hideSystemUI(url)

                // 核心修改：在页面加载完成时再次确认下拉刷新的启用状态 (Re-verify swipe refresh state on page finish)
                val isChapterPage = url?.contains("chapter") == true
                swipeRefresh.isEnabled = webView.scrollY == 0 && !isChapterPage
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                hideSystemUI(url)

                // 历史记录更新时同步刷新状态 (Sync refresh state on history update)
                val isChapterPage = url?.contains("chapter") == true
                swipeRefresh.isEnabled = webView.scrollY == 0 && !isChapterPage
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    swipeRefresh.isRefreshing = false
                    
                    // 核心修改：连接失败（包括验证取消）时，保持 WebView 隐藏，使用加载图遮盖原生的错误页面
                    // (Keep WebView hidden and lock loading view on connection error)
                    webView.visibility = View.INVISIBLE
                    loadingView.visibility = View.VISIBLE
                    loadingView.tag = "load_error_lock" // 标记错误状态，防止 onProgressChanged 将其显示

                    if (loadingView.animation == null) {
                        val pulse = AnimationUtils.loadAnimation(this@MainActivity, R.anim.pulse_animation)
                        loadingView.startAnimation(pulse)
                    }
                    
                    Toast.makeText(this@MainActivity, "连接失败，请检查配置", Toast.LENGTH_LONG).show()
                    showConfigDialog()
                }
            }
        }

        webView.setOnLongClickListener {
            // 核心修改：仅在 URL 包含 "chapter" 字段时激活长按保存功能 (Activate long-press save only if URL contains "chapter")
            val currentUrl = webView.url
            if (currentUrl?.contains("chapter") == true) {
                val result = webView.hitTestResult
                if (result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    val imageUrl = result.extra
                    if (imageUrl != null) {
                        val dialog = AlertDialog.Builder(this)
                            .setTitle("保存图片")
                            .setMessage("要下载这张漫画页面吗？")
                            .setPositiveButton("下载") { _, _ -> saveImageToGallery(imageUrl) }
                            .setNegativeButton("取消", null)
                            .create()
                        
                        dialog.show()
                        
                        // 按钮颜色定制为 #3581b2 (Custom button color)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor("#3581b2".toColorInt())
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor("#3581b2".toColorInt())
                    }
                    return@setOnLongClickListener true
                }
            }
            false
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentUrl = webView.url
                val rootSuffixes = listOf("library", "updates", "history", "sources", "extensions", "migrate", "more")
                val isRootPage = rootSuffixes.any { suffix ->
                    currentUrl?.endsWith(suffix) == true || currentUrl?.endsWith("$suffix/") == true
                }

                if (isRootPage) {
                    showConfigDialog()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    showConfigDialog()
                }
            }
        })
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.ic_launcher_background)
        swipeRefresh.setOnRefreshListener {
            swipeRefresh.isRefreshing = false
            webView.reload()
        }
    }

    private fun saveImageToGallery(url: String) {
        try {
            val request = DownloadManager.Request(url.toUri())
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

        val dialog = AlertDialog.Builder(this)
            .setTitle("服务器配置")
            .setView(view)
            .setCancelable(savedUrl.isNullOrEmpty().not())
            .setPositiveButton("保存并进入") { _, _ ->
                val url = editUrl.text.toString()
                val user = editUser.text.toString()
                val pass = editPass.text.toString()

                if (url.isNotEmpty()) {
                    prefs.edit {
                        putString("url", url)
                        putString("user", user)
                        putString("pass", pass)
                    }

                    WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
                    webView.clearCache(true)
                    webView.tag = null
                    webView.loadUrl(url)
                }
            }
            .setNegativeButton("退出应用") { _, _ ->
                finish()
            }
            .create()

        dialog.show()
        
        // 核心改动：在对话框显示后设置按钮颜色 (Set custom button color #3581b2 after show)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor("#3581b2".toColorInt())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor("#3581b2".toColorInt())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 执行反向动画 (下一页/向右)
                wiperView.startWipeAnimation(fromLeftToRight = false)
                simulateKey("ArrowRight", 39)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // 执行正向动画 (上一页/向左)
                wiperView.startWipeAnimation(fromLeftToRight = true)
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

    private fun hideSystemUI(targetUrl: String? = null) {
        val currentUrl = targetUrl ?: (if (::webView.isInitialized) webView.url else null)
        // 增加判空保护 (Add null protection)
        val isChapterPage = !currentUrl.isNullOrEmpty() && currentUrl.contains("chapter")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 核心修复：只有进入章节页面才进入沉浸全屏模式。普通页面应保持状态栏可见且不被覆盖。
            window.setDecorFitsSystemWindows(!isChapterPage)
            window.insetsController?.let { controller ->
                if (isChapterPage) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    // 核心修改：在黑色背景下，我们需要“浅色/白色”图标，所以 appearance 参数应为 0
                    // (Ensure light icons for the black status bar background on Android 11+)
                    controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (isChapterPage) {
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
            } else {
                // 普通页面清除全屏和隐藏导航栏的 Flag (Clear flags for normal pages)
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                
                // 针对 Android 6.0+，清除 LIGHT_STATUS_BAR 确保图标在黑色背景下是白色的
                // (For Android M+, ensure icons are light to contrast with BLACK background)
                var flags = window.decorView.systemUiVisibility
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                window.decorView.systemUiVisibility = flags
            }
        }

        // 核心修改：简化状态栏颜色 (Simplified status bar color)
        // 非章节页直接使用黑色 Color.BLACK，章节页保持透明以支持沉浸模式
        window.statusBarColor = if (isChapterPage) {
            Color.TRANSPARENT
        } else {
            Color.BLACK
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = if (isChapterPage) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }
}
