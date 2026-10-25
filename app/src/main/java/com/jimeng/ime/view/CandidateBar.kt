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
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.prefs.ManagedPreference
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.SkinCache
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
 * 这样出来的效果就是用户要的：「整个候选栏是固定的，字在里面排版，
 * 超过显示上限才缩小」。
 */
class CandidateBar(context: Context) : FrameLayout(context) {

    companion object {
        /** 候选栏高度。
         *
         *  40f → 34f：用户要「候选栏上下压一点，给码区留位置」。
         *  压候选栏而不是压键盘，是因为键盘是 4 行按键、压了就不好按；
         *  候选栏只是文字，34dp 放一行 20sp 的字仍有余量。
         *
         *  压下来的 6dp 不会浪费：码区浮层的锚点是
         *  「键盘高度 + 候选栏高度」，候选栏一矮，码区就自动下移 6dp，
         *  正好拉开和翻译栏的距离。见 JimengIME.buildInputContents。
         */
        const val HEIGHT_DP = 34f

        /**
         * 一页最多显示几个候选。照 fcitx5 的
         * `expandedCandidateGridSpanCount`（竖屏默认 6）。
         * 超出的候选交给展开页。
         */
        const val MAX_ITEMS = 6

        /** 每格最小宽度，照原版适配器里的 `minimumWidth = dp(40)` */
        private const val ITEM_MIN_WIDTH_DP = 40f

        /**
         * 每格左右内边距。原版是 10dp，这里收窄到 5dp。
         *
         * 原因：一格 = 栏宽 / 6 ≈ 53dp（360dp 屏幕上），而两个字在 20sp 下要占 40dp，
         * 左右各留 10dp 就变成 60dp > 53dp，第 6 格会被挤到第二行（然后被 40dp 高的
         * 候选栏裁掉，看着只有 5 格）。收到 5dp 后一格只要 50dp，六个字按**原大小**
         * 就放得下，不用缩字，也不用把格子撑成固定等宽。
         */
        private const val ITEM_PADDING_DP = 5f
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

    /** 点文本编辑按钮：整块换成光标 / 编辑页（照 fcitx5 ButtonsBarUi.cursorMoveButton） */
    var onTextEditingClicked: (() -> Unit)? = null

    /** ⋯ 按钮：弹出菜单（设置收在里面） */
    val moreButton = ToolbarButtonView(context, theme, R.drawable.ic_more_horiz_24) {
        onMoreClicked?.invoke()
    }

    /** 工具栏模式切换回调（false = 回候选区），JimengIME 用来收弹出菜单 */
    var onToolbarModeChanged: ((Boolean) -> Unit)? = null

    // ---- 候选栏上的「快捷粘贴」----

    /** 点了快捷粘贴胶囊：把这条内容上屏 */
    var onQuickPasteClicked: (() -> Unit)? = null

    /**
     * 快捷粘贴胶囊。只在「空闲（没打字）+ 不是换页 + 有待粘贴内容」时露脸，
     * 顶替中间那块候选区——右端那一格（⌄ / 🎤 / 收键盘）不动。
     */
    private val quickPasteView = ClipboardQuickPasteView(context, theme).apply {
        setOnClickListener { onQuickPasteClicked?.invoke() }
        visibility = View.GONE
    }

    /** 当前待粘贴内容（null = 没有，胶囊不露脸） */
    private var quickPasteText: String? = null

    // ---- 语音条槽位 ----

    /**
     * 语音条的宿主。占的就是中间那块候选区（右端那一格不占），
     * 高度只有候选栏的 40dp——**不额外吃窗口高度，也不会盖住上面的应用**。
     *
     * 用户原来那句「那个录音弹窗能不能放到候选栏里」，落点就是这里：
     * 以前是挂在 holder 上、飘在键盘上方的一块卡片，现在整块搬进这一格。
     */
    private val voiceSlot = android.widget.FrameLayout(context).apply {
        visibility = View.GONE
    }

    /** 现在是不是语音条在占着中间那块地方。 */
    private var voiceMode = false

    /**
     * 把语音条挂进候选栏。
     *
     * 由 JimengIME 在搭视图时调一次；之后语音那边只管自己的显示 / 收起，
     * 候选栏靠 VoiceBarUi.onVisibleChanged 收到通知自动让位 / 复位。
     */
    fun attachVoiceBar(view: View) {
        voiceSlot.removeAllViews()
        voiceSlot.addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
    }

    /**
     * 语音条露脸 / 收起时切换候选栏模式。
     *
     * 露脸：候选区、工具栏、换页标题条全部让位，右端那一格**留着麦克风**——
     * 录音中再按一下就是「说完了」，这个语义跟原来一模一样。
     * 收起：按当前状态（打字中 / 空闲 / 换页）原样还回去。
     */
    fun setVoiceMode(active: Boolean) {
        if (voiceMode == active) return
        voiceMode = active
        voiceSlot.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            coverByVoice()
        } else {
            restoreAfterVoice()
        }
    }

