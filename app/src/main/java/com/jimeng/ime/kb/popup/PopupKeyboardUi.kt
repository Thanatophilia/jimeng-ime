/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb.popup

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import com.jimeng.ime.kb.AutoScaleTextView
import com.jimeng.ime.kb.keyboard.KeyAction
import com.jimeng.ime.kb.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.gravityCenter
import splitties.views.gravityEnd
import splitties.views.gravityStart
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 长按展开的九宫格小键盘（变体字符）。
 *
 * 布局思路照搬 fcitx5：
 * - 最多 5 列：rowCount = ceil(n / 5)，columnCount = round(n / rowCount)
 * - 初始焦点列贴住手指位置（[calcInitialFocusedColumn]）
 * - 数字按**列优先**填进格子，行为倒序（因为 verticalLayout 后加的在下边）
 *
 * ```
 *                    c───┬───┬───┐
 *                    │   │ 4 │ 5 │
 *                 ┌─ ├───p───┼───┤ ─┐
 *   popupKeyHeight│  │ 3 │ 1 │ 2 │  │
 *                 └─ └───┼───┼───┘  │
 *                        │   │      │popupHeight
 *                 ┌───── │o─┐│      │
 *  bounds.height()│      ││a││      │
 *                 └───── └┴─┴┘ ─────┘
 * ```
 * `o` 触发键左上角，`p` 气泡左上角，`c` 容器左上角，`a` 初始焦点格
 */
class PopupKeyboardUi(
    override val ctx: Context,
    theme: Theme,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val radius: Float,
    private val keyWidth: Int,
    private val keyHeight: Int,
    private val popupHeight: Int,
    private val keys: Array<String>,
    private val labels: Array<String>
) : PopupContainerUi(ctx, theme, outerBounds, triggerBounds, onDismissSelf) {

    class PopupKeyUi(override val ctx: Context, val theme: Theme, val text: String) : Ui {

        val textView = view(::AutoScaleTextView) {
            this.text = this@PopupKeyUi.text
            scaleMode = AutoScaleTextView.Mode.Proportional
            textSize = 23f
            setTextColor(theme.keyTextColor)
        }

        override val root = frameLayout {
            add(textView, lParams {
                gravity = gravityCenter
            })
        }
    }

    private val inactiveBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.popupBackgroundColor)
    }

    private val focusBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.genericActiveBackgroundColor)
    }

    private val rowCount: Int
    private val columnCount: Int

    private val focusRow: Int
    private val focusColumn: Int

    init {
        val keyCount: Float = keys.size.toFloat()
        rowCount = ceil(keyCount / 5).toInt()
        columnCount = (keyCount / rowCount).roundToInt()

        focusRow = 0
        focusColumn = calcInitialFocusedColumn(columnCount, keyWidth, outerBounds, triggerBounds)
    }

    /**
     * X 偏移由两段组成：
     * 1. 触发键左边缘 → 气泡左边缘
     * 2. 最左列 → 初始焦点列
     *
     * Y 偏移同样两段：
     * 1. 触发键顶边 → 气泡顶边
     * 2. 最顶行 → 初始焦点行（最底行）
     */
    override val offsetX = ((triggerBounds.width() - keyWidth) / 2) - (keyWidth * focusColumn)
    override val offsetY = (triggerBounds.height() - popupHeight) - (keyHeight * (rowCount - 1))

    private val columnOrder = createColumnOrder(columnCount, focusColumn)

    /**
     * 下标小的行画在下边。例如：
     * ```
     * [[2, 0, 1, 3], [6, 4, 5, 7]]
     * ```
     * 显示成
     * ```
     * | 6 | 4 | 5 | 7 |
     * | 2 | 0 | 1 | 3 |
     * ```
     * 其中 `0` 是默认焦点。
     */
    private val keyOrders = Array(rowCount) { row ->
        IntArray(columnCount) { col -> row * columnCount + columnOrder[col] }
    }

    private var focusedIndex = keyOrders[focusRow][focusColumn]

    private val keyUis = labels.map {
        PopupKeyUi(ctx, theme, it)
    }

    init {
        markFocus(focusedIndex)
    }

    override val root = verticalLayout root@{
        background = inactiveBackground
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        // 倒序添加，因为后加的视图显示在下边
        for (i in rowCount - 1 downTo 0) {
            val order = keyOrders[i]
            add(horizontalLayout row@{
                for (j in 0 until columnCount) {
                    val keyUi = keyUis.getOrNull(order[j])
                    if (keyUi == null) {
                        // 第一列为空时，整行向右（末尾）对齐
                        gravity = if (j == 0) gravityEnd else gravityStart
                    } else {
                        add(keyUi.root, lParams(keyWidth, keyHeight))
                    }
                }
            }, lParams(width = matchParent))
        }
    }

    private fun markFocus(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = focusBackground
            textView.setTextColor(theme.genericActiveForegroundColor)
        }
    }

    private fun markInactive(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = null
            textView.setTextColor(theme.popupTextColor)
        }
    }

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        // 手指越出当前行底部 30% 以上才换行，避免抖
        var newRow = rowCount - (y / keyHeight - 0.2).roundToInt()
        // 手指越出当前列边界就换列
        var newColumn = floor(x / keyWidth).toInt()
        // 超出 ±2 行/列范围就认为手指逃出去了，收起面板
        if (newRow < -2 || newRow > rowCount + 1 || newColumn < -2 || newColumn > columnCount + 1) {
            onDismissSelf(this)
            return true
        }
        newRow = limitIndex(newRow, rowCount)
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = keyOrders[newRow][newColumn]
        if (newFocus < keyUis.size) {
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
        }
        return false
    }

    override fun onTrigger(): KeyAction? {
        val key = keys.getOrNull(focusedIndex) ?: return null
        return KeyAction.FcitxKeyAction(key)
    }
}