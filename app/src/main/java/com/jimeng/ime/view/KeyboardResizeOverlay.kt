/*
 * 键盘尺寸调节层（主人 #20 的「直接在键盘上拉伸」）。
 *
 * 为什么单独做一层 View，而不是直接改键盘：
 *   键盘那 4 行的高度全靠在父容器上均分，改高度只能改父容器（kbHost）的 LayoutParams；
 *   左右/底部留白在 fcitx5 原版里是用「空白 View」占位的。这一层把三件事包在一起：
 *     1. 画几条半透明的拖拽手柄（上沿一条、左右两条、下部一条）
 *     2. 吃掉手柄上的触摸，换算成新的尺寸写回 prefs
 *     3. 拖动时实时预览（只改视图，不重建），松手才落盘 + 通知重建
 *
 * 为什么不放进键盘内部：
 *   触摸要先给键盘的 CustomGestureView 处理，手柄会被抢走事件（按键会亮）。
 *   挂在 kbHost 之上、与键盘同级（FrameLayout 后加的子 View 在上），
 *   手柄能先吃到触摸；手柄以外的区域 return false，事件照样落到下面的键盘。
 *
 * 只在「调节模式」下才 VISIBLE，平时 GONE，不影响打字。
 */
package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme

/**
 * 键盘尺寸调节浮层。
 *
 * 手柄位置（都贴在键盘外沿，互不重叠）：
 * ```
 *      ┌─────────── 上沿（调高度）───────────┐
 *      │左                                 右│
 *      │边                                 边│
 *      │（调                                （调
 *      │边距）                              边距）
 *      └─────────── 下沿（调底部边距）────────┘
 * ```
 *
 * @param ctx 上下文
 * @param theme 当前主题，用来配色
 * @param listener 尺寸变化回调（拖动中也会回调，方便实时预览）
 */
