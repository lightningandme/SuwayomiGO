package com.suwayomi.go

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.ichi2.anki.api.AddContentApi
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.hypot

/**
 * 专门处理漫画 OCR 逻辑的管理类 (Management class for Manga OCR logic)
 */
data class JapaneseWord(
    val surface: String,
    val baseForm: String,
    val pos: String,
    val reading: String,
    val definition: String // 新增字段
)

data class OcrResponse(
    val text: String,
    val translation: String,
    val words: List<JapaneseWord>
) {
    companion object {
        fun fromJson(jsonString: String): OcrResponse {
            val json = JSONObject(jsonString)
            val wordsArray = json.getJSONArray("words")
            val wordList = mutableListOf<JapaneseWord>()
            // 修改 OcrResponse 中的解析逻辑
            for (i in 0 until wordsArray.length()) {
                val w = wordsArray.getJSONObject(i)
                wordList.add(JapaneseWord(
                    w.optString("s"),
                    w.optString("b"),
                    w.optString("p"),
                    w.optString("r"),
                    w.optString("d") // 读取释义
                ))
            }
            return OcrResponse(
                text = json.optString("text"),
                translation = json.optString("translation"),
                words = wordList
            )
        }
    }
}

class MangaOcrManager(private val webView: WebView) {

    // 定义切图大小 (Crop size) - 按照设备分辨率的短边的 0.8 倍规定 (Set to 0.8 times the shorter side of the screen resolution)
    private val cropSize = (webView.context.resources.displayMetrics.run {
        widthPixels.coerceAtMost(heightPixels) * 0.8
    }).toInt()
    private val client = OkHttpClient()
    private var lastRequestTime: Long = 0

    // 在 MangaOcrManager 类顶部声明
    private val api: AddContentApi by lazy {
        AddContentApi(webView.context.applicationContext)
    }
    /**
     * 常规点按识别 (Regular click recognition)
     */
    fun processCrop(clickX: Int, clickY: Int) {
        if (webView.width <= 0 || webView.height <= 0) return

        val fullBitmap = createBitmap(webView.width, webView.height)
        val canvas = Canvas(fullBitmap)
        webView.draw(canvas)

        val half = cropSize / 2
        val actualWidth = cropSize.coerceAtMost(webView.width)
        val actualHeight = cropSize.coerceAtMost(webView.height)
        val left = (clickX - half).coerceIn(0, (webView.width - actualWidth).coerceAtLeast(0))
        val top = (clickY - half).coerceIn(0, (webView.height - actualHeight).coerceAtLeast(0))

        val relX = clickX - left
        val relY = clickY - top

        val cropped = Bitmap.createBitmap(fullBitmap, left, top, actualWidth, actualHeight)
        val base64String = bitmapToBase64(cropped)
        sendToOcrServer(base64String, relX, relY, clickY)
    }

    /**
     * 处理触摸点序列，识别是点击还是闭合圈 (Process touch points, identify loop or click)
     */
    fun processTouchPoints(points: List<PointF>): Boolean {
        if (points.isEmpty()) return false

        // 优先识别闭合圈 (Prioritize identifying closed loop)
        if (isClosedLoop(points)) {
            val rect = getBoundingBox(points)
            // 确保圈定的范围不是太小 (Ensure the area is not too small)
            if (rect.width() > 10 && rect.height() > 10) {
                Log.d("MangaOcr", "识别到闭合圈，范围: $rect")
                processRectCrop(rect)
                return true
            }
        }

        // 识别点击 (Identify click)
        val start = points.first()
        val end = points.last()
        val dist = hypot(start.x - end.x, start.y - end.y)
        if (dist < 20) { // 20 像素以内的位移视为点击 (Displacement within 20px treated as click)
            Log.d("MangaOcr", "检测到点按: (${start.x}, ${start.y})，启动常规切图...")
            processCrop(start.x.toInt(), start.y.toInt())
            return true
        }

        return false
    }

