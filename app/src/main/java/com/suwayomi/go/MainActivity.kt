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
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.suwayomi.go.widget.StripWiperView


@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loadingView: ImageView
    private lateinit var wiperView: StripWiperView
    private lateinit var flashView: View
    private lateinit var prefs: SharedPreferences
    private var isAutoProtocolFallback = false


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
        flashView = findViewById(R.id.flashView)

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
                // 当进度达到 100% 且加载视图可见时，启动验证与延迟隐藏逻辑
                // (Trigger verification and delayed hide logic when progress is 100%)
                if (newProgress == 100 && loadingView.isVisible && loadingView.tag == null) {
                    loadingView.tag = "verifying" 
                    
                    performSuwayomiVerification(view) { isSuwayomi ->
                        // 确保在验证期间没有发生新的页面加载 (Ensure no new load started during verification)
                        if (loadingView.tag != "verifying") return@performSuwayomiVerification

                        if (isSuwayomi) {
                            loadingView.tag = "is_ending" // 标记正在处理结束逻辑
                            
                            // 核心修复：如果通过自动回退成功访问，将成功的 URL 保存回配置 (Save successful fallback URL to prefs)
                            val currentUrl = view?.url
                            if (!currentUrl.isNullOrEmpty()) {
                                val savedUrl = prefs.getString("url", "")
                                // 检查当前加载地址是否与保存的地址协议不同
                                if (currentUrl != savedUrl && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
                                    prefs.edit { putString("url", currentUrl) }
                                }
                            }

                            // 实现要求：webview加载完成，加载动画任继续运行2秒 (Keep animation for 2s after load)
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

                // 核心逻辑：页面刷新（或开始加载新页面）时触发动画并隐藏内容
                // 确保“首次冷启动”和“页面刷新”都能看到加载效果 (Ensure load visibility on cold start/refresh)
                webView.visibility = View.INVISIBLE
                
                // 每次开始加载新页面时，重置 tag 为 null，允许正常的“加载完成逻辑”触发 (Reset tag for new load)
                // 这也会清除回退过程中设置的临时 tag
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
                    val failingUrl = request.url.toString()
                    
                    // 核心修改：自动协议适应逻辑 (Auto-protocol adaptation logic)
                    // 如果优先尝试的 https 失败了且之前没试过回退，则尝试回退到 http
                    if (failingUrl.startsWith("https://") && !isAutoProtocolFallback) {
                        isAutoProtocolFallback = true
                        // 核心改动：标记正在回退中，防止错误页面的进度 100% 提前触发配置窗 (Mark fallback to prevent premature config dialog)
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

    /**
     * 执行 Suwayomi 服务器验证逻辑 (Execute Suwayomi server verification logic)
     */
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

        val savedUrl = prefs.getString("url", "")
        // 修改：初始不强制填充协议，保持整洁 (Keep input clean, don't force prefix)
        editUrl.setText(savedUrl)
        editUser.setText(prefs.getString("user", ""))
        editPass.setText(prefs.getString("pass", ""))

        val dialog = AlertDialog.Builder(this)
            .setTitle("服务器配置")
            .setView(view)
            .setCancelable(savedUrl.isNullOrEmpty().not())
            .setNeutralButton("更多设置") { _, _ ->
                showMoreSettingsDialog()
            }
            .setPositiveButton("保存并进入", null) // 设为 null，后面通过 setOnClickListener 重写以防止自动关闭
            .setNegativeButton("退出应用") { _, _ ->
                finish()
            }
            .create()

        dialog.show()
        
        // 在 show() 之后获取按钮并设置逻辑，这样可以控制对话框不自动关闭 (Get buttons after show() to control dismissal manually)
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        positiveButton.setTextColor("#3581b2".toColorInt())
        negativeButton.setTextColor("#3581b2".toColorInt())
        neutralButton.setTextColor("#3581b2".toColorInt())

        positiveButton.setOnClickListener {
            var url = editUrl.text.toString().trim()
            val user = editUser.text.toString().trim()
            val pass = editPass.text.toString().trim()

            if (url.isEmpty()) {
                // 当 URL 为空时提示，并不关闭对话框 (Toast if URL empty, keep dialog open)
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            } else {
                // 核心修改：优先尝试 https，这是目前网页访问的标准 (Prioritize https by default)
                if (!url.contains("://")) {
                    url = "https://$url"
                }

                // 获取原先储存的信息以进行比对 (Get previously stored info for comparison)
                val oldUrl = prefs.getString("url", "")
                val oldUser = prefs.getString("user", "")
                val oldPass = prefs.getString("pass", "")

                // 识别是否有任何不一致 (Check if there's any inconsistency)
                val isChanged = url != oldUrl || user != oldUser || pass != oldPass

                if (isChanged) {
                    // 核心逻辑：如果配置发生变更，放弃尝试立即生效，转而冻结界面并提示用户彻底重启 (Freeze UI and prompt restart if info changed)
                    // 这应对 WebView 内存缓存顽疾的最稳妥策略。
                    
                    // 1. 立即保存新配置到存储 (Save new config immediately)
                    prefs.edit {
                        putString("url", url)
                        putString("user", user)
                        putString("pass", pass)
                    }

                    // 2. 彻底切断当前连接并冻结界面 (Sever current connection and freeze UI)
                    webView.stopLoading()
                    webView.visibility = View.GONE
                    swipeRefresh.isEnabled = false
                    
                    // 3. 弹出无法取消的提示框以冻结操作 (Show non-dismissible prompt to freeze operations)
                    val restartDialog = AlertDialog.Builder(this@MainActivity)
                        .setTitle("配置已更新")
                        .setMessage("应用即将退出，请手动重启！")
                        .setCancelable(false) // 禁用取消，强制用户看到提示 (Force user to see the prompt)
                        .setPositiveButton("好，我知道了") { _, _ ->
                            // 核心修改：在确认重启后才执行基础清理 (Perform basic clear only after user confirmation)
                            WebViewDatabase.getInstance(this@MainActivity).clearHttpAuthUsernamePassword()
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            webView.clearCache(true)
                            webView.clearHistory()

                            // 退出 Activity 组并杀死进程 (Exit activity affinity and kill process)
                            finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                        .setNegativeButton("不，我手滑了") { _, _ ->
                            // 核心修改：恢复旧配置并恢复界面 (Restore old config and UI)
                            prefs.edit {
                                putString("url", oldUrl)
                                putString("user", oldUser)
                                putString("pass", oldPass)
                            }
                            webView.visibility = View.VISIBLE
                            
                            // 恢复刷新状态：根据旧 URL 逻辑同步 (Sync refresh state)
                            val isChapterPage = oldUrl?.contains("chapter") == true
                            swipeRefresh.isEnabled = !isChapterPage
                            
                            // 重新加载原 URL (Reload original URL)
                            webView.loadUrl(oldUrl ?: "")
                        }
                        .show()
                    
                    // 定制按钮颜色
                    restartDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor("#3581b2".toColorInt())
                    restartDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor("#3581b2".toColorInt())
                    
                    // 关闭配置对话框 (Dismiss config dialog)
                    dialog.dismiss()
                } else {
                    // 如果信息没有变化，正常执行加载
                    webView.loadUrl(url)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showMoreSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.more_settings, null)
        val checkVolumePaging = view.findViewById<SwitchCompat>(R.id.checkVolumePaging)
        
        // 加载当前保存的状态 (Load saved state)
        checkVolumePaging.isChecked = prefs.getBoolean("volume_paging", true)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                prefs.edit {
                    putBoolean("volume_paging", checkVolumePaging.isChecked)
                }
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        
        // 定制按钮颜色
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor("#3581b2".toColorInt())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor("#3581b2".toColorInt())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 核心用意：通过音量键实现翻页，并配合 WiperView 动画掩盖翻页时的视觉突变 (Use volume keys for paging with wiper animation)
        
        // 优化 1：仅在章节阅读页面拦截音量键，普通页面（如设置、书架）保留系统音量控制
        val isChapterPage = webView.url?.contains("chapter") == true
        if (!isChapterPage) return super.onKeyDown(keyCode, event)

        val volumePagingEnabled = prefs.getBoolean("volume_paging", true)

        // 优化 2：同步动画与换页逻辑。
        val switchDelay = if (volumePagingEnabled) 200L else 0L

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumePagingEnabled) flashView.visibility = View.VISIBLE
                Handler(Looper.getMainLooper()).postDelayed({
                    simulateKey("ArrowRight", 39)
                }, switchDelay)
                Handler(Looper.getMainLooper()).postDelayed({
                    flashView.visibility = View.GONE
                }, 500)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (volumePagingEnabled) flashView.visibility = View.VISIBLE
                Handler(Looper.getMainLooper()).postDelayed({
                    simulateKey("ArrowLeft", 37)
                }, switchDelay)
                Handler(Looper.getMainLooper()).postDelayed({
                    flashView.visibility = View.GONE
                }, 500)
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
