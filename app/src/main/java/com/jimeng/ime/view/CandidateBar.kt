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
     * 布局照 fcitx5：候选从左铺到右，最右边 40dp 留给展开按钮。
     * 这里在 fcitx5 的基础上多两条主人的要求：
     *   1. 一页最多 6 个，多的交给下一页 / 展开页
     *   2. 候选要「加宽」——宽度够就把余量平分给每个候选，铺满整行
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

        val left = dp(4f)
        val available = expandBtn.left - left
        if (available <= 0f) return

        // 一页最多 MAX_ITEMS 个（下标仍是页内下标，和 candidates 一一对应）
        val shown = candidates.take(MAX_ITEMS)
        if (shown.isEmpty()) return

        // 先按字宽算自然宽度
        val natural = shown.map { word ->
            val tw = candidatePaint.measureText(word)
            textWidths.add(tw)
            maxOf(tw + dp(ITEM_PADDING_DP) * 2f, dp(ITEM_MIN_WIDTH_DP))
        }
        val totalNatural = natural.sum()

        val widths = if (totalNatural <= available) {
            // 放得下：把余量平分掉，候选自然就变宽、铺满整行
            val extra = (available - totalNatural) / shown.size
            natural.map { it + extra }
        } else {
            // 放不下：整体等比压缩。
            // 照 fcitx5 的思路——宽度是跟着文字量走的，
            // 宽的候选分到的格子仍然更宽，不会被窄候选挤成一样宽。
            val ratio = available / totalNatural
            natural.map { it * ratio }
        }

        var cursor = left
        for (i in shown.indices) {
            itemRects.add(RectF(cursor, 0f, cursor + widths[i], h))
            cursor += widths[i]
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
            // 候选词居中。照 fcitx5 的 AutoScaleTextView（Proportional）：
            // 文字比格子宽就等比缩小，绝不让它画出格子外跟隔壁黏在一起。
            candidatePaint.color = theme.candidateTextColor
            val word = candidates[i]
            val tw = textWidths.getOrElse(i) { candidatePaint.measureText(word) }
            // 可用宽度：正常情况是格子减左右内边距；格子被压得比内边距还窄时
            // 退化成格宽的 72%，保证「缩字」这条路永远走得通，文字不会画到隔壁去。
            val innerW = maxOf(
                rect.width() - dp(ITEM_PADDING_DP) * 2f,
                rect.width() * 0.72f
            )
            if (tw > innerW && tw > 0f && innerW > 0f) {
                val save = canvas.save()
                val scale = innerW / tw
                // 以格子中心为原点缩放，画完恢复
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

    /** 返回 (候选下标, 是否点到展开按钮)，没有命中就是 (-1, false) */
    private fun hitTest(x: Float, y: Float): Pair<Int, Boolean> {
        computeItemRects()
        for ((i, r) in itemRects.withIndex()) {
            if (r.contains(x, y)) return i to false
        }
        if (expandBtn.contains(x, y)) return -1 to true
        return -1 to false
    }
}