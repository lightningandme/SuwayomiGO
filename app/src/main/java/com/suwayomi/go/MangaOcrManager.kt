package com.suwayomi.go

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.google.android.material.bottomsheet.BottomSheetDialog
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
import java.io.OutputStream

/**
 * 专门处理漫画 OCR 逻辑的管理类
 */
// 定义单词数据结构
data class JapaneseWord(
    val surface: String,
    val baseForm: String,
    val pos: String,
    val reading: String
)

data class OcrResponse(
    val text: String,
    val translation: String, // 核心：增加这一行
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
                translation = json.optString("translation"), // 解析翻译
                words = wordList
            )
        }
    }
}

class MangaOcrManager(private val webView: WebView) {

    // 定义切图大小
    private val cropSize = 600
    private val client = OkHttpClient() // 单例客户端

    /**
     * 执行局部切图的核心函数
     * @param clickX 点击的 X 坐标
     * @param clickY 点击的 Y 坐标
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

        // 核心计算：点击点在小图里的相对位置
        val relX = clickX - left
        val relY = clickY - top

        val cropped = Bitmap.createBitmap(fullBitmap, left, top, actualWidth, actualHeight)

        // 调试用：保存到本地
        saveBitmapToDownload(webView.context, cropped, "crop_${System.currentTimeMillis()}")

        // 转码并发送
        val base64String = bitmapToBase64(cropped)
        // 修改这里的调用，传入相对坐标
        sendToOcrServer(base64String, relX, relY)
    }

    // 1. 修改参数列表，接收 x 和 y
    private fun sendToOcrServer(base64Image: String, relX: Int, relY: Int) {
        // 从 SharedPreferences 获取最新的 OCR 服务器地址 (Fetch the latest OCR server URL)
        val prefs = webView.context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("ocr_server_url", "http://192.168.137.1:12233/ocr") ?: ""

        if (serverUrl.isEmpty()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(webView.context, "请先在“更多设置”中配置 OCR 服务器地址", Toast.LENGTH_LONG).show()
            }
            return
        }

        // 2. 在 JSON 中加入坐标
        val json = JSONObject().apply {
            put("image", base64Image)
            put("x", relX) // 传入相对坐标 X
            put("y", relY) // 传入相对坐标 Y
        }.toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        try {
            val request = Request.Builder().url(serverUrl).post(body).build()

            Log.d("MangaOcr", "正在发送请求 (含坐标 $relX, $relY) 到: $serverUrl")

            client.newCall(request).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            Log.e("MangaOcr", "服务器返回错误: ${response.code}")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(webView.context, "OCR 服务器响应错误: ${response.code}", Toast.LENGTH_SHORT).show()
                            }
                            return
                        }

                        val bodyString = response.body?.string() ?: ""
                        if (bodyString.isEmpty()) {
                            Log.e("MangaOcr", "服务器返回内容为空")
                            return
                        }

                        try {
                            val ocrResult = OcrResponse.fromJson(bodyString)
                            Log.d("MangaOcrResult", "成功识别: ${ocrResult.text}")

                            Handler(Looper.getMainLooper()).post {
                                showResultBottomSheet(ocrResult)
                            }
                        } catch (e: Exception) {
                            Log.e("MangaOcr", "JSON 解析失败: ${e.message}")
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    Log.e("MangaOcr", "网络请求失败: ${e.message}")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(webView.context, "无法连接到 OCR 服务器，请检查地址", Toast.LENGTH_LONG).show()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MangaOcr", "URL 格式错误: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(webView.context, "OCR 地址格式错误", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    fun saveBitmapToDownload(context: Context, bitmap: Bitmap, fileName: String) {
        val relativePath = Environment.DIRECTORY_PICTURES + "/MangaCrop"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var uri: Uri? = null

        try {
            uri = resolver.insert(contentUri, contentValues)
            uri?.let {
                val outputStream: OutputStream? = resolver.openOutputStream(it)
                outputStream?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }
            }
        } catch (e: Exception) {
            Log.e("MangaOcr", "保存失败: ${e.message}")
            uri?.let { resolver.delete(it, null, null) }
        }
    }

    private fun showResultBottomSheet(result: OcrResponse) {
        Handler(Looper.getMainLooper()).post {
            val context = webView.context
            val dialog = BottomSheetDialog(context)

            // --- 墨水屏优化 (E-ink Optimization) ---
            dialog.window?.let { window ->
                // 1. 禁用背景变暗：保持背景画面不变 (Disable dimming to keep background clear)
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                // 2. 禁用弹出动画：减少墨水屏残影 (Disable window animations to reduce ghosting)
                window.setWindowAnimations(0)
            }

            val view = LayoutInflater.from(context).inflate(R.layout.layout_ocr_result, null)

            val tvTranslation = view.findViewById<TextView>(R.id.text_translation)
            val tvFullOcr = view.findViewById<TextView>(R.id.text_full_ocr)
            val containerWords = view.findViewById<LinearLayout>(R.id.container_words)

            // 实现复制按钮逻辑 (Implement copy button logic)
            view.findViewById<android.view.View>(R.id.btn_copy)?.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("OCR Text", result.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }

            // 1. 立即显示本地已有的数据 (OCR 和 分词)
            tvFullOcr.text = result.text
            tvTranslation.text = "AI 正在思考..." // 预设文案
            tvTranslation.alpha = 0.5f

            // 3. 动态渲染单词卡片（带词性上色）
            result.words.forEach { word ->
                val wordView = TextView(context).apply {
                    // 如果表面形和原型一致，就只显示一个，更清爽
                    text = if (word.surface == word.baseForm) {
                        "${word.surface}\n "
                    } else {
                        "${word.surface}\n[${word.baseForm}]"
                    }

                    textSize = 12f
                    setPadding(25, 12, 25, 12)
                    gravity = android.view.Gravity.CENTER

                    // --- 词性上色逻辑 ---
                    val bgColor = when {
                        word.pos.contains("名") -> "#E3F2FD" // 名词：淡蓝色
                        word.pos.contains("动") -> "#E8F5E9" // 动词：淡绿色
                        word.pos.contains("形容") -> "#FFF3E0" // 形容词：淡橙色
                        word.pos.contains("助") -> "#F5F5F5" // 助词：浅灰色
                        else -> "#FFFFFF"
                    }

                    val strokeColor = when {
                        word.pos.contains("名") -> "#2196F3"
                        word.pos.contains("动") -> "#4CAF50"
                        else -> "#CCCCCC"
                    }

                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(bgColor.toColorInt())
                        cornerRadius = 12f
                        setStroke(2, strokeColor.toColorInt())
                    }

                    setOnClickListener {
                        // 点击显示读音 and 详细词性
                        val detail = "【${word.reading}】\n类型: ${word.pos}"
                        Toast.makeText(context, detail, Toast.LENGTH_SHORT).show()
                    }
                }

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 10, 20, 10)
                }
                containerWords.addView(wordView, params)
            }


            dialog.setContentView(view)
            dialog.show()
            // 2. 核心：发起异步翻译请求
            fetchTranslationAsync(tvTranslation)
        }
    }

    private fun fetchTranslationAsync(textView: TextView) {
        // 这里使用你已有的网络库（比如 OkHttp 或简单的 Thread）
        // 从 SharedPreferences 获取最新的 OCR 服务器地址 (Fetch the latest OCR server URL)
        val prefs = webView.context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("ocr_server_url", "http://192.168.137.1:12233/ocr") ?: ""
        val translationUrl = serverUrl.replace("/ocr", "/get_translation")
        Thread {
            try {
                // 请求后端的 /get_translation 接口
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(translationUrl)
                    .build()

                val response = client.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "")
                val translation = json.optString("translation")

                // 回到主线程更新 UI
                Handler(Looper.getMainLooper()).post {
                    textView.text = translation
                    textView.alpha = 1.0f // 恢复亮度
                    // 墨水屏优化：移除淡入动画，直接刷新文字 (Remove fade-in animation for E-ink)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    textView.text = "翻译加载失败"
                }
            }
        }.start()
    }
}