    /** 语音条占位：中间那块地方只留它和右端的麦克风。 */
    private fun coverByVoice() {
        recycler.visibility = View.GONE
        quickPasteView.visibility = View.GONE
        menuButton.visibility = View.GONE
        toolbarRow.visibility = View.GONE
        titleRow.visibility = View.GONE
        pageExtension.visibility = View.GONE
        expandButton.visibility = View.GONE
        hideKeyboardButton.visibility = View.GONE
        micButton.visibility = View.VISIBLE
    }

    /** 语音条走了：按当前模式把中间那块地方还回去。 */
    private fun restoreAfterVoice() {
        recycler.visibility = View.VISIBLE
        if (pageMode) {
            // 极少见：录音期间被换页盖掉。让页面自己那一套恢复
            titleRow.visibility = View.VISIBLE
            pageExtension.visibility =
                if (pageExtension.childCount > 0) View.VISIBLE else View.GONE
            menuButton.visibility = View.GONE
            toolbarRow.visibility = View.GONE
            expandButton.visibility = View.GONE
            micButton.visibility = View.GONE
            hideKeyboardButton.visibility = View.GONE
            return
        }
        titleRow.visibility = View.GONE
        pageExtension.visibility = View.GONE
        if (toolbarMode) {
            menuButton.visibility = View.VISIBLE
            menuButton.iconRotation = if (toolbarExpanded) 90f else -90f
            toolbarRow.clearAnimation()
            toolbarRow.visibility = if (toolbarExpanded) View.VISIBLE else View.GONE
            expandButton.visibility = View.GONE
            applyIdleRightSlot()
        } else {
            menuButton.visibility = View.GONE
            menuButton.iconRotation = 0f
            toolbarRow.visibility = View.GONE
            micButton.visibility = View.GONE
            hideKeyboardButton.visibility = View.GONE
            expandButton.visibility = View.VISIBLE
            expandButton.dimmed = candidates.isEmpty()
        }
        applyQuickPasteSlot()
    }

    /**
     * 设置 / 清空候选栏上的快捷粘贴。
     *
     * 传 null 就是收起来（用户已经在键盘上做过操作，或者内容被剪贴板同步清掉了）。
     */
    fun setQuickPaste(text: String?) {
        val changed = quickPasteText != text
        quickPasteText = text
        if (text != null && changed) quickPasteView.bind(text)
        // 内容没变也得重算可见性：可能刚从换页退回来、或者刚从打字态回到空闲
        applyQuickPasteSlot()
    }

    /**
     * 决定快捷粘贴胶囊露不露脸。
     *
     * 三个条件缺一不可：
     *   1. 有内容（[quickPasteText] 非空）
     *   2. 空闲 —— [toolbarMode] 为 true，说明没有候选在显示
     *   3. 不在换页 —— 换页时那块地方归剪贴板页 / 菜单页
     */
    private fun applyQuickPasteSlot() {
        quickPasteView.visibility = if (
            quickPasteText != null && toolbarMode && !pageMode && !voiceMode
        ) View.VISIBLE else View.GONE
    }

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

