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
 * 此前的实现把它画在同一块 Canvas 上，字一多候选格子就铺到箭头底下、
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

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val path = Path()

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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

        arrowPaint.color = when {
            dimmed -> if (theme.isDark) 0xFF4A4D50.toInt() else 0xFFC4C7CA.toInt()
            isPressed -> theme.accentKeyBackgroundColor
            else -> theme.altKeyTextColor
        }
        canvas.drawPath(path, arrowPaint)
    }

    companion object {
        /** 箭头宽度，照 CandidateUi 的 lParams(dp(40)) */
        const val WIDTH_DP = 40f
    }
}