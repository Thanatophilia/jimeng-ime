package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager

/**
 * 候选栏。
 *
 * 左边是 preedit（拼音串），右边是当前页候选，最右是翻页/展开按钮。
 * 和 fcitx5-android 的候选栏一样是自绘的：候选数固定 6 个，
 * 每个候选按测量出来的宽度从左往右排，超出的裁掉。
 */
class CandidateBar(context: Context) : View(context) {

    companion object {
        /** 候选栏高度（dp）。气泡预留空间要扣掉这一块，见 JimengIME 的 paddingTop */
        const val HEIGHT_DP = 44f
    }

    interface Listener {
        /** 点了某个候选，index 是当前页内的下标 */
        fun onPickCandidate(index: Int)
        /** 翻页，backward=true 是上一页 */
        fun onPage(backward: Boolean)
        /** 点了展开按钮 */
        fun onExpand()
    }

    var listener: Listener? = null

    private val theme: Theme = ThemeManager.activeTheme
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var preedit: String = ""
    private var candidates: List<String> = emptyList()
    private var page = 0
    private var isLastPage = false
    private var hasPrevPage = false

    private val itemRects = mutableListOf<RectF>()
    private val textWidths = mutableListOf<Float>()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val preeditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = theme.candidateCommentColor
    }
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(19f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = theme.candidateTextColor
    }

    private var pressedIndex = -1
    private var pressedPageBtn = 0    // 0 无, 1 上一页, 2 下一页, 3 展开

    private val prevBtn = RectF()
    private val nextBtn = RectF()
    private val expandBtn = RectF()

    init {
        setBackgroundColor(theme.keyboardColor)
        isClickable = true
    }

    /** 更新候选内容；返回是否真的有变化（用来决定要不要重绘） */
    fun update(preedit: String, candidates: List<String>, page: Int, isLastPage: Boolean, hasPrev: Boolean) {
        val changed = this.preedit != preedit ||
            this.candidates != candidates ||
            this.page != page ||
            this.isLastPage != isLastPage ||
            this.hasPrevPage != hasPrev
        if (!changed) return
        this.preedit = preedit
        this.candidates = candidates
        this.page = page
        this.isLastPage = isLastPage
        this.hasPrevPage = hasPrev
        active = preedit.isNotEmpty() || candidates.isNotEmpty()
        invalidate()
    }

    fun clear() = update("", emptyList(), 0, true, false)

    private var active = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(HEIGHT_DP).toInt())
    }

    /**
     * 计算 preedit 宽度、候选词矩形、右侧翻页/展开按钮矩形。
     *
     * 关键：onDraw 和 hitTest 必须共用同一份布局结果。
     * 之前只在 onDraw 里填 itemRects，触摸时一旦还没绘制过（或者绘制被跳过），
     * hitTest 就命中不到任何候选词，表现就是「候选栏看得见但点不动」。
     */
    private fun computeItemRects() {
        itemRects.clear()
        textWidths.clear()
        if (!active || width == 0) {
            prevBtn.setEmpty()
            nextBtn.setEmpty()
            expandBtn.setEmpty()
            return
        }
        val h = height.toFloat()
        var x = dp(12f)
        if (preedit.isNotEmpty()) {
            x += preeditPaint.measureText(preedit) + dp(14f)
        }
        // 右侧按钮宽度先扣掉
        val btnW = dp(30f)
        val rightEdge = width - dp(6f)
        expandBtn.set(rightEdge - btnW, 0f, rightEdge, h)
        nextBtn.set(expandBtn.left - btnW, 0f, expandBtn.left, h)
        prevBtn.set(nextBtn.left - btnW, 0f, nextBtn.left, h)
        val available = prevBtn.left - x - dp(4f)

        var consumed = 0f
        for ((i, word) in candidates.withIndex()) {
            val tw = candidatePaint.measureText(word)
            val itemW = tw + dp(20f)
            if (consumed + itemW > available && i > 0) break
            itemRects.add(RectF(x + consumed, dp(4f), x + consumed + itemW, h - dp(4f)))
            textWidths.add(tw)
            consumed += itemW
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) {
            // 空状态给一行淡提示
            barPaint.color = theme.candidateCommentColor
            preeditPaint.alpha = 90
            canvas.drawText("霁梦输入法", dp(14f), height / 2f + dp(5f), preeditPaint)
            preeditPaint.alpha = 255
            return
        }
        computeItemRects()
        val h = height.toFloat()
        var x = dp(12f)
        // preedit
        if (preedit.isNotEmpty()) {
            val pw = preeditPaint.measureText(preedit)
            val baseline = h / 2f - (preeditPaint.descent() + preeditPaint.ascent()) / 2
            canvas.drawText(preedit, x, baseline, preeditPaint)
            x += pw + dp(14f)
        }
        drawPagerButton(canvas, prevBtn, true, hasPrevPage && page > 0)
        drawPagerButton(canvas, nextBtn, false, !isLastPage)
        drawExpandButton(canvas, expandBtn, candidates.isNotEmpty())

        val baseline = h / 2f - (candidatePaint.descent() + candidatePaint.ascent()) / 2
        for ((i, rect) in itemRects.withIndex()) {
            if (i >= candidates.size) break
            if (i == pressedIndex) {
                barPaint.color = if (theme.isDark) 0x33FFFFFF else 0x1F000000
                canvas.drawRoundRect(rect, dp(6f), dp(6f), barPaint)
            }
            candidatePaint.color = theme.candidateTextColor
            canvas.drawText(candidates[i], rect.left + dp(10f), baseline, candidatePaint)
        }
    }

    private fun drawPagerButton(canvas: Canvas, rect: RectF, up: Boolean, enabled: Boolean) {
        barPaint.color = if (theme.isDark) {
            if (enabled) 0xFFB0B4B9.toInt() else 0xFF4A4D50.toInt()
        } else {
            if (enabled) 0xFF5F6368.toInt() else 0xFFC4C7CA.toInt()
        }
        barPaint.strokeWidth = dp(1.8f)
        barPaint.style = Paint.Style.STROKE
        barPaint.strokeCap = Paint.Cap.ROUND
        val cx = rect.centerX()
        val cy = rect.centerY()
        val s = dp(5f)
        val path = android.graphics.Path()
        if (up) {
            path.moveTo(cx + s * 0.6f, cy - s)
            path.lineTo(cx - s * 0.6f, cy)
            path.lineTo(cx + s * 0.6f, cy + s)
        } else {
            path.moveTo(cx - s * 0.6f, cy - s)
            path.lineTo(cx + s * 0.6f, cy)
            path.lineTo(cx - s * 0.6f, cy + s)
        }
        canvas.drawPath(path, barPaint)
    }

    private fun drawExpandButton(canvas: Canvas, rect: RectF, enabled: Boolean) {
        barPaint.color = when {
            !enabled -> if (theme.isDark) 0xFF4A4D50.toInt() else 0xFFC4C7CA.toInt()
            pressedPageBtn == 3 -> theme.accentKeyBackgroundColor
            else -> if (theme.isDark) 0xFFB0B4B9.toInt() else 0xFF5F6368.toInt()
        }
        barPaint.style = Paint.Style.FILL
        val cx = rect.centerX()
        val cy = rect.centerY()
        val s = dp(1.8f)
        val gap = dp(4f)
        for (i in -1..1) {
            canvas.drawCircle(cx + i * gap, cy, s, barPaint)
        }
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
                pressedPageBtn = hit.second
                // 没命中任何候选/按钮也要消费掉，别让事件穿到下层应用
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val hit = hitTest(event.x, event.y)
                if (hit.first != pressedIndex || hit.second != pressedPageBtn) {
                    pressedIndex = hit.first
                    pressedPageBtn = hit.second
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = hitTest(event.x, event.y)
                when {
                    hit.first >= 0 && hit.first == pressedIndex ->
                        listener?.onPickCandidate(hit.first)
                    hit.second != 0 && hit.second == pressedPageBtn -> when (hit.second) {
                        1 -> listener?.onPage(true)
                        2 -> listener?.onPage(false)
                        3 -> listener?.onExpand()
                    }
                }
                pressedIndex = -1
                pressedPageBtn = 0
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                pressedPageBtn = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 返回 (候选下标, 按钮编号)，没有命中就是 (-1, 0) */
    private fun hitTest(x: Float, y: Float): Pair<Int, Int> {
        computeItemRects()
        for ((i, r) in itemRects.withIndex()) {
            if (r.contains(x, y)) return i to 0
        }
        if (expandBtn.contains(x, y)) return -1 to 3
        if (nextBtn.contains(x, y)) return -1 to 2
        if (prevBtn.contains(x, y)) return -1 to 1
        return -1 to 0
    }
}