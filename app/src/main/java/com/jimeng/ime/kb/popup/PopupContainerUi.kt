/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb.popup

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.jimeng.ime.kb.keyboard.KeyAction
import com.jimeng.ime.kb.theme.Theme
import splitties.views.dsl.core.Ui
import kotlin.math.roundToInt

/**
 * 长按展开面板（小键盘 / 菜单）的公共基类。
 *
 * 子类只管两件事：
 * 1. 画出面板本身（[root]）
 * 2. 手指移到哪、松手时提交什么（[onChangeFocus] / [onTrigger]）
 *
 * 定位、边界计算、列顺序这些共用逻辑都在这里。
 */
abstract class PopupContainerUi(
    override val ctx: Context,
    val theme: Theme,
    val outerBounds: Rect,
    val triggerBounds: Rect,
    val onDismissSelf: PopupContainerUi.() -> Unit
) : Ui {

    abstract override val root: View

    /** 相对触发键 [triggerBounds] 的水平偏移 */
    abstract val offsetX: Int

    /** 相对触发键 [triggerBounds] 的竖直偏移 */
    abstract val offsetY: Int

    /**
     * 算初始焦点列。
     *
     * 从中间列开始，左边放不下就往右挪，右边放不下就往左挪——
     * 目的是让手指按下的那个键，正好落在某一个格子上。
     */
    fun calcInitialFocusedColumn(
        columnCount: Int,
        columnWidth: Int,
        outerBounds: Rect,
        triggerBounds: Rect
    ): Int {
        // assume trigger bounds inside outer bounds
        val leftSpace = triggerBounds.left - outerBounds.left
        val rightSpace = outerBounds.right - triggerBounds.right
        var col = (columnCount - 1) / 2
        while (columnWidth * col > leftSpace) col--
        while (columnWidth * (columnCount - col - 1) > rightSpace) col++
        return col.coerceIn(0, columnCount - 1)
    }

    /**
     * 生成列顺序，优先级：中间 → 右 → 左 → 右右 → 左左
     *
     * ```
     * | 6 | 4 | 2 | 0 | 1 | 3 | 5 |
     * ```
     *
     * 某一侧空间不够就跳过那一格：
     * ```
     *    | 3 | 2 | 1 | 0 |(no free space)
     * (no free space)| 0 | 1 | 2 | 3 |
     * ```
     */
    fun createColumnOrder(columnCount: Int, initialFocus: Int) = IntArray(columnCount).also {
        var order = 0
        it[initialFocus] = order++
        for (i in 1 until columnCount * 2) {
            val sign = if (i % 2 == 0) -1 else 1
            val delta = (i / 2f).roundToInt()
            val nextColumn = initialFocus + sign * delta
            if (nextColumn < 0 || nextColumn >= columnCount) continue
            it[nextColumn] = order++
        }
    }

    fun changeFocus(x: Float, y: Float): Boolean {
        return onChangeFocus(x - offsetX, y - offsetY)
    }

    /**
     * 处理焦点切换。
     *
     * [x]、[y] 是相对容器自身左上角的坐标。
     *
     * @return 手势是否被消费掉（true 表示不再往触发键继续分发）
     */
    abstract fun onChangeFocus(x: Float, y: Float): Boolean

    /** 松手时返回要提交的动作，null 表示什么也不做 */
    abstract fun onTrigger(): KeyAction?

    companion object {
        fun limitIndex(i: Int, limit: Int) = if (i < 0) 0 else if (i >= limit) limit - 1 else i
    }
}