@SuppressLint("ViewConstructor")
class KeyboardResizeOverlay(
    ctx: Context,
    private val theme: Theme,
    private val listener: Listener
) : FrameLayout(ctx) {

    interface Listener {
        /** 上沿拖拽：给出「新的键盘高度像素」，调用方自己换算成百分比 */
        fun onDragHeight(newHeightPx: Int, finished: Boolean)

        /** 左右拖拽：给出「新的单侧留白 dp」 */
        fun onDragSidePadding(newDp: Int, finished: Boolean)

        /** 下沿拖拽：给出「新的底部留白 dp」 */
        fun onDragBottomPadding(newDp: Int, finished: Boolean)

        /** 点「完成」按钮收起调节层 */
        fun onExitResize()
    }

    // ---- 手柄尺寸 ----
    /** 手柄条厚度：够粗好抓，又不至于遮住键帽的字 */
    private val handleThickness = dp(14)
    /** 左右手柄的宽度（比厚度宽，因为横向拖要抓得稳） */
    private val sideHandleWidth = dp(26)
    /** 底部手柄高度 */
    private val bottomHandleHeight = dp(20)

    // ---- 当前值（由外部 setSizes 灌进来，拖动时在基础上算）----
    private var baseHeightPx = 0
    private var baseSidePaddingDp = 0
    private var baseBottomPaddingDp = 0
    /** 一屏的密度值，dp ↔ px */
    private val density: Float = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.accentKeyBackgroundColor
        alpha = 150
    }
    /** 拖动时手柄加深，让人知道自己抓住的是哪一条 */
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.accentKeyBackgroundColor
        alpha = 235
    }

    // ---- 拖动状态 ----
    private enum class Drag { None, Height, SideLeft, SideRight, Bottom }
    private var dragging = Drag.None
    private var dragStartRaw = 0f
    private var dragStartHeight = 0
    private var dragStartSideDp = 0
    private var dragStartBottomDp = 0

    /** 上一次上报的值，相同就不重复回调（避免拖动时每像素都发一次） */
    private var lastHeightPx = -1
    private var lastSideDp = -1
    private var lastBottomDp = -1

    /** 顶部提示条：告诉主人现在拖的是哪个值、当前是多少 */
    private val hint = TextView(ctx).apply {
        textSize = 13f
        setTextColor(theme.accentKeyTextColor)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(5), dp(14), dp(5))
        setBackgroundColor(theme.accentKeyBackgroundColor)
    }

    /** 「完成」按钮：右下角一个小方块 */
    private val doneButton = TextView(ctx).apply {
        text = "完成"
        textSize = 13f
        setTextColor(theme.accentKeyTextColor)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setBackgroundColor(theme.accentKeyBackgroundColor)
        setOnClickListener { listener.onExitResize() }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        // 提示条贴在左上，完成按钮贴右下，都不挡手柄
        addView(
            hint,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = handleThickness
            }
        )
        addView(
            doneButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = bottomHandleHeight + dp(6)
                rightMargin = dp(8)
            }
        )
    }

    /**
     * 灌入当前的尺寸，作为拖动的起点。
     *
     * @param heightPx 键盘高度
     * @param sidePaddingDp 单侧留白
     * @param bottomPaddingDp 底部留白
     */
    fun setSizes(heightPx: Int, sidePaddingDp: Int, bottomPaddingDp: Int) {
        baseHeightPx = heightPx
        baseSidePaddingDp = sidePaddingDp
        baseBottomPaddingDp = bottomPaddingDp
        lastHeightPx = heightPx
        lastSideDp = sidePaddingDp
        lastBottomDp = bottomPaddingDp
        updateHint()
        invalidate()
    }

    private fun updateHint() {
        hint.text = "高 ${heightToPercent(baseHeightPx)}% · 边 $baseSidePaddingDp dp · 底 $baseBottomPaddingDp dp"
    }

    /** 键盘高度像素 → 屏高百分比（和 JimengIME.keyboardHeightPx 用同一个分母） */
    private fun heightToPercent(px: Int): Int {
        val base = resources.displayMetrics.heightPixels
        return if (base <= 0) 0 else px * 100 / base
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    // ------------------------------------------------------------ 画手柄

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 上沿：整条横的，调高度
        val topPaint = if (dragging == Drag.Height) activePaint else paint
        canvas.drawRoundRect(
            RectF(dp(6).toFloat(), 0f, w - dp(6), handleThickness.toFloat()),
            dp(7).toFloat(), dp(7).toFloat(), topPaint
        )

        // 下沿：整条横的，调底部留白
        val bottomPaint = if (dragging == Drag.Bottom) activePaint else paint
        canvas.drawRoundRect(
            RectF(dp(6).toFloat(), h - bottomHandleHeight, w - dp(6), h),
            dp(7).toFloat(), dp(7).toFloat(), bottomPaint
        )

        // 左右两条：竖的，调两侧留白。避开上下手柄的高度，免得压在一起
        val sideTop = (handleThickness + dp(2)).toFloat()
        val sideBottom = h - bottomHandleHeight.toFloat() - dp(2)
        if (sideBottom > sideTop) {
            val leftPaint = if (dragging == Drag.SideLeft) activePaint else paint
            canvas.drawRoundRect(
                RectF(0f, sideTop, sideHandleWidth.toFloat(), sideBottom),
                dp(7).toFloat(), dp(7).toFloat(), leftPaint
            )
            val rightPaint = if (dragging == Drag.SideRight) activePaint else paint
            canvas.drawRoundRect(
                RectF(w - sideHandleWidth, sideTop, w, sideBottom),
                dp(7).toFloat(), dp(7).toFloat(), rightPaint
            )
        }
    }

    // ------------------------------------------------------------ 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val zone = hitZone(event.x, event.y) ?: return false
                dragging = zone
                dragStartRaw = if (zone == Drag.Height || zone == Drag.Bottom) event.rawY else event.rawX
                dragStartHeight = baseHeightPx
                dragStartSideDp = baseSidePaddingDp
                dragStartBottomDp = baseBottomPaddingDp
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging == Drag.None) return false
                when (dragging) {
                    // 往上拖 = 键盘变高（rawY 变小）
                    Drag.Height -> {
                        val delta = (dragStartRaw - event.rawY).toInt()
                        val newH = (dragStartHeight + delta).coerceIn(minHeightPx(), maxHeightPx())
                        if (newH != lastHeightPx) {
                            lastHeightPx = newH
                            baseHeightPx = newH
                            listener.onDragHeight(newH, false)
                            updateHint()
                            invalidate()
                        }
                    }

                    // 左右拖：往内拖 = 留白变大（手指从边往里走，边距增大）
                    Drag.SideLeft -> {
                        val deltaDp = ((event.rawX - dragStartRaw) / density).toInt()
                        val newDp = (dragStartSideDp + deltaDp).coerceIn(MIN_SIDE_DP, MAX_SIDE_DP)
                        if (newDp != lastSideDp) {
                            lastSideDp = newDp
                            baseSidePaddingDp = newDp
                            listener.onDragSidePadding(newDp, false)
                            updateHint()
                            invalidate()
                        }
                    }

                    Drag.SideRight -> {
                        val deltaDp = ((dragStartRaw - event.rawX) / density).toInt()
                        val newDp = (dragStartSideDp + deltaDp).coerceIn(MIN_SIDE_DP, MAX_SIDE_DP)
                        if (newDp != lastSideDp) {
                            lastSideDp = newDp
                            baseSidePaddingDp = newDp
                            listener.onDragSidePadding(newDp, false)
                            updateHint()
                            invalidate()
                        }
                    }

                    // 下沿往上拖 = 键盘底边抬高 = 底部留白变大。
                    // 和另外三条边一个逻辑：手指抓着键盘的边，边跟着手指走
                    Drag.Bottom -> {
                        val deltaDp = ((dragStartRaw - event.rawY) / density).toInt()
                        val newDp = (dragStartBottomDp + deltaDp).coerceIn(MIN_BOTTOM_DP, MAX_BOTTOM_DP)
                        if (newDp != lastBottomDp) {
                            lastBottomDp = newDp
                            baseBottomPaddingDp = newDp
                            listener.onDragBottomPadding(newDp, false)
                            updateHint()
                            invalidate()
                        }
                    }

                    Drag.None -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val zone = dragging
                dragging = Drag.None
                invalidate()
                // 松手才落盘：告诉外面把当前值存进 prefs 并重建键盘
                when (zone) {
                    Drag.Height -> listener.onDragHeight(baseHeightPx, true)
                    Drag.SideLeft, Drag.SideRight -> listener.onDragSidePadding(baseSidePaddingDp, true)
                    Drag.Bottom -> listener.onDragBottomPadding(baseBottomPaddingDp, true)
                    Drag.None -> Unit
                }
                return zone != Drag.None
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 判断按在哪个手柄上。
     *
     * 四条手柄互不重叠：上/下是整条横的，左右是从上沿下面到下沿上面，
     * 所以这里只要按「先上下、后左右」的顺序判，就不会有歧义。
     */
    private fun hitZone(x: Float, y: Float): Drag? {
        // 上沿（调到 handleThickness）
        if (y <= handleThickness) return Drag.Height
        // 下沿
        if (y >= height - bottomHandleHeight) return Drag.Bottom
        // 左右两条（中间的竖段）
        if (y >= handleThickness && y <= height - bottomHandleHeight) {
            if (x <= sideHandleWidth) return Drag.SideLeft
            if (x >= width - sideHandleWidth) return Drag.SideRight
        }
        return null
    }

    // ---- 高度允许范围：屏高的 20% ~ 70% ----
    private fun minHeightPx(): Int = resources.displayMetrics.heightPixels * MIN_HEIGHT_PERCENT / 100
    private fun maxHeightPx(): Int = resources.displayMetrics.heightPixels * MAX_HEIGHT_PERCENT / 100

    companion object {
        const val MIN_HEIGHT_PERCENT = 20
        const val MAX_HEIGHT_PERCENT = 70

        const val MIN_SIDE_DP = 0
        const val MAX_SIDE_DP = 60

        const val MIN_BOTTOM_DP = 0
        const val MAX_BOTTOM_DP = 60
    }
}
