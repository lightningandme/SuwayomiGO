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
import com.ichi2.anki.api.AddContentApi
import android.app.Activity
import android.content.Intent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

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

    // 1. 定义 API 实例 (参考 AnkiDroidHelper 的构造方式)
    private val api by lazy { AddContentApi(webView.context.applicationContext) }
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
        // 定义 Anki 的专用权限字符串
        val ankiPermission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

        // --- 1. 权限检测 (双重检查) ---
        // 检查 A: 是否拥有系统层面的权限 (Jidoujisho 逻辑)
        val hasSystemPermission = ContextCompat.checkSelfPermission(context, ankiPermission) == PackageManager.PERMISSION_GRANTED

        // 检查 B: API 是否真的可用 (尝试调用)
        var isApiReady = false
        if (hasSystemPermission) {
            try {
                api.deckList // 尝试一次调用
                isApiReady = true
            } catch (e: Exception) {
                // 即使有系统权限，API 也可能抛异常
                isApiReady = false
            }
        }

        // --- 2. 如果没权限，发起申请 ---
        if (!hasSystemPermission || !isApiReady) {
            if (context is Activity) {
                Toast.makeText(context, "正在请求 Anki 授权...", Toast.LENGTH_SHORT).show()

                // 优先尝试方案 A：申请 Android 系统权限 (模仿 Jidoujisho)
                // 这通常会弹出一个系统级的对话框
                ActivityCompat.requestPermissions(context, arrayOf(ankiPermission), 101)

                // 只有当 API 明确抛出 SecurityException 时，我们才尝试 Intent 跳转 (作为备选)
                // 但为了防止冲突，这里我们依赖 MainActivity 的 onRequestPermissionsResult 来处理后续

                // 如果你想双管齐下，可以把 Intent 逻辑放在 catch 块里，但通常 ActivityCompat 就够了
                return
            }
            return
        }

        // --- 3. 执行导出 (权限已就绪) ---
        try {
            val deckName = "MangaOCR_Study"
            val modelName = "Manga_Dict_v1"
            val fields = arrayOf("单词", "读音", "释义", "例句")

            // 查找或创建 Deck
            var finalDeckId: Long? = null
            val deckList = api.deckList
            if (deckList != null) {
                for (entry in deckList.entries) {
                    if (entry.value == deckName) {
                        finalDeckId = entry.key
                        break
                    }
                }
            }
            if (finalDeckId == null) {
                finalDeckId = api.addNewDeck(deckName)
            }

            // 查找或创建 Model
            var finalModelId: Long? = null
            val modelList = api.modelList
            if (modelList != null) {
                for (entry in modelList.entries) {
                    if (entry.value == modelName) {
                        finalModelId = entry.key
                        break
                    }
                }
            }

            if (finalModelId == null) {
                finalModelId = api.addNewCustomModel(
                    modelName,
                    fields,
                    arrayOf("Card 1"),
                    arrayOf("<div style='font-size:30px; color:#1E88E5;'>{{单词}}</div>"),
                    arrayOf("""
                    <div style='font-size:30px; color:#1E88E5;'>{{单词}}</div>
                    <div style='font-size:18px; color:#666;'>【{{读音}}】</div>
                    <hr>
                    <div style='text-align:left; background:#f5f5f5; padding:15px; border-radius:10px;'>
                        <b style='color:#D81B60;'>释义:</b><br>{{释义}}
                    </div>
                    <div style='margin-top:15px; color:gray; font-size:12px;'>
                        Source: {{例句}}
                    </div>
                """.trimIndent()),
                    null, null, null
                )
            }

            if (finalDeckId != null && finalModelId != null) {
                val fieldValues = arrayOf(
                    word.baseForm,
                    word.reading,
                    word.definition.replace("\n", "<br>"),
                    sourceSentence
                )
                // tags 传 null 即可
                val noteId = api.addNote(finalModelId, finalDeckId, fieldValues, null)

                if (noteId != null) {
                    Toast.makeText(context, "成功导出到 Anki", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            Log.e("MangaOCR", "Export error", e)
            // 如果这里报错 SecurityException，说明 ActivityCompat 申请还没生效，提示用户重试
            if (e is SecurityException) {
                Toast.makeText(context, "权限同步中，请再点一次", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "错误: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
