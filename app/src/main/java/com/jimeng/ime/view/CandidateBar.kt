package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager
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

        /** 候选词左右内边距（dp）。
         *  fcitx5 原版是 10dp，但那是按它的格宽算的。这台设备 360dp 宽、
         *  扣掉 40dp 箭头后 6 等分，每格只有 52.3dp：
         *  留 10dp 内边距就只剩 32dp，两个汉字（40dp）会被压到 80%；
         *  留 6dp 内边距剩 40.3dp，两个汉字正好放得下、一点不缩。
         *  只有三个字以上的长词才会缩——这就是「超过上限才缩小」。 */
        private const val ITEM_PADDING_DP = 6f

        /**
         * 候选栏固定分成几格。
         *
         * 照 fcitx5 的 expandedCandidateGridSpanCount（竖屏默认 6）：
         * 缺口宽度 / 6 = 每格宽度，**和候选文字多少无关**。
         * 所以候选再多，格子位置和格子宽度都不动，字号也不动——
         * 多出来的候选交给展开页看。
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

    // ---- 布局参数（照 fcitx5 HorizontalCandidateComponent 的 AutoFillWidth） ----
    //
    // fcitx5 的真实做法（之前小雅看错了，这里记清楚）：
    //   · layoutMinWidth = view.width / maxSpanCount - dividerWidth
    //     候选栏被**固定切成 6 等份**，每格宽度和文字多少无关；
    //   · layoutFlexGrow = if (candidates.size < 6) 0f else 1f
    //     不满 6 个也不拉宽，格子位置固定不动；
    //   · FlexboxLayoutManager 的 canScrollHorizontally() = false
    //     **候选栏根本不滑动**；
    //   · 文字用 AutoScaleTextView(Mode.Proportional)，只有当某个词
    //     比**自己那一格**宽时才缩那一格的字（contentWidth / textWidth），
    //     这就是主人说的「超过候选栏显示上限才会缩小」。
    //
    // 也就是说：候选栏是「固定的」，字在里面排版；
    // 不是格子跟着字变宽，也不是整行能滑。小雅上一版做成滑动是错的。

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
     * 布局照 fcitx5 的 AutoFillWidth 模式：
     *   - 最右边 40dp 是展开按钮（CandidateUi 的 lParams(dp(40)) endOfParent）；
     *   - 剩下的区域**固定切成 MAX_ITEMS 等份**，
     *     每格宽 = 可用宽 / MAX_ITEMS - 分隔线宽，和文字多少完全无关；
     *   - 候选从第一格开始往右填，不满也不拉宽、不留空位变化，
     *     所以「字打多少，格子位置都不动」——这就是「候选栏是固定的」；
     *   - 只有某个词比自己那一格还宽时，才在绘制时缩**那一格**的字
     *     （AutoScaleTextView 的 Mode.Proportional），其余格字号不变。
     */
    private fun computeItemRects() {
        itemRects.clear()
        textWidths.clear()
        if (!active || width == 0) {
            expandBtn.setEmpty()
            return
        }
        val h = height.toFloat()

        // 右侧展开按钮（照 CandidateUi：40dp 宽、垂直居中、贴右）
        expandBtn.set(width - dp(EXPAND_BTN_DP), 0f, width.toFloat(), h)

        val viewW = expandBtn.left
        if (viewW <= 0f) return

        // 固定 MAX_ITEMS 等份：每格宽度只跟栏宽有关，跟文字无关（照 fcitx5）
        val dividerW = dp(1f)
        val cellW = viewW / MAX_ITEMS - dividerW
        if (cellW <= 0f) return

        // 先按字宽量一遍（绘制缩字要用）
        val shown = candidates.take(MAX_ITEMS)
        for (word in shown) {
            textWidths.add(candidatePaint.measureText(word))
        }

        // 从左往右按固定格宽摆格子。不满 MAX_ITEMS 就只摆前面几个，
        // 后面的格子空着——候选栏本身不动，位置永远是这 6 个固定位置。
        var cursor = 0f
        for (i in shown.indices) {
            itemRects.add(RectF(cursor, 0f, cursor + cellW, h))
            cursor += cellW + dividerW
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        computeItemRects()
        val h = height.toFloat()
        val baseline = h / 2f - (candidatePaint.descent() + candidatePaint.ascent()) / 2

        for ((i, rect) in itemRects.withIndex()) {
            if (i >= candidates.size) break
            // 按下高亮（照 CandidateItemUi 的 pressHighlightDrawable）
            if (i == pressedIndex) {
                barPaint.color = theme.keyPressHighlightColor
                barPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(rect, dp(4f), dp(4f), barPaint)
            }
            // 候选词居中。照 fcitx5 的 AutoScaleTextView(Mode.Proportional)：
            // 字号本是 20sp；**只有**这个词比自己那一格（减左右内边距）还宽时，
            // 才把这一格的字等比缩小，别格的字号一点不动。
            // 这就是主人说的「字多了字号不变，超过候选栏显示上限才缩小」。
            candidatePaint.color = theme.candidateTextColor
            val word = candidates[i]
            val tw = textWidths.getOrElse(i) { candidatePaint.measureText(word) }
            val innerW = rect.width() - dp(ITEM_PADDING_DP) * 2f
            if (tw > innerW && tw > 0f && innerW > 0f) {
                // 照 AutoScaleTextView(Mode.Proportional)：
                // scale = contentWidth / textWidth，水平和垂直一起缩。
                // 以格子中心为轴缩放，文字中心不动，缩完宽度正好等于 innerW，
                // 仍然是居中的——和原版 calculateBaselineX 的居中算法等价。
                val scale = innerW / tw
                val save = canvas.save()
                canvas.scale(scale, scale, rect.centerX(), rect.centerY())
                canvas.drawText(word, rect.centerX() - tw / 2f, baseline, candidatePaint)
                canvas.restoreToCount(save)
            } else {
                canvas.drawText(word, rect.centerX() - tw / 2f, baseline, candidatePaint)
            }

            // 候选之间的竖分隔线（照 FlexboxVerticalDecoration：上下各缩 8dp）
            if (i < itemRects.size - 1) {
                val dx = rect.right
                canvas.drawLine(dx, dp(8f), dx, h - dp(8f), dividerPaint)
            }
        }

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
        //
        // 这里**没有滑动**：照 fcitx5 的 FlexboxLayoutManager
        // （canScrollHorizontally() = false），候选栏是固定的，
        // 只有点选和箭头两种操作。
        if (!active) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(event.x, event.y)
                pressedIndex = hit.first
                expandPressed = hit.second
                // 没命中任何候选/按钮也要消费掉，别让事件穿到下层应用
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val hit = hitTest(event.x, event.y)
                if (hit.first != pressedIndex || hit.second != expandPressed) {
                    pressedIndex = hit.first
                    expandPressed = hit.second
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
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
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 返回 (候选下标, 是否点到展开按钮)，没有命中就是 (-1, false)。
     *
     * 命中顺序和 fcitx5 的 CandidateUi 结构对齐：
     * 那边箭头是一个独立的 View（endOfParent），候选区是另一个 View
     * （before(expandButton)），两者物理隔开、永不重叠。
     * 这里自绘也必须守住同一条边界——**箭头区域归箭头，候选不许抢**。
     *
     * 候选栏现在固定 6 等份，格子永远不会铺到箭头底下，
     * 但这条边界还是留着：以后改布局也不会再犯「点箭头点到候选」的错。
     */
    private fun hitTest(x: Float, y: Float): Pair<Int, Boolean> {
        computeItemRects()
        // 箭头区域直接归箭头，先判、判到就返回
        if (expandBtn.contains(x, y)) return -1 to true
        for ((i, r) in itemRects.withIndex()) {
            if (r.contains(x, y)) return i to false
        }
        return -1 to false
    }
}