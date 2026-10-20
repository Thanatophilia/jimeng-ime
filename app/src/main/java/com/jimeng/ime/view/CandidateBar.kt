package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 候选栏。**整套照抄 fcitx5-android**
 * （input/candidates/horizontal/HorizontalCandidateComponent.kt
 *  + input/bar/ui/CandidateUi.kt）。
 *
 * 为什么照抄：之前的自绘版本一直在「格子宽度 / 缩字 / 滑动」上打转，
 * 怎么调都不像谷歌输入法。fcitx5 的实现其实很简单，就是三件事：
 *
 *   1. 外层是个 FrameLayout：左边 RecyclerView，右边 40dp 的独立箭头 View；
 *   2. RecyclerView 用 FlexboxLayoutManager，**禁止滑动**
 *      （canScrollHorizontally/Vertically 都返回 false）；
 *   3. 每格 `minWidth = 栏宽 / 6 - 分隔线宽`，`flexGrow` 决定要不要拉伸。
 *      文字超过格子时，AutoScaleTextView 自己等比缩字。
 *
 * 这样出来的效果就是主人要的：「整个候选栏是固定的，字在里面排版，
 * 超过显示上限才缩小」。
 */
class CandidateBar(context: Context) : FrameLayout(context) {

    companion object {
        /** 候选栏高度，和之前一致 */
        const val HEIGHT_DP = 40f

        /**
         * 一页最多显示几个候选。照 fcitx5 的
         * `expandedCandidateGridSpanCount`（竖屏默认 6）。
         * 超出的候选交给展开页。
         */
        const val MAX_ITEMS = 6

        /** 每格最小宽度，照原版适配器里的 `minimumWidth = dp(40)` */
        private const val ITEM_MIN_WIDTH_DP = 40f

        /** 每格左右内边距，照原版适配器里的 `setPaddingDp(10, 0, 10, 0)` */
        private const val ITEM_PADDING_DP = 10f
    }

    interface Listener {
        /** 点了第 index 个候选（0 起，对应引擎返回的候选下标） */
        fun onPickCandidate(index: Int)

        /** 点了右侧箭头 */
        fun onExpand()
    }

    var listener: Listener? = null

    private val theme: Theme = ThemeManager.activeTheme
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 展开页开着的时候箭头朝上 */
    private var expanded = false

    /** 当前这一页要显示的候选 */
    private var candidates: List<String> = emptyList()

    // ---- RecyclerView + Flexbox，照 HorizontalCandidateComponent ----

    /**
     * 每格的最小宽度 = 栏宽 / MAX_ITEMS - 分隔线宽。
     * 照原版的 `layoutMinWidth`，在 onSizeChanged 和候选更新时算。
     */
    private var layoutMinWidth = 0

    /** 0f = 不拉伸（候选不足一页）；1f = 平分铺满（候选满一页） */
    private var layoutFlexGrow = 1f

    /**
     * 照原版的 secondLayoutPassNeeded：
     * 候选数 < MAX_ITEMS 但 RecyclerView 一行放不下全部时，
     * 需要在 layout 完成后再把已显示的格子拉宽铺满。
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    /** 单元格之间（以及候选区与箭头之间）的竖分隔线，照原版 dividerDrawable */
    private val dividerDrawable = ShapeDrawable(RectShape()).apply {
        val size = max(1, dp(1f).roundToInt())
        intrinsicWidth = size
        intrinsicHeight = size
        paint.color = theme.dividerColor
    }

