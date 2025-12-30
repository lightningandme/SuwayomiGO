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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import androidx.core.graphics.createBitmap

/**
 * 专门处理漫画 OCR 逻辑的管理类
 */
class MangaOcrManager(private val webView: WebView) {

    // 定义切图大小
    private val cropSize = 400

    /**
     * 执行局部切图的核心函数
     * @param clickX 点击的 X 坐标
     * @param clickY 点击的 Y 坐标
     * @param onBitmapReady 成功切图后的回调，返回 Base64 字符串
     */
    fun processCrop(clickX: Int, clickY: Int, onBitmapReady: (String) -> Unit) {
        if (webView.width <= 0 || webView.height <= 0) return

        // 1. 创建与 WebView 等大的 Bitmap
        val fullBitmap = createBitmap(webView.width, webView.height)

        // 2. 抓取画面：WebView 无法直接传给 PixelCopy.request
        // 使用 Canvas 绘制方式更通用且兼容 API 24
        val canvas = Canvas(fullBitmap)
        webView.draw(canvas)

        // 3. 计算裁剪范围并增加越界保护
        val half = cropSize / 2
        val actualWidth = cropSize.coerceAtMost(webView.width)
        val actualHeight = cropSize.coerceAtMost(webView.height)
        val left = (clickX - half).coerceIn(0, (webView.width - actualWidth).coerceAtLeast(0))
        val top = (clickY - half).coerceIn(0, (webView.height - actualHeight).coerceAtLeast(0))

        // 4. 执行裁剪
        val cropped = Bitmap.createBitmap(fullBitmap, left, top, actualWidth, actualHeight)

        // 【调试代码】保存到相册
        val timestamp = System.currentTimeMillis()
        saveBitmapToDownload(webView.context, cropped, "crop_$timestamp")

        // 5. 转码并回调给主界面
        onBitmapReady(bitmapToBase64(cropped))
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