    /**
     * 换页时右端的「页面扩展位」（照 fcitx5 的 InputWindow.onCreateBarExtension）。
     *
     * 原版剪贴板页在候选栏右端挂一个 delete_sweep 清空按钮，文本编辑页挂一个
     * 剪贴板按钮——都是「这一页自己的操作」。我们照这个思路留一个槽：
     * setPage 时把页面给的 View 塞进来，clearPage 时摘掉。
     */
    private val pageExtension = android.widget.FrameLayout(context).apply {
        visibility = View.GONE
    }

    /** 当前是不是在「换页」状态（剪贴板 / 更多） */
    private var pageMode = false

    /**
     * 进页面之前工具栏按钮排是不是展开着的。
     *
     * 进去时会把 toolbarRow 藏起来，如果只藏不复位 [toolbarExpanded]，
     * 返回后状态是「展开」但视图是 GONE——再点箭头会因「值没变」直接 return，
     * 按钮排永远回不来（用户报的那个 bug）。所以要记一笔，返回时原样恢复。
     */
    private var pageRestoreExpanded = false

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

    // ---- 右端「一格两身份」（照 fcitx5：候选展开箭头 ↔ 语音麦克风）----

    /** 点麦克风：交给 JimengIME 接语音转文本 */
    var onMicClicked: (() -> Unit)? = null

    /**
     * 语音输入按钮：跟候选展开箭头**共用右端这一格**。
     *
     * 照 fcitx5-android 的做法（IdleUi.hideKeyboardButton / CandidateUi.expandButton）：
     * 原版右端从来只有一个格子，打字时是「展开候选 ⌄」，不打字时换成麦克风。
     * 两者互斥、同一位置，所以视觉上不挤，手指也永远知道右端那一格管什么。
     *
     * 这里我做成「两个 View 叠在同一位置、同一尺寸」，按状态切换可见性——
     * 比原版换图标的做法省事，图标本身也不用重画（⌄ / 🎤 差异太大）。
     */
    val micButton = ToolbarButtonView(context, theme, R.drawable.ic_mic_24) {
        onMicClicked?.invoke()
    }

    // ---- 右端「一格三身份」的第三种：收起键盘 ----

    /** 点收起键盘箭头：交给 JimengIME 调 requestHideSelf() */
    var onHideKeyboardClicked: (() -> Unit)? = null

    /**
     * 收起键盘箭头：**只在「空闲 + 关掉语音按钮」时**顶替麦克风出现。
     *
     * 用户要的效果：
     *   - 打字中：右端永远是可展开的候选箭头（不能动，动了没法开九宫格）
     *   - 空闲 + 语音按钮开：麦克风
     *   - 空闲 + 语音按钮关：**这个更细更淡、底下带一道短横线的 ⌄**，点了收键盘
     *
     * 为什么闲时不用候选展开箭头兼职收键盘：⌄ 在用户的直觉里已经等于
     * 「往下展开候选」了，同一个形状干两件相反的事（一个展开、一个收起），
     * 一定会按错。所以闲时干脆换成形状明显不同的这一个，语义和样子对上。
     */
    val hideKeyboardButton = HideKeyboardButtonView(context, theme).apply {
        setOnClickListener {
            // 换页 / 打字中都不该响（那时它本来就是 GONE，这里再兜一道）
            if (!pageMode && toolbarMode) onHideKeyboardClicked?.invoke()
        }
    }

    /** 键盘偏好（语音按钮开关就住这儿）。用 get() 现取，避免和 AppPrefs.init 的时序打架 */
    private val appPrefs get() = AppPrefs.getInstance().keyboard