    private val adapter = object : RecyclerView.Adapter<CandidateViewHolder>() {

        // 不开 stable ids：候选词可能重复（同名词），用 hashCode 当 id 会撞车，
        // 撞了 RecyclerView 会认错 holder、显示串位。每次都是全量刷新，用不上它。
        override fun getItemCount() = candidates.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): CandidateViewHolder {
            val ui = CandidateItemView(parent.context, theme).apply {
                // 照原版适配器：minimumWidth = dp(40)、setPaddingDp(10, 0, 10, 0)
                minimumWidth = dp(ITEM_MIN_WIDTH_DP).toInt()
                setPadding(
                    dp(ITEM_PADDING_DP).toInt(),
                    0,
                    dp(ITEM_PADDING_DP).toInt(),
                    0
                )
                layoutParams = FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            return CandidateViewHolder(ui)
        }

        override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
            holder.update(position, candidates[position])
            // 照原版：minWidth 和 flexGrow 在绑定时刷
            holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                minWidth = layoutMinWidth
                flexGrow = layoutFlexGrow
            }
            holder.itemView.setOnClickListener {
                val idx = holder.idx
                if (idx >= 0) listener?.onPickCandidate(idx)
            }
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun onViewRecycled(holder: CandidateViewHolder) {
            holder.itemView.setOnClickListener(null)
            holder.clear()
        }
    }

    private val layoutManager = object : FlexboxLayoutManager(context) {
        // 照原版：候选栏不滑动
        override fun canScrollVertically() = false
        override fun canScrollHorizontally() = false

        override fun onLayoutCompleted(state: RecyclerView.State) {
            super.onLayoutCompleted(state)
            val cnt = childCount
            if (secondLayoutPassNeeded) {
                if (cnt < adapter.itemCount) {
                    // 一行放不下全部候选 —— 把已显示的格子拉宽铺满
                    // （更新 LayoutParams 会再次触发 onLayoutCompleted，跳过第二次防死循环）
                    if (secondLayoutPassDone) return
                    secondLayoutPassDone = true
                    for (i in 0 until cnt) {
                        getChildAt(i)?.updateLayoutParams<LayoutParams> {
                            flexGrow = 1f
                        }
                    }
                } else {
                    secondLayoutPassNeeded = false
                }
            }
        }
    }

    private val recycler = object : RecyclerView(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // 照原版：栏宽变了，格宽跟着变
            val spanCount = currentSpanCount()
            layoutMinWidth = w / spanCount - dividerDrawable.intrinsicWidth
            // 直接遍历自己的 children（不碰外部字段，避免构造期访问到还没赋值的 recycler）
            for (i in 0 until childCount) {
                getChildAt(i)?.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = layoutMinWidth
                }
            }
        }
    }.apply {
        id = View.generateViewId()
        itemAnimator = null
        adapter = this@CandidateBar.adapter
        layoutManager = this@CandidateBar.layoutManager
        addItemDecoration(FlexboxDividerDecoration(dividerDrawable))
        clipToPadding = false
        // 候选不满一页时右边会有空白区，这里必须自己吃掉触摸，
        // 否则事件会穿过输入法窗口落到下面的应用上（之前踩过的坑）。
        isClickable = true
    }

    /** 右侧展开箭头：独立 View，40dp，贴右边（照 CandidateUi 的 endOfParent + before 约束） */
    val expandButton = ExpandButtonView(context, theme).apply {
        setOnClickListener {
            listener?.onExpand()
        }
    }

    init {
        setBackgroundColor(theme.keyboardColor)
        // 整条候选栏都吃掉触摸，绝不放行到下层应用
        isClickable = true
        addView(
            recycler,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                rightMargin = dp(ExpandButtonView.WIDTH_DP).toInt()
            }
        )
        addView(
            expandButton,
            LayoutParams(dp(ExpandButtonView.WIDTH_DP).toInt(), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )
    }

    /**
     * 一行的格数。
     * fcitx5 竖屏取 expandedCandidateGridSpanCount（默认 6）、横屏取
     * expandedCandidateGridSpanCountLandscape（默认 12）；主人这台主要竖屏用，
     * 两边都按 6 走，和之前的版本保持一致。
     */
    private fun currentSpanCount(): Int = MAX_ITEMS

    /**
     * 高度固定为 HEIGHT_DP。
     * 注意要真正走 FrameLayout 的测量流程（super.onMeasure），
     * 才能把里面的 RecyclerView 和箭头一起量出来——只 setMeasuredDimension
     * 的话子 View 永远不会被测量，候选栏会是空的。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = dp(HEIGHT_DP).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    /**
     * 更新候选。照原版 onCandidateUpdate 的 AutoFillWidth 分支：
     *   layoutMinWidth = 栏宽 / MAX_ITEMS - 分隔线宽
     *   layoutFlexGrow = 候选数 < MAX_ITEMS ? 0f : 1f
     *   secondLayoutPassNeeded = 候选数 < MAX_ITEMS
     */
    @SuppressLint("NotifyDataSetChanged")
    fun update(candidates: List<String>) {
        // 一页只显示前 MAX_ITEMS 个：Flexbox 在禁止纵向滚动的情况下
        // 会把超出的候选折到第二行，而候选栏高度固定 40dp、第二行会被裁掉
        // （还会影响 flexGrow 的判定）。多出来的交给展开页，
        // 而且这样格子位置 i 正好等于引擎的候选下标，点选不会错位。
        val shown = candidates.take(MAX_ITEMS)
        if (this.candidates == shown) return
        this.candidates = shown

        val spanCount = currentSpanCount()
        // 照原版：w 用的是 RecyclerView 自己的宽度（已经不含右侧箭头），
        // 所以这里取 recycler.width，而不是整个栏的 width，
        // 否则每格会偏宽、第 6 个格会被挤出可视区。
        val w = recycler.width
        layoutMinWidth = if (w > 0) {
            w / spanCount - dividerDrawable.intrinsicWidth
        } else {
            0
        }
        layoutFlexGrow = if (shown.size < MAX_ITEMS) 0f else 1f
        secondLayoutPassNeeded = shown.size < MAX_ITEMS
        secondLayoutPassDone = false

        adapter.notifyDataSetChanged()
        expandButton.dimmed = candidates.isEmpty()
    }

    fun clear() = update(emptyList())

    /** 展开页开合：箭头转方向 */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        expandButton.expanded = value
    }
}