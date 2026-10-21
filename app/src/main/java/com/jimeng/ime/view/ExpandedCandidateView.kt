package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.utils.parsePreedit
import com.jimeng.ime.kb.utils.setVerticalScrollbarThumbColor
import com.jimeng.ime.kb.utils.singleSideBorderDrawable

/**
 * 候选展开页。排版照 fcitx5-android 的 ExpandedCandidateLayout + GridExpandedCandidateWindow，
 * 左侧再加一列拼音（主人要求，像谷歌/fcitx5那样）。
 *
 * 样子（竖屏）：
 *   ┌──────┬────────────────────────┬─────┐
 *   │ ni   │ 候选1 │ 候选2 │ 候选3  │  ↑  │
 *   ├──────┼───────┼───────┼────────┤     │
 *   │ li   │ 候选4 │ 候选5 │ 候选6  │  ↓  │
 *   ├──────┼───────┼───────┼────────┤     │
 *   │ mi   │ 候选7 │ 候选8 │ 候选9  │  ⌫  │
 *   ├──────┼───────┼───────┼────────┤     │
 *   │ 单字 │  ...                   │  ↵  │
 *   └──────┴────────────────────────┴─────┘
 *
 * 三段结构：
 *   - 左侧 18% 宽：拼音列，**只显示当前正在转换的那一个音节**（高亮），
 *     下面跟着它的模糊音变体（zh/z、ch/c、sh/s、n/l、an/ang… 这些容易打错的谐音）。
 *     选完这个字，引擎把拼音吃掉一段，下一个音节自动顶上来——就是
 *     谷歌/fcitx5 那种「先第一个拼音，选完才显示第二个」的节奏。
 *   - 中间：候选网格，竖屏 6 列、横屏 8 列（原版 expandedCandidateGridSpanCount 默认值）
 *   - 右侧 15% 宽：操作键 ↑ ↓ ⌫ ↵
 *
 * 当前停在哪一个音节由 context 的 sel_start 换算成段号得到。
 * 点模糊音变体 = 用那个拼法把整串重打一遍，再回到这一段的候选。
 *
 * 和 fcitx5 的差异：fcitx5 左侧是「简繁转换」之类的 tab action，rime 没这个概念，
 * 这里换成了主人要的拼音。
 */
