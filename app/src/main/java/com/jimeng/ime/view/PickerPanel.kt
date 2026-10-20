/*
 * 符号 / 表情 / 颜文字面板。
 *
 * 排版、数据、交互全部照 fcitx5-android 的 PickerLayout / PickerPageUi / PickerTabsUi：
 *
 *   ┌──────────────────────────────────────┐
 *   │ [1?#] [( )] [⇄] [±] [①] [◇] [全]      │  顶栏：当前面板内的分类
 *   ├──────────────────────────────────────┤        （符号/表情用图标，颜文字用文字）
 *   │  ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐     │
 *   │  │  │  │  │  │  │  │  │  │  │  │     │  网格：符号 10x3=28 格
 *   │  ├──┼──┼──┼──┼──┼──┼──┼──┼──┼──┤     │        表情 7x3=20 格
 *   │  │  │  │  │  │  │  │  │  │  │⌫ │     │        颜文字 4x3=12 格
 *   │  └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘     │        符号/表情右下角那格是删除键
 *   ├──────────────────────────────────────┤
 *   │ ▬▬▬▬                                  │  分页进度条（2dp）
 *   ├──────────────────────────────────────┤
 *   │ ABC  ,  [切换]  空格  .  回车          │  底部小键盘（面板高度的 25%）
 *   └──────────────────────────────────────┘
 *
 * 和原版一致的细节：
 *   - 顶栏是一排分类标签，不是「表情/颜文字」两个大 tab；
 *   - 选中标签不透明，未选中 0.5 透明度，没有下划线（原版就只有透明度）；
 *   - 翻页既能横滑也能点标签；
 *   - 符号/表情页右下角是删除键，长按连续删；
 *   - 底部第三个键：符号页是「回数字键盘」图标，表情 <-> 颜文字互换；
 *   - 一页放满一屏，靠翻页看后面的。
 *
 * 面板本身不负责显示/隐藏，由 JimengIME 决定什么时候盖到键盘上。
 */
