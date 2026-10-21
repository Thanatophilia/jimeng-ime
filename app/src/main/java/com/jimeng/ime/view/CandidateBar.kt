package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.jimeng.ime.R
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

        /** 工具栏：点了右端的展开/收起箭头（expand = 要展开） */
        fun onToolbarToggle(expand: Boolean)
    }

    var listener: Listener? = null

    private val theme: Theme = ThemeManager.activeTheme
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 展开页开着的时候箭头朝上 */
    private var expanded = false

    /** 当前这一页要显示的候选 */
    private var candidates: List<String> = emptyList()

    // ---- 工具栏（没打字时替代候选区，照 fcitx5 KawaiiBar 的 IdleUi）----

    /** 没打字时整条候选区当工具栏用；一打字自动让位回候选区 */
    private var toolbarMode = false

    /** 工具栏模式下，按钮排是否展开（menuButton 旋转 + 按钮排滑入滑出） */
    private var toolbarExpanded = false

    /**
     * 工具栏按钮排：剪贴板 + 更多（弹菜单）。
     * fcitx5 的 ButtonsBarUi 还挂 undo / redo / 光标，那些功能以后接。
     */
    private val toolbarRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
    }

    /**
     * 菜单按钮：画 ⌄（expand_more），**旋转 ±90° 扫成 ›/‹**——
     * 照 fcitx5 IdleUi 的 menuButton（iconRotation + iconAnimate().rotation(200ms)）：
     * 收起态 ›（-90°，往右展开），展开态 ‹（+90°，往左收回去）。
     */
    val menuButton = ToolbarButtonView(
        context, theme, R.drawable.ic_baseline_expand_more_24
    ) {
        listener?.onToolbarToggle(!toolbarExpanded)
    }

    /** 菜单按钮图标的目标旋转角：-90° = ›（收起）、+90° = ‹（展开），180° 大回环很丝滑 */
    private val menuTargetRotation
        get() = if (toolbarExpanded) 90f else -90f

    /** 点 ⋯ 弹出的菜单（设置在里面），由 JimengIME 提供具体动作 */
    var onMoreClicked: (() -> Unit)? = null

    /** 点剪贴板按钮：弹出剪贴板历史面板 */
    var onClipboardClicked: (() -> Unit)? = null

    /** ⋯ 按钮：弹出菜单（设置收在里面） */
    val moreButton = ToolbarButtonView(context, theme, R.drawable.ic_more_horiz_24) {
        onMoreClicked?.invoke()
    }

    /** 工具栏模式切换回调（false = 回候选区），JimengIME 用来收弹出菜单 */
    var onToolbarModeChanged: ((Boolean) -> Unit)? = null

    // ---- 「换页」标题条（照 fcitx5 TitleUi）----

    /**
     * 整块换页时（剪贴板 / 更多），候选栏不动，只把内容换成
     * 「← 标题」——正是 fcitx5 的 TitleUi：左边 40dp 返回按钮，右边标题，
     * 用的是同一个 40dp 高度。这样换页时视线不跳，用户永远知道自己在哪一页。
     */
    private val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    private val backButton = ToolbarButtonView(
        context, theme, R.drawable.ic_baseline_arrow_back_24
    ) {
        onBackClicked?.invoke()
    }

    private val titleText = android.widget.TextView(context).apply {
        textSize = 16f
        typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        setTextColor(theme.altKeyTextColor)
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 标题条上点了返回箭头 */
    var onBackClicked: (() -> Unit)? = null

    /** 当前是不是在「换页」状态（剪贴板 / 更多） */
    private var pageMode = false

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
            // 工具栏模式下右端箭头不响应（工具栏开关在最左 menuButton）；
            // 候选模式：展开候选页（⌄ 开、⌃ 收）
            if (!toolbarMode) listener?.onExpand()
        }
    }

    init {
        setBackgroundColor(theme.keyboardColor)
        // 整条候选栏都吃掉触摸，绝不放行到下层应用
        isClickable = true
        // 候选区：RecyclerView 占满，右端 40dp 留给展开箭头
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

        // ---- 工具栏：最左 menuButton + 中段按钮排（照 fcitx5 IdleUi 的结构）----
        // menuButton 的 ⌄ 靠旋转扫成 ›/‹；展开时按钮排从它右边滑出来
        addView(
            menuButton,
            LayoutParams(dp(ToolbarButtonView.WIDTH_DP).toInt(), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        )
        toolbarRow.addView(
            ToolbarButtonView(context, theme, R.drawable.ic_content_paste_24) {
                onClipboardClicked?.invoke()
            },
            toolbarLp()
        )
        toolbarRow.addView(
            moreButton,
            toolbarLp()
        )
        addView(
            toolbarRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                // 给最左的 menuButton 让位
                leftMargin = dp(ToolbarButtonView.WIDTH_DP).toInt()
            }
        )

        // ---- 换页标题条：← 返回 + 标题（照 fcitx5 TitleUi）----
        titleRow.addView(
            backButton,
            LayoutParams(dp(ToolbarButtonView.WIDTH_DP).toInt(), LayoutParams.MATCH_PARENT)
        )
        titleRow.addView(
            titleText,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = dp(8f).toInt()
            }
        )
        addView(
            titleRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    /**
     * 切进「换页」模式：候选区 / 工具栏全部让位，只显示「← 标题」。
     *
     * 照 fcitx5 的 KawaiiBarStateMachine：State 从 Idle / Candidate 切到 Title 时，
     * 整条 bar 换成 TitleUi，剪贴板和更多页都是这么进去的。
     */
    fun setPage(title: String) {
        pageMode = true
        titleText.text = title
        recycler.visibility = View.GONE
        expandButton.visibility = View.GONE
        menuButton.visibility = View.GONE
        toolbarRow.visibility = View.GONE
        titleRow.visibility = View.VISIBLE
    }

    /** 退出「换页」模式，回到正常状态（候选 / 工具栏由 toolbarMode 决定） */
    fun clearPage() {
        if (!pageMode) return
        pageMode = false
        titleRow.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        // 注意：这里必须显式复原，不能指望 JimengIME 再调一次 setToolbarMode——
        // toolbarMode 的值通常没变，那边会提前 return，menuButton 就永远藏着了。
        if (toolbarMode) {
            menuButton.visibility = View.VISIBLE
            menuButton.iconRotation = -90f
            expandButton.visibility = View.INVISIBLE
        } else {
            menuButton.visibility = View.GONE
            menuButton.iconRotation = 0f
            expandButton.visibility = View.VISIBLE
            expandButton.dimmed = candidates.isEmpty()
        }
    }

    /** 现在是不是在换页里（剪贴板 / 更多） */
    fun isPageMode() = pageMode

    /** 工具栏按钮的布局参数：40dp 宽 × 跟候选栏一样高 */
    private fun toolbarLp() = LayoutParams(
        dp(ToolbarButtonView.WIDTH_DP).toInt(),
        LayoutParams.MATCH_PARENT
    )

    // ---- 工具栏滑入滑出动画（照 fcitx5 IdleUi 的 in/out AnimationSet，200ms）----

    /** 进场：淡入 + 从左侧滑入自身宽度的 30% */
    private val toolbarInAnim = android.view.animation.AnimationSet(true).apply {
        duration = 200L
        addAnimation(android.view.animation.AlphaAnimation(0f, 1f))
        addAnimation(
            android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, -0.3f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                0, 0f, 0, 0f
            )
        )
    }

    /** 出场：淡出 + 向左滑出自身宽度的 30% */
    private val toolbarOutAnim = android.view.animation.AnimationSet(true).apply {
        duration = 200L
        addAnimation(android.view.animation.AlphaAnimation(1f, 0f))
        addAnimation(
            android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, -0.3f,
                0, 0f, 0, 0f
            )
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
        // 工具栏开着的时候来候选了：整条让位回候选区
        if (toolbarMode) setToolbarMode(false)
    }

    fun clear() = update(emptyList())

    /** 展开页开合：箭头转方向 */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        expandButton.expanded = value
    }

    /**
     * 切换「工具栏模式」：没打字时 true——候选区让位给工具栏按钮排，
     * 最左 menuButton 出现（⌄ 已随上轮复用改成 ›）；打字时 false——恢复候选区。
     */
    fun setToolbarMode(value: Boolean) {
        if (toolbarMode == value) return
        toolbarMode = value
        // 模式切换不播动画（照 fcitx5：非用户触发的状态变化 instant）
        toolbarExpanded = false
        toolbarRow.visibility = View.GONE
        expandButton.expanded = false
        if (value) {
            // 进工具栏：menuButton 以 › 露脸（图标 -90°），按钮排收着
            menuButton.visibility = View.VISIBLE
            menuButton.iconRotation = -90f
            expandButton.visibility = View.INVISIBLE
        } else {
            // 回候选区：menuButton 藏起来，右端 ⌄ 回来
            menuButton.visibility = View.GONE
            menuButton.iconRotation = 0f
            expandButton.visibility = View.VISIBLE
            expandButton.dimmed = candidates.isEmpty()
        }
        onToolbarModeChanged?.invoke(value)
    }

    /**
     * 应用工具栏展开 / 收起：menuButton 的 › **旋转 180° 翻成 ‹**（200ms，
     * 照 fcitx5 menuButton 的 iconAnimate().rotation(200ms)），按钮排丝滑滑入滑出
     * （照 IdleUi 的 in/out AnimationSet：淡入淡出 + 水平 30% 平移，200ms）。
     * 收起/展开的业务决策在 JimengIME.onToolbarToggle，这里只管画。
     */
    fun setToolbarExpanded(value: Boolean) {
        if (toolbarExpanded == value) return
        toolbarExpanded = value
        if (toolbarMode && value) {
            toolbarRow.visibility = View.VISIBLE
            toolbarRow.startAnimation(toolbarInAnim)
        } else {
            // 先播滑出、动画走完再落 GONE，收起的过程才看得见；
            // 200ms 内又点开的话（toolbarExpanded 已翻回 true）就不收了
            toolbarRow.startAnimation(toolbarOutAnim)
            toolbarRow.postDelayed({
                if (!toolbarExpanded) toolbarRow.visibility = View.GONE
            }, 200L)
        }
        // ›（-90°）↔ ‹（+90°）丝滑翻身：转的是**图标**不是按钮本身
        // （照 fcitx5 menuButton 的 iconAnimate().rotation(200ms)）
        android.animation.ObjectAnimator
            .ofFloat(menuButton, "iconRotation", menuButton.iconRotation, menuTargetRotation)
            .apply { duration = 200L; start() }
    }
}