@SuppressLint("ViewConstructor")
class ExpandedCandidateView(
    context: Context,
    private val theme: Theme,
    /**
     * 展开页高度（px），一般等于键盘高度。
     * 现在滚动交给布局管理器了，这个值只作兼容保留。
     */
    @Suppress("unused") private val keyboardHeightPx: Int
) : LinearLayout(context) {

    companion object {
        /** 网格列数：竖屏 6（原版默认） */
        private const val SPAN_PORTRAIT = 6

        /** 网格列数：横屏 8（原版默认） */
        private const val SPAN_LANDSCAPE = 8

        /** 右侧操作列宽度比例，对齐原版 matchConstraintPercentWidth = 0.15f */
        private const val ACTION_COLUMN_PERCENT = 0.15f

        /** 左侧拼音列宽度比例，对齐原版 tabsContainer 的 matchConstraintPercentWidth = 0.15f */
        private const val PREEDIT_COLUMN_PERCENT = 0.15f

        /** 左边最多列几个谐音变体，太多反而挑花眼 */
        private const val MAX_FUZZY_VARIANTS = 6

        /**
         * tab id 约定（`onTriggerTabAction` 收到的就是这些）：
         *   ≥ 0  → 第几个谐音变体
         *   -1   → 第 0 个音节格（当前正在转换的那一个）
         *   -2   → 第 1 个音节格
         *   ……
         *   音节格 id = TAB_SYLLABLE_BASE + 下标，见 [syllableTabId]
         *   -100 → 底部固定的「单字」开关
         *
         * 用「音节格用负数、变体用非负数」来区分，因为两批数据都在同一个
         * 滚动列表里，必须能一眼认出点的是哪一类。
         */
        private const val TAB_SYLLABLE_BASE = -1
        private const val TAB_SINGLE_CHAR = -100

        /** 第 [index] 个音节格对应的 tab id */
        private fun syllableTabId(index: Int) = TAB_SYLLABLE_BASE - index

        /** 竖屏 keyVerticalMargin 默认 7dp、横屏 4dp（照 ThemePrefs.twinInt 的默认值） */
        private const val KEY_VERTICAL_MARGIN_DP = 7f
        private const val KEY_VERTICAL_MARGIN_LANDSCAPE_DP = 4f

        /**
         * 韵母模糊音对照，**长的写在前面**。
         *
         * 顺序很重要：`iang` 必须比 `ang` 先匹配，
         * 否则 `xiang` 会先被 `ang→an` 变成 `xian`（这个恰好也对），
         * 但 `xiong` 会被 `ong→un` 变成 `xiun`（错的读音）。
         */
        private val FUZZY_SUFFIX = listOf(
            "iang" to "ian",
            "uang" to "uan",
            "ang" to "an",
            "eng" to "en",
            "ing" to "in"
        )

        /**
         * 汉语拼音的声母表，**两字母的写在前面**（zh/ch/sh 要先于 z/c/s 匹配），
         * 否则 "zhong" 会被拆成 z + hong。
         */
        private val INITIALS = listOf(
            "zh", "ch", "sh",
            "b", "p", "m", "f", "d", "t", "n", "l",
            "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"
        )

        /**
         * 容易打错的声母：键是正确拼法，值是可能被写成的谐音。
         *
         * 平翘舌（zh/z、ch/c、sh/s）、n/l、f/h、l/r、g/k 是方言里最常混的；
         * b/p/m 和 d/t 是双唇音、舌尖音互混（`bu` 打成 `pu`、`mu`）。
         * 主人明确要过「`bu` 也要能点出它的同音拼音」，所以这两组必须在。
         */
        private val INITIAL_FUZZY: Map<String, List<String>> = mapOf(
            "zh" to listOf("z"),
            "z" to listOf("zh"),
            "ch" to listOf("c"),
            "c" to listOf("ch"),
            "sh" to listOf("s"),
            "s" to listOf("sh"),
            "n" to listOf("l"),
            "l" to listOf("n", "r"),
            "f" to listOf("h"),
            "h" to listOf("f"),
            "r" to listOf("l"),
            "g" to listOf("k"),
            "k" to listOf("g"),
            "b" to listOf("p", "m"),
            "p" to listOf("b", "m"),
            "m" to listOf("b", "p"),
            "d" to listOf("t"),
            "t" to listOf("d")
        )
    }

    interface Listener {
        /** 点了某个候选，index 是**全局**下标（不是页内下标） */
        fun onPickCandidate(globalIndex: Int)

        /** 点了退格 */
        fun onBackspace()

        /** 点了回车 */
        fun onReturn()

        /**
         * 点了左边某个**音节格**（不是变体）。
         *
         * @param index 它在「还没转换音节列表」里的位置（0 = 最前面那个）
         *
         * 主人要的语义：默认谁都不高亮，点了哪一个才高亮哪一个；
         * 再点一次同一个就取消高亮。高亮 = 锁定，此时改谐音只改这一段。
         */
        fun onPickSyllable(index: Int)

        /**
         * 点了左边某个谐音变体 [variant]。
         *
         * 改的是**当前锁定的那个音节**；没有锁定时改最前面那个
         * （也就是引擎正在转换的那一段）。
         */
        fun onPickFuzzyVariant(variant: String)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val spanCount: Int =
        if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) SPAN_LANDSCAPE else SPAN_PORTRAIT

    /**
     * 候选网格。**照抄 fcitx5 的 `ExpandedCandidateLayout.recyclerView`**：
     * RecyclerView + GridLayoutManager（spanSizeLookup = [SpanHelper]）+ [GridDecoration]。
     *
     * 为什么换掉自己画的 View：自绘那版把「算格子 / 量宽度 / 处理触摸 / 缩放文字」
     * 全塞在一个 onDraw 里，任何一处和原版差一点，看着就是「差点意思」。
     * 原版这套是 RecyclerView 负责滚动和回收、SpanHelper 负责分格、
     * GridDecoration 负责线、CandidateItemView 负责一格的样子，
     * 每件事都由系统或原版代码管，没有自己发挥的空间。
     */
    private val candidateAdapter = PagingCandidateAdapter(theme)

    private val gridLayoutManager = GridLayoutManager(context, spanCount).apply {
        spanSizeLookup = SpanHelper(candidateAdapter, this)
    }

    private val gridDivider by lazy {
        android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape()).apply {
            val size = maxOf(1, dp(1f).toInt())
            intrinsicWidth = size
            intrinsicHeight = size
            paint.color = theme.dividerColor
        }
    }

    private val grid = RecyclerView(context).apply {
        itemAnimator = null
        adapter = candidateAdapter
        layoutManager = gridLayoutManager
        addItemDecoration(GridDecoration(gridDivider))
        isVerticalScrollBarEnabled = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private val actionColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.keyboardColor)
    }

    /**
     * 左侧拼音列。**照抄 fcitx5 的 `ExpandedCandidateLayout.tabsContainer`**：
     *
     *   ┌ tabsContainer（0.15 宽，背景 = 右边一条 1dp 竖线、上下缩 keyVerticalMargin）
     *   │  ├ scrollableTabs（RecyclerView，可滚，占满剩余高度）
     *   │  └ pinnedTabs    （RecyclerView，固定贴底，wrapContent 高，不跟着滚）
     *
     * 可滚区放「当前音节 + 谐音变体」，pinned 区放常驻的「单字」。
     * 两个都用 verticalLayoutManager，条目的样子都来自 PreeditTabActionsAdapter。
     */
    private val tabsContainer = FrameLayout(context).apply {
        // 照原版：singleSideBorderDrawable(dp(1), theme.dividerColor, Gravity.RIGHT, inset)
        // inset = 竖屏 7dp / 横屏 4dp，所以右边那条竖线不顶到上下边
        val landscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val inset = dp(
            if (landscape) KEY_VERTICAL_MARGIN_LANDSCAPE_DP else KEY_VERTICAL_MARGIN_DP
        ).toInt()
        background = singleSideBorderDrawable(
            maxOf(1, dp(1f).toInt()),
            theme.dividerColor,
            Gravity.RIGHT,
            inset
        )
    }

    /** 可滚动的那一份 tab：先列全部音节格，再列选中音节的谐音变体 */
    private val preeditTabsAdapter = object : PreeditTabActionsAdapter(theme, false) {
        override fun onTriggerTabAction(id: Int) {
            if (id >= 0) {
                // 谐音变体：id 就是它在变体列表里的位置
                val variant = currentVariants.getOrNull(id) ?: return
                listener?.onPickFuzzyVariant(variant)
                return
            }
            // 音节格：id = TAB_SYLLABLE_BASE - 下标
            val index = TAB_SYLLABLE_BASE - id
            if (index < 0 || index >= syllables.size) return
            listener?.onPickSyllable(index)
        }
    }

    /**
     * 固定贴底的那一份（「单字」）。
     *
     * 用 `indicatorStyle = true`，和原版的 pinned tabs 一样：
     * 平时透明 + 底下一条 altKey 色的小条，开着时小条变 accent 色。
     */
    private val pinnedTabsAdapter = object : PreeditTabActionsAdapter(theme, true) {
        override fun onTriggerTabAction(id: Int) {
            if (id == TAB_SINGLE_CHAR) toggleSingleChar()
        }
    }

    /** 可滚动 tab 区（照原版 scrollableTabs） */
    private val scrollableTabs = RecyclerView(context).apply {
        itemAnimator = null
        // 照原版：常显滚动条、1dp 宽、thumb 用 candidateTextColor
        isScrollbarFadingEnabled = false
        scrollBarSize = maxOf(1, dp(1f).toInt())
        setVerticalScrollbarThumbColor(theme.candidateTextColor)
        adapter = preeditTabsAdapter
        layoutManager = LinearLayoutManager(context)
        isVerticalScrollBarEnabled = true
        // 拼音列右侧那条竖线由 tabsContainer 画，这里不要自己的背景
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    /** 固定贴底的 tab 区（照原版 pinnedTabs） */
    private val pinnedTabs = RecyclerView(context).apply {
        itemAnimator = null
        // 照原版：底部 pinned 区不滚，也不把滚动传给外层
        overScrollMode = OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false
        isVerticalScrollBarEnabled = false
        adapter = pinnedTabsAdapter
        layoutManager = LinearLayoutManager(context)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    /** 当前这一格音节的谐音变体（点了第几个就从这里取），和列表显示保持同一份 */
    private var currentVariants: List<String> = emptyList()

    /**
     * 还没转换的**全部**音节（如 `buhaowan` → ["bu", "hao", "wan"]）。
     *
     * 注意：这份是**内部完整列表**，左列不会全列出来——只显示 [targetSyllable]
     * 那一节（外加它的谐音变体）。留着完整列表是为了两件事：
     *   1. 算出「当前这一节在整串字母里的起点」（[planFuzzyPick]）；
     *   2. 判断音节个数有没有变（个数变了说明选掉/退掉了一节，锁定要作废）。
     */
    private var syllables: List<String> = emptyList()

    /**
     * 主人**键盘上打出来的**原始拼法——这一节的「正主」，左列第一格永远显示它。
     *
     * 主人明确要求的：打了 `hui`，不管后面点了 `fui` 还是哪个谐音，
     * 第一格都得是 `hui`，不许被点过的那个谐音顶掉位置。
     *
     * 为什么这个字段以前不管用：旧做法是「删光字母 → 一个个重打」，
     * 中间会经历 "" → "f" → "fu" → "fui" 好几个状态，每个状态都触发
     * `updatePreedit`，于是 originalSyllable 被一路覆盖成 "fui"。
     *
     * 现在换谐音走 `nativeSetInput` 一步到位（见 `JimengIME.pickFuzzyVariant`），
     * 中间状态没有了，这个字段才真正稳得住。
     */
    private var originalSyllable: String = ""

    /**
     * 现在**喂给引擎的**是哪一个谐音拼法；null = 引擎里就是 [originalSyllable] 本身。
     *
     * 用来分辨「引擎里这一节换了拼法」是我们点出来的、还是主人自己改的字母：
     *   · 等于它 → 我们点谐音换的，[originalSyllable] 保住不动；
     *   · 都不等 → 主人自己在键盘上改了字母，[originalSyllable] 跟着换。
     */
    private var appliedVariant: String? = null

    /**
     * 被主人点中并**锁定**的音节下标；null = 没有任何一段被锁（默认状态）。
     *
     * 锁定的含义：
     *   - 左列对应的那一格高亮；
     *   - 点谐音变体时，改的是这一段；
     *   - 按退格**第一下只解锁**，不删字母（主人明确要求的）。
     */
    private var lockedSyllable: Int? = null

    /** 已经选成汉字的前缀（纯拼音时是空串）。用来判断需不需要重建 */
    private var convertedPrefix: String = ""

    /** 整张候选表（未筛选）。单字模式就是从这份里筛 */
    private var allCandidates: List<String> = emptyList()

    /** 是否只看单字 */
    private var singleCharOnly = false

    init {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(theme.barColor)
        // 高度由外面挂上来时给的 LayoutParams 决定（和键盘同高）
        // 盖住键盘时整块区域都要吃掉触摸：
        // 否则点在候选格之间的空白会穿透到下面的键盘，误打一个字出来。
        isClickable = true
        isFocusable = false

        // 照原版 ExpandedCandidateLayout 的结构：
        //   tabsContainer（0.15）+ recyclerView（中间，占满）+ embeddedKeyboard（0.15）
        tabsContainer.addView(
            scrollableTabs,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                // 照原版 above(pinnedTabs)：可滚区从顶一直到 pinned 区上沿
                bottomMargin = dp(PreeditTabView.HEIGHT_DP).toInt()
            }
        )
        tabsContainer.addView(
            pinnedTabs,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
        )

        addView(
            tabsContainer,
            LayoutParams(0, LayoutParams.MATCH_PARENT, PREEDIT_COLUMN_PERCENT)
        )
        addView(
            grid,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f - PREEDIT_COLUMN_PERCENT - ACTION_COLUMN_PERCENT)
        )
        addView(
            actionColumn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, ACTION_COLUMN_PERCENT)
        )

        // 照原版 BaseExpandedCandidateWindow.bindCandidateUiViewHolder：
        // 点一下选这个候选，长按不做额外动作（rime 没有候选动作菜单）
        candidateAdapter.apply {
            onClick = { idx -> listener?.onPickCandidate(idx) }
        }

        // 右侧操作列：↑ ↓ 滚一屏、⌫ 退格、↵ 回车，四键均分高度
        actionColumn.addView(makeActionButton("↑") { scrollByPage(false) }, weightParams())
        actionColumn.addView(makeActionButton("↓") { scrollByPage(true) }, weightParams())
        actionColumn.addView(makeActionButton("⌫") { listener?.onBackspace() }, weightParams())
        actionColumn.addView(makeActionButton("↵") { listener?.onReturn() }, weightParams())

        refreshSingleCharTab()
    }

    /**
     * 点「单字」：在「整张候选表」和「只有单字」之间切换。
     *
     * 只看单字时，候选是按 `length == 1` 从整张表里筛出来的，
     * 但点击仍然要**还原成整张表里的原始下标**，否则会选错候选
     * （筛选后的第 3 个，在整张表里可能是第 17 个）。
     */
    private fun toggleSingleChar() {
        singleCharOnly = !singleCharOnly
        refreshSingleCharTab()
        applyCandidateFilter()
    }

    /** 刷新底部「单字」tab 的选中态（pinned 项的指示条颜色由它决定） */
    private fun refreshSingleCharTab() {
        pinnedTabsAdapter.updateTabs(
            listOf(
                CandidateTab(
                    id = TAB_SINGLE_CHAR,
                    text = "单字",
                    isCheckable = true,
                    isChecked = singleCharOnly
                )
            )
        )
    }

    /**
     * 按当前筛选状态把候选交给网格。
     *
     * 网格的下标是「交给它的那张表」里的下标，而 `Listener.onPickCandidate`
     * 要的是**全局下标**，所以这里要把筛选后的下标映射回原始下标。
     */
    private fun applyCandidateFilter() {
        if (!singleCharOnly) {
            candidateAdapter.submit(allCandidates.withIndex().map { it.value to it.index })
            gridLayoutManager.spanSizeLookup?.invalidateSpanIndexCache()
            return
        }
        val picked = mutableListOf<Pair<String, Int>>()
        for ((i, word) in allCandidates.withIndex()) {
            if (word.length == 1) picked.add(word to i)
        }
        candidateAdapter.submit(picked)
        gridLayoutManager.spanSizeLookup?.invalidateSpanIndexCache()
    }

    /**
     * 更新左边拼音列。
     *
     * @param preedit rime 的 composition.preedit。**注意它里面没有分隔符**：
     *                已选的段是汉字、没选的段是字母，直接拼在一起，
     *                比如选了「不」之后就是 "不haowan"。
     *                所以这里交给 [parsePreedit] 拆：前面汉字段 + 后面音节表切分。
     *
     * 默认**不高亮任何一格**（主人要的）。只有主人亲手点了某一格，
     * 那一格才高亮并锁定；再点一次取消。
     */
    fun updatePreedit(preedit: String) {
        val parsed = parsePreedit(preedit)
        // 自愈：上一次点了谐音，但引擎最后没照着重打（preedit 里这一节还是别的拼法），
        // 就把「喂进去的是哪个谐音」清掉。不清的话它会一直挂着，
        // 万一主人自己后来又打出同一个拼法，会被误认成我们点的。
        val applied = appliedVariant
        if (applied != null && parsed.syllables.firstOrNull() != applied) {
            appliedVariant = null
        }
        if (parsed.syllables == syllables && parsed.convertedPrefix == convertedPrefix) return
        // 音节个数没变（比如刚把 hao 换成 zhao 重打了一遍），锁要留在原来那一格：
        // 主人刚点亮的那一格不能自己灭掉，不然改完一个谐音还得再点一次。
        // 个数变了（选掉一个、退掉一个）就作废，回到「谁都不高亮」的默认状态。
        val keepLock = parsed.syllables.size == syllables.size
        val firstNow = parsed.syllables.firstOrNull() ?: ""
        if (keepLock && originalSyllable.isNotEmpty()) {
            // 个数没变 → 这一节还是同一节，[originalSyllable] 原则上不动。
            //
            // 唯一要判断的是：引擎里现在这个拼法，是我们点谐音换上去的，
            // 还是主人自己在键盘上改的？
            //   · 等于 appliedVariant → 我们换的，originalSyllable 保住
            //     （主人要的：打了 hui 就一直显示 hui，点了 fui 也不许顶掉）；
            //   · 等于 originalSyllable → 主人自己改回来了，appliedVariant 作废；
            //   · 都不是 → 主人自己在键盘上改了字母，跟着换。
            val ourVariant = appliedVariant
            if (ourVariant != null && firstNow == ourVariant) {
                // 我们自己点的谐音，原样保留
            } else if (firstNow == originalSyllable) {
                appliedVariant = null
            } else {
                originalSyllable = firstNow
                appliedVariant = null
            }
        } else {
            // 换了一节（选掉/退掉一个，或者本来就没有）→ 重来
            originalSyllable = firstNow
            appliedVariant = null
        }
        syllables = parsed.syllables
        convertedPrefix = parsed.convertedPrefix
        if (!keepLock) lockedSyllable = null
        rebuildPreeditColumn()
    }

    /** 当前锁定的音节；没锁定就取最前面那个（引擎正在转换的） */
    private fun targetSyllable(): String {
        val idx = lockedSyllable
        return if (idx != null && idx in syllables.indices) syllables[idx]
        else syllables.firstOrNull() ?: ""
    }

    /**
     * 主人点了第 [index] 个音节格。
     *
     * 语义（主人明确要求的）：
     *   - 点一个之前没锁的 → 锁定它，这一格高亮；
     *   - 点已经锁着的那一个 → 解锁，回到「谁都不高亮」。
     */
    fun toggleSyllableLock(index: Int) {
        if (index !in syllables.indices) return
        lockedSyllable = if (lockedSyllable == index) null else index
        rebuildPreeditColumn()
    }

    /** 现在有没有音节被锁着（退格要先问这个） */
    fun hasLockedSyllable(): Boolean =
        lockedSyllable != null && lockedSyllable!! in syllables.indices

    /**
     * 现在锁着的是第几个音节；没有锁定返回 null。
     *
     * 改谐音要用它：主人点了 hao 再点谐音，改的就该是 hao 这一节，
     * 不能老是改最前面那个。没锁时调用方按「最前面那个」处理。
     */
    fun lockedSyllableIndex(): Int? =
        lockedSyllable?.takeIf { it in syllables.indices }

    /**
     * 算出「把某个音节换成 [variant] 之后，整串字母该是什么」。
     *
     * 为什么整串都在这算、不让调用方自己拼：连续换两次谐音时，
     * 引擎里那串字母（比如 `fui`）如果拿别的切法去算起点，就会错位、多出字母。
     * 这里一律用**左列正在显示的那份分段**（`syllables`）算，前后才对得齐。
     *
     * @param raw 引擎里那串原始字母（去分隔符、小写）
     * @return 换完之后的整串字母；对不上时返回 null（此时什么都不该做）
     */
    fun planFuzzyPick(variant: String, raw: String): String? {
        if (variant.isEmpty() || variant.any { it !in 'a'..'z' }) return null
        if (syllables.isEmpty()) return null
        val idx = lockedSyllableIndex() ?: 0
        val target = syllables.getOrNull(idx) ?: return null
        if (target == variant) return null
        // 这一节在整串字母里的起点 = 它前面几节的长度之和
        val start = syllables.take(idx).sumOf { it.length }
        // 左列显示的分段和引擎字母对不上 → 放弃，不动主人的字
        if (!raw.startsWith(target, start)) return null
        // 记住「这一节即将被换成 variant」，这样 updatePreedit 收到新 preedit 时
        // 能认出这是我们自己换的，不会把 originalSyllable 也改掉——
        // 主人要的：打了 hui，点了 fui，第一格还得是 hui。
        if (idx == 0) appliedVariant = variant
        return raw.substring(0, start) + variant + raw.substring(start + target.length)
    }

    /**
     * 解除锁定。
     *
     * @return 之前有锁 → 返回 true（调用方应该「这次只解锁、不删字母」）；
     *         之前没锁 → 返回 false（正常退格）。
     */
    fun releaseSyllableLock(): Boolean {
        if (!hasLockedSyllable()) return false
        lockedSyllable = null
        rebuildPreeditColumn()
        return true
    }

    /**
     * 重建拼音列：**只列当前这一节**（引擎正在转换的那一段），
     * 再列出它的谐音变体。
     *
     * 主人明确要求的：打 `buhaowan` 时，编码区**只能**出现 `bu`（或 bu 的谐音），
     * 后面的 `hao`、`wan` 一个字都不许提前露出来。等选了第一个字，
     * 引擎把 `bu` 吃掉，`hao` 变成当前这一段，左列才轮到显示 `hao`，
     * 依此类推——是不是已经转过一个字，引擎的 preedit 自己会说
     * （`不haowan` → 当前是 hao）。
     *
     * 打 buhaowan 的样子（默认谁都不高亮）：
     *   ┌──────┐
     *   │  bu  │   ← 当前这一节，普通格、没有底色
     *   ├──────┤
     *   │  pu  │   ← bu 的谐音变体（完整音节，不是单个声母）
     *   │  mu  │
     *   └──────┘
     * 点了 bu 之后：bu 那一格带上淡色块（高亮 = 锁定）。
     */
    private fun rebuildPreeditColumn() {
        // 一个拼音都不剩（全成汉字了）：左列清空
        if (syllables.isEmpty()) {
            currentVariants = emptyList()
            preeditTabsAdapter.updateTabs(emptyList())
            return
        }

        val target = targetSyllable()
        // 第一格固定显示**键盘打出来的原始拼法**（[originalSyllable]），
        // 不跟着引擎里当前喂进去的谐音走——主人点了 fui，这一格还是 hui。
        val head = originalSyllable.ifEmpty { target }
        // 谐音变体也围绕 head 生成：
        //   · 点了 fui 之后引擎里是 fui，如果拿 target 去算，变体就成了 hui，
        //     和第一格重复；拿 head 算永远是 hui → fui，列表顺序纹丝不动。
        val variants = fuzzyVariantsOf(head)
        currentVariants = variants

        val tabs = mutableListOf<CandidateTab>()
        // 只列当前这一节。后面的音节还没轮到，提前显示会让人以为已经打完了。
        if (head.isNotEmpty() && head.all { it in 'a'..'z' }) {
            tabs.add(
                CandidateTab(
                    id = syllableTabId(0),
                    text = head,
                    isCheckable = true,
                    isChecked = (lockedSyllable == 0)
                )
            )
        }
        // 谐音变体：普通格，按下才有高亮，点了就按那个拼法重打这一段
        for ((i, variant) in variants.withIndex()) {
            tabs.add(CandidateTab(id = i, text = variant))
        }
        preeditTabsAdapter.updateTabs(tabs)
        scrollableTabs.scrollToPosition(0)
    }

    /** 把音节切成 (声母, 韵母)。没有声母时第一项是空串（比如 "an"、"er"） */
    private fun splitInitial(syllable: String): Pair<String, String> {
        for (ini in INITIALS) {
            if (syllable.length > ini.length && syllable.startsWith(ini)) {
                return ini to syllable.substring(ini.length)
            }
        }
        return "" to syllable
    }

    /** 韵母的谐音：ang↔an、eng↔en、ing↔in、iang↔ian、uang↔uan */
    private fun finalVariantsOf(final: String): List<String> {
        val out = mutableListOf<String>()
        for ((long, short) in FUZZY_SUFFIX) {
            if (final.endsWith(long)) {
                out.add(final.dropLast(long.length) + short)
                return out
            }
        }
        for ((long, short) in FUZZY_SUFFIX) {
            if (final.endsWith(short) && !final.endsWith(long)) {
                out.add(final.dropLast(short.length) + long)
                return out
            }
        }
        return out
    }

    /**
     * 一个音节可能被打错的谐音。
     *
     * 声母对：zh/z、ch/c、sh/s、n/l、f/h、l/r、g/k
     * 韵母对：ang/an、eng/en、ing/in、iang/ian、uang/uan
     *
     * 只列「换声母」「换韵母」「两个一起换」，不再往下套，
     * 免得出几十个没人看得过来。
     */
    private fun fuzzyVariantsOf(syllable: String): List<String> {
        // 已经转成汉字了（或者带大写/符号）就不硬凑谐音
        if (syllable.isEmpty() || syllable.any { it !in 'a'..'z' }) return emptyList()
        val (initial, final) = splitInitial(syllable)
        val initials = INITIAL_FUZZY[initial].orEmpty()
        val finals = finalVariantsOf(final)

        val out = LinkedHashSet<String>()
        fun add(v: String) {
            if (out.size < MAX_FUZZY_VARIANTS && v.isNotEmpty() && v != syllable) {
                out.add(v)
            }
        }
        // 先只换声母（这个错得最多）
        for (i in initials) add(i + final)
        // 再只换韵母
        for (f in finals) add(initial + f)
        // 最后两个一起换
        for (i in initials) {
            for (f in finals) add(i + f)
        }
        return out.toList()
    }

    private fun weightParams() =
        LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun makeActionButton(label: String, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(theme.altKeyTextColor)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * 更新内容。
     *
     * @param candidates 整张候选表（全局顺序）
     * @param preedit rime 的 composition.preedit，左边拼音列用
     */
    fun update(candidates: List<String>, preedit: String = "") {
        allCandidates = candidates
        applyCandidateFilter()
        grid.scrollToPosition(0)
        updatePreedit(preedit)
    }

    /**
     * 上/下滚动一屏。**照抄 fcitx5 的 `GridExpandedCandidateWindow.prevPage/nextPage`**：
     *
     *   prev = findFirstCompletelyVisibleItemPosition() - 1
     *   next = findLastCompletelyVisibleItemPosition() + 1
     *
     * 用 `LinearSmoothScroller` + `SNAP_TO_END/START` 滚到那一项的边上，
     * 所以每按一次都是「整屏整屏地翻」，不会只挪一行。
     */
    private fun scrollByPage(down: Boolean) {
        if (candidateAdapter.itemCount == 0) return
        if (down) {
            var next = gridLayoutManager.findLastCompletelyVisibleItemPosition() + 1
            if (next >= candidateAdapter.itemCount) next = candidateAdapter.itemCount - 1
            if (next < 0) return
            gridLayoutManager.startSmoothScroll(
                object : LinearSmoothScroller(context) {
                    override fun getVerticalSnapPreference() = SNAP_TO_START
                }.apply { targetPosition = next })
        } else {
            var prev = gridLayoutManager.findFirstCompletelyVisibleItemPosition() - 1
            if (prev < 0) prev = 0
            gridLayoutManager.startSmoothScroll(
                object : LinearSmoothScroller(context) {
                    override fun getVerticalSnapPreference() = SNAP_TO_END
                }.apply { targetPosition = prev })
        }
    }

}
