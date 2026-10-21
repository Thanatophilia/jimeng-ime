package com.jimeng.ime.kb.page

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme
import splitties.dimensions.dp

/**
 * 「整块换页」的页面基类。
 *
 * 照 fcitx5-android 的 `input/wm/InputWindow` 思路：
 * 键盘区就是一整块，点剪贴板 / 更多时**整块换成新页面**，
 * 候选栏同时变成「← 标题」，点返回箭头回键盘。
 *
 * 为什么不继续用浮层：主人说「点了之后眼花缭乱」——浮层贴在候选栏上方，
 * 跟候选、码区、键盘抢视线，还容易叠在一起。原版是换页，视线不跳、
 * 位置固定、有标题和返回，用户永远知道自己在哪。
 *
 * 子类只管画 [root]，标题和返回由候选栏那侧负责。
 */
abstract class InputPage(val context: Context, val theme: Theme) {

    /** 页面内容（铺满整个键盘区） */
    abstract val root: View

    /** 候选栏上显示的标题 */
    abstract val title: String

    /**
     * 候选栏标题条右端的「页面扩展按钮」（照 fcitx5 的 InputWindow.onCreateBarExtension）。
     *
     * 原版剪贴板页在这里放 delete_sweep 清空按钮，文本编辑页放剪贴板按钮。
     * 返回 null 表示这一页不需要扩展按钮。
     */
    open val barExtension: View? = null

    /** 页面显示后回调（刷新数据等） */
    open fun onAttached() {}

    /** 页面被收起时清理（关弹窗、停任务等） */
    open fun onDetached() {}
}

/**
 * 菜单页：图标网格，照 fcitx5 的 `StatusAreaWindow`（4 列网格）。
 *
 * 原版放的是「主题 / 输入法选项 / 重载配置 / 虚拟键盘」，
 * 我们暂时只有「设置」和「回到键盘」两项，以后往里加。
 */
class MenuPage(
    context: Context,
    theme: Theme,
    private val items: List<Item>
) : InputPage(context, theme) {

    data class Item(val label: String, val iconRes: Int, val action: () -> Unit)

    companion object {
        /** 网格列数，跟原版 gridLayoutManager(4) 一致 */
        private const val COLUMNS = 4
        private const val ROW_HEIGHT_DP = 72
        private const val ICON_SIZE_DP = 28
    }

    override val title: String = "更多"

    override val root: View = LinearLayout(context).also { grid ->
        grid.orientation = LinearLayout.VERTICAL
        grid.setBackgroundColor(theme.barColor)
        val rowHeight = context.dp(ROW_HEIGHT_DP)
        items.chunked(COLUMNS).forEach { rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowItems.forEach { item ->
                row.addView(
                    buildCell(item),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                )
            }
            // 末行不满 4 个时补空位，免得剩下几格被拉宽
            repeat(COLUMNS - rowItems.size) {
                row.addView(
                    View(context),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight)
            )
        }
    }

    private fun buildCell(item: Item): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cellBackground()
            isClickable = true
            setOnClickListener { item.action() }
        }
        cell.addView(
            ImageView(context).apply {
                setImageResource(item.iconRes)
                setColorFilter(theme.keyTextColor)
            },
            LinearLayout.LayoutParams(context.dp(ICON_SIZE_DP), context.dp(ICON_SIZE_DP))
        )
        cell.addView(
            TextView(context).apply {
                text = item.label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(theme.altKeyTextColor)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(6) }
        )
        return cell
    }

    private fun cellBackground() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply {
                cornerRadius = context.dp(8).toFloat()
                setColor(theme.keyPressHighlightColor)
            }
        )
        addState(intArrayOf(), ColorDrawable(0))
    }
}
