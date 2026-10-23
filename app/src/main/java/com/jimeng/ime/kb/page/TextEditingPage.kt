package com.jimeng.ime.kb.page

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.jimeng.ime.R
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.view.ToolbarButtonView
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

/**
 * 文本编辑页：整块铺满键盘区的「光标 / 编辑」专用页。
 *
 * **这个文件是 fcitx5-android `input/editing/TextEditingUi.kt` 的对照移植**，
 * 约束一条一条抄过来，不自己发明分栏算法。
 *
 * 原版布局（ConstraintLayout 硬约束）：
 * ```
 *          左列        中列        右列        操作列
 * 行 0:  [  ◀   ]  [   ↑   ]  [   ▶   ]  [ 全选 ]
 * 行 1:  [  ◀   ]  [ 选择  ]  [   ▶   ]  [ 复制 ]
 * 行 2:  [  ◀   ]  [   ↓   ]  [   ▶   ]  [ 粘贴 ]
 * 行 3:  [   行首   ]  [   行尾   ]     [ 删除 ]
 * ```
 *
 * 原版约束源码对照（`TextEditingUi.root`，一行一行对应）：
 * ```kotlin
 * leftButton:  topOfParent + leftOfParent + above(homeButton) + rightToLeftOf(selectButton)
 * upButton:    topOfParent + leftToRightOf(leftButton) + above(selectButton) + rightToLeftOf(rightButton)
 * selectButton: below(upButton) + leftToRightOf(leftButton) + above(downButton) + rightToLeftOf(rightButton)
 * downButton:  below(selectButton) + leftToRightOf(leftButton) + above(homeButton) + rightToLeftOf(rightButton)
 * rightButton: topOfParent + leftToRightOf(selectButton) + above(endButton) + rightToLeftOf(copyButton)
 * homeButton:  below(downButton) + leftOfParent + bottomOfParent + rightToLeftOf(endButton)
 * endButton:   below(downButton) + leftToRightOf(homeButton) + bottomOfParent + rightToLeftOf(backspaceButton)
 * selectAllButton: topOfParent + leftToRightOf(rightButton) + rightOfParent + above(cutButton) + percentWidth=0.3f
 * cutButton:   below(selectAllButton) + leftToRightOf(rightButton) + rightOfParent + above(copyButton) + 0.3f
 * copyButton:  below(cutButton) + leftToRightOf(rightButton) + rightOfParent + above(pasteButton) + 0.3f
 * pasteButton: below(copyButton) + leftToRightOf(rightButton) + rightOfParent + above(backspaceButton) + 0.3f
 * backspaceButton: below(pasteButton) + leftToRightOf(rightButton) + rightOfParent + bottomOfParent + 0.3f
 * ```
 *
 * **注意一个原版细节**：`endButton` 的右邻居是 `backspaceButton`，
 * 而 `backspaceButton` 的上邻居是 `pasteButton` —— 所以底行三格的边界
 * 完全由上面那几列的约束链推出来，不需要人工算百分比。
 * 这正是先前用 LinearLayout 手算分栏永远对不齐的原因。
 *
 * 按钮样式也照抄 `TextEditingButton`：
 * - bordered：`shadowedKeyBackgroundDrawable(bkgColor, shadowColor, radius, shadowWidth, hInset=4dp, vInset=4dp)`
 *   阴影靠 `LayerDrawable.setLayerInset` 实现，**不是 margin**——所以按钮格子本身是顶满的，
 *   这也解释了为什么原版九个格子永远一样大。
 * - altStyle：右列五格用 `altKeyBackgroundColor` / `altKeyTextColor`
 * - activated：`enableActivatedState()` 里那套 `genericActive*` 颜色
 */
