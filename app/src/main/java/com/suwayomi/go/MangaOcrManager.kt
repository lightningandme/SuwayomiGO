package com.suwayomi.go

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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

    private fun sendToOcrServer(base64Image: String, relX: Int, relY: Int, absClickY: Int) {
        // 读取 WebView 的 Title 并提取漫画名称 (Read WebView title and extract manga name)
        // 取 ": Chapter" 之前的所有字符 (Get all characters before ": Chapter")
        val fullTitle = webView.title ?: ""
        val mangaName = fullTitle.substringBefore(": Chapter")
        Log.d("MangaOcr", mangaName)

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
            put("mangaName", mangaName) // 将提取的漫画名称加入请求字段 (Add extracted manga name to request)
        }.toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        try {
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
                        } catch (e: Exception) {
                            Log.e("MangaOcr", "Parse failed")
                        }
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("MangaOcr", "Network error")
                }
            })
        } catch (e: Exception) {
            Log.e("MangaOcr", "Request error")
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showResultBottomSheet(result: OcrResponse, absClickY: Int) {
        Handler(Looper.getMainLooper()).post {
            val context = webView.context
            
            // --- 彻底拦截：通过匿名类重写 dispatchKeyEvent (Full interception via override) ---
            val dialog = object : android.app.Dialog(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val keyCode = event.keyCode
                    // 拦截音量上键和下键，防止在弹窗显示时触发翻页 (Intercept Volume keys to prevent paging)
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        // 核心修复：如果在 ACTION_DOWN 时就 dismiss，随后的 ACTION_UP 事件会因弹窗消失而传递给 Activity，
                        // 从而触发 Activity 中的翻页逻辑。因此我们改为在 ACTION_UP 时才执行 dismiss。
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_UP) {
                            this.dismiss()
                        }
                        // 关键：无论按下还是抬起，都返回 true，表示该事件被“吞掉”了，不会传给底层组件
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

            // --- 核心逻辑：预先测量并禁用动画 (Core logic: pre-measure and disable animation) ---
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
                // 强制设为自适应宽高，方便自由拖动 (Set to wrap_content for free dragging)
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.START

                val safeZone = screenWidth.coerceAtMost(screenHeight) / 8

                // 初始定位 (Initial Positioning)
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

                // --- 实现手动拖动功能 (Implement manual dragging) ---
                var initialX = 0f
                var initialY = 0f
                var initialTouchX = 0f
                var initialTouchY = 0f

                dragHandleContainer.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x.toFloat()
                            initialY = params.y.toFloat()
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            v.performClick()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                            params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                            window.attributes = params // 实时更新 (Real-time update)
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
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { textView.text = "翻译加载失败" }
            }
        }.start()
    }
}
