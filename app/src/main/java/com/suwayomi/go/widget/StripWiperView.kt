package com.suwayomi.go.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.concurrent.thread

class StripWiperView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val stripCount = 3
    private val interval = 300L      // 墨水屏稍微慢点更稳
    private val holdDuration = 800L
    private val whitePaint = Paint().apply { color = Color.WHITE }

    init {
        // 【关键】设置 Surface 背景透明，并置于顶层
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
        holder.addCallback(this)
    }

    fun startWipeAnimation(fromLeftToRight: Boolean) {
        // 在独立线程中绘制，完全无视系统 UI 限制
        thread {
            val startTime = System.currentTimeMillis()
            val totalDuration = (stripCount - 1) * interval + holdDuration
            val stripWidth = width.toFloat() / stripCount

            while (System.currentTimeMillis() - startTime < totalDuration) {
                // 1. 获取画布（如果 Surface 还没准备好，则跳过）
                val canvas = holder.lockCanvas() ?: continue
                val currentTime = System.currentTimeMillis() - startTime

                try {
                    // 2. 清除上一帧：必须先刷成透明，否则之前的白色块会重叠
                    canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

                    // 3. 遍历条带
                    for (i in 0 until stripCount) {
                        val sTime = i * interval
                        val eTime = sTime + holdDuration

                        if (currentTime in sTime..eTime) {
                            // 计算方向
                            val visualIndex = if (fromLeftToRight) i else (stripCount - 1) - i
                            val left = visualIndex * stripWidth

                            // 4. 只绘制纯白色块
                            canvas.drawRect(left, 0f, left + stripWidth, height.toFloat(), whitePaint)
                        }
                    }
                } finally {
                    // 5. 解锁并提交内容
                    holder.unlockCanvasAndPost(canvas)
                }

                // 墨水屏不需要太高帧率，适当休眠减少 CPU 占用
                Thread.sleep(30)
            }

            // 6. 动画彻底结束，最后清理一次画布，确保恢复透明
            val lastCanvas = holder.lockCanvas()
            if (lastCanvas != null) {
                lastCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                holder.unlockCanvasAndPost(lastCanvas)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}