class TextEditingPage(
    context: Context,
    theme: Theme,
    private val onDirectionKey: (Int) -> Unit,
    private val onSelectToggle: () -> Unit,
    private val onSelectAll: () -> Unit,
    private val onCut: () -> Unit,
    private val onCopy: () -> Unit,
    private val onPaste: () -> Unit,
    private val onBackspace: () -> Unit,
    private val onOpenClipboard: () -> Unit,
    private val hasSelection: () -> Boolean
) : InputPage(context, theme) {

    companion object {
        /** 照原版 `TextEditingButton` 的 hInset / vInset */
        private const val H_INSET_DP = 4
        private const val V_INSET_DP = 4
        /** 照原版 shadowWidth = dp(1) */
        private const val SHADOW_WIDTH_DP = 1
        /** 圆角，原版走 ThemePrefs.textEditingButtonRadius */
        private const val RADIUS_DP = 4
        /** 图标 24dp */
        private const val ICON_DP = 24
    }

    override val title: String = "文本编辑"

    /** 候选栏右上角剪贴板（照原版 `TextEditingUi.extension`） */
    override val barExtension: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ToolbarButtonView(context, theme, R.drawable.ic_clipboard_24) { onOpenClipboard() },
            LinearLayout.LayoutParams(context.dp(40), context.dp(40))
        )
    }

    // ---- 十二个按钮，顺序和命名跟原版一模一样 ----
    private val leftButton = iconButton(R.drawable.ic_baseline_keyboard_arrow_left_24) {
        onDirectionKey(KeyEvent.KEYCODE_DPAD_LEFT)
    }
    private val upButton = iconButton(R.drawable.ic_baseline_keyboard_arrow_up_24) {
        onDirectionKey(KeyEvent.KEYCODE_DPAD_UP)
    }
    private val selectButton = textButton("选择") {
        onSelectToggle()
        updateSelection()
    }
    private val downButton = iconButton(R.drawable.ic_baseline_keyboard_arrow_down_24) {
        onDirectionKey(KeyEvent.KEYCODE_DPAD_DOWN)
    }
    private val rightButton = iconButton(R.drawable.ic_baseline_keyboard_arrow_right_24) {
        onDirectionKey(KeyEvent.KEYCODE_DPAD_RIGHT)
    }
    private val homeButton = iconButton(R.drawable.ic_baseline_first_page_24) {
        onDirectionKey(KeyEvent.KEYCODE_MOVE_HOME)
    }
    private val endButton = iconButton(R.drawable.ic_baseline_last_page_24) {
        onDirectionKey(KeyEvent.KEYCODE_MOVE_END)
    }
    private val selectAllButton = textButton("全选", altStyle = true) { onSelectAll() }
    private val cutButton = textButton("剪切", altStyle = true) { onCut() }.apply {
        visibility = View.GONE
    }
    private val copyButton = textButton("复制", altStyle = true) { onCopy() }
    private val pasteButton = textButton("粘贴", altStyle = true) { onPaste() }
    private val backspaceButton = iconButton(
        R.drawable.ic_baseline_backspace_24,
        altStyle = true
    ) { onBackspace() }

    /**
     * 根布局：**完全照抄原版的 ConstraintLayout 约束**。
     *
     * 一条约束都不改、不加 margin、不用 weight。
     * 原版怎么连，这里就怎么连。
     */
    override val root: View = ConstraintLayout(context).apply {
        setBackgroundColor(theme.barColor)
        isClickable = true

        // ---- 左侧 ◀：贯穿行 0~2 ----
        add(leftButton, lParams {
            topOfParent()
            leftOfParent()
            above(homeButton)
            rightToLeftOf(selectButton)
        })

        // ---- 中列 ↑ / 选择 / ↓ ----
        add(upButton, lParams {
            topOfParent()
            leftToRightOf(leftButton)
            above(selectButton)
            rightToLeftOf(rightButton)
        })
        add(selectButton, lParams {
            below(upButton)
            leftToRightOf(leftButton)
            above(downButton)
            rightToLeftOf(rightButton)
        })
        add(downButton, lParams {
            below(selectButton)
            leftToRightOf(leftButton)
            above(homeButton)
            rightToLeftOf(rightButton)
        })

        // ---- ▶：同样贯穿行 0~2 ----
        add(rightButton, lParams {
            topOfParent()
            leftToRightOf(selectButton)
            above(endButton)
            rightToLeftOf(copyButton)
        })

        // ---- 底行：行首（跨前两列）/ 行尾（第三列）----
        add(homeButton, lParams {
            below(downButton)
            leftOfParent()
            bottomOfParent()
            rightToLeftOf(endButton)
        })
        add(endButton, lParams {
            below(downButton)
            leftToRightOf(homeButton)
            bottomOfParent()
            rightToLeftOf(backspaceButton)
        })

        // ---- 右列五格：固定 30% 宽 ----
        add(selectAllButton, lParams {
            topOfParent()
            leftToRightOf(rightButton)
            rightOfParent()
            above(cutButton)
            matchConstraintPercentWidth = 0.3f
        })
        add(cutButton, lParams {
            below(selectAllButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(copyButton)
            matchConstraintPercentWidth = 0.3f
        })
        add(copyButton, lParams {
            below(cutButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(pasteButton)
            matchConstraintPercentWidth = 0.3f
        })
        add(pasteButton, lParams {
            below(copyButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(backspaceButton)
            matchConstraintPercentWidth = 0.3f
        })
        add(backspaceButton, lParams {
            below(pasteButton)
            leftToRightOf(rightButton)
            rightOfParent()
            bottomOfParent()
            matchConstraintPercentWidth = 0.3f
        })
    }

    override fun onAttached() {
        updateSelection()
    }

    /** 照原版 `TextEditingUi.updateSelection()` */
    fun updateSelection() {
        val has = hasSelection()
        selectAllButton.visibility = if (has) View.GONE else View.VISIBLE
        cutButton.visibility = if (has) View.VISIBLE else View.GONE
        (selectButton as? EditButton)?.setButtonActivated(has)
    }

    // ------------------------------------------------------------------ 按钮

    /**
     * 照抄 `TextEditingButton`：一个自绘的按钮 View。
     *
     * 原版关键点是 **背景 drawable 里用 `InsetDrawable` 留出 4dp，
     * 而不是给 View 加 margin** —— 所以 View 本身就顶满网格格子，
     * 九格大小永远一致，只有里面的「色块」内缩 4dp 而已。
     */
    private inner class EditButton(altStyle: Boolean) : View(context) {
        private val alt = altStyle
        private val shadowWidth = context.dp(SHADOW_WIDTH_DP)
        private val hInset = context.dp(H_INSET_DP)
        private val vInset = context.dp(V_INSET_DP)
        private val radius = context.dp(RADIUS_DP).toFloat()

        private var activated = false
        private var iconDrawable: Drawable? = null
        private var label: String? = null

        init {
            isClickable = true
            isFocusable = false
            refreshBackground()
        }

        fun setIcon(iconRes: Int) {
            iconDrawable = checkNotNull(
                androidx.core.content.ContextCompat.getDrawable(context, iconRes)
            ).mutate()
            label = null
            refreshTint()
            invalidate()
        }

        fun setText(text: String) {
            label = text
            iconDrawable = null
            refreshTint()
            invalidate()
        }

        fun setButtonActivated(value: Boolean) {
            if (activated == value) return
            activated = value
            refreshBackground()
            refreshTint()
            invalidate()
        }

        private fun normalBg() = if (alt) theme.altKeyBackgroundColor else theme.keyBackgroundColor

        private fun normalFg() = if (alt) theme.altKeyTextColor else theme.keyTextColor

        private fun refreshBackground() {
            // 照 shadowedKeyBackgroundDrawable：底下一层阴影色，上面一层键色，
            // 用 setLayerInset 各留 hInset/vInset
            val shadow = LayerDrawable(
                arrayOf(
                    radiusDrawable(radius, theme.keyShadowColor),
                    radiusDrawable(
                        radius,
                        if (activated) theme.genericActiveBackgroundColor else normalBg()
                    )
                )
            )
            shadow.setLayerInset(0, hInset, vInset, hInset, vInset - shadowWidth)
            shadow.setLayerInset(1, hInset, vInset, hInset, vInset)

            // 按压色（照原版 RippleDrawable 的 insetRadiusDrawable）
            val rippleMask = InsetDrawable(radiusDrawable(radius, android.graphics.Color.WHITE), hInset, vInset, hInset, vInset)
            val ripple = RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor),
                shadow,
                rippleMask
            )
            background = ripple
        }

        private fun refreshTint() {
            val fg = if (activated) theme.genericActiveForegroundColor else normalFg()
            iconDrawable?.setTint(fg)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fg = if (activated) theme.genericActiveForegroundColor else normalFg()
            val icon = iconDrawable
            if (icon != null) {
                val size = context.dp(ICON_DP)
                val cx = (width - size) / 2
                val cy = (height - size) / 2
                icon.setBounds(cx, cy, cx + size, cy + size)
                icon.draw(canvas)
            } else {
                // 文字居中（用 View 的 paint 直接画，省一个子 View）
                textPaint.color = fg
                val text = label ?: return
                val fm = textPaint.fontMetrics
                val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
                canvas.drawText(text, (width - textPaint.measureText(text)) / 2f, baseline, textPaint)
            }
        }

        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = context.dp(14).toFloat()
            textAlign = android.graphics.Paint.Align.LEFT
            typeface = android.graphics.Typeface.DEFAULT
        }
    }

    /** 圆角纯色 drawable（照原版 `radiusDrawable`） */
    private fun radiusDrawable(radius: Float, color: Int): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun iconButton(
        iconRes: Int,
        altStyle: Boolean = false,
        action: () -> Unit
    ): View = EditButton(altStyle).apply {
        setIcon(iconRes)
        setOnClickListener { action() }
    }

    private fun textButton(
        label: String,
        altStyle: Boolean = false,
        action: () -> Unit
    ): View = EditButton(altStyle).apply {
        setText(label)
        setOnClickListener { action() }
    }
}