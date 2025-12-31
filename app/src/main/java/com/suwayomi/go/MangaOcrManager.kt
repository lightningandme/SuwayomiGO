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

/**
 * 专门处理漫画 OCR 逻辑的管理类
 */
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
            override fun onFailure(call: Call, e: IOException) {
                Log.e("MangaOcr", "网络请求失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e("MangaOcr", "服务器返回错误: ${response.code}")
                        return
                    }

                    // Use ?. to safely access body and provide a fallback empty string
                    val resultJson = response.body?.string() ?: ""

                    if (resultJson.isNotEmpty()) {
                        val text = JSONObject(resultJson).optString("text", "")
                        Log.d("MangaOcr", "--- 识别成功 ---")
                        Log.d("MangaOcr", "原文内容: $text")
                    } else {
                        Log.e("MangaOcr", "响应体为空")
                    }

                    // 如果需要在主线程处理结果（如弹窗），请解开下面注释
                    /*
                    Handler(Looper.getMainLooper()).post {
                        // 在这里更新 UI
                    }
                    */
                }
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
}
