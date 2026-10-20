package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme
import kotlin.math.ceil

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
    /** 展开页高度（px），一般等于键盘高度；布局还没测量完时用它估滚动步长 */
    private val keyboardHeightPx: Int
) : LinearLayout(context) {

    companion object {
        /** 网格列数：竖屏 6（原版默认） */
        private const val SPAN_PORTRAIT = 6

        /** 网格列数：横屏 8（原版默认） */
        private const val SPAN_LANDSCAPE = 8

        /** 每个候选格高度（dp），对齐 GridPagingCandidateViewAdapter 的 dp(40) */
        private const val CELL_HEIGHT_DP = 40f

        /** 每格左右留白（dp）：文字和格子边线不贴死，缩字时也留一点呼吸 */
        private const val CELL_PADDING_DP = 6f

        /** 右侧操作列宽度比例，对齐原版 matchConstraintPercentWidth = 0.15f */
        private const val ACTION_COLUMN_PERCENT = 0.15f

        /** 左侧拼音列宽度比例 */
        private const val PREEDIT_COLUMN_PERCENT = 0.18f

        /** 候选字号（sp），对齐 CandidateItemUi 的 20f */
        private const val CANDIDATE_TEXT_SP = 20f

        /** 拼音列字号（sp），比候选小一点，不抢候选的视觉重心 */
        private const val PREEDIT_TEXT_SP = 16f

        /** 一格拼音按钮的高度（dp） */
        private const val PREEDIT_CELL_DP = 44f

        /** 拼音段之间用什么字符切（雾凇拼音的 delimiter 是空格和小撇号两种） */
        private const val SYLLABLE_DELIMITERS = " '"

        /** 左边最多列几个谐音变体，太多反而挑花眼 */
        private const val MAX_FUZZY_VARIANTS = 6

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

        /** 容易打错的声母：键是正确拼法，值是可能被写成的谐音 */
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
            "k" to listOf("g")
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
         * 点了左边高亮的那一个音节（当前正在转换的这段）。
         *
         * 点它 = 把光标锁到这一段（候选只剩这段的字），再点一次 = 解除锁定。
         */
        fun onPickPreeditSegment(segmentIndex: Int)

        /**
         * 点了第 [segmentIndex] 段的谐音变体 [variant]。
         *
         * 比如当前音是 zhong，主人点了左边的 zong，
         * 就把这一段的拼法换成 zong 重新给候选（对应「打错声母」这种情况）。
         */
        fun onPickFuzzyVariant(segmentIndex: Int, variant: String)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val spanCount: Int =
        if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) SPAN_LANDSCAPE else SPAN_PORTRAIT

    /** 候选网格 */
    private val grid = CandidateGridView(context)

    private val gridScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(
            grid,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private val actionColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.keyboardColor)
    }

    /** 左侧拼音列（可滚动，拼音段多了也不会被切） */
    private val preeditScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
    }
    private val preeditColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.keyboardColor)
    }

    /**
     * 左侧整列：上面是可滚动的拼音段，最下面固定一个「单字」。
     *
     * 「单字」固定在列底（也就是键盘最下方），拼音段再多也不会把它顶走。
     */
    private val leftColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.keyboardColor)
    }

    /**
     * 左下角固定的「单字」按钮。
     *
     * 点了就只显示**长度为 1 个字的候选**——这是纯界面筛选：
     * 候选表本来就是 `nativeGetAllCandidates` 给的整张表，
     * 不动引擎、不改 schema，所以不会触发重新部署。
     */
    private val singleCharButton = TextView(context).apply {
        text = "单字"
        textSize = PREEDIT_TEXT_SP
        gravity = Gravity.CENTER
        isClickable = true
        maxLines = 1
        setOnClickListener { toggleSingleChar() }
    }

    /** 当前拼音各段，如 ["ni", "hao", "ma"] */
    private var segments: List<String> = emptyList()

    /** 当前光标停在哪个段（从 0 开始；-1 表示没有锁定任何段） */
    private var activeSegment = -1

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

        preeditScroll.addView(
            preeditColumn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 左列 = 上面滚动的拼音段（占满剩余高度）+ 底部固定的「单字」
        leftColumn.addView(preeditScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        leftColumn.addView(
            singleCharButton,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(PREEDIT_CELL_DP).toInt())
        )

        addView(
            leftColumn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, PREEDIT_COLUMN_PERCENT)
        )
        addView(
            gridScroll,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f - PREEDIT_COLUMN_PERCENT - ACTION_COLUMN_PERCENT)
        )
        addView(
            actionColumn,
            LayoutParams(0, LayoutParams.MATCH_PARENT, ACTION_COLUMN_PERCENT)
        )

        // 右侧操作列：↑ ↓ 滚一屏、⌫ 退格、↵ 回车，四键均分高度
        actionColumn.addView(makeActionButton("↑") { scrollByPage(false) }, weightParams())
        actionColumn.addView(makeActionButton("↓") { scrollByPage(true) }, weightParams())
        actionColumn.addView(makeActionButton("⌫") { listener?.onBackspace() }, weightParams())
        actionColumn.addView(makeActionButton("↵") { listener?.onReturn() }, weightParams())

        refreshSingleCharButton()
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
        refreshSingleCharButton()
        applyCandidateFilter()
    }

    /** 刷新「单字」按钮的外观：开着的时侯用 accent 高亮，和当前拼音段一致 */
    private fun refreshSingleCharButton() {
        if (singleCharOnly) {
            singleCharButton.setBackgroundColor(theme.accentKeyBackgroundColor)
            singleCharButton.setTextColor(theme.accentKeyTextColor)
        } else {
            singleCharButton.setBackgroundColor(theme.keyboardColor)
            singleCharButton.setTextColor(theme.candidateTextColor)
        }
    }

    /**
     * 按当前筛选状态把候选交给网格。
     *
     * 网格的下标是「交给它的那张表」里的下标，而 `Listener.onPickCandidate`
     * 要的是**全局下标**，所以这里要把筛选后的下标映射回原始下标。
     */
    private fun applyCandidateFilter() {
        if (!singleCharOnly) {
            grid.setCandidates(allCandidates, spanCount)
            return
        }
        val picked = mutableListOf<Pair<String, Int>>()
        for ((i, word) in allCandidates.withIndex()) {
            if (word.length == 1) picked.add(word to i)
        }
        grid.setCandidatesWithIndex(picked, spanCount)
    }

    /**
     * 更新左边拼音列。
     *
     * @param preedit 完整拼音串（rime 的 composition.preedit，段之间是空格）
     * @param activeSeg 当前光标所处的段（由 sel_start 换算来的段号）；
     *                  传 -1 或超出范围表示「没有锁定任何一段」
     */
    fun updatePreedit(preedit: String, activeSeg: Int) {
        val parts = splitIntoSegments(preedit)
        val seg = if (activeSeg in parts.indices) activeSeg else -1
        if (parts == segments && seg == activeSegment) return
        segments = parts
        activeSegment = seg
        rebuildPreeditColumn()
    }

    /**
     * 把拼音串切成段。
     *
     * rime 的 preedit 用空格（或小撇号）分隔音节/词，比如 "ni hao" / "ni'hao"。
     * 这里按任意分隔符切，同时把分隔符本身丢掉。
     * 切不出段（比如单音节 "ni"）时返回只有一个元素的列表。
     */
    private fun splitIntoSegments(preedit: String): List<String> {
        if (preedit.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in preedit) {
            if (ch in SYLLABLE_DELIMITERS) {
                if (sb.isNotEmpty()) {
                    parts.add(sb.toString())
                    sb.clear()
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }

    /**
     * 重建拼音列：**只显示当前正在转换的那一个音节**，下面跟着它的谐音变体。
     *
     * 为什么只显示一个：谷歌和 fcitx5 都是这个顺序——先把第一个音节摆出来，
     * 选掉这个字，rime 的 editor 自然把光标推到下一段，
     * 左边的音节就换成下一个。整串拼音一次全摊开，反而看不清现在在选哪个字。
     */
    private fun rebuildPreeditColumn() {
        preeditColumn.removeAllViews()
        if (segments.isEmpty()) return
        // 没算出来（-1）或越界时默认看第一段——就是「先展示第一个拼音」
        val cur = if (activeSegment in segments.indices) activeSegment else 0
        val current = segments[cur]

        // 当前音节：高亮。点它 = 锁定这一段（再点一次 = 解除锁定，回整句）
        preeditColumn.addView(
            makeSegmentButton(current, isActive = true) { listener?.onPickPreeditSegment(cur) }
        )

        // 谐音变体：可能打错的声母/韵母，点一下就按那个拼法重来
        for (variant in fuzzyVariantsOf(current)) {
            preeditColumn.addView(
                makeSegmentButton(variant, isActive = false) {
                    listener?.onPickFuzzyVariant(cur, variant)
                }
            )
        }
        preeditScroll.scrollTo(0, 0)
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

    private fun makeSegmentButton(
        label: String,
        isActive: Boolean,
        onClick: () -> Unit
    ): View = TextView(context).apply {
        text = label
        textSize = PREEDIT_TEXT_SP
        gravity = Gravity.CENTER
        isClickable = true
        maxLines = 1
        val h = dp(PREEDIT_CELL_DP).toInt()
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, h)
        if (isActive) {
            setBackgroundColor(theme.accentKeyBackgroundColor)
            setTextColor(theme.accentKeyTextColor)
        } else {
            setBackgroundColor(theme.keyboardColor)
            setTextColor(theme.candidateTextColor)
        }
        setOnClickListener { onClick() }
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
     * @param preedit 完整拼音串（段之间是空格），左边拼音列用
     * @param activeSeg 当前光标所处的段（-1 或越界当「整句」）
     */
    fun update(candidates: List<String>, preedit: String = "", activeSeg: Int = -1) {
        allCandidates = candidates
        applyCandidateFilter()
        gridScroll.scrollTo(0, 0)
        updatePreedit(preedit, activeSeg)
    }

    /**
     * 上/下滚动一屏。
     *
     * 展开页里已经是**整张候选表**了，不需要再去让引擎翻页
     * （引擎翻页只换当前页那几条，网格里看不出变化，像失灵）。
     * 所以这里做成真正的滚动：一屏 = 当前可见高度，最后不足一屏就滚到底。
     */
    private fun scrollByPage(down: Boolean) {
        // 高度还没测量出来（第一次点击太快）时，用键盘高度当一屏
        val viewH = if (gridScroll.height > 0) gridScroll.height else keyboardHeightPx
        val step = viewH - dp(CELL_HEIGHT_DP).toInt()
        if (step <= 0) return
        val contentH = if (grid.height > 0) grid.height else viewH
        val max = maxOf(0, contentH - viewH)
        val target = if (down) gridScroll.scrollY + step else gridScroll.scrollY - step
        gridScroll.smoothScrollTo(0, target.coerceIn(0, max))
    }

    /**
     * 候选网格。自己算格子、自己画：触摸命中和绘制共用同一份矩形。
     *
     * 为什么不用 RecyclerView：候选只有几十条，全部画出来也不卡，
     * 少一层 Adapter/ViewHolder 更好看懂、好改。
     */
    private inner class CandidateGridView(context: Context) : View(context) {
        private var candidates: List<String> = emptyList()

        /**
         * 每个格子在**整张候选表**里的原始下标。
         *
         * 平时等于 0,1,2...；开了「单字」筛选后，网格里只剩单字，
         * 这时点第 i 个格子必须选原始表里的 [globalIndexes][i]，
         * 否则选出来的是另一个词。
         */
        private var globalIndexes: IntArray = IntArray(0)
        private var spanCount = SPAN_PORTRAIT
        private val cellRects = mutableListOf<RectF>()
        private var pressedCell = -1

        /** 每个候选的文字宽度（px），和 [candidates] 一一对应 */
        private var textWidths = FloatArray(0)

        /** 每个候选占几格（照 SpanHelper 的算法），和 [candidates] 一一对应 */
        private var spans = IntArray(0)

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(CANDIDATE_TEXT_SP)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            color = theme.candidateTextColor
        }
        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.dividerColor
            strokeWidth = maxOf(1f, dp(1f))
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setCandidates(list: List<String>, spansCount: Int) {
            setCandidatesWithIndex(list.withIndex().map { it.value to it.index }, spansCount)
        }

        /**
         * 带原始下标地设置候选：`pairs` 是 (词, 在整张候选表里的下标)。
         * 「单字」筛选就是靠它把点击映射回原始候选表。
         */
        fun setCandidatesWithIndex(pairs: List<Pair<String, Int>>, spansCount: Int = spanCount) {
            candidates = pairs.map { it.first }
            globalIndexes = IntArray(pairs.size) { pairs[it].second }
            spanCount = spansCount
            pressedCell = -1
            // 候选换了就重量宽度和格数，别用上一批的缓存
            measureAll()
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            // 行数由「每个候选占几格」决定，所以先跑一遍分行算法（不落矩形）
            val rows = layoutCells(w.toFloat(), null)
            setMeasuredDimension(w, rows * dp(CELL_HEIGHT_DP).toInt())
        }

        /**
         * 照 fcitx5 的 SpanHelper：候选**不按等宽格子排**，而是按文字宽度比例占格。
         *
         * 宽度按 `字宽 / 字号` 换成「几个字宽」的倍数，再 `ceil(倍数 / 1.5)` 得格数：
         *   一两个字 → 1~2 格，三到五个字 → 2~4 格，长候选最多占满整行。
         *
         * 一行剩下的位置如果**塞不下下一个候选**，就把当前这个候选**拉伸填满行尾**
         * （`span = spanCount - spanCol`，原版注释叫 stretch this item to fill the line），
         * 这样每行右边都不会留一块莫名其妙的空白。
         *
         * 返回行数。传 [out] 就把每格矩形写进去（绘制和触摸共用同一份）。
         */
        private fun layoutCells(widthPx: Float, out: MutableList<RectF>?): Int {
            out?.clear()
            if (candidates.isEmpty() || widthPx <= 0f) return 0
            if (spans.size != candidates.size) measureAll()
            val cellW = widthPx / spanCount
            val cellH = dp(CELL_HEIGHT_DP)
            var spanCol = 0
            var row = 0
            for (i in candidates.indices) {
                var span = spans[i]
                val nextSpan = if (i + 1 < candidates.size) spans[i + 1] else 0
                // 剩下的位置放不下下一个候选：把这个候选拉伸到行尾
                if (nextSpan > 0 && spanCol + span + nextSpan > spanCount) {
                    span = spanCount - spanCol
                }
                // 兜底：自己就超出本行剩余格数，截断到行尾
                if (spanCol + span > spanCount) span = spanCount - spanCol
                out?.add(
                    RectF(
                        spanCol * cellW,
                        row * cellH,
                        (spanCol + span) * cellW,
                        (row + 1) * cellH
                    )
                )
                spanCol += span
                if (spanCol >= spanCount) {
                    spanCol = 0
                    row++
                }
            }
            return if (spanCol > 0) row + 1 else row
        }

        /**
         * 预量每个候选的文字宽度，并换算成「占几格」。
         *
         * 照 fcitx5 的 `measureWidth`：先用 `字宽 / 字号` 得到一个**倍数量**
         * （几个字宽），再 `ceil(倍数 / 1.5)` 得格数，最少 1 格、最多一整行。
         * 用倍数而不是像素，换字号、换密度都不会重新算错。
         */
        private fun measureAll() {
            textWidths = FloatArray(candidates.size) { paint.measureText(candidates[it]) }
            spans = IntArray(candidates.size) { i ->
                val ratio = if (paint.textSize > 0f) textWidths[i] / paint.textSize else 0f
                ceil(ratio / 1.5f).toInt().coerceIn(1, spanCount)
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (candidates.isEmpty()) return
            layoutCells(width.toFloat(), cellRects)
            val cellH = dp(CELL_HEIGHT_DP)
            for ((i, rect) in cellRects.withIndex()) {
                if (i >= candidates.size) break
                if (i == pressedCell) {
                    highlightPaint.color = theme.keyPressHighlightColor
                    canvas.drawRoundRect(rect, dp(4f), dp(4f), highlightPaint)
                }
                val word = candidates[i]
                val tw = textWidths.getOrElse(i) { paint.measureText(word) }
                val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2
                paint.color = theme.candidateTextColor
                // 照 fcitx5 的 AutoScaleTextView（Mode.Proportional）：
                // 文字比格子宽就**等比缩小**、居中画，绝不让字画出格子跟隔壁黏成一团。
                val innerW = rect.width() - dp(CELL_PADDING_DP) * 2f
                if (tw > innerW && tw > 0f && innerW > 0f) {
                    val scale = innerW / tw
                    val save = canvas.save()
                    canvas.scale(scale, scale, rect.centerX(), rect.centerY())
                    canvas.drawText(word, rect.centerX() - tw / 2f, baseline, paint)
                    canvas.restoreToCount(save)
                } else {
                    canvas.drawText(word, rect.centerX() - tw / 2f, baseline, paint)
                }
                // 格子之间的竖分隔线（照 GridDecoration：1dp 竖线、上下各缩 8dp）
                val left = rect.left
                if (left > 0f) {
                    canvas.drawLine(left, rect.top + dp(8f), left, rect.bottom - dp(8f), dividerPaint)
                }
            }
            // 每行底部的横分隔线
            var y = cellH
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, dividerPaint)
                y += cellH
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            layoutCells(width.toFloat(), cellRects)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedCell = hitCell(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val hit = hitCell(event.x, event.y)
                    if (hit != pressedCell) {
                        pressedCell = hit
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val hit = hitCell(event.x, event.y)
                    if (hit >= 0 && hit == pressedCell) {
                        // 上报的是「整张候选表」里的下标，不是筛选后网格里的位置
                        listener?.onPickCandidate(globalIndexes.getOrElse(hit) { hit })
                    }
                    pressedCell = -1
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedCell = -1
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun hitCell(x: Float, y: Float): Int {
            for ((i, r) in cellRects.withIndex()) {
                if (r.contains(x, y)) return i
            }
            return -1
        }
    }
}
