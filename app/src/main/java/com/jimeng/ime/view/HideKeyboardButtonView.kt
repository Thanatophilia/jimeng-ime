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
 * 候选栏右端的「收起键盘」箭头。
 *
 * 跟 [ExpandButtonView]（展开候选 ⌄）**占同一格、不同时机出现**：
 *
 *   | 状态                        | 右端那一格           | 点了做啥   |
 *   |----------------------------|---------------------|-----------|
 *   | 打字中                       | [ExpandButtonView]  | 展开候选页  |
 *   | 没打字 + 「显示语音按钮」开    | 🎤 麦克风            | 语音输入    |
 *   | 没打字 + 「显示语音按钮」关    | **本类**            | 收起键盘    |
 *
 * ## 为什么两个 ⌄ 要画得不一样
 *
 * 用户特意提的：两格都在最右端、都朝下，光看形状分不清按下去是
 * 「展开候选」还是「把键盘收掉」——一个打开一个关闭，按错了很烦。
 *
 * 所以这一版做三重区分，都朝「浅」的方向走：
 *   1. **笔画细**：1.4dp（展开箭头是 2dp）
 *   2. **颜色淡**：altKeyTextColor 再压到 72% 不透明度
 *   3. **多一道短横线**：箭头下面压一条细横线——Material 的
 *      `keyboard_hide` 图标就是这个样子，一眼认出「把键盘收下去」
 *
 * 展开箭头那边保持原样（粗、实色、光秃秃一个 V），两者放一起绝不会认错。
 */
class HideKeyboardButtonView(context: Context, private val theme: Theme) : View(context) {

    /** 没有候选 / 不可用时灰掉（跟 [ExpandButtonView.dimmed] 同一套观感） */
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

    /** 箭头本体：细笔（1.4dp），这是跟展开箭头最直观的区别 */
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 底下那道短横线：同样细笔，证明「这是收键盘不是开候选」 */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        strokeCap = Paint.Cap.ROUND
    }

    /** 箭头颜色压到 72% 不透明，比展开箭头「浅」一档 */
    private val idleAlpha = (255 * 0.72f).toInt()

    init {
        // 照原版：按压高亮用 keyPressHighlightColor
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
        // 展开箭头的 V 半宽是 5dp，这里收到 4.6dp，整体略小一圈
        val s = dp(4.6f)

        // 整组（V + 横线）比展开箭头矮一点，垂直居中时略偏下，
        // 视觉重心才对得上——展开箭头只有一个 V，本类多一道横线
        val arrowCy = cy - dp(1.2f)
        val barCy = cy + dp(5.4f)
        val barHalfWidth = dp(4.4f)

        path.reset()
        // V 朝下（跟 ExpandButtonView 收起态同向，但更细更小）
        path.moveTo(cx - s, arrowCy - s * 0.45f)
        path.lineTo(cx, arrowCy + s * 0.55f)
        path.lineTo(cx + s, arrowCy - s * 0.45f)

        val color = when {
            dimmed -> if (theme.isDark) 0xFF4A4D50.toInt() else 0xFFC4C7CA.toInt()
            isPressed -> theme.accentKeyBackgroundColor
            else -> theme.altKeyTextColor
        }

        arrowPaint.color = color
        arrowPaint.alpha = if (isPressed || dimmed) 255 else idleAlpha
        canvas.drawPath(path, arrowPaint)

        // 底下那道短横线：keyboard_hide 的「键盘收起来」语义
        barPaint.color = color
        barPaint.alpha = if (isPressed || dimmed) 255 else idleAlpha
        canvas.drawLine(cx - barHalfWidth, barCy, cx + barHalfWidth, barCy, barPaint)
    }

    companion object {
        /** 跟 [ExpandButtonView.WIDTH_DP] 一样宽——两者共用右端同一格 */
        const val WIDTH_DP = 40f
    }
}
