package com.jimeng.ime.kb.page

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.jimeng.ime.kb.theme.Theme
import splitties.dimensions.dp

/**
 * 「整块换页」的页面基类。
 *
 * 照 fcitx5-android 的 `input/wm/InputWindow` 思路：
 * 键盘区就是一整块，点剪贴板 / 更多时**整块换成新页面**，
 * 候选栏同时变成「← 标题」，点返回箭头回键盘。
 *
 * 为什么不继续用浮层：用户说「点了之后眼花缭乱」——浮层贴在候选栏上方，
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
 *
 * 一页最多两排（8 格），多了用 ViewPager2 左右滑动翻页，
 * 底部一条「‹ 1/2 ›」分页条，滑动时页码跟着变。
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
        val action: () -> Unit = {}
    )
    companion object {
        /** 网格列数，跟原版 gridLayoutManager(4) 一致 */
        private const val COLUMNS = 4
        /** 一页最多几排。多了不往下堆，翻页（用户：菜单只放两排） */
        private const val ROWS_PER_PAGE = 2
        /** 一页最多几格 = 两排 × 4 列 */
        private const val PAGE_SIZE = COLUMNS * ROWS_PER_PAGE
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
        /** 分页条到网格的距离 */
        private const val PAGER_GAP_DP = 14
    }
    override val title: String = "更多"

    /** 一页放几格 = 两排 × 4 列，items 按这个切成页 */
    private val pages: List<List<Item>> = items.chunked(PAGE_SIZE)

    /** ViewPager2，左右滑动翻页 */
    private val pager: ViewPager2 by lazy { ViewPager2(context) }

    /**
     * 分页条中间的「1 / 2」文字。
     *
     * 故意用可空而不是 lateinit：只有 items 超过一页时才会建分页条，
     * 而 ViewPager2 一排版就会回调 onPageSelected——那时分页条可能还不存在。
     * 以前这里是 lateinit，只有一页时直接崩（UninitializedPropertyAccessException），
     * 进程一死，下次开键盘就整库重新部署几十秒。
     */
    private var pageIndicator: TextView? = null

    /** 分页条左箭头（‹） */
    private var prevButton: TextView? = null

    /** 分页条右箭头（›） */
    private var nextButton: TextView? = null

    /**
     * 页面根布局。
     *
     * 外面套一层 [FrameLayout] 是为了让整块网格**竖向居中**：
     * 键盘区有约 290dp 高，而菜单项只占一两排，直接铺在 LinearLayout 里
     * 会全部贴顶，下面空出一大片——看着像「只画了一半」。
     * 居中之后上下留白对称，视线也落在中间。
     *
     * 为什么用 lazy：分页要在构造阶段之后才能渲染（要读 [items]、
     * 给 [pager]/[pageIndicator] 赋值），而 InputPage 的 root 是构造期属性，
     * 直接初始化没法引用这些实例状态，所以推迟到第一次被 attach 时再建。
     */
    override val root: View by lazy { buildRoot() }

    private fun buildRoot(): View {
        val frame = FrameLayout(context).also { frame ->
            frame.setBackgroundColor(theme.barColor)
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            // ViewPager2：左右滑动翻页。每页是一个 2×4 的网格。
            pager.apply {
                orientation = ViewPager2.ORIENTATION_HORIZONTAL
                offscreenPageLimit = 1
                adapter = MenuPageAdapter()
                // 当前页变化时：更新页码文字 + 箭头置灰。
                // 分页条可能还没建出来（只有一页时根本不建），所以全部空安全——
                // 这里以前是 lateinit，ViewPager2 一排版就崩，代价是整库重新部署。
                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        pageIndicator?.text = "${position + 1} / ${pages.size}"
                        prevButton?.alpha = if (position == 0) 0.3f else 1f
                        nextButton?.alpha = if (position >= pages.size - 1) 0.3f else 1f
                    }
                })
            }
            column.addView(
                pager,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dp(ROW_HEIGHT_DP * ROWS_PER_PAGE + ROW_GAP_DP)
                )
            )
            // 一页放得下就不用分页条，省得占高度
            if (pages.size > 1) {
                column.addView(
                    buildPager(),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = context.dp(PAGER_GAP_DP) }
                )
            }
            frame.addView(
                column,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL
                )
            )
        }
        return frame
    }

    /** 左右滑动翻页：一页一个 2×4 网格 */
    private inner class MenuPageAdapter :
        RecyclerView.Adapter<MenuPageAdapter.PageHolder>() {
        inner class PageHolder(val view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val grid = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // ViewPager2 硬性要求：每页根 view 必须 MATCH_PARENT × MATCH_PARENT
                // （onChildViewAttachedToWindow 会检查，不满足直接抛异常崩溃），
                // 页面内容自己再撑满这整块。
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return PageHolder(grid)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val grid = holder.view as LinearLayout
            grid.removeAllViews()
            val rowHeight = context.dp(ROW_HEIGHT_DP)
            val rowGap = context.dp(ROW_GAP_DP)
            val page = pages[position]
            page.chunked(COLUMNS).forEachIndexed { rowIndex, rowItems ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                rowItems.forEach { item ->
                    row.addView(
                        buildCell(item),
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1f
                        )
                    )
                }
                // 末行不满 4 个时补空位，免得剩下几格被拉宽
                repeat(COLUMNS - rowItems.size) {
                    row.addView(
                        View(context),
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1f
                        )
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
        }

        override fun getItemCount(): Int = pages.size
    }

    /** 分页条：‹ 1/2 ›。点箭头翻页，页码跟着 ViewPager2 走 */
    private fun buildPager(): View {
        val pager = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = pagerButton("‹")
        val next = pagerButton("›")
        val indicator = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(theme.altKeyTextColor)
        }
        // 先赋值再挂监听：箭头点下去要能改页码，得先有这两个引用
        prevButton = prev
        nextButton = next
        pageIndicator = indicator
        prev.setOnClickListener {
            if (this.pager.currentItem > 0) this.pager.currentItem--
        }
        next.setOnClickListener {
            if (this.pager.currentItem < pages.size - 1) this.pager.currentItem++
        }
        pager.addView(prev)
        pager.addView(
            indicator,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = context.dp(12)
                rightMargin = context.dp(12)
            }
        )
        pager.addView(next)
        // 首屏也要置灰一次（第一页时 ‹ 应置灰）
        indicator.text = "1 / ${pages.size}"
        prev.alpha = 0.3f
        next.alpha = if (pages.size > 1) 1f else 0.3f
        return pager
    }

    /** 分页箭头（‹ / ›）：一个点击区 + 一个大字符 */
    private fun pagerButton(symbol: String) = TextView(context).apply {
        text = symbol
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        // 点按区域大一点，手指好按
        setPadding(
            context.dp(10), context.dp(4),
            context.dp(10), context.dp(4)
        )
        isClickable = true
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
