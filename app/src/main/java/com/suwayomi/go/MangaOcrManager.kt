package com.suwayomi.go

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
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
import kotlin.math.hypot
import androidx.core.net.toUri

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
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_UP) {
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
            .setNeutralButton("更多查询") { _, _ ->
                val options = arrayOf("Moji 辞书", "Weblio (日日)", "Google 搜索", "沪江小 D")
                val query = word.baseForm

                android.app.AlertDialog.Builder(context)
                    .setTitle("选择搜索引擎")
                    .setItems(options) { _, which ->
                        val url = when (which) {
                            0 -> "https://www.mojidict.com/details/$query"
                            1 -> "https://www.weblio.jp/content/$query"
                            2 -> "https://www.google.com/search?q=$query+意味"
                            3 -> "https://dict.hjenglish.com/jp/jc/$query"
                            else -> ""
                        }
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            url.toUri()))
                    }
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun exportToAnki(context: Context, word: JapaneseWord, sourceSentence: String) {
        val permission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        // 检查 Manifest 声明是否被系统认可
        val hasPermission = context.checkCallingOrSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e("AnkiError", "系统权限位未激活")
            // 尝试触发 Anki 的授权界面 (通过查询来触发安全对话框)
            try {
                val intent = android.content.Intent().apply {
                    setClassName("com.ichi2.anki", "com.ichi2.anki.IntentHandler")
                    action = android.content.Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, "test")
                }
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Toast.makeText(context, "请在 Anki 弹窗中选择'总是允许'", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "请确保已安装官方版 AnkiDroid", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // === 1. 构建卡片内容 (HTML 魔法) ===
        // 我们把“单词”放在正面。
        // 把“读音”、“释义”、“例句”通过 HTML 排版全部塞进背面。
        // 这样无论用户用什么模板，看起来都像是一个自定义模板。

        val frontContent = word.baseForm

        val backContent = """
            <div style="text-align:center; margin-bottom:15px;">
                <span style="font-size:24px; color:#555;">${word.reading}</span>
            </div>
            
            <div style="background-color:#F5F5F5; padding:10px; border-radius:5px; margin-bottom:15px; text-align:left;">
                <b style="color:#2196F3; font-size:12px;">释义 DEFINITION</b><br>
                <div style="font-size:16px; margin-top:5px; line-height:1.5;">${word.definition.replace("\n", "<br>")}</div>
            </div>
            
            <div style="border-top:1px dashed #ddd; padding-top:10px; text-align:left;">
                <b style="color:#FF9800; font-size:12px;">来源 CONTEXT</b><br>
                <div style="font-style:italic; color:#666; margin-top:5px;">${sourceSentence}</div>
            </div>
        """.trimIndent()

        // === 2. 尝试静默添加 (Silent Add) ===
        try {
            val uri = android.net.Uri.parse("content://com.ichi2.anki.flashcards/notes")
            val values = android.content.ContentValues().apply {
                put("deck_name", "Manga_OCR_Cards") // 自动归类到这个牌组
                put("model_name", "Basic")          // 使用最基础的 Basic 模板 (Front/Back)

                // 关键：Basic 只有两个字段，我们用 \u001f 分隔
                put("fields", "$frontContent\u001f$backContent")

                put("tags", "MangaOCR")
            }

            val resultUri = context.contentResolver.insert(uri, values)
            if (resultUri != null) {
                Toast.makeText(context, "✅ 已存入 Anki", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Log.e("MangaOcr", "静默添加失败: ${e.message}", e)
        }

        // === 3. 失败后跳转 (Intent Fallback) ===
        // 只有当静默失败时才跳出
        try {
            val intent = android.content.Intent().apply {
                setClassName("com.ichi2.anki", "com.ichi2.anki.IntentHandler")
                action = android.content.Intent.ACTION_SEND
                type = "text/plain"

                // 同样使用 HTML 拼接策略
                val fields = arrayOf(frontContent, backContent)
                putExtra("com.ichi2.anki.extra.FIELDS", fields)
                putExtra("com.ichi2.anki.extra.SOURCE", "MangaOCR")
                putExtra(android.content.Intent.EXTRA_TEXT, frontContent)
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "启动 Anki 失败", Toast.LENGTH_SHORT).show()
        }
    }
}
