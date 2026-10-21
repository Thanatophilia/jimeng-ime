package com.jimeng.ime.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.Gravity
import android.widget.FrameLayout
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.utils.pressHighlightDrawable

/**
 * 展开页左侧那一列里的**一格**。照抄 fcitx5-android 的
 * input/candidates/expanded/CandidateTabActionsAdapter.kt 里的 `TabActionUi`。
 *
 * 原版结构：
 *   root（CustomGestureView，background = createBackground(...)，height 由 label 撑）
 *     └─ AutoScaleTextView（matchParent × 36dp，gravity = center，16sp，Proportional）
 *
 * 三种外观（和原版一一对应）：
 *
 *  1. **普通格**（checkable = false，比如某个谐音变体）
 *     → background = pressHighlightDrawable，只有按下时浮一层淡色。
 *
 *  2. **可选中格**（checkable = true，indicatorStyle = false ← 当前音节用这个）
 *     → 按下 / 选中（activated）都是同一层 `keyPressHighlightColor` 淡色块。
 *       注意是**淡色块**，不是 accent 蓝底白字——这是之前和原版最大的差别。
 *
 *  3. **pinned 格**（indicatorStyle = true ← 底部「单字」用这个）
 *     → 透明底，最下面一条 11×2dp 的指示条：选中时 accent 色、平时 altKey 色。
 *       这就是原版底部常驻 tab 的样子。
 */
class PreeditTabView(
    context: Context,
    private val theme: Theme,
    private val indicatorStyle: Boolean = false
) : FrameLayout(context) {

    companion object {
        /** 格子高度，照原版 `lParams(matchParent, ctx.dp(36))` */
        const val HEIGHT_DP = 36f

        /** 字号，照原版 `textSize = 16f` */
        const val TEXT_SP = 16f

        /** pinned 指示条：11×2dp，距底 4dp（照原版 setLayerSize / setLayerInsetBottom） */
        private const val INDICATOR_WIDTH_DP = 11f
        private const val INDICATOR_HEIGHT_DP = 2f
        private const val INDICATOR_BOTTOM_DP = 4f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    /** 文字：16sp、单行、居中，装不下等比缩（照原版 AutoScaleTextView.Mode.Proportional） */
    val label = AutoScaleTextView(context).apply {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = TEXT_SP
        isSingleLine = true
        gravity = Gravity.CENTER
        setTextColor(theme.candidateTextColor)
    }

    /**
     * 这一格「能不能选中」。设 true 时换用选中样式。
     * 对应原版 `checkable` 字段（只有它变化才重建 background）。
     */
    var checkable: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            background = createBackground(value)
        }

    init {
        background = createBackground(false)
        // 照原版：label 高度 36dp 撑起整格，root 宽 matchParent
        addView(
            label,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(HEIGHT_DP)).apply {
                gravity = Gravity.CENTER
            }
        )
        isClickable = true
    }

    /** 更新成某个 tab 的样子 */
    fun update(tab: CandidateTab) {
        if (label.text?.toString() != tab.text) label.text = tab.text
        checkable = tab.isCheckable
        // 必须先换 background 再设 activated：换背景会重置 drawable 状态
        isActivated = tab.isChecked
    }

    /**
     * 照原版 `createBackground(checkable, indicatorStyle)` 原样翻译。
     *
     * `checkable = false`  → 只有按下高亮
     * `checkable = true, indicatorStyle = false` → 按下 + 选中都用淡色块
     * `checkable = true, indicatorStyle = true`  → 层叠：底层 11×2dp 指示条（选中 accent / 平时 altKey），
     *                                              上层按下高亮
     */
    private fun createBackground(checkable: Boolean): Drawable {
        if (!checkable) {
            return pressHighlightDrawable(theme.keyPressHighlightColor)
        }
        if (!indicatorStyle) {
            val highlight = ColorDrawable(theme.keyPressHighlightColor)
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), highlight)
                addState(intArrayOf(android.R.attr.state_activated), highlight)
            }
        }
        val states = arrayOf(
            intArrayOf(android.R.attr.state_activated),
            intArrayOf()
        )
        val colors = intArrayOf(
            theme.accentKeyBackgroundColor,
            theme.altKeyBackgroundColor
        )
        return LayerDrawable(
            arrayOf(
                ShapeDrawable(RectShape()).apply {
                    setTintList(ColorStateList(states, colors))
                },
                pressHighlightDrawable(theme.keyPressHighlightColor)
            )
        ).apply {
            setLayerSize(0, dp(INDICATOR_WIDTH_DP), dp(INDICATOR_HEIGHT_DP))
            setLayerGravity(0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setLayerInsetBottom(0, dp(INDICATOR_BOTTOM_DP))
        }
    }
}