package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.jimeng.ime.R
import com.jimeng.ime.kb.core.FcitxKeyMapping
import com.jimeng.ime.kb.core.InputMethodEntry
import com.jimeng.ime.kb.core.KeyStates
import com.jimeng.ime.kb.core.KeySym
import com.jimeng.ime.kb.keyboard.BaseKeyboard
import com.jimeng.ime.kb.keyboard.ImageLayoutSwitchKey
import com.jimeng.ime.kb.keyboard.ImagePickerSwitchKey
import com.jimeng.ime.kb.keyboard.KeyAction
import com.jimeng.ime.kb.keyboard.KeyActionListener
import com.jimeng.ime.kb.keyboard.KeyDef
import com.jimeng.ime.kb.keyboard.LayoutSwitchKey
import com.jimeng.ime.kb.keyboard.NumberKeyboard
import com.jimeng.ime.kb.keyboard.ReturnKey
import com.jimeng.ime.kb.keyboard.SpaceKey
import com.jimeng.ime.kb.keyboard.TextKeyboard
import com.jimeng.ime.kb.keyboard.TextPickerSwitchKey
import com.jimeng.ime.kb.keyboard.radiusDrawable
import com.jimeng.ime.kb.picker.PickerData
import com.jimeng.ime.kb.picker.PickerWindow
import com.jimeng.ime.kb.theme.Theme
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class PickerPanel(
    context: Context,
    private val theme: Theme,
    keyboardHeightPx: Int
) : LinearLayout(context) {

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        /** 底部小键盘占面板高度的比例，对齐 fcitx5 的 0.25 */
        private const val MINI_KEYBOARD_PERCENT = 25
        /** 顶栏高度 */
        private const val TAB_BAR_DP = 40
        /** 网格底部分页进度条高度，对齐 fcitx5 的 dp(2) */
        private const val PAGINATION_DP = 2
    }

    /** 网格里点了某一项，交给输入法上屏 */
    var onCommit: ((String) -> Unit)? = null
    /** 底部小键盘要求切布局（比如按下 ABC 或回数字键盘） */
    var onLayoutSwitch: ((String) -> Unit)? = null
    /** 底部小键盘要求切面板（表情 <-> 颜文字） */
    var onPickerSwitch: ((PickerWindow.Key) -> Unit)? = null
    /** 主键键盘共用的动作分发器（底部小键盘和删除键都走它） */
    var keyActionListener: KeyActionListener? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val tabBarHeightPx = dp(TAB_BAR_DP)
    private val miniKeyboardHeightPx = keyboardHeightPx * MINI_KEYBOARD_PERCENT / 100

    private val tabItems = mutableListOf<TabItem>()

    /** 当前面板类型 */
    private var currentKey: PickerWindow.Key = PickerWindow.Key.Emoji
    /** 当前面板的数据（分类列表） */
    private var categories: List<Pair<PickerData.Category, Array<String>>> = emptyList()
    /** 每个分类从第几页开始 */
    private val categoryPageStart = mutableListOf<Int>()
    /** 每页属于哪个分类 */
    private val pageToCategory = mutableListOf<Int>()
    /** 当前高亮的分类 */
    private var activeCategory = 0

    // ---------------- 顶部标签栏 ----------------
    private val tabBar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    private val tabScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        setBackgroundColor(theme.barColor)
        addView(
            tabBar,
            FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    // ---------------- 中间网格 ----------------
    private val pagerAdapter = PageAdapter()
    private val pager = ViewPager2(context).apply {
        adapter = pagerAdapter
        setBackgroundColor(theme.keyboardColor)
        overScrollMode = View.OVER_SCROLL_NEVER
        // 原版默认允许横滑翻页
        isUserInputEnabled = true
    }
    private val placeholder = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 110))
        setBackgroundColor(theme.keyboardColor)
        isClickable = true
        visibility = View.GONE
    }
    private val gridArea = FrameLayout(context).apply {
        setBackgroundColor(theme.keyboardColor)
        isClickable = true
        addView(
            pager,
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
        addView(
            placeholder,
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
    }

    // ---------------- 分页进度条 ----------------
    private val paginationHighlight = View(context).apply {
        setBackgroundColor(theme.keyTextColor)
        visibility = View.GONE
    }
    private val paginationBar = FrameLayout(context).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 76))
        addView(
            paginationHighlight,
            FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshPagination() }
    }
    private var pageCount = 0
    private var scrollPage = 0
    private var scrollProgress = 0f

    // ---------------- 底部小键盘 ----------------
    /** 三个面板各有一个小键盘，因为第三个键不一样 */
    private val keyboards = HashMap<PickerWindow.Key, PickerKeyboard>()
    private var currentKeyboard: PickerKeyboard? = null
    private val keyboardSlot = FrameLayout(context)

    // ---------------- 删除键长按连删 ----------------
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            fireBackspace()
            repeatHandler.postDelayed(this, 60L)
        }
    }

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(theme.keyboardColor)
        // 面板盖住整个键盘区，空白处也要吃掉点击，别漏到下面的主键盘
        isClickable = true

        addView(tabScroll, LinearLayout.LayoutParams(MATCH, tabBarHeightPx))
        addView(gridArea, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(paginationBar, LinearLayout.LayoutParams(MATCH, dp(PAGINATION_DP)))
        addView(keyboardSlot, LinearLayout.LayoutParams(MATCH, miniKeyboardHeightPx))

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                syncCategoryForPage(position)
            }

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                syncCategoryForPage(position)
                val start = categoryPageStart.getOrElse(activeCategory) { 0 }
                scrollPage = position - start
                scrollProgress = positionOffset
                refreshPagination()
            }
        })

        showKey(PickerWindow.Key.Emoji)
    }

    // ------------------------------------------------------------ 对外

    /** 切到某个面板（符号 / 表情 / 颜文字） */
    fun showKey(key: PickerWindow.Key) {
        if (key == currentKey && categories.isNotEmpty()) return
        currentKey = key
        categories = PickerData.dataOf(key)
        buildTabs()
        buildPages()
        selectCategory(0, animate = false)
        swapKeyboard(key)
    }

    /** 给底部小键盘和删除键设置监听（和主键盘共用同一个分发器） */
    fun bindListeners(keyActionListener: KeyActionListener?) {
        this.keyActionListener = keyActionListener
        keyboards.values.forEach {
            it.externalListener = keyActionListener
        }
    }

    fun onInputMethodUpdate(entry: InputMethodEntry) {
        keyboards.values.forEach { it.onInputMethodUpdate(entry) }
    }

    // ------------------------------------------------------------ 顶栏

    private inner class TabItem(val label: TextView, val icon: ImageView) {
        val root = FrameLayout(context).apply {
            addView(label, FrameLayout.LayoutParams(MATCH, MATCH).apply { gravity = Gravity.CENTER })
            addView(icon, FrameLayout.LayoutParams(MATCH, MATCH).apply { gravity = Gravity.CENTER })
            background = RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor),
                null,
                null
            )
        }
    }

    private fun buildTabs() {
        tabBar.removeAllViews()
        tabItems.clear()

        categories.forEachIndexed { index, pair ->
            val cat = pair.first
            val label = TextView(context).apply {
                text = cat.label
                // 颜文字分类标签是 "(^_^)" 这种长串，16sp 在均分宽度里会被截断
                textSize = if (cat.label.length > 3) 11f else 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isSingleLine = true
                includeFontPadding = false
                setTextColor(theme.keyTextColor)
                visibility = View.GONE
            }
            val icon = ImageView(context).apply {
                setImageResource(cat.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(theme.keyTextColor, PorterDuff.Mode.SRC_IN)
                visibility = View.GONE
            }
            val item = TabItem(label, icon)
            // 有图标用图标（符号 / 表情），没有图标就用文字（颜文字）
            if (cat.icon != 0) icon.visibility = View.VISIBLE else label.visibility = View.VISIBLE
            item.root.setOnClickListener { selectCategory(index, animate = false) }
            tabBar.addView(
                item.root,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            )
            tabItems += item
        }

        updateTabState(0)
    }

    private fun updateTabState(activeIndex: Int) {
        val active = ColorUtils.setAlphaComponent(theme.keyTextColor, 255)
        val inactive = ColorUtils.setAlphaComponent(theme.keyTextColor, 128)
        tabItems.forEachIndexed { index, item ->
            val color = if (index == activeIndex) active else inactive
            item.label.setTextColor(color)
            item.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    // ------------------------------------------------------------ 分页 / 网格

    private fun buildPages() {
        val pageSize = PickerData.pageSize(currentKey)
        val pages = mutableListOf<List<String>>()
        categoryPageStart.clear()
        pageToCategory.clear()

        categories.forEachIndexed { catIndex, (_, items) ->
            categoryPageStart += pages.size
            items.toList().chunked(pageSize).forEach { chunk ->
                pages += chunk
                pageToCategory += catIndex
            }
        }

        pagerAdapter.pages = pages
        pagerAdapter.notifyDataSetChanged()

        val empty = pages.isEmpty()
        placeholder.isVisible = empty
        pager.isVisible = !empty
        if (empty) placeholder.text = "这里还没有内容"

        activeCategory = 0
        pageCount = if (pages.isEmpty()) 0 else categories[0].second.toList()
            .chunked(pageSize).size
        scrollPage = 0
        scrollProgress = 0f
        refreshPagination()
    }

    private fun selectCategory(index: Int, animate: Boolean) {
        if (categories.isEmpty()) return
        val safe = index.coerceIn(0, categories.lastIndex)
        val start = categoryPageStart.getOrElse(safe) { 0 }
        activeCategory = safe
        pager.setCurrentItem(start, animate)
        updateTabState(safe)
        pageCount = pagesOfCategory(safe)
        scrollPage = 0
        scrollProgress = 0f
        refreshPagination()
    }

    /** 翻页滑到别的分类时，顶栏高亮和进度条也跟着换 */
    private fun syncCategoryForPage(position: Int) {
        val cat = pageToCategory.getOrNull(position) ?: return
        if (cat == activeCategory) return
        activeCategory = cat
        updateTabState(cat)
        pageCount = pagesOfCategory(cat)
        refreshPagination()
    }

    private fun pagesOfCategory(index: Int): Int {
        val pageSize = PickerData.pageSize(currentKey)
        return categories.getOrNull(index)?.second?.toList()?.chunked(pageSize)?.size ?: 0
    }

    private fun refreshPagination() {
        if (pageCount <= 1) {
            paginationHighlight.isVisible = false
            return
        }
        val width = paginationBar.width
        if (width <= 0) return
        paginationHighlight.isVisible = true
        val cell = width.toFloat() / pageCount
        val lp = paginationHighlight.layoutParams as FrameLayout.LayoutParams
        lp.width = cell.roundToInt().coerceAtLeast(1)
        lp.height = FrameLayout.LayoutParams.MATCH_PARENT
        lp.leftMargin = ((scrollPage + scrollProgress) * cell).roundToInt()
            .coerceIn(0, (width - lp.width).coerceAtLeast(0))
        paginationHighlight.layoutParams = lp
    }

    // ------------------------------------------------------------ 底部小键盘

    private fun swapKeyboard(key: PickerWindow.Key) {
        val kb = keyboards.getOrPut(key) { makeKeyboard(key) }
        if (currentKeyboard !== kb) {
            keyboardSlot.removeAllViews()
            keyboardSlot.addView(
                kb,
                FrameLayout.LayoutParams(MATCH, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            currentKeyboard = kb
        }
    }

    private fun makeKeyboard(key: PickerWindow.Key): PickerKeyboard {
        // 第三个键：符号面板回数字键盘（图标），表情写 :-) 去颜文字，
        // 颜文字用笑脸图标回表情 —— 和 fcitx5 的 PickerWindowPreset 一致
        val switchKey: KeyDef = when (key) {
            PickerWindow.Key.Symbol ->
                ImageLayoutSwitchKey(R.drawable.ic_number_pad, NumberKeyboard.Name)
            PickerWindow.Key.Emoji ->
                TextPickerSwitchKey(":-)", PickerWindow.Key.Emoticon)
            PickerWindow.Key.Emoticon ->
                ImagePickerSwitchKey(R.drawable.ic_baseline_tag_faces_24, PickerWindow.Key.Emoji)
        }
        return PickerKeyboard(context, theme, switchKey).apply {
            externalListener = keyActionListener
            onLayoutSwitchRequest = { to -> onLayoutSwitch?.invoke(to) }
            onPickerSwitchRequest = { k -> onPickerSwitch?.invoke(k) }
        }
    }

    private fun fireBackspace() {
        keyActionListener?.onKeyAction(
            KeyAction.SymAction(
                KeySym(FcitxKeyMapping.FcitxKey_BackSpace),
                KeyStates.Virtual
            ),
            KeyActionListener.Source.Keyboard
        )
    }

    // ------------------------------------------------------------ 一页网格

    /** 一个页面 = 固定行列的静态网格，和 fcitx5 的 PickerPageUi 一样按行均分 */
    @SuppressLint("ViewConstructor")
    private inner class PageView(val key: PickerWindow.Key) : LinearLayout(context) {

        private val columnCount = PickerData.columnCount(key)
        private val rowCount = PickerData.rowCount(key)
        private val pageSize = PickerData.pageSize(key)
        private val cellTextSize = PickerData.textSize(key)
        private val showBackspace = key != PickerWindow.Key.Emoticon

        private val cells = ArrayList<TextView>(pageSize)

        init {
            orientation = VERTICAL
            setBackgroundColor(theme.keyboardColor)
            isClickable = true

            var remaining = pageSize
            for (row in 0 until rowCount) {
                val lastRow = row == rowCount - 1
                val capacity = columnCount - if (lastRow && showBackspace) 1 else 0
                val count = min(capacity, remaining)
                if (count <= 0) break

                val rowLayout = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                }
                addView(
                    rowLayout,
                    LinearLayout.LayoutParams(MATCH, 0, 1f)
                )

                repeat(count) {
                    val tv = TextView(context).apply {
                        gravity = Gravity.CENTER
                        setTextColor(theme.keyTextColor)
                        includeFontPadding = false
                        isClickable = true
                    }
                    rowLayout.addView(
                        tv,
                        LinearLayout.LayoutParams(0, MATCH, 1f)
                    )
                    cells += tv
                }
                remaining -= count

                // 右下角那格给删除键，宽 1.5 格（原版是 15% 宽）
                if (lastRow && showBackspace) {
                    rowLayout.addView(
                        makeBackspaceView(),
                        LinearLayout.LayoutParams(0, MATCH, 1.5f)
                    )
                }
            }
        }

        fun setItems(items: List<String>) {
            cells.forEachIndexed { index, cell ->
                val text = items.getOrNull(index)
                if (text.isNullOrEmpty()) {
                    cell.text = ""
                    cell.setOnClickListener(null)
                    cell.isClickable = false
                } else {
                    cell.text = text
                    cell.textSize = cellTextSize
                    cell.isClickable = true
                    cell.setOnClickListener { onCommit?.invoke(text) }
                }
            }
        }

        private fun makeBackspaceView(): ImageView {
            val iv = ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_backspace_24)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setColorFilter(theme.keyTextColor, PorterDuff.Mode.SRC_IN)
                setBackground(radiusDrawable(dp(4).toFloat(), theme.keyBackgroundColor))
                isClickable = true
            }
            iv.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        fireBackspace()
                        repeatHandler.postDelayed(repeatRunnable, 400L)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        repeatHandler.removeCallbacks(repeatRunnable)
                        true
                    }
                    else -> false
                }
            }
            return iv
        }
    }

    private class PageHolder(val page: PageView) : RecyclerView.ViewHolder(page)

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        var pages: List<List<String>> = emptyList()

        /**
         * 不同面板的列数/行数/字号不一样，所以按面板类型分池复用，
         * 否则切面板后拿到的旧页还是上一个面板的网格。
         */
        override fun getItemViewType(position: Int): Int = currentKey.ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val page = PageView(currentKey)
            // ViewPager2 强制要求每页撑满容器，写 wrap_content 会直接抛
            // IllegalStateException: Pages must fill the whole ViewPager2
            page.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            return PageHolder(page)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.page.setItems(pages.getOrNull(position) ?: emptyList())
        }

        override fun getItemCount(): Int = pages.size
    }
}

