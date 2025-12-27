package com.suwayomi.go.widget
/*
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

//翻页动画备胎1：光晕横扫
class WiperView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: LinearGradient? = null
    private val gradientMatrix = Matrix()
    private var screenWidth = 0f

    // 控制动画进度的变量 (0.0 -> 1.0)
    private var progress = 0f

    init {
        // 初始状态不可见，避免遮挡
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenWidth = w.toFloat()

        // 优化 1: 颜色定义
        // 使用纯白 (Color.WHITE) 在中间，两边是透明
        // 之前如果是半透明白，遮挡效果就不够
        val colors = intArrayOf(
            Color.TRANSPARENT,
            Color.WHITE,       // 核心区域：纯白，不透明
            Color.WHITE,       // 核心区域加宽：连续两个纯白点
            Color.TRANSPARENT
        )

        // 优化 2: 位置分布
        // 调整 positions 让纯白区域占据中间的一定宽度，而不是仅仅一条线
        // 例如：从 0.4 到 0.6 都是纯白，这样光带就有了“厚度”
        val positions = floatArrayOf(0f, 0.4f, 0.6f, 1f)

        gradient = LinearGradient(
            -screenWidth, 0f,
            screenWidth * 2, 0f,
            colors,
            positions,
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 只有在动画进行时才绘制
        if (visibility != VISIBLE || gradient == null) return

        // 根据进度计算偏移量。
        // 进度0时，渐变中心在屏幕最左侧；进度1时，渐变中心在屏幕最右侧。
        val translate = screenWidth * 2 * (progress - 0.5f)

        gradientMatrix.setTranslate(translate, 0f)
        gradient!!.setLocalMatrix(gradientMatrix)

        // 绘制覆盖全屏的矩形，但填充内容是移动的渐变光波
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /**
     * 执行扫描动画
     * @param fromLeftToRight true表示从左往右扫(下一页)，false表示从右往左扫(上一页)
     */
    fun startWipeAnimation(fromLeftToRight: Boolean) {
        // 如果已经在动画中，先取消
        animate().cancel()
        visibility = VISIBLE

        // 设置动画起点和终点
        val startProgress = if (fromLeftToRight) 0f else 1f
        val endProgress = if (fromLeftToRight) 1f else 0f

        ValueAnimator.ofFloat(startProgress, endProgress).apply {
            duration = 1000 // 600ms 的扫描时间比较适中，既有翻页感又不拖沓
            interpolator = AccelerateDecelerateInterpolator() // 先加速后减速，更自然

            addUpdateListener { animation ->
                // 更新进度并触发重绘
                progress = animation.animatedValue as Float
                invalidate()
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 动画结束后隐藏 View
                    visibility = GONE
                    // 重置进度
                    progress = if(fromLeftToRight) 1f else 0f
                }
            })
            start()
        }
    }
}
*/