    private fun isClosedLoop(points: List<PointF>): Boolean {
        if (points.size < 10) return false
        val start = points.first()
        val end = points.last()
        val dist = hypot(start.x - end.x, start.y - end.y)

        // 计算路径总长度 (Calculate total path length)
        var pathLength = 0f
        for (i in 0 until points.size - 1) {
            pathLength += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }

        val density = webView.context.resources.displayMetrics.density
        // 启发式判断：起点和终点足够接近，且路径长度明显大于起终点距离且达到一定长度
        // (Heuristic: start/end close enough, path length significantly longer than dist and exceeds threshold)
        return dist < 40 * density && pathLength > 100 * density && pathLength > dist * 2
    }

    private fun getBoundingBox(points: List<PointF>): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun processRectCrop(rect: RectF) {
        if (webView.width <= 0 || webView.height <= 0) return

        val fullBitmap = createBitmap(webView.width, webView.height)
        val canvas = Canvas(fullBitmap)
        webView.draw(canvas)

        val left = rect.left.toInt().coerceIn(0, webView.width - 1)
        val top = rect.top.toInt().coerceIn(0, webView.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, webView.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, webView.height)

        val width = right - left
        val height = bottom - top

        val cropped = Bitmap.createBitmap(fullBitmap, left, top, width, height)
        val base64String = bitmapToBase64(cropped)

        val clickY = (top + bottom) / 2
        // 根据需求：relX, relY 均为 0，clickY 为矩形中心 Y 坐标 (relX, relY are 0, clickY is center Y)
        sendToOcrServer(base64String, 0, 0, clickY)
    }

    private fun sendToOcrServer(base64Image: String, relX: Int, relY: Int, absClickY: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRequestTime < 3000) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(webView.context, "请求过快，稍后再试", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val fullTitle = webView.title ?: ""
        val mangaName = fullTitle.substringBefore(" - Suwayomi")

        val prefs = webView.context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("ocr_server_url", "") ?: ""
        val ocrUrl = "${serverUrl}/ocr"

        if (ocrUrl.isEmpty()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(webView.context, "请先配置 OCR 地址", Toast.LENGTH_LONG).show()
            }
            return
        }

        val json = JSONObject().apply {
            put("image", base64Image)
            put("x", relX)
            put("y", relY)
            put("mangaName", mangaName)
        }.toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        try {
            lastRequestTime = currentTime

            val request = Request.Builder().url(ocrUrl).post(body).build()
            client.newCall(request).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) return
                        val bodyString = response.body?.string() ?: ""
                        try {
                            val ocrResult = OcrResponse.fromJson(bodyString)
                            Handler(Looper.getMainLooper()).post {
                                showResultBottomSheet(ocrResult, absClickY)
                            }
                        } catch (_: Exception) {
                            Log.e("MangaOcr", "Parse failed")
                        }
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("MangaOcr", "Network error: ${e.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("MangaOcr", "Request error: ${e.message}")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams", "SetTextI18n")
    private fun showResultBottomSheet(result: OcrResponse, absClickY: Int) {
        Handler(Looper.getMainLooper()).post {
            val context = webView.context
            
            val dialog = object : android.app.Dialog(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val keyCode = event.keyCode
                    // 拦截音量上键和音量下键 (Intercept Volume Up and Volume Down)
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        // 无论音量加还是音量减，只要抬起按键 (ACTION_UP) 就关闭对话框 (Dismiss on key up for both buttons)
                        if (event.action == KeyEvent.ACTION_UP) {
                            this.dismiss()
                        }
                        return true 
                    }
                    return super.dispatchKeyEvent(event)
                }
            }
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

            val view = LayoutInflater.from(context).inflate(R.layout.layout_ocr_result, null)
            val tvTranslation = view.findViewById<TextView>(R.id.text_translation)
            val tvFullOcr = view.findViewById<TextView>(R.id.text_full_ocr)
            val containerWords = view.findViewById<LinearLayout>(R.id.container_words)
            val dragHandleContainer = view.findViewById<View>(R.id.drag_handle_container)

            view.findViewById<View>(R.id.btn_copy)?.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR Text", result.text))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }

            tvFullOcr.text = result.text
            tvTranslation.text = "AI 正在思考..."
            tvTranslation.alpha = 0.5f

