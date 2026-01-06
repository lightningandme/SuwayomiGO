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

/**
 * 专门处理漫画 OCR 逻辑的管理类 (Management class for Manga OCR logic)
 */
data class JapaneseWord(
    val surface: String,
    val baseForm: String,
    val pos: String,
    val reading: String
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
            for (i in 0 until wordsArray.length()) {
                val w = wordsArray.getJSONObject(i)
                wordList.add(JapaneseWord(
                    w.optString("s"), w.optString("b"),
                    w.optString("p"), w.optString("r")
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
                    text = if (word.surface == word.baseForm) "${word.surface}\n " else "${word.surface}\n[${word.baseForm}]"
                    textSize = 12f
                    setPadding(20, 10, 20, 10)
                    gravity = android.view.Gravity.CENTER
                    val bgColor = when {
                        word.pos.contains("名") -> "#E3F2FD"
                        word.pos.contains("动") -> "#E8F5E9"
                        word.pos.contains("形容") -> "#FFF3E0"
                        word.pos.contains("助") -> "#F5F5F5"
                        else -> "#FFFFFF"
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(bgColor.toColorInt())
                        cornerRadius = 8f
                        setStroke(1, "#DDDDDD".toColorInt())
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
}
