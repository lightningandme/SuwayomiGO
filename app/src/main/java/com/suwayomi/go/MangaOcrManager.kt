package com.suwayomi.go

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.core.graphics.createBitmap
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.Toast
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.toColorInt

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
    val words: List<JapaneseWord>
) {
    companion object {
        fun fromJson(jsonString: String): OcrResponse {
            val json = JSONObject(jsonString)
            val text = json.optString("text", "")
            val wordsArray = json.getJSONArray("words")
            val wordList = mutableListOf<JapaneseWord>()

            for (i in 0 until wordsArray.length()) {
                val w = wordsArray.getJSONObject(i)
                wordList.add(JapaneseWord(
                    surface = w.optString("s"),
                    baseForm = w.optString("b"),
                    pos = w.optString("p"),
                    reading = w.optString("r")
                ))
            }
            return OcrResponse(text, wordList)
        }
    }
}
class MangaOcrManager(private val webView: WebView) {

    // 定义切图大小
    private val cropSize = 400
    private val client = OkHttpClient() // 单例客户端
    private val serverUrl = "http://192.168.137.1:12233/ocr" // 替换为你的真实IP
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

        val cropped = Bitmap.createBitmap(fullBitmap, left, top, actualWidth, actualHeight)

        // 1. 调试用：保存到本地
        saveBitmapToDownload(webView.context, cropped, "crop_${System.currentTimeMillis()}")

        // 2. 转码并发送
        val base64String = bitmapToBase64(cropped)
        sendToOcrServer(base64String)
    }

    private fun sendToOcrServer(base64Image: String) {
        // 构建请求体 JSON
        val json = JSONObject().apply {
            put("image", base64Image)
        }.toString()

        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(serverUrl).post(body).build()

        Log.d("MangaOcr", "正在发送请求到: $serverUrl")

        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // .use 会自动关闭 response，防止内存泄漏
                response.use {
                    if (!response.isSuccessful) {
                        Log.e("MangaOcr", "服务器返回错误: ${response.code}")
                        return
                    }

                    // 1. 获取后端返回的 JSON 字符串
                    val bodyString = response.body?.string() ?: ""

                    if (bodyString.isEmpty()) {
                        Log.e("MangaOcr", "服务器返回内容为空")
                        return
                    }

                    try {
                        // 2. 解析 JSON 到 OcrResponse 对象
                        val ocrResult = OcrResponse.fromJson(bodyString)

                        Log.d("MangaOcrResult", "成功识别: ${ocrResult.text}")

                        // 3. 切换回主线程 (UI Thread) 来显示 BottomSheet
                        Handler(Looper.getMainLooper()).post {
                            showResultBottomSheet(ocrResult)
                        }

                    } catch (e: Exception) {
                        Log.e("MangaOcr", "JSON 解析失败: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                Log.e("MangaOcr", "网络请求失败: ${e.message}")
            }
        })
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
                put(MediaStore.MediaColumns.IS_PENDING, 1) // 锁定文件，写入中
            }
        }

        val resolver = context.contentResolver
        // 使用 Images.Media 兼容 API 24
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        var uri: Uri? = null

        try {
            uri = resolver.insert(contentUri, contentValues)

            uri?.let {
                val outputStream: OutputStream? = resolver.openOutputStream(it)
                outputStream?.use { stream ->
                    // 写入图片数据
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0) // 解锁文件
                    resolver.update(it, contentValues, null, null)
                }
                Log.d("MangaOcr", "图片已保存: $fileName.jpg")
            }
        } catch (e: Exception) {
            Log.e("MangaOcr", "保存失败: ${e.message}")
            uri?.let { resolver.delete(it, null, null) }
        }
    }

    private fun showResultBottomSheet(result: OcrResponse) {
        // 切换到主线程操作 UI
        Handler(Looper.getMainLooper()).post {
            val context = webView.context
            val dialog = BottomSheetDialog(context)
            val view = LayoutInflater.from(context).inflate(R.layout.layout_ocr_result, null)

            val tvFullOcr = view.findViewById<TextView>(R.id.text_full_ocr)
            val containerWords = view.findViewById<LinearLayout>(R.id.container_words)
            val btnCopy = view.findViewById<Button>(R.id.btn_copy)

            tvFullOcr.text = result.text

// 动态添加单词卡片
            result.words.forEach { word ->
                val wordView = TextView(context).apply {
                    // 表面形 (换行) 原型
                    text = "${word.surface}\n[${word.baseForm}]"

                    // 修复 sp 报错：直接赋值 12f，系统默认单位就是 SP
                    textSize = 12f

                    setTextColor(android.graphics.Color.BLACK)
                    setPadding(30, 15, 30, 15)

                    // 给卡片加一个简单的背景边框
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        setColor("#F5F5F5".toColorInt()) // 浅灰色背景
                        cornerRadius = 8f // 圆角
                        setStroke(2, "#CCCCCC".toColorInt()) // 灰色边框
                    }
                    background = shape

                    setOnClickListener {
                        Toast.makeText(context, "词性: ${word.pos}\n读音: ${word.reading}", Toast.LENGTH_SHORT).show()
                    }
                }

                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 20, 0) // 单词之间的间距
                }
                containerWords.addView(wordView, params)
            }

            btnCopy.setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("OCR Result", result.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }

            dialog.setContentView(view)
            dialog.show()
        }
    }
}
