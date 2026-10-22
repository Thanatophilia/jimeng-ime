package com.jimeng.ime.kb.page

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
 * 一格菜单项 = 一个图标 + 一行字。开关类的项（预测开关等）
 * 用图标底下的圆底表示「开着」，不靠改标签文字。
 */
class MenuPage(
    context: Context,
    theme: Theme,
    private val items: List<Item>
) : InputPage(context, theme) {
    /**
     * 一格菜单项。
     *
     * 两种图标二选一：
     *  - [iconRes]：普通矢量图标（现有的大多数项都走这条）
     *  - [textIcon]：直接画一个字当图标。简繁切换用这个——
     *    「简」「繁」两个字本身就是最好的图标，比任何矢量图都直观。
     *
     * [active] 是「这一项现在是开着的」：图标底下垫一块圆形底色，
     * 一眼就能看出来，不用在标签后面加「· 开」那种文字尾巴
     * （标签一变长，4 列网格里就会被挤变形）。
     */
    data class Item(
        val label: String,
        val iconRes: Int = 0,
        val textIcon: String? = null,
        val active: Boolean = false,
        val action: () -> Unit
    )
    companion object {
        /** 网格列数，跟原版 gridLayoutManager(4) 一致 */
        private const val COLUMNS = 4
        private const val ROW_HEIGHT_DP = 72
        private const val ICON_SIZE_DP = 28
        /** 文字图标的字号：跟 28dp 的图标框配起来刚好占满 */
        private const val TEXT_ICON_SIZE_SP = 22
        /**
         * 「开着」的图标底下那块圆底的直径。
         *
         * 比 [ICON_SIZE_DP] 大一圈：圆底只比图标大一点点时，
         * 看着像图标被一个框箍住了；大出一圈才像「底托」，
         * 图标浮在上面，一眼能看出这一项是开着的。
         */
        private const val ICON_ACTIVE_BG_DP = 38
        /**
         * 行与行之间的空隙。
         *
         * 原来两行是紧贴着的（72+72），上行下沿和下行上沿挤在一起，
         * 看着像一整块。加上这道缝之后，上行往上、下行往下，
         * 两排各成一组，扫一眼就能分清。
         */
        private const val ROW_GAP_DP = 16
    }
    override val title: String = "更多"

    /**
     * 页面根布局。
     *
     * 外面套一层 [FrameLayout] 是为了让整块网格**竖向居中**：
     * 键盘区有约 290dp 高，而 7 个菜单项只占两行（2×72dp=144dp），
     * 直接铺在 LinearLayout 里会全部贴顶，下面空出一大片——
     * 看着像「只画了一半」。居中之后上下留白对称，视线也落在中间。
     */
    override val root: View = FrameLayout(context).also { frame ->
        frame.setBackgroundColor(theme.barColor)
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rowHeight = context.dp(ROW_HEIGHT_DP)
        val rowGap = context.dp(ROW_GAP_DP)
        items.chunked(COLUMNS).forEachIndexed { rowIndex, rowItems ->
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
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight
                ).apply {
                    // 第一行不加，后面的行都往上顶一段——上下两排就分开了
                    if (rowIndex > 0) topMargin = rowGap
                }
            )
        }
        frame.addView(
            grid,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL
            )
        )
    }
    private fun buildCell(item: Item): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = cellBackground()
            isClickable = true
            setOnClickListener { item.action() }
        }
        val iconBox = FrameLayout(context).apply {
            // 尺寸一律按「圆底」的大小来：不管这一项开没开，格子都占同样大，
            // 开关一切换，图标位置完全不动——否则一开一关整格内容会跳一下。
            layoutParams = LinearLayout.LayoutParams(
                context.dp(ICON_ACTIVE_BG_DP),
                context.dp(ICON_ACTIVE_BG_DP)
            )
            // 「开着」的项：底下垫一块圆底，图标就从背景里浮出来。
            // 比在标签后面加「· 开」干净：标签长度不变，4 列网格不会挤歪，
            // 而且开关状态一眼可见——有没有圆底比读一个字快得多。
            if (item.active) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.genericActiveBackgroundColor)
                }
            }
        }
        // 真正的图标只有 28dp，在 38dp 的圆底里居中：
        // 图标贴到圆边会显得「塞满了」，四周留出呼吸空间才像底托。
        val iconSize = context.dp(ICON_SIZE_DP)
        val iconLp = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        val text = item.textIcon
        if (text != null) {
            iconBox.addView(
                TextView(context).apply {
                    this.text = text
                    textSize = TEXT_ICON_SIZE_SP.toFloat()
                    gravity = Gravity.CENTER
                    setTextColor(theme.keyTextColor)
                    // 下面一条下划线，把单字托住，看起来像个「标签」
                    paint.isUnderlineText = true
                },
                iconLp
            )
        } else {
            iconBox.addView(
                ImageView(context).apply {
                    setImageResource(item.iconRes)
                    setColorFilter(theme.keyTextColor)
                },
                iconLp
            )
        }
        cell.addView(iconBox)
        cell.addView(
            TextView(context).apply {
                this.text = item.label
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
