package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager
import kotlin.math.abs
/**
 * 候选栏。排版照 fcitx5-android 的 CandidateUi + HorizontalCandidateComponent。
 *
 * fcitx5 的样子：
 *   ┌─────────────────────────────────────────────┬────┐
 *   │ 候选 │ 候选 │ 候选 │ 候选 │ 候选 │ 候选      │ ⌄  │
 *   └─────────────────────────────────────────────┴────┘
 *
 * 要点（和原版一致）：
 *   - 候选从左往右铺，**没有翻页按钮**，放不下的交给展开页。
 *   - 右边只有一个 40dp 展开按钮，图标是向下的箭头 ⌄；展开后转成向上 ⌃。
 *   - 候选之间画 1dp 竖分隔线，上下各缩 8dp（原版 FlexboxVerticalDecoration）。
 *   - 候选最小宽度 40dp、左右内边距 10dp，字号 20sp、sans-serif-medium。
 *   - 候选栏高 40dp（原版 KawaiiBarComponent.HEIGHT）。
 *
 * 输入码（拼音串）已经拆到 input/preedit/PreeditUi（照抄 fcitx5-android），
 * 作为浮层单独显示在候选栏上方一行，和 fcitx5-android / Gboard 的布局一致
 * ——这样拼音再长也不会挤掉候选。
 */
class CandidateBar(context: Context) : View(context) {

    companion object {
        /** 候选栏高度（dp）。气泡预留空间要扣掉这一块，见 JimengIME 的 paddingTop */
        const val HEIGHT_DP = 40f

        /** 右侧展开按钮宽度（dp），对齐 fcitx5 的 lParams(dp(40)) */
        private const val EXPAND_BTN_DP = 40f

        /** 候选词左右内边距（dp）：比 fcitx5 的 10 更宽松，让候选不挤在一起 */
        private const val ITEM_PADDING_DP = 16f

        /** 候选词最小宽度（dp）：比 fcitx5 的 40 更宽，二字词看着才舒展 */
        private const val ITEM_MIN_WIDTH_DP = 56f

        /**
         * 候选栏最多显示几个候选。
         *
         * 主人要求「二字词一次只展示六个」：超过这个数就分页、
         * 剩下的交给展开页看（fcitx5 是靠滑动，这里靠展开页）。
         * 候选用不完的宽度会平分给这 6 个，字不会挤在左边。
         */
        private const val MAX_ITEMS = 6
    }

    interface Listener {
        /** 点了某个候选，index 是当前页内的下标 */
        fun onPickCandidate(index: Int)

        /** 点了展开按钮 */
        fun onExpand()
    }
    var listener: Listener? = null

    private val theme: Theme = ThemeManager.activeTheme
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var candidates: List<String> = emptyList()
    /** 是否处于展开状态（决定箭头朝上还是朝下） */
    private var expanded = false

    private val itemRects = mutableListOf<RectF>()
    private val textWidths = mutableListOf<Float>()

    // ---- 横向滑动 ----
    // fcitx5 的候选栏是 FlexboxLayoutManager，格子按文字自然宽铺开，
    // 一行放不下就左右滑，字号永远是 20sp、绝不缩字。这里自绘，就用
    // 「一个偏移量 + OverScroller 惯性」把同样的手感做出来。

    /** 候选区的横向滚动偏移（px），0 = 从头看 */
    private var scrollOffset = 0f

