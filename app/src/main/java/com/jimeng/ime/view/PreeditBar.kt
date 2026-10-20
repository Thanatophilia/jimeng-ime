/*
 * SPDX-License-Identifier: GPL-3.0
 */
package com.jimeng.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager

/**
 * 输入码显示区（preedit）。
 *
 * 布局参照 fcitx5-android 的 PreeditUi：单独一行，浮在候选栏上方，
 * 不和候选词抢横向空间，这也是 Gboard 的样子。
 *
 * 和 fcitx5 的区别：fcitx5 的 preedit 是 FormattedText（带 flags 的分段），
 * 由 JNI 层把 composition.preedit 的每一段连同 flags 一起传上来。
 * 本项目的 nativeGetContext 只返回拼好的整串，所以这里拿不到逐段 flags，
 * 只能整体一种样式。等 JNI 侧补上分段字段后，把 [update] 的入参换成
 * 「分段列表」即可，绘制部分不用改结构。
 *
 * 超长时右对齐（左端裁掉），因为正在敲的永远是串尾，这一条和 Gboard 一致。
 */
class PreeditBar(context: Context) : View(context) {

    companion object {
        /** 码区高度（dp）。比候选栏略矮，有输入时才占位 */
        const val HEIGHT_DP = 34f

        /** 左右内边距（dp） */
        private const val PADDING_DP = 14f
    }

    private val theme: Theme = ThemeManager.activeTheme
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var text: String = ""

    /** 是否有内容。没有内容时高度会被压成 0，不占键盘空间 */
    private var active = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(17f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = theme.candidateTextColor
    }

    init {
        setBackgroundColor(theme.keyboardColor)
        // 码区不接受触摸，事件直接放行
        isClickable = false
        isFocusable = false
    }

    /**
     * 更新码区内容。
     *
     * @param text 当前的输入码串（rime 的 composition.preedit 原样）
     */
    fun update(text: String) {
        if (this.text == text) return
        this.text = text
        active = text.isNotEmpty()
        // 有内容才占高度，没内容把空间还给键盘
        requestLayout()
        invalidate()
    }

    fun clear() = update("")

    /**
     * 高度恒定，始终占满 [HEIGHT_DP]。
     *
     * 这里**不能**按有无内容收缩：本项目的输入法窗口是 WRAP_CONTENT 高度、
     * 贴屏幕底对齐，窗口一变高，键盘就会被顶上去 34dp，打字时整块键盘跳一下。
     * Gboard 的提示条同理，是常驻的固定条。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(HEIGHT_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

        if (!active) {
            // 空状态：这一行常驻，那就别让它空着难看，
            // 给一句淡提示（原来这句在候选栏里，候选栏现在只管候选了）
            textPaint.color = theme.candidateCommentColor
            textPaint.alpha = 90
            canvas.drawText("霁梦输入法", dp(PADDING_DP), baseline, textPaint)
            textPaint.alpha = 255
            textPaint.color = theme.candidateTextColor
            return
        }

        val available = width - dp(PADDING_DP) * 2
        if (available <= 0f) return

        val tw = textPaint.measureText(text)

        // 超长时右对齐，把左边裁掉——正在敲的是串尾，必须能看见
        val drawX = if (tw > available) {
            dp(PADDING_DP) - (tw - available)
        } else {
            dp(PADDING_DP)
        }
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), h)
        canvas.drawText(text, drawX, baseline, textPaint)
        canvas.restore()
    }
}
