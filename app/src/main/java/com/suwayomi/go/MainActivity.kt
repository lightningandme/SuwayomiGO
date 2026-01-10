package com.suwayomi.go


import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
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
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.math.abs


@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingView: ImageView
    private lateinit var flashView: View
    private lateinit var ocrIndicator: View
    private lateinit var prefs: SharedPreferences
    private var isAutoProtocolFallback = false
    // 标记位：用于区分长按是否已被处理 (Flag to track if long press was handled)
    private var isLongPressHandled = false
    
    // 核心修改：OCR 模式开关标记 (OCR mode toggle flag)
    private var isOcrEnabled = false
    private val touchPoints = mutableListOf<PointF>()

    private lateinit var ocrManager: MangaOcrManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 默认进入沉浸式全面屏，并适配状态栏 (Default immersive edge-to-edge)
        hideSystemUI()

        prefs = getSharedPreferences("AppConfig", MODE_PRIVATE)
        webView = findViewById(R.id.webview)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingView = findViewById(R.id.loadingProgress)
        flashView = findViewById(R.id.flashView)
        ocrIndicator = findViewById(R.id.ocrIndicator)

        // 初始化 OCR 管理类
        ocrManager = MangaOcrManager(webView)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        setupMangaOcrTouch()

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

    private fun setOcrEnabled(enabled: Boolean) {
        isOcrEnabled = enabled
        ocrIndicator.isVisible = enabled
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
                // 当进度达到 100% 且加载视图可见时，启动验证与延迟隐藏逻辑
                // (Trigger verification and delayed hide logic when progress is 100%)
                if (newProgress == 100 && loadingView.isVisible && loadingView.tag == null) {
                    loadingView.tag = "verifying"

                    performSuwayomiVerification(view) { isSuwayomi ->
                        // 确保在验证期间没有发生新的页面加载 (Ensure no new load started during verification)
                        if (loadingView.tag != "verifying") return@performSuwayomiVerification

                        if (isSuwayomi) {
                            loadingView.tag = "is_ending" // 标记正在处理结束逻辑

                            // 核心修改：已禁止自动保存跳转后的地址，确保配置始终为干净的服务器基准地址
                            // (Auto-saving redirected URLs is disabled to keep configuration as the clean server base)
                            /*
                            val currentUrl = view?.url
                            if (!currentUrl.isNullOrEmpty()) {
                                val savedUrl = prefs.getString("url", "")
                                // 检查当前加载地址是否与保存的地址协议不同
                                if (currentUrl != savedUrl && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
                                    prefs.edit { putString("url", currentUrl) }
                                }
                            }
                            */

                            // 实现要求：webView加载完成，加载动画任继续运行2秒 (Keep animation for 2s after load)
                            webView.postDelayed({
                                // 再次检查进度，防止延迟期间用户又触发了新的刷新
                                if (webView.progress == 100) {
                                    webView.visibility = View.VISIBLE
                                    loadingView.clearAnimation() // 停止呼吸灯动画 (Stop Pulse Animation)
                                    loadingView.visibility = View.GONE
                                    swipeRefresh.isRefreshing = false
                                }
                                loadingView.tag = null // 重置标记
                            }, 2000)
                        } else {
                            // 验证失败：识别到非 Suwayomi 服务器 (Verification failed: Not a Suwayomi server)
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "连接失败，请检查配置", Toast.LENGTH_LONG).show()
                                webView.visibility = View.INVISIBLE
                                loadingView.visibility = View.VISIBLE
                                loadingView.tag = "verify_failed_lock" // 锁定加载状态

                                if (loadingView.animation == null) {
                                    val pulse = AnimationUtils.loadAnimation(this@MainActivity, R.anim.pulse_animation)
                                    loadingView.startAnimation(pulse)
                                }
                                showConfigDialog()
                            }
                        }
                    }
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

                // 核心逻辑：退出章节页面时自动关闭 OCR 监听状态 (Automatically disable OCR mode when leaving chapter)
                if (!isChapterPage) {
                    setOcrEnabled(false)
                }

                // 核心逻辑：页面刷新（或开始加载新页面）时触发动画并隐藏内容
                // 确保“首次冷启动”和“页面刷新”都能看到加载效果 (Ensure load visibility on cold start/refresh)
                webView.visibility = View.INVISIBLE

                // 每次开始加载新页面时，重置 tag 为 null，允许正常的“加载完成逻辑”触发 (Reset tag for new load)
                // This would also clear temporary tags set during fallbacks
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

                // 核心逻辑：退出章节页面时自动关闭 OCR 监听状态 (适用于单页应用路由跳转)
                // (Automatically disable OCR mode when leaving chapter - for SPA navigation)
                if (!isChapterPage) {
                    setOcrEnabled(false)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val failingUrl = request.url.toString()

                    // 核心修改：自动协议适应逻辑 (Auto-protocol adaptation logic)
                    if (failingUrl.startsWith("https://") && !isAutoProtocolFallback) {
                        isAutoProtocolFallback = true
                        // Mark fallback to prevent premature config dialog
                        loadingView.tag = "protocol_fallback"

                        val fallbackUrl = failingUrl.replaceFirst("https://", "http://")
                        view?.post { view.loadUrl(fallbackUrl) }
                        return
                    }

                    // 彻底失败或无需回退时重置标记
                    isAutoProtocolFallback = false
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
            // Activate long-press save only if URL contains "chapter"
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

    private var lastDownX = 0f
    private var lastDownY = 0f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMangaOcrTouch() {
        webView.setOnTouchListener { _, event ->
            val isChapterPage = webView.url?.contains("chapter") == true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastDownX = event.x
                    lastDownY = event.y
                    if (isOcrEnabled && isChapterPage) {
                        touchPoints.clear()
                        touchPoints.add(PointF(event.x, event.y))
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isOcrEnabled && isChapterPage) {
                        touchPoints.add(PointF(event.x, event.y))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - lastDownX
                    val deltaY = event.y - lastDownY
                    val absDeltaX = abs(deltaX)
                    val absDeltaY = abs(deltaY)

                    // 核心逻辑：在章节页面检测下滑手势切换 OCR 模式
                    if (isChapterPage && deltaY > 400 && absDeltaY > absDeltaX * 1.5) {
                        setOcrEnabled(!isOcrEnabled)
                        val statusText = if (isOcrEnabled) "OCR 模式已开启" else "OCR 模式已关闭"
                        Toast.makeText(this, statusText, Toast.LENGTH_LONG).show()
                        return@setOnTouchListener true
                    }

                    // 检测左右滑动手势映射为方向键翻页
                    if (isChapterPage && isOcrEnabled && absDeltaX > 300 && absDeltaX > absDeltaY * 1.5) {
                        if (deltaX > 0) {
                            simulateKey("ArrowLeft", 37)
                        } else {
                            simulateKey("ArrowRight", 39)
                        }
                        return@setOnTouchListener true
                    }

                    // OCR 识别逻辑：优先交给 ocrManager 处理手势序列 (Identify closed loop or click)
                    if (isChapterPage && isOcrEnabled) {
                        touchPoints.add(PointF(event.x, event.y))
                        if (ocrManager.processTouchPoints(touchPoints)) {
                            return@setOnTouchListener true
                        }
                    }
                }
            }

            // Consumes events to block webView interaction in OCR mode
            if (isOcrEnabled && isChapterPage) {
                true
            } else {
                false
            }
        }
    }

    private fun performSuwayomiVerification(view: WebView?, callback: (Boolean) -> Unit) {
        val js = """
            (function() {
                try {
                    var scripts = document.getElementsByTagName('script');
                    var foundMarker = false;
                    for (var i = 0; i < scripts.length; i++) {
                        if (scripts[i].textContent.indexOf('<<suwayomi-subpath-injection>>') !== -1) {
                            foundMarker = true;
                            break;
                        }
                    }
                    var titleMatch = document.title.indexOf('Suwayomi') !== -1;
                    var meta = document.querySelector('meta[name="apple-mobile-web-app-title"]');
                    var metaMatch = meta && meta.content && meta.content.indexOf('Suwayomi') !== -1;
                    return foundMarker || titleMatch || metaMatch;
                } catch (e) {
                    return false;
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(js) { result ->
            callback(result == "true")
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
        val btnTestUrl = view.findViewById<View>(R.id.btnTestUrl)

        val savedUrlString = prefs.getString("url", "")
        editUrl.setText(savedUrlString)
        editUser.setText(prefs.getString("user", ""))
        editPass.setText(prefs.getString("pass", ""))

        // 创建并配置对话框 (Create and configure the dialog)
        val dialog = AlertDialog.Builder(this)
            .setTitle("服务器配置")
            .setView(view)
            .setCancelable(savedUrlString.isNullOrEmpty().not())
            .setNeutralButton("更多设置") { _, _ ->
                showMoreSettingsDialog()
            }
            .setPositiveButton("进入页面", null)
            .setNegativeButton("退出应用") { _, _ ->
                finish()
            }
            .create()

        btnTestUrl.setOnClickListener {
            var rawInput = editUrl.text.toString().trim()
            if (rawInput.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (rawInput.contains("：")) {
                rawInput = rawInput.replace("：", ":")
                editUrl.setText(rawInput)
            }

            val user = editUser.text.toString().trim()
            val pass = editPass.text.toString().trim()

            Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show()

            fun performTest(baseUrl: String, fallbackToHttps: Boolean) {
                try {
                    val client = OkHttpClient()
                    val requestBuilder = Request.Builder().url(baseUrl)
                    
                    // Add Basic Auth if credentials provided
                    if (user.isNotEmpty() && pass.isNotEmpty()) {
                        val auth = "$user:$pass"
                        val base64Auth = Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)
                        requestBuilder.addHeader("Authorization", "Basic $base64Auth")
                    }
                    
                    val request = requestBuilder.build()

                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (fallbackToHttps) {
                                runOnUiThread { performTest("https://$rawInput", false) }
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val isAuthError = response.code == 401
                            val isReachable = response.isSuccessful || isAuthError
                            val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }

                            val hasMarker = body.contains("<<suwayomi-subpath-injection>>")
                            val hasTitle = body.contains("<title>Suwayomi")
                            val hasMeta = body.contains("apple-mobile-web-app-title") && body.contains("Suwayomi")
                            val isSuwayomi = hasMarker || hasTitle || hasMeta

                            runOnUiThread {
                                if (isReachable) {
                                    // 核心微调：仅确保地址以 / 结尾，禁止将处理后的地址或跳转后的地址回传给地址栏 (Ensure trailing slash and prohibit backflow to UI)
                                    val cleanedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                                    // editUrl.setText(cleanedUrl) // 禁止回传处理后的 URL 到 UI (Stop updating the URL in the dialog)
                                    
                                    if (isSuwayomi) {
                                        // 仅执行自动保存操作，不进入网页，不关闭对话框 (Perform auto-save only)
                                        prefs.edit {
                                            putString("url", cleanedUrl)
                                            putString("user", user)
                                            putString("pass", pass)
                                        }
                                        Toast.makeText(this@MainActivity, "连接成功（配置已保存）", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val msg = if (isAuthError) "账号密码错误" else "未识别到 Suwayomi 服务"
                                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    if (fallbackToHttps) {
                                        performTest("https://$rawInput", false)
                                    } else {
                                        Toast.makeText(this@MainActivity, "服务器响应错误: ${response.code}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            response.close()
                        }
                    })
                } catch (_: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "URL 格式无效，请检查符号", Toast.LENGTH_LONG).show()
                    }
                }
            }

            if (!rawInput.startsWith("http://") && !rawInput.startsWith("https://")) {
                performTest("http://$rawInput", true)
            } else {
                performTest(rawInput, false)
            }
        }

        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        positiveButton.setTextColor("#3581b2".toColorInt())
        negativeButton.setTextColor("#3581b2".toColorInt())
        neutralButton.setTextColor("#3581b2".toColorInt())

        positiveButton.setOnClickListener {
            val urlInput = editUrl.text.toString().trim()
            val userInput = editUser.text.toString().trim()
            val passInput = editPass.text.toString().trim()

            if (urlInput.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            } else {
                // 处理 URL 协议补全用于后续比较 (Handle URL protocol completion for comparison)
                var urlToLoad = urlInput
                if (!urlToLoad.contains("://")) {
                    urlToLoad = "https://$urlToLoad"
                }

                // 获取已保存（测试通过）的配置信息 (Get saved (tested) configuration)
                val savedUrl = prefs.getString("url", "")
                val savedUser = prefs.getString("user", "")
                val savedPass = prefs.getString("pass", "")

                // 在比对前确保输入地址以 / 结尾 (Ensure URL ends with / before comparison)
                val cleanedUrlToLoad = if (urlToLoad.endsWith("/")) urlToLoad else "$urlToLoad/"

                // 核心微调：检查当前输入是否与已测试通过的配置一致
                // 任何未经测试通过的更改全都会被拦截并提示 (Intercept any untested changes)
                if (cleanedUrlToLoad != savedUrl || userInput != savedUser || passInput != savedPass) {
                    Toast.makeText(this, "配置已更改，请再次测试连接", Toast.LENGTH_SHORT).show()
                } else {
                    // 仅在配置匹配时执行载入页面操作
                    // (Only perform page load when configuration matches)
                    webView.loadUrl(urlToLoad)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showMoreSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.more_settings, null)
        val checkVolumePaging = view.findViewById<SwitchCompat>(R.id.checkVolumePaging)
        val editOcrUrl = view.findViewById<EditText>(R.id.editOcrUrl)
        val btnTestOcr = view.findViewById<View>(R.id.btnTestOcr)

        checkVolumePaging.isChecked = prefs.getBoolean("volume_paging", true)
        editOcrUrl.setText(prefs.getString("ocr_server_url", ""))

        btnTestOcr.setOnClickListener {
            var rawInput = editOcrUrl.text.toString().trim()
            if (rawInput.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (rawInput.contains("：")) {
                rawInput = rawInput.replace("：", ":")
                editOcrUrl.setText(rawInput)
            }

            // Use main credentials for OCR test as well
            val user = prefs.getString("user", "") ?: ""
            val pass = prefs.getString("pass", "") ?: ""

            Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show()

            fun performTest(baseUrl: String, fallbackToHttps: Boolean) {
                val testUrl = if (baseUrl.endsWith("/")) "${baseUrl}ocr" else "$baseUrl/ocr"
                
                try {
                    val client = OkHttpClient()
                    val requestBuilder = Request.Builder().url(testUrl)
                    
                    if (user.isNotEmpty() && pass.isNotEmpty()) {
                        val auth = "$user:$pass"
                        val base64Auth = Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP)
                        requestBuilder.addHeader("Authorization", "Basic $base64Auth")
                    }
                    
                    val request = requestBuilder.build()

                    client.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (fallbackToHttps) {
                                runOnUiThread { performTest("https://$rawInput", false) }
                            } else {
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val isAuthError = response.code == 401
                            val isSuccess = response.isSuccessful || response.code == 405 || isAuthError
                            val body = try { response.body?.string() ?: "" } catch (_: Exception) { "" }

                            val hasMarker = body.contains("<<suwayomi-subpath-injection>>")
                            val hasTitle = body.contains("<title>Suwayomi")
                            val hasMeta = body.contains("apple-mobile-web-app-title") && body.contains("Suwayomi")
                            val isSuwayomi = hasMarker || hasTitle || hasMeta

                            runOnUiThread {
                                if (isSuccess) {
                                    editOcrUrl.setText(baseUrl)
                                    val msg = when {
                                        isAuthError -> "连接成功，但账号密码错误"
                                        isSuwayomi -> "连接成功：已识别 Suwayomi 服务"
                                        else -> "连接成功！"
                                    }
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                } else {
                                    if (fallbackToHttps) {
                                        performTest("https://$rawInput", false)
                                    } else {
                                        Toast.makeText(this@MainActivity, "服务器响应错误: ${response.code}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            response.close()
                        }
                    })
                } catch (_: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "URL 格式无效，请检查符号", Toast.LENGTH_LONG).show()
                    }
                }
            }

            if (!rawInput.startsWith("http://") && !rawInput.startsWith("https://")) {
                performTest("http://$rawInput", true)
            } else {
                performTest(rawInput, false)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                prefs.edit {
                    putBoolean("volume_paging", checkVolumePaging.isChecked)
                    putString("ocr_server_url", editOcrUrl.text.toString().trim())
                }
            }
            .setNegativeButton("取消", null)
            // 核心实现：当此对话框以任何形式关闭时（确定、取消、物理返回），都重新弹出服务器配置对话框
            // (Core implementation: When this dialog is closed in any way (OK, Cancel, Back), reappear the server config dialog)
            .setOnDismissListener {
                showConfigDialog()
            }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor("#3581b2".toColorInt())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor("#3581b2".toColorInt())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isChapterPage = webView.url?.contains("chapter") == true
        if (isChapterPage && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            event?.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        val isChapterPage = webView.url?.contains("chapter") == true
        if (isChapterPage) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    isLongPressHandled = true
                    Toast.makeText(this, "长按音量下：此快捷键暂留", Toast.LENGTH_SHORT).show()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    isLongPressHandled = true
                    setOcrEnabled(!isOcrEnabled)
                    val statusText = if (isOcrEnabled) "OCR 模式已开启" else "OCR 模式已关闭"
                    Toast.makeText(this, statusText, Toast.LENGTH_LONG).show()
                    return true
                }
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val isChapterPage = webView.url?.contains("chapter") == true
        if (isChapterPage && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (!isLongPressHandled) {
                handleShortPressPaging(keyCode)
            }
            isLongPressHandled = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleShortPressPaging(keyCode: Int) {
        val volumePagingEnabled = prefs.getBoolean("volume_paging", true)
        val switchDelay = if (volumePagingEnabled) 100L else 0L

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumePagingEnabled) flashView.visibility = View.VISIBLE
                Handler(Looper.getMainLooper()).postDelayed({
                    simulateKey("ArrowRight", 39)
                }, switchDelay)
                Handler(Looper.getMainLooper()).postDelayed({
                    flashView.visibility = View.GONE
                }, 400)
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (volumePagingEnabled) flashView.visibility = View.VISIBLE
                Handler(Looper.getMainLooper()).postDelayed({
                    simulateKey("ArrowLeft", 37)
                }, switchDelay)
                Handler(Looper.getMainLooper()).postDelayed({
                    flashView.visibility = View.GONE
                }, 400)
            }
        }
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
        val isChapterPage = !currentUrl.isNullOrEmpty() && currentUrl.contains("chapter")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(!isChapterPage)
            window.insetsController?.let { controller ->
                if (isChapterPage) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
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
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                var flags = window.decorView.systemUiVisibility
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                window.decorView.systemUiVisibility = flags
            }
        }

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "授权成功！请再次选择卡片导出", Toast.LENGTH_SHORT).show()
            } else {
                // 核心逻辑：检查用户是否点击了“不再询问” (Check if 'Don't ask again' was checked)
                val permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
                val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

                if (!showRationale) {
                    // 用户拒绝并点击了“不再询问”，或者在系统设置中彻底禁用
                    showPermissionSettingsDialog()
                } else {
                    Toast.makeText(this, "权限被拒绝，无法使用导出功能", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 引导用户去设置页的对话框 (Guide user to Settings)
    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要导出权限")
            .setMessage("你已经禁用了 Anki 访问权限。请前往[设置 -> 应用 -> SuwayomiGO -> 权限]中手动开启“读取和写入 Anki 数据库”权限，或者尝试重新安装SuwayomiGO！")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