    /**
     * 「显示语言输入按钮」开关变了的时候，立刻把右端那一格换过来。
     *
     * 不换的话，用户在设置页关掉开关，回到键盘还得等下次重建视图才生效——
     * 用户会以为没生效。这里当场换，跟 showLangSwitchKey 一个待遇。
     */
    private val voiceButtonListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        // 只在「空闲且不在换页」时管：打字中右端是候选箭头，换页时整格让位
        if (toolbarMode && !pageMode) applyIdleRightSlot()
    }

    /**
     * 决定空闲时右端那一格露谁：麦克风 or 收起键盘箭头。
     *
     * 只在**空闲**时调用。打字中那一格固定是候选展开箭头，谁也别来抢。
     */
    private fun applyIdleRightSlot() {
        // 录音中右端固定是麦克风（再按一下「说完了」），谁也别来抢
        if (voiceMode) return
        expandButton.visibility = View.GONE
        if (appPrefs.showVoiceInputButton.getValue()) {
            hideKeyboardButton.visibility = View.GONE
            micButton.visibility = View.VISIBLE
        } else {
            micButton.visibility = View.GONE
            hideKeyboardButton.visibility = View.VISIBLE
        }
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
            // 照原版：minWidth 和 flexGrow 在绑定时刷；
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
                val child = getChildAt(i) ?: continue
                child.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
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
        // 皮肤给了候选栏背景图就用图，没给就纯色。图坏掉时 SkinCache 自动回退纯色。
        val bgImage = theme.candidateBarImage
        background = if (bgImage != null) {
            SkinCache.drawable(context, theme.skinId, bgImage, fallbackColor = theme.keyboardColor)
        } else {
            null
        }
        if (bgImage == null) setBackgroundColor(theme.keyboardColor)
        // 整条候选栏都吃掉触摸，绝不放行到下层应用
        isClickable = true
        // 右端只有一个格子（照 fcitx5）：打字时是 ⌄ 展开箭头，不打字时是 🎤 麦克风。
        // 两个 View 叠在同一位置同一尺寸，靠可见性切换——绝不同时出现，也不会挤。
        val rightSlot = dp(ExpandButtonView.WIDTH_DP).toInt()
        // 候选区：RecyclerView 占满，右端让出那一格
        addView(
            recycler,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                rightMargin = rightSlot
            }
        )
        // 快捷粘贴胶囊：跟候选区同位置同尺寸，盖在 RecyclerView 之上。
        // 只在空闲时露脸（那时 RecyclerView 本来就是空的），所以不会挡住候选。
        addView(
            quickPasteView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                rightMargin = rightSlot
            }
        )
        addView(
            expandButton,
            LayoutParams(rightSlot, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )
        // 麦克风：跟展开箭头同位置同尺寸，初始隐藏（不打字时才换上它）
        addView(
            micButton,
            LayoutParams(rightSlot, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )
        micButton.visibility = View.GONE
        // 收起键盘箭头：右端那一格的**第三张脸**。跟麦克风同一个位置，
        // 空闲时按「显示语言输入按钮」开关二选一露面（见 applyIdleRightSlot）
        addView(
            hideKeyboardButton,
            LayoutParams(rightSlot, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )
        hideKeyboardButton.visibility = View.GONE
        // 开关一变就当场换脸（跟 showLangSwitchKey 那套一样）
        appPrefs.showVoiceInputButton.registerOnChangeListener(voiceButtonListener)

        // ---- 工具栏：最左 menuButton + 中段按钮排（照 fcitx5 IdleUi 的结构）----
        // menuButton 的 ⌄ 靠旋转扫成 ›/‹；展开时按钮排从它右边滑出来
        addView(
            menuButton,
            LayoutParams(dp(ToolbarButtonView.WIDTH_DP).toInt(), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        )
        toolbarRow.addView(
            ToolbarButtonView(context, theme, R.drawable.ic_cursor_move_24) {
                onTextEditingClicked?.invoke()
            },
            toolbarLp()
        )
        toolbarRow.addView(
            ToolbarButtonView(context, theme, R.drawable.ic_clipboard_24) {
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
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                // 左边给最左的 menuButton 让位
                leftMargin = dp(ToolbarButtonView.WIDTH_DP).toInt()
                // 右边给麦克风那一格让位（跟候选栏右端让出展开箭头是同一个道理）
                rightMargin = dp(ToolbarButtonView.WIDTH_DP).toInt()
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
            pageExtension,
            LayoutParams(dp(ToolbarButtonView.WIDTH_DP).toInt() * 2, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )
        addView(
            titleRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                // 换页时麦克风隐藏，标题条直接铺满整行：不留下那块空白，看着才干净
                rightMargin = 0
            }
        )
        // 语音条：跟候选区同一个位置、同一个高度，盖在所有东西之上。
        // 右端那格让出来，录音中还能按麦克风「说完了」。
        // 平时 GONE，不参与任何绘制和触摸。
        addView(
            voiceSlot,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                rightMargin = rightSlot
            }
        )
    }

    /**
     * 切进「换页」模式：候选区 / 工具栏全部让位，只显示「← 标题」。
     *
     * 照 fcitx5 的 KawaiiBarStateMachine：State 从 Idle / Candidate 切到 Title 时，
     * 整条 bar 换成 TitleUi，剪贴板和更多页都是这么进去的。
     */
    fun setPage(title: String, extension: View? = null) {
        pageMode = true
        // 记下当前工具栏展开状态，返回时要原样还回去
        pageRestoreExpanded = toolbarExpanded
        // 进页面时把工具栏整个收干净（状态也要复位，不能只藏视图）
        toolbarExpanded = false
        toolbarRow.visibility = View.GONE
        expandButton.expanded = false
        titleText.text = title
        recycler.visibility = View.GONE
        expandButton.visibility = View.GONE
        menuButton.visibility = View.GONE
        toolbarRow.visibility = View.GONE
        // 换页时右端那一格整个让位：标题条要占满整行，留着会被点到
        micButton.visibility = View.GONE
        hideKeyboardButton.visibility = View.GONE
        // 页面自己的操作按钮挂到右端扩展位（照 fcitx5 的 onCreateBarExtension）
        pageExtension.removeAllViews()
        if (extension != null) {
            // 撑满这 80dp 槽，页面自己决定内容靠左还是靠右：
            // 剪贴板的删除图标要贴最右，文本编辑的剪贴板按钮跟原来一样靠左
            pageExtension.addView(
                extension,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            pageExtension.visibility = View.VISIBLE
        } else {
            pageExtension.visibility = View.GONE
        }
        titleRow.visibility = View.VISIBLE
        // 进换页：快捷粘贴让位（那块地方归剪贴板页 / 菜单页）
        applyQuickPasteSlot()
    }

    /** 退出「换页」模式，回到正常状态（候选 / 工具栏由 toolbarMode 决定） */
    fun clearPage() {
        if (!pageMode) return
        pageMode = false
        titleRow.visibility = View.GONE
        // 页面走了，它挂的扩展按钮也摘掉
        pageExtension.removeAllViews()
        pageExtension.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        // 右端那一格：回到页面以前的模式（打字中 ⌄ / 空闲 🎤 或收起键盘），下面两个分支里恢复
        micButton.visibility = View.GONE
        hideKeyboardButton.visibility = View.GONE
        // 注意：这里必须显式复原，不能指望 JimengIME 再调一次 setToolbarMode——
        // toolbarMode 的值通常没变，那边会提前 return，menuButton 就永远藏着了。
        if (toolbarMode) {
            menuButton.visibility = View.VISIBLE
            // 空闲：右端那一格按开关露麦克风或收起键盘箭头
            expandButton.visibility = View.GONE
            applyIdleRightSlot()
            // 进来前按钮排是展开的 -> 让它在返回后依旧展开（不播动画，瞬时还原）
            toolbarExpanded = pageRestoreExpanded
            expandButton.expanded = false
            if (toolbarExpanded) {
                toolbarRow.visibility = View.VISIBLE
                menuButton.iconRotation = 90f
            } else {
                toolbarRow.visibility = View.GONE
                menuButton.iconRotation = -90f
            }
        } else {
            menuButton.visibility = View.GONE
            menuButton.iconRotation = 0f
            // 打字中：右端那一格回到候选展开箭头（不受语音按钮开关影响）
            micButton.visibility = View.GONE
            hideKeyboardButton.visibility = View.GONE
            expandButton.visibility = View.VISIBLE
            expandButton.dimmed = candidates.isEmpty()
        }
        pageRestoreExpanded = false
        // 退出换页：回到空闲的话快捷粘贴该重新露脸
        applyQuickPasteSlot()
    }

    /**
     * 视图从窗口上摘掉时，把 prefs 监听也摘掉。
     *
     * 输入法视图一般活得比服务久，但不摘的话，万一重建视图，
     * 旧的 CandidateBar 还在监听，两个实例抢着改自己那张已经不在屏幕上的图，
     * 白费劲还容易看不清是谁在生效。
     */
    override fun onDetachedFromWindow() {
        try {
            appPrefs.showVoiceInputButton.unregisterOnChangeListener(voiceButtonListener)
        } catch (_: Throwable) {
            // prefs 还没初始化过就没什么可摘的
        }
        super.onDetachedFromWindow()
    }

    /**
     * 工具栏按钮的布局参数：**宽度按份数均分铺满**，跟候选栏的格子一致。
     *
     * 候选栏每格是「栏宽 / 6」平分出来的，格子之间的间隔天然相等；
     * 工具栏原来固定 40dp 紧贴左边排，右边空一大片，间隔和候选栏完全不同。
     * 改成 weight=1 均分后，按钮之间的间隔就和候选栏一样是平的。
     *
     * 注意必须用 LinearLayout.LayoutParams —— toolbarRow 是 LinearLayout，
     * 传 FrameLayout 的那套进去会被 generateLayoutParams 重建成 weight=0 的，
     * 看上去就是「全都挤在左边不动」。
     */
    private fun toolbarLp() = android.widget.LinearLayout.LayoutParams(
        0,
        LayoutParams.MATCH_PARENT,
        1f
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
     * expandedCandidateGridSpanCountLandscape（默认 12）；用户这台主要竖屏用，
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
        // 这里**不再**自己 `if (toolbarMode) setToolbarMode(false)`。
        //
        // 照 fcitx5：CandidateUi 和 IdleUi 谁露面是由 KawaiiBarStateMachine 的
        // 状态决定的（switchUiByState 只读状态），候选适配器只负责画格子，
        // 从不去改「当前是候选还是工具栏」。
        // 以前这里顺手切一刀，等于在状态机之外又有一个地方能改模式，
        // 两处判断一旦不同步就是用户看到的「候选栏状态和引擎对不上」。
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
        // 换页期间：整条候选栏归页面管（标题条 + 页面自己的扩展按钮）。
        // 这里只把值记下来，不碰视图、也不回调 —— 回调会让 JimengIME 走到
        // `if (!showing) detachPage()`，用户在菜单里点个「简繁」就被踢回键盘，
        // 想接着调下一项还得重新点进菜单。真实模式由 clearPage() 在退出时按
        // 这个值原样还原。
        if (pageMode) return
        // 模式切换不播动画（照 fcitx5：非用户触发的状态变化 instant）
        toolbarExpanded = false
        if (voiceMode) {
            // 录音条正占着中间那块：视图先别动，等它收起时由 restoreAfterVoice 统一还位。
            // 这里要是照常切，右端那一格会从麦克风变成 ⌄，正在录音的用户就找不到「说完了」。
            onToolbarModeChanged?.invoke(value)
            return
        }
        toolbarRow.visibility = View.GONE
        expandButton.expanded = false
        if (value) {
            // 进工具栏：menuButton 以 › 露脸（图标 -90°），按钮排收着
            menuButton.visibility = View.VISIBLE
            menuButton.iconRotation = -90f
            // 空闲：右端那一格按「显示语言输入按钮」开关决定是麦克风还是收起键盘箭头
            // 换页期间不碰：整格已经让位给标题条了，露出来会盖在「← 剪贴板」上
            if (!pageMode) {
                applyIdleRightSlot()
            }
        } else {
            // 回候选区：menuButton 藏起来，右端 ⌄ 回来，麦克风/收键盘都让位
            menuButton.visibility = View.GONE
            menuButton.iconRotation = 0f
            if (!pageMode) {
                micButton.visibility = View.GONE
                hideKeyboardButton.visibility = View.GONE
                expandButton.visibility = View.VISIBLE
                expandButton.dimmed = candidates.isEmpty()
            }
        }
        onToolbarModeChanged?.invoke(value)
        // 空闲 ↔ 候选 / 换页 都可能改变快捷粘贴该不该露脸
        applyQuickPasteSlot()
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