/**
 * 面板底部那一行小键盘：ABC / 逗号 / [切换] / 空格 / 句号 / 回车。
 *
 * 和 fcitx5 的 PickerLayout.Keyboard 一致：只有一行，靠父容器给固定高度。
 * 第三个键按面板不同而不同，由 PickerPanel.makeKeyboard() 决定。
 */
@SuppressLint("ViewConstructor")
class PickerKeyboard(
    context: Context,
    theme: Theme,
    switchKey: KeyDef
) : BaseKeyboard(
    context, theme, listOf(
        listOf(
            LayoutSwitchKey("ABC", TextKeyboard.Name),
            PunctuationKey(","),
            switchKey,
            SpaceKey(),
            PunctuationKey("."),
            ReturnKey()
        )
    )
) {

    /** 布局切换请求（第三个键是 LayoutSwitchKey 时） */
    var onLayoutSwitchRequest: ((String) -> Unit)? = null
    /** 面板切换请求（第三个键是 PickerSwitchKey 时） */
    var onPickerSwitchRequest: ((PickerWindow.Key) -> Unit)? = null
    /** bindListeners 设进来的真实监听器 */
    var externalListener: KeyActionListener? = null

    class PunctuationKey(val symbol: String) : KeyDef(
        KeyDef.Appearance.Text(
            displayText = symbol,
            textSize = 23f,
            percentWidth = 0.1f,
            variant = KeyDef.Appearance.Variant.Alternative
        ),
        setOf(
            KeyDef.Behavior.Press(KeyAction.FcitxKeyAction(symbol))
        )
    )

    init {
        // 第三个键发出来的切换动作交给面板自己处理，
        // 不走全局的 switchLayout / pickerSwitch（那些是给主键盘用的）
        keyActionListener = object : KeyActionListener {
            override fun onKeyAction(action: KeyAction, source: KeyActionListener.Source) {
                when (action) {
                    is KeyAction.LayoutSwitchAction ->
                        onLayoutSwitchRequest?.invoke(action.act)
                    is KeyAction.PickerSwitchAction ->
                        action.key?.let { onPickerSwitchRequest?.invoke(it) }
                    else -> externalListener?.onKeyAction(action, source)
                }
            }
        }
    }


}