    /** 当前最多能滚多远（px），候选放得下时是 0 */
    private var maxScroll = 0f

    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var startScroll = 0f
    /** 这一次手势是否已经变成「滑动」了（滑过就不算点击） */
    private var dragging = false
    /** 这次是按在展开按钮上起手的（按按钮不该触发候选滑动） */
    private var downOnExpand = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(20f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = theme.candidateTextColor
    }
    /** 候选之间的竖分隔线（照原版 dividerDrawable，1dp、theme.dividerColor） */
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.dividerColor
        strokeWidth = dp(1f)
    }

    private var pressedIndex = -1
    private var expandPressed = false
    private val expandBtn = RectF()

    init {
        setBackgroundColor(theme.keyboardColor)
        isClickable = true
    }

    /**
     * 更新候选内容。
     *
     * 没有 page / isLastPage 参数了：候选栏不再显示分页信息，
     * 一次把当前页候选铺出来，放不下的交给展开页。
     */
    fun update(candidates: List<String>) {
        if (this.candidates == candidates) return
        this.candidates = candidates
        // 候选换了一整批（选词、翻页、重新输入），滚动位置跟着回到最左边，
        // 不然会接着上一批的位置，一进来就看到半截格子。
        scrollOffset = 0f
        scroller.forceFinished(true)
        // 展开着的时候即使候选空了也要保持可点，否则箭头就点不回去（收不起来）
        active = candidates.isNotEmpty() || expanded
        invalidate()
    }

    fun clear() = update(emptyList())

    /** 展开页开合时调用，让箭头转个方向 */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        // 收起后如果没有候选，就重新变回不拦截触摸
        active = candidates.isNotEmpty() || value
        invalidate()
    }

    private var active = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(HEIGHT_DP).toInt())
    }

    /**
     * 计算候选词矩形和展开按钮矩形。
     *
     * 关键：onDraw 和 hitTest 必须共用同一份布局结果。
     * 之前只在 onDraw 里填 itemRects，触摸时一旦还没绘制过（或者绘制被跳过），
     * hitTest 就命中不到任何候选词，表现就是「候选栏看得见但点不动」。
     *
     * 布局照 fcitx5：候选从左铺到右，最右边 40dp 留给展开按钮。
     * 这里在 fcitx5 的基础上多两条主人的要求：
     *   1. 一页最多 6 个，多的交给下一页 / 展开页
     *   2. 候选要「加宽」——宽度够就把余量平分给每个候选，铺满整行
     *   3. 放不下时**不压缩、不缩字**，格子保持文字的自然宽度，
     *      整行可以左右滑（照 fcitx5 的 FlexboxLayoutManager）。
     */
    private fun computeItemRects() {
        itemRects.clear()
        textWidths.clear()
        if (!active || width == 0) {
            expandBtn.setEmpty()
            maxScroll = 0f
            return
        }
        val h = height.toFloat()

        // 右侧展开按钮（照 CandidateUi：40dp 宽、垂直居中、贴右）
        expandBtn.set(width - dp(EXPAND_BTN_DP), 0f, width.toFloat(), h)

        // 候选内容的坐标系从 0 开始，绘制时整体平移 -scrollOffset。
        // 两头各留 4dp，让第一格/最后一格不贴边。
        val sidePad = dp(4f)
        val viewW = expandBtn.left
        val fitWidth = viewW - sidePad * 2f
        if (fitWidth <= 0f) {
            maxScroll = 0f
            return
        }

        // 一页最多 MAX_ITEMS 个（下标仍是页内下标，和 candidates 一一对应）
        val shown = candidates.take(MAX_ITEMS)
        if (shown.isEmpty()) {
            maxScroll = 0f
            return
        }

        // 先按字宽算自然宽度
        val natural = shown.map { word ->
            val tw = candidatePaint.measureText(word)
            textWidths.add(tw)
            maxOf(tw + dp(ITEM_PADDING_DP) * 2f, dp(ITEM_MIN_WIDTH_DP))
        }
        val totalNatural = natural.sum()

        val widths: List<Float>
        if (totalNatural <= fitWidth) {
            // 放得下：把余量平分掉，候选自然就变宽、铺满整行
            val extra = (fitWidth - totalNatural) / shown.size
            widths = natural.map { it + extra }
            maxScroll = 0f
        } else {
            // 放不下：格子保持自然宽度，字号一个像素都不缩，
            // 整行改成可以左右滑（主人要的就是 fcitx5 这个手感）。
            widths = natural
            // 内容总宽 = 左侧 4dp + 所有格子；可视宽 = 到箭头左沿。
            // 滚到最右时最后一格紧贴箭头左沿，右边的 sidePad 不会再露出来。
            maxScroll = maxOf(sidePad + totalNatural - viewW, 0f)
        }

        var cursor = sidePad
        for (i in shown.indices) {
            itemRects.add(RectF(cursor, 0f, cursor + widths[i], h))
            cursor += widths[i]
        }

        // 候选换了 / 变少了，把偏移收回到合法范围，免得滑到空白里
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
        if (maxScroll <= 0f) scroller.forceFinished(true)
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        computeItemRects()
        val h = height.toFloat()
        val baseline = h / 2f - (candidatePaint.descent() + candidatePaint.ascent()) / 2

        // 候选区裁到展开按钮左边：滚出去的部分不许画到箭头底下
        val saveClip = canvas.save()
        canvas.clipRect(0f, 0f, expandBtn.left, h)

        // 整体平移 -scrollOffset：格子坐标是「一行铺到底」的，
        // 滚到哪儿就画哪儿（照 ScrollView 的做法）
        val saveScroll = canvas.save()
        canvas.translate(-scrollOffset, 0f)

        for ((i, rect) in itemRects.withIndex()) {
            if (i >= candidates.size) break
            // 按下高亮（照 CandidateItemUi 的 pressHighlightDrawable）
            if (i == pressedIndex) {
                barPaint.color = theme.keyPressHighlightColor
                barPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, dp(4f), dp(4f), barPaint)
            }
            // 候选词居中。字号恒为 20sp，**绝不缩字**——
            // 格子是按文字自然宽铺的，装不下就让整行左右滑（照 fcitx5）。
            candidatePaint.color = theme.candidateTextColor
            val word = candidates[i]
            val tw = textWidths.getOrElse(i) { candidatePaint.measureText(word) }
            canvas.drawText(word, rect.centerX() - tw / 2f, baseline, candidatePaint)

            // 候选之间的竖分隔线（照 FlexboxVerticalDecoration：上下各缩 8dp）
            if (i < itemRects.size - 1) {
                val dx = rect.right
                canvas.drawLine(dx, dp(8f), dx, h - dp(8f), dividerPaint)
            }
        }

        canvas.restoreToCount(saveScroll)
        canvas.restoreToCount(saveClip)

        drawExpandButton(canvas)
    }

    /**
     * 画右侧展开按钮：一个向下的箭头 ⌄。
     *
     * 照 fcitx5 的 ic_baseline_expand_more_24（Material 的 expand_more）；
     * 展开后换成向上的 ⌃（ic_baseline_expand_less_24）。
     */
    private fun drawExpandButton(canvas: Canvas) {
        if (expandBtn.isEmpty) return
        barPaint.style = Paint.Style.STROKE
        barPaint.strokeWidth = dp(2f)
        barPaint.strokeCap = Paint.Cap.ROUND
        barPaint.strokeJoin = Paint.Join.ROUND
        barPaint.color = when {
            candidates.isEmpty() ->
                if (theme.isDark) 0xFF4A4D50.toInt() else 0xFFC4C7CA.toInt()
            expandPressed -> theme.accentKeyBackgroundColor
            else -> theme.altKeyTextColor
        }

        val cx = expandBtn.centerX()
        val cy = expandBtn.centerY()
        val s = dp(5f)
        val path = Path()
        if (expanded) {
            // ⌃ 展开着：箭头朝上
            path.moveTo(cx - s, cy + s * 0.5f)
            path.lineTo(cx, cy - s * 0.5f)
            path.lineTo(cx + s, cy + s * 0.5f)
        } else {
            // ⌄ 收起：箭头朝下
            path.moveTo(cx - s, cy - s * 0.5f)
            path.lineTo(cx, cy + s * 0.5f)
            path.lineTo(cx + s, cy - s * 0.5f)
        }
        canvas.drawPath(path, barPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 候选栏一旦激活（有拼音或有候选），整块区域都要吃掉触摸事件。
        //
        // 只要还 return false，事件就会被放行、穿透到被键盘盖住的应用界面，
        // 表现就是「点候选词反而点到了屏幕上的按钮」。
        if (!active) return false

        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 手指按下去先停住惯性（照 ScrollView：一碰就停）
                scroller.forceFinished(true)
                tracker.clear()
                tracker.addMovement(event)
                downX = event.x
                startScroll = scrollOffset
                dragging = false
                val hit = hitTest(event.x, event.y)
                pressedIndex = hit.first
                expandPressed = hit.second
                // 按在箭头上就别当滑动起点：箭头是个按钮，按歪了也只是取消点击，
                // 不该顺手把候选栏拖着走。
                downOnExpand = hit.second
                // 没命中任何候选/按钮也要消费掉，别让事件穿到下层应用
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker.addMovement(event)
                val dx = event.x - downX
                if (!dragging && !downOnExpand && abs(dx) > touchSlop && maxScroll > 0f) {
                    // 越过手指抖动阈值，正式进入滑动状态，
                    // 之前按下的那个候选就当没按过（不然滑完会误选词）
                    dragging = true
                    pressedIndex = -1
                    expandPressed = false
                }
                if (dragging) {
                    // 手指往左滑 = 内容往左走 = 偏移变大，所以是 startScroll - dx
                    scrollOffset = (startScroll - dx).coerceIn(0f, maxScroll)
                    invalidate()
                } else {
                    val hit = hitTest(event.x, event.y)
                    if (hit.first != pressedIndex || hit.second != expandPressed) {
                        pressedIndex = hit.first
                        expandPressed = hit.second
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    // 松手后按手指速度做惯性滚动（照 fling 的手感）
                    tracker.computeCurrentVelocity(1000)
                    val vx = tracker.xVelocity
                    if (abs(vx) > minFlingVelocity) {
                        scroller.fling(
                            scrollOffset.toInt(), 0,
                            -vx.toInt(), 0,
                            0, maxScroll.toInt(), 0, 0
                        )
                        postInvalidateOnAnimation()
                    }
                    dragging = false
                    pressedIndex = -1
                    expandPressed = false
                    invalidate()
                    return true
                }
                val hit = hitTest(event.x, event.y)
                when {
                    hit.first >= 0 && hit.first == pressedIndex ->
                        listener?.onPickCandidate(hit.first)
                    hit.second && expandPressed ->
                        listener?.onExpand()
                }
                pressedIndex = -1
                expandPressed = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                expandPressed = false
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 惯性滚动每帧推进（View 自带的方法，postInvalidateOnAnimation 后会回调） */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currX.toFloat().coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 输入法 View 是常驻的，除非键盘彻底销毁；此时把 tracker 还给池子
        velocityTracker?.recycle()
        velocityTracker = null
        scroller.forceFinished(true)
    }

    /**
     * 返回 (候选下标, 是否点到展开按钮)，没有命中就是 (-1, false)。
     *
     * 命中顺序和 fcitx5 的 CandidateUi 结构对齐：
     * 那边箭头是一个独立的 View（endOfParent），候选区是另一个 View
     * （before(expandButton)），两者物理隔开、永不重叠。
     * 这里自绘也必须守住同一条边界——**箭头区域归箭头，候选不许抢**。
     *
     * 之前就是少了这条边界：字少时格子刚好到箭头左沿不冲突，
     * 字一多格子铺到箭头底下，候选就先被命中，点箭头等于选中了
     * 藏在下面的候选，所以「字打多了箭头打不开」。
     */
    private fun hitTest(x: Float, y: Float): Pair<Int, Boolean> {
        computeItemRects()
        // 第一步：箭头区域直接归箭头，先判、判到就返回
        if (expandBtn.contains(x, y)) return -1 to true
        // 第二步：候选区只在箭头左边，越界的位置一律不算（画面上也被裁掉了）
        if (x >= expandBtn.left) return -1 to false
        // 格子坐标是一行铺到底的，命中判定要加回滚动偏移，
        // 这样「看到的就是点到的」。
        val contentX = x + scrollOffset
        for ((i, r) in itemRects.withIndex()) {
            if (r.contains(contentX, y)) return i to false
        }
        return -1 to false
    }
}