            result.words.forEach { word ->
                val wordView = TextView(context).apply {
                    // 显示逻辑保持不变
                    text = if (word.surface == word.baseForm) "${word.surface}\n " else "${word.surface}\n[${word.baseForm}]"
                    textSize = 12f
                    setPadding(20, 10, 20, 10)
                    gravity = android.view.Gravity.CENTER

                    // 颜色逻辑保持不变
                    val bgColor = when {
                        word.pos.contains("名") -> "#E3F2FD"
                        word.pos.contains("動") -> "#E8F5E9"
                        word.pos.contains("形容") -> "#FFF3E0"
                        word.pos.contains("感") -> "#F5F5F5"
                        else -> "#FFFFFF"
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(bgColor.toColorInt())
                        cornerRadius = 8f
                        setStroke(1, "#DDDDDD".toColorInt())
                    }

                    // ---【核心修复】：添加点击事件 ---
                    isClickable = true
                    isFocusable = true
                    // 添加水波纹效果 (可选，但在纯代码里写比较麻烦，这里先只加点击逻辑)
                    setOnClickListener {
                        showWordDetailDialog(context, word, result.text) // <--- 调用新函数
                    }
                }

                containerWords.addView(wordView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 8, 16, 8)
                })
            }

            val displayMetrics = context.resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels
            
            view.measure(
                View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val actualDialogHeight = view.measuredHeight

            dialog.setContentView(view)

            dialog.window?.let { window ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setWindowAnimations(0) 
                window.attributes.windowAnimations = 0
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.decorView.setPadding(0, 0, 0, 0)

                val params = window.attributes
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.START

                val safeZone = screenWidth.coerceAtMost(screenHeight) / 8

                if (absClickY - safeZone - actualDialogHeight > 0) {
                    params.y = absClickY - safeZone - actualDialogHeight
                } else if (absClickY + safeZone + actualDialogHeight < screenHeight) {
                    params.y = absClickY + safeZone
                } else {
                    if (absClickY > screenHeight / 2) {
                        params.y = 0
                        params.height = absClickY - safeZone
                    } else {
                        params.y = absClickY + safeZone
                        params.height = screenHeight - (absClickY + safeZone)
                    }
                }
                params.x = (screenWidth - view.measuredWidth) / 2
                window.attributes = params

                val dragState = object {
                    var x = 0f
                    var y = 0f
                    var touchX = 0f
                    var touchY = 0f
                }

                dragHandleContainer.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            dragState.x = params.x.toFloat()
                            dragState.y = params.y.toFloat()
                            dragState.touchX = event.rawX
                            dragState.touchY = event.rawY
                            v.performClick()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = (dragState.x + (event.rawX - dragState.touchX)).toInt()
                            params.y = (dragState.y + (event.rawY - dragState.touchY)).toInt()
                            window.attributes = params 
                            true
                        }
                        else -> false
                    }
                }
            }

            dialog.show()
            fetchTranslationAsync(tvTranslation)
        }
    }

    private fun fetchTranslationAsync(textView: TextView) {
        val prefs = webView.context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("ocr_server_url", "") ?: ""
        val translationUrl = "${serverUrl}/get_translation"
        Thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(translationUrl).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val translation = json.optString("translation")
                Handler(Looper.getMainLooper()).post {
                    textView.text = translation
                    textView.alpha = 1.0f
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post { textView.text = "翻译加载失败" }
            }
        }.start()
    }

    /**
     * 在应用内显示 WebView 对话框，实现不跳出应用浏览网页 (Show WebView dialog in-app)
     * 修改为占据屏幕 70% 高度 (Occupies 70% of screen height)
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showWebViewDialog(context: Context, url: String) {
        // 使用 BottomSheetDialog 实现下半屏显示 (Use BottomSheetDialog for bottom-half display)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        
        val webViewContainer = WebView(context).apply {
            // 设置初始高度为屏幕的 70% (Set initial height to 70% of screen)
            val screenHeight = context.resources.displayMetrics.heightPixels
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenHeight * 0.7).toInt())
            
            settings.javaScriptEnabled = true // 启用 JavaScript (Enable JavaScript)
            settings.domStorageEnabled = true // 启用 DOM 存储 (Enable DOM Storage)
            // 确保点击网页链接时仍在当前 WebView 中打开 (Ensure links open within current WebView)
            webViewClient = android.webkit.WebViewClient() 
            loadUrl(url)

            // ---【核心修复】：解决滑动冲突 (Fix scrolling conflict) ---
            // 当用户在网页上滑动时，禁止 BottomSheet 拦截触摸事件
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 允许用户向下滑动关闭，但优先保证网页垂直滚动
                        // 如果网页可以滚动，则禁止父容器拦截
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 始终禁止父容器在滑动过程中拦截
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false // 返回 false 允许 WebView 继续处理自己的触摸逻辑
            }
        }

        dialog.setContentView(webViewContainer)
        
        // 配置 BottomSheet 行为，设置默认高度为 70% (Configure behavior, set peek height to 70%)
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.7).toInt()
        
        // 监听物理返回键以支持网页回退 (Listen for back key to support web navigation)
        webViewContainer.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && webViewContainer.canGoBack()) {
                webViewContainer.goBack()
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    /**
     * 显示单词详情并提供 Anki 导出选项
     */
    private fun showWordDetailDialog(context: Context, word: JapaneseWord, sourceSentence: String) {
        // 构建显示的详情文本
        val message = StringBuilder()
        message.append("【读音】 ${word.reading}\n")
        message.append("【词性】 ${word.pos}\n\n")
        message.append("【释义】\n")

        // 如果后端传回了释义，就显示；否则提示去查词
        if (word.definition.isNotBlank()) {
            message.append(word.definition)
        } else {
            message.append("(本地词典未收录此词，请点击下方按钮去Web搜索)")
        }

        android.app.AlertDialog.Builder(context)
            .setTitle(word.baseForm) // 标题显示原型
            .setMessage(message.toString())
            .setPositiveButton("存入 Anki") { dialog, _ ->
                exportToAnki(context, word, sourceSentence)
                dialog.dismiss()
            }
            .setNeutralButton("Web搜索") { _, _ ->
                val options = arrayOf("Weblio (日中)","Kotobank (日日)", "Jisho (日英)", "Massif (例句)", "Google 搜索")
                val query = try { URLEncoder.encode(word.baseForm, "UTF-8") } catch (_: Exception) { word.baseForm }

                android.app.AlertDialog.Builder(context)
                    .setTitle("选择搜索引擎")
                    .setItems(options) { _, which ->
                        val url = when (which) {
                            0 -> "https://cjjc.weblio.jp/content/$query"
                            1 -> "https://kotobank.jp/word/$query"
                            2 -> "https://jisho.org/search/$query"
                            3 -> "https://massif.la/ja/search?q=$query"
                            4 -> "https://www.google.com/search?q=$query+意味"
                            else -> ""
                        }
                        if (url.isNotEmpty()) {
                            showWebViewDialog(context, url) // 使用应用内浏览 (Use in-app browsing)
                        }
                    }
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun exportToAnki(context: Context, word: JapaneseWord, sourceSentence: String) {
        val ankiPermission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        val hasPermission = ContextCompat.checkSelfPermission(context, ankiPermission) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            if (context is Activity) {
                val permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
                // 如果系统建议显示理由，说明还没到“不再询问”的地步
                if (ActivityCompat.shouldShowRequestPermissionRationale(context, permission)) {
                    ActivityCompat.requestPermissions(context, arrayOf(permission), 101)
                } else {
                    // 可能是第一次申请，或者是已经点了“不再询问”
                    // 先尝试申请，如果直接失败，onRequestPermissionsResult 会接管弹窗
                    ActivityCompat.requestPermissions(context, arrayOf(permission), 101)
                }
            }
            return
        }

        try {
            // --- 核心修复点 1: 检查 API 连接状态 (Check API Connectivity) ---
            // 首次运行或清除缓存后，deckList 往往是 null，需要触发 AnkiDroid 的内部授权界面
            val currentDecks = api.deckList
            if (currentDecks == null) {
                // 如果拿不到牌组列表，通常是因为 Anki 还没授权给本 App
                // 此时调用任意一个写操作，会触发 AnkiDroid 弹出“是否允许 SuwayomiGO 访问”的对话框
                Toast.makeText(context, "请在 Anki 中点击“允许”后重试", Toast.LENGTH_LONG).show()
                return
            }
            val deckName = "SuwayomiGO"
            val modelName = "SuwayomiGO_Dict_v1"
            val fields = arrayOf("单词", "读音", "释义", "原句", "来源")
            val fullTitle = webView.title ?: ""
            val mangaName = fullTitle.substringBefore(" - Suwayomi")

            // --- 核心修复点 2: 使用更稳健的查询 (Robust ID Lookup) ---
            val deckId = currentDecks.entries.find { it.value == deckName }?.key ?: api.addNewDeck(deckName)

            // 同理，重新获取 modelList
            val currentModels = api.modelList
            val modelId = currentModels?.entries?.find { it.value == modelName }?.key ?: api.addNewCustomModel(
                modelName, fields, arrayOf("Card 1"),
                // 1. 正面模板 (Front): 单词居中 (Centered Word)
                arrayOf("""
            <div style='text-align:center; font-size:35px; color:#3581b2; font-weight:bold; margin-top:20px;'>
                {{单词}}
            </div>
        """.trimIndent()),
                // 2. 背面模板 (Back): 布局微调 (Layout adjustment)
                arrayOf("""
            <div style='text-align:center;'>
                <div style='font-size:35px; color:#3581b2; font-weight:bold;'>{{单词}}</div>
                <div style='font-size:20px; color:#252743; margin-bottom:10px;'>【{{读音}}】</div>
            </div>
            
            <hr>
            
            <div style='text-align:left; padding:0 10px;'>
                <div style='margin-bottom:15px;'>
                    <b style='color:#252743; font-size:13px;'>原句:</b><br>
                    <div style='color:#444; font-style:italic; font-size:16px; margin-top:4px;'>{{原句}}</div>
                </div>
                
                <div style='text-align:right;'>
                    <div style='font-size:13px; color:#252743;'>{{来源}}</div>
                </div>
                
                <div style='background:#f9f9f9; padding:12px; border-radius:8px; border-left:4px solid #D81B60;'>
                    <b style='color:#D81B60; font-size:13px;'>释义:</b><br>
                    <div style='margin-top:4px; line-height:1.5; font-size:16px;'>{{释义}}</div>
                </div>
            </div>
        """.trimIndent()),
                null, null, null
            )


            if (deckId != null && modelId != null) {
                // --- 适配 api-v1.1.0 的 findDuplicateNotes ---
                // 这个方法要求传入 mid (Model ID) 和一个 key (String)
                // 这里的 key 通常指的就是卡片的“首选字段”，即我们的 word.baseForm

                val duplicateNotes = api.findDuplicateNotes(modelId, word.baseForm)

                // 如果返回的列表不为空，说明已经存在重复笔记
                if (duplicateNotes != null && duplicateNotes.isNotEmpty()) {
                    Toast.makeText(context, "Anki 中已存在该词，无需重复导出", Toast.LENGTH_SHORT).show()
                    return // 发现重复，直接跳出
                }
                // --- 去重结束 ---

                val values = arrayOf(
                    word.baseForm,
                    word.reading,
                    word.definition.replace("\n", "<br>"),
                    sourceSentence,
                    mangaName
                )

                val noteId = api.addNote(modelId, deckId, values, null)
                if (noteId != null) {
                    Toast.makeText(context, "已成功导出至 Anki", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "导出失败，请检查 Anki 是否运行", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("AnkiAPI", "Export failed: ${e.message}")
            // --- 核心修复点 3: 细化报错提示 ---
            val errorMsg = e.message ?: ""
            if (errorMsg.contains("permission", ignoreCase = true)) {
                Toast.makeText(context, "请在 Anki 设置中开启 API 权限", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "导出出错，请确认 Anki 已启动并登录", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
