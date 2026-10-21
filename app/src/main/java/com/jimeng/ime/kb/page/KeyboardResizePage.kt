package com.jimeng.ime.kb.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme

/**
 * 「调整键盘」页：上下拖动直接调键盘高度。
 *
 * 为什么做成整块换页（跟剪贴板 / 文本编辑页同一套机制）：
 *   - 换页时主键盘已经摘下来了（[com.jimeng.ime.JimengIME.attachPage] 里的
 *     keyboard.onDetach()），这里不会跟按键手势打架；
 *   - 候选栏会变成「← 调整键盘」，返回键也能退出，用户永远知道自己在哪；
 *   - 写的是设置页那两个滑块用的同一份 prefs，
 *     拖完回设置页看到的数字一致，不会出现两套值打架。
 *
 * 界面（自上而下）：
 *   - 中间大字：当前百分比
 *   - 下面一行提示
 *   - 底部：可上下拖的把手（贴屏幕底，永远看得见）
 *
 * 换算：
 *   把「本页可拖动的竖直距离」均分给 [MIN_PERCENT]~[MAX_PERCENT] 的整个区间，
 *   所以拖满一次就能从最小走到最大，不用按整屏像素去算
 *   （换页后这里只有键盘那么高，按整屏算的话从 34% 拖到 70% 要拉 850px 以上，
 *   一次根本拖不到）。
 *
 * 拖动过程中只动把手位置和数字（committed = false），松手才真写 prefs 并
 * 重排键盘（committed = true）。否则每拖一像素就改一次窗口高度，
 * 窗口会跟着抖，还没法把把手拖出屏幕。
 */
class KeyboardResizePage(
    context: Context,
    theme: Theme,
    /** 当前方向下键盘高度占屏幕的百分比 */
    initialPercent: Int,
    /** 百分比变化回调（已夹在合法区间内；committed 表示是否松手提交） */
    private val onPercentChanged: (percent: Int, committed: Boolean) -> Unit
) : InputPage(context, theme) {

    companion object {
        /** 和设置页两个滑块保持一致的可调范围 */
        const val MIN_PERCENT = 20
        const val MAX_PERCENT = 70

        /** 把手高度 */
        private const val HANDLE_HEIGHT_DP = 52f

        /** 把手中间那条小横杠 */
        private const val GRIP_WIDTH_DP = 44f
        private const val GRIP_HEIGHT_DP = 4f
    }

    override val title: String = "调整键盘"

    /** 当前百分比（拖动中会变） */
    private var percent: Int = initialPercent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    /** 中间那个大百分比 */
    private val percentText = TextView(context).apply {
        textSize = 34f
        setTextColor(theme.keyTextColor)
        gravity = Gravity.CENTER
    }

    /** 提示 */
    private val hintText = TextView(context).apply {
        textSize = 13f
        setTextColor(theme.altKeyTextColor)
        gravity = Gravity.CENTER
    }

    /** 可上下拖动的把手 */
    private val handle = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(theme.altKeyBackgroundColor)
        }
        isClickable = true
    }

    /** 把手中间的小横杠 */
    private val grip = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(GRIP_HEIGHT_DP / 2f).toFloat()
            setColor(theme.altKeyTextColor)
        }
        alpha = 0.5f
    }

    /** 把手高度（像素），构造函数里算好，拖动时用 */
    private val handleHeightPx = dp(HANDLE_HEIGHT_DP)

    override val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        setBackgroundColor(theme.barColor)
    }

    init {
        handle.addView(
            grip,
            FrameLayout.LayoutParams(
                dp(GRIP_WIDTH_DP),
                dp(GRIP_HEIGHT_DP),
                Gravity.CENTER
            )
        )

        val column = root as LinearLayout
        // 数字和提示一起居中占满中间那块
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        center.addView(
            percentText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        center.addView(
            hintText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) }
        )

        column.addView(
            center,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        column.addView(
            handle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                handleHeightPx
            )
        )

        handle.setOnTouchListener(DragListener())
        updateTexts()
    }

    /**
     * 拖动：把页面可用的竖直距离均分给整个百分比区间。
     *
     * 手指往上 → 键盘变高（百分比加）；往下 → 变矮。
     * 把手跟着手指走，但夹在页面内，拖到头把手停住、数字不再变。
     */
    private inner class DragListener : View.OnTouchListener {
        private var downY = 0f
        /** 按下时的百分比 */
        private var startPercent = 0
        /** 按下时把手已经偏移了多少（重复拖时从当前位置接着来） */
        private var startOffset = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            // 可拖的总行程：页面高度扣掉把手自己
            val travel = (root.height - handleHeightPx).coerceAtLeast(1)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startPercent = percent
                    startOffset = handle.translationY
                    // 抓住时把手亮一点，告诉主人「按上了」
                    handle.alpha = 0.75f
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    // 往上拖（dy 为负）→ 百分比加
                    val delta = -dy / travel * (MAX_PERCENT - MIN_PERCENT)
                    val next = (startPercent + delta).toInt().coerceIn(MIN_PERCENT, MAX_PERCENT)
                    if (next != percent) {
                        percent = next
                        updateTexts()
                        onPercentChanged(percent, false)
                    }
                    // 把手跟手，但夹在页面内
                    handle.translationY = (startOffset + dy).coerceIn(0f, travel.toFloat())
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handle.alpha = 1f
                    handle.translationY = 0f
                    // 松手才真的提交
                    onPercentChanged(percent, true)
                    return true
                }
            }
            return false
        }
    }

    private fun updateTexts() {
        percentText.text = "$percent%"
        hintText.text = "上下拖动下面的把手，调整键盘高度"
    }

    /** 页面收起时补交一次，防「拖完直接按返回」丢改动 */
    override fun onDetached() {
        onPercentChanged(percent, true)
    }
}
