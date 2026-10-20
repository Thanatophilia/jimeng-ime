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

/**
 * 候选展开页。排版照 fcitx5-android 的 ExpandedCandidateLayout + GridExpandedCandidateWindow。
 *
 * fcitx5 的样子（竖屏 6 列网格 + 右侧一列操作键）：
 *   ┌───────────────────────────────┬─────┐
 *   │ 候选1 │ 候选2 │ 候选3 │ 候选4  │  ↑  │
 *   ├───────┼───────┼───────┼────────┤     │
 *   │ 候选5 │ 候选6 │ 候选7 │ 候选8  │  ↓  │
 *   ├───────┼───────┼───────┼────────┼─────┤
 *   │ 候选9 │ 候选10│ 候选11│ 候选12 │  ⌫  │
 *   ├───────┼───────┼───────┼────────┤     │
 *   │ ...                            │  ↵  │
 *   └───────────────────────────────┴─────┘
 *
 * 要点（和原版一致）：
 *   - 中间是候选网格，竖屏 6 列、横屏 8 列（原版 expandedCandidateGridSpanCount 默认值）。
 *   - 右边 15% 宽是一列操作键，从上到下：↑ 上一页、↓ 下一页、⌫ 退格、↵ 回车。
 *   - 网格里每个候选高 40dp，点一下上屏。
 *   - 数据来自 RimeNative.nativeGetAllCandidates：整张候选表，不受当前页限制。
 *
 * 和 fcitx5 的差异（本项目暂时不做）：
 *   - fcitx5 左侧还有一列「候选标签栏」（简繁转换之类的 tab action），
 *     rime 的候选表没这个概念，这里不做。
 *   - fcitx5 用 Paging 分页加载，这里直接取全部（rime 候选表本身有上限，量不大）。
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

        /** 右侧操作列宽度比例，对齐原版 matchConstraintPercentWidth = 0.15f */
        private const val ACTION_COLUMN_PERCENT = 0.15f

        /** 候选字号（sp），对齐 CandidateItemUi 的 20f */
        private const val CANDIDATE_TEXT_SP = 20f
    }

    interface Listener {
        /** 点了某个候选，index 是**全局**下标（不是页内下标） */
        fun onPickCandidate(globalIndex: Int)

        /** 点了退格 */
        fun onBackspace()

        /** 点了回车 */
        fun onReturn()
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

    init {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(theme.barColor)
        // 高度由外面挂上来时给的 LayoutParams 决定（和键盘同高）
        // 盖住键盘时整块区域都要吃掉触摸：
        // 否则点在候选格之间的空白会穿透到下面的键盘，误打一个字出来。
        isClickable = true
        isFocusable = false

        addView(
            gridScroll,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f - ACTION_COLUMN_PERCENT)
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

    /** 更新候选列表。candidates 是整张候选表（全局顺序） */
    fun update(candidates: List<String>) {
        grid.setCandidates(candidates, spanCount)
        gridScroll.scrollTo(0, 0)
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
        private var spanCount = SPAN_PORTRAIT
        private val cellRects = mutableListOf<RectF>()
        private var pressedCell = -1

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

        fun setCandidates(list: List<String>, spans: Int) {
            candidates = list
            spanCount = spans
            pressedCell = -1
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val rows = if (candidates.isEmpty()) 0
            else (candidates.size + spanCount - 1) / spanCount
            setMeasuredDimension(w, rows * dp(CELL_HEIGHT_DP).toInt())
        }

        private fun computeCellRects() {
            cellRects.clear()
            if (candidates.isEmpty() || width == 0) return
            val cellW = width.toFloat() / spanCount
            val cellH = dp(CELL_HEIGHT_DP)
            for (i in candidates.indices) {
                val row = i / spanCount
                val col = i % spanCount
                cellRects.add(
                    RectF(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH)
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (candidates.isEmpty()) return
            computeCellRects()
            for ((i, rect) in cellRects.withIndex()) {
                if (i >= candidates.size) break
                if (i == pressedCell) {
                    highlightPaint.color = theme.keyPressHighlightColor
                    canvas.drawRoundRect(rect, dp(4f), dp(4f), highlightPaint)
                }
                val word = candidates[i]
                val tw = paint.measureText(word)
                val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2
                paint.color = theme.candidateTextColor
                canvas.drawText(word, rect.centerX() - tw / 2f, baseline, paint)
            }
            // 每行底部的横分隔线
            val cellH = dp(CELL_HEIGHT_DP)
            var y = cellH
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, dividerPaint)
                y += cellH
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            computeCellRects()
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
                        listener?.onPickCandidate(hit)
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
