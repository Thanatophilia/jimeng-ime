/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.withSave
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("AppCompatCustomView")
class AutoScaleTextView @JvmOverloads constructor(
    context: Context?,
    attributeSet: AttributeSet? = null
) : TextView(context, attributeSet) {

    enum class Mode {
        /**
         * do not scale or ellipse text, overflow when cannot fit width
         */
        None,

        /**
         * only scale in X axis, makes text looks "condensed" or "slim"
         */
        Horizontal,

        /**
         * scale both in X and Y axis, align center vertically
         */
        Proportional
    }

    var scaleMode = Mode.None

    private var needsMeasureText = true
    private val fontMetrics = Paint.FontMetrics()
    private val textBounds = Rect()

    private var needsCalculateTransform = true
    private var baselineX = 0.0f
    private var baselineY = 0.0f
    private var textScaleX = 1.0f
    private var textScaleY = 1.0f

    override fun setText(charSequence: CharSequence?, bufferType: BufferType) {
        if (charSequence == null || !text.contentEquals(charSequence)) {
            needsMeasureText = true
            needsCalculateTransform = true
            super.setText(charSequence, bufferType)
            requestLayout()
            invalidate()
        }
    }

    /**
     * 换字体时必须让测量失效。
     *
     * 原版 fcitx5 只用系统字体，所以没有这个方法；但空格键要换字体，
     * 不同字体的「字宽 / 字形边界 / fontMetrics」全都不一样。
     * 只调 setTypeface 不重新测量的话，字会偏心到一边、或者缩放的倍数还是旧字体的，
     * 看起来就是「换了字体但位置歪了」。
     */
    override fun setTypeface(tf: Typeface?) {
        super.setTypeface(tf)
        needsMeasureText = true
        needsCalculateTransform = true
        requestLayout()
        invalidate()
    }

    /**
     * 改字号时同样要作废测量结果。
     *
     * 原版 fcitx5 的键位字号是写死在 KeyDef 里的，构造时 setTextSize 一次就再没变过，
     * 所以基类没处理这件事。但空格键的字号是运行时可切的（开关一开就从小号切成大号），
     * 如果只调 super.setTextSize 而不清 [needsMeasureText]，[textBounds]/[fontMetrics]
     * 还是按旧字号算出来的，[onMeasure] 报出去的高度、[calculateTransform] 用的宽度
     * 全部是旧值 —— 表现就是「字号设大了但字一点没变」。
     */
    override fun setTextSize(size: Float) {
        super.setTextSize(size)
        invalidateTextMetrics()
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        invalidateTextMetrics()
    }

    private fun invalidateTextMetrics() {
        needsMeasureText = true
        needsCalculateTransform = true
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val width = measureTextBounds().width() + paddingLeft + paddingRight
        val height = ceil(fontMetrics.bottom - fontMetrics.top + paddingTop + paddingBottom).toInt()
        val maxHeight = if (maxHeight >= 0) maxHeight else Int.MAX_VALUE
        val maxWidth = if (maxWidth >= 0) maxWidth else Int.MAX_VALUE
        setMeasuredDimension(
            measure(widthMode, widthSize, min(max(width, minimumWidth), maxWidth)),
            measure(heightMode, heightSize, min(max(height, minimumHeight), maxHeight))
        )
    }

    private fun measure(specMode: Int, specSize: Int, calculatedSize: Int): Int = when (specMode) {
        MeasureSpec.EXACTLY -> specSize
        MeasureSpec.AT_MOST -> min(calculatedSize, specSize)
        else -> calculatedSize
    }

    private fun measureTextBounds(): Rect {
        if (needsMeasureText) {
            val paint = paint
            paint.getFontMetrics(fontMetrics)
            val codePointCount = Character.codePointCount(text, 0, text.length)
            if (codePointCount == 1) {
                // use actual text bounds when there is only one "character",
                // e.g. full-width punctuation
                paint.getTextBounds(text.toString(), 0, text.length, textBounds)
            } else {
                textBounds.set(
                    /* left = */ 0,
                    /* top = */ floor(fontMetrics.top).toInt(),
                    /* right = */ ceil(paint.measureText(text.toString())).toInt(),
                    /* bottom = */ ceil(fontMetrics.bottom).toInt()
                )
            }
            needsMeasureText = false
        }
        return textBounds
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (needsCalculateTransform || changed) {
            calculateTransform(right - left, bottom - top)
            needsCalculateTransform = false
        }
    }

    private fun calculateTransform(viewWidth: Int, viewHeight: Int) {
        val contentWidth: Int = viewWidth - paddingLeft - paddingRight
        val contentHeight: Int = viewHeight - paddingTop - paddingBottom
        measureTextBounds()
        val textLeft: Float = textBounds.left.toFloat()
        val textWidth: Float = textBounds.width().toFloat()
        val textTop: Float = fontMetrics.top
        val textHeight: Float = fontMetrics.bottom - fontMetrics.top
        if (textWidth > contentWidth) {
            when (scaleMode) {
                Mode.None -> {
                    textScaleX = 1.0f
                    textScaleY = 1.0f
                }
                Mode.Horizontal -> {
                    textScaleX = contentWidth / textWidth
                    textScaleY = 1.0f
                }
                Mode.Proportional -> {
                    val scale = contentWidth / textWidth
                    textScaleX = scale
                    textScaleY = scale
                }
            }
        } else {
            textScaleX = 1.0f
            textScaleY = 1.0f
        }
        baselineX = calculateBaselineX(paddingLeft, contentWidth, textLeft, textWidth, textScaleX)
        baselineY = calculateBaselineY(paddingTop, contentHeight, textTop, textHeight, textScaleY)
    }

    private fun calculateBaselineX(
        contentLeft: Int,
        contentWidth: Int,
        textLeft: Float,
        textWidth: Float,
        scaleX: Float
    ): Float {
        val scaledTextLeft = textLeft * scaleX
        val scaledTextWidth = textWidth * scaleX
        val horizontalGravity =
            Gravity.getAbsoluteGravity(gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        val targetLeft: Float = @SuppressLint("RtlHardcoded") when (horizontalGravity) {
            Gravity.LEFT -> contentLeft.toFloat()
            Gravity.RIGHT -> contentLeft + contentWidth - scaledTextWidth
            else -> contentLeft + (contentWidth - scaledTextWidth) / 2.0f
        }
        return targetLeft - scaledTextLeft
    }

    private fun calculateBaselineY(
        contentTop: Int,
        contentHeight: Int,
        textTop: Float,
        textHeight: Float,
        scaleY: Float
    ): Float {
        val scaledTextTop = textTop * scaleY
        val scaledTextHeight = textHeight * scaleY
        val verticalGravity = gravity and Gravity.VERTICAL_GRAVITY_MASK
        val targetTop: Float = when (verticalGravity) {
            Gravity.TOP -> contentTop.toFloat()
            Gravity.BOTTOM -> contentTop + contentHeight - scaledTextHeight
            else -> contentTop + (contentHeight - scaledTextHeight) / 2.0f
        }
        return targetTop - scaledTextTop
    }

    override fun onDraw(canvas: Canvas) {
        if (needsCalculateTransform) {
            calculateTransform(width, height)
            needsCalculateTransform = false
        }
        val paint = paint
        paint.color = currentTextColor
        canvas.withSave {
            translate(scrollX.toFloat(), scrollY.toFloat())
            translate(baselineX, baselineY)
            scale(textScaleX, textScaleY)
            drawText(text.toString(), 0.0f, 0.0f, paint)
        }
    }

    override fun getBaseline(): Int {
        return paddingTop + baselineY.roundToInt()
    }
}
