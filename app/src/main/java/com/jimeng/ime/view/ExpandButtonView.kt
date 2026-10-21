package com.jimeng.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import com.jimeng.ime.kb.theme.Theme

/**
 * 候选栏右侧的展开箭头。照抄 fcitx5-android 的 ToolButton
 * （input/bar/ui/ToolButton.kt）+ CandidateUi 里的 `lParams(dp(40))`。
 *
 * 原版是「一个 40dp 的独立控件，贴在最右边」，和候选区在布局上物理隔开。
 * 小雅之前把它画在同一块 Canvas 上，字一多候选格子就铺到箭头底下、
 * 把点击抢走了——现在它自己是一个 View，抢不走了。
 *
 * 图标是 Material 的 expand_more（⌄），展开后换成 expand_less（⌃）。
 */
class ExpandButtonView(context: Context, private val theme: Theme) : View(context) {

    /** 展开页开着的时候箭头朝上 */
    var expanded = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 没有候选的时候灰掉（照原版的 disabled 观感） */
    var dimmed = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 工具栏模式：画左右尖括号（→ 展开 / ← 收起），不画上下箭头。
     * 主人给的 Material arrow_back_ios / arrow_forward_ios 那对图形。
     */
    var horizontal = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val path = Path()

    /** arrow_back_ios（←），主人给的 960 视口图形 */
    private val backArrowPath = Path().apply {
        moveTo(400f, 880f); lineTo(0f, 480f); lineTo(400f, 80f)
        lineTo(471f, 151f); lineTo(142f, 480f); lineTo(471f, 809f); close()
    }

    /** arrow_forward_ios（→），同一对图标 */
    private val forwardArrowPath = Path().apply {
        moveTo(321f, 880f); lineTo(250f, 809f); lineTo(579f, 480f)
        lineTo(250f, 151f); lineTo(321f, 80f); lineTo(721f, 480f); close()
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 尖括号是实心图形，用 FILL 画（跟线条箭头分开） */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 图标配色：灰掉 / 按压 / 普通，三种状态 */
    private fun iconColor(): Int = when {
        dimmed -> if (theme.isDark) 0xFF4A4D50.toInt() else 0xFFC4C7CA.toInt()
        isPressed -> theme.accentKeyBackgroundColor
        else -> theme.altKeyTextColor
    }

    init {
        // 照原版 pressHighlightDrawable(theme.keyPressHighlightColor)
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                ColorDrawable(theme.keyPressHighlightColor)
            )
        }
        isClickable = true
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        // ---- 工具栏模式：画左右尖括号（→ 展开 / ← 收起）----
        if (horizontal) {
            // 960 视口里图形占 800 单位高，缩放成 20dp：
            // 跟工具栏图标「24dp 格子里 20dp 内容」一个占比，视觉一致
            val scale = dp(20f) / 800f
            canvas.save()
            canvas.translate(cx - 480f * scale, cy - 480f * scale)
            canvas.scale(scale, scale)
            fillPaint.color = iconColor()
            // 收起（→）= 工具栏还没展开；展开（←）= 正在显示工具栏
            canvas.drawPath(if (expanded) backArrowPath else forwardArrowPath, fillPaint)
            canvas.restore()
            return
        }

        // ---- 候选栏模式：上下箭头（expand_more / expand_less）----
        // Material expand_more 图标 24dp 里箭头宽 12dp，这里按 10dp 画，占比接近
        val s = dp(5f)

        path.reset()
        if (expanded) {
            // ⌃ 展开着：箭头朝上（expand_less）
            path.moveTo(cx - s, cy + s * 0.5f)
            path.lineTo(cx, cy - s * 0.5f)
            path.lineTo(cx + s, cy + s * 0.5f)
        } else {
            // ⌄ 收起：箭头朝下（expand_more）
            path.moveTo(cx - s, cy - s * 0.5f)
            path.lineTo(cx, cy + s * 0.5f)
            path.lineTo(cx + s, cy - s * 0.5f)
        }

        arrowPaint.color = iconColor()
        canvas.drawPath(path, arrowPaint)
    }

    companion object {
        /** 箭头宽度，照 CandidateUi 的 lParams(dp(40)) */
        const val WIDTH_DP = 40f
    }
}