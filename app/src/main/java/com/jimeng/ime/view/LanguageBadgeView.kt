/*
 * 切换语言时在码区位置闪一下的语言标识。
 *
 * 主人要的是：小地球键切语言，码区那一条上能看见「切到哪门语言」——
 * 中文「拼」、英文「En」、日语「あ」，而且以后加的别的语言也得有。
 *
 * ── 为什么单独做一个 View，不直接往码区里塞字 ────────────────
 * 码区（[com.jimeng.ime.input.preedit.PreeditUi]）的内容是引擎的 preedit：
 * 主人一敲键它就被真编码覆盖，一删干净就整行 GONE。把「あ」写在里面，
 * 主人还没看清就被第一个字母顶掉了。
 *
 * 所以提示独立一层，和码区**同位置**、同一个底块样式，只是自己控消失时机：
 * 切过来显示，约 900ms 后淡出。这期间真开始打字的话就让位（见 [dismiss])。
 *
 * ── 定位 ──────────────────────────────────────────────────
 * 和码区一样挂最外层 holder、GRAVITY_BOTTOM + bottomMargin 浮在候选栏上沿，
 * **不参与窗口高度计算**。键盘跳动时由 [attach] 里那份 LayoutParams 跟着更新，
 * 所以不会出现「键盘挪了、提示留在原地」。
 */
package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme

@SuppressLint("ViewConstructor")
class LanguageBadgeView(
    context: Context,
    private val theme: Theme,
) : TextView(context) {

    companion object {
        /** 停留多久开始淡出。够看清，又不至于挡着打字。 */
        private const val HOLD_MS = 900L
        /** 淡出时长。 */
        private const val FADE_MS = 200L
    }

    private val main = Handler(Looper.getMainLooper())

    /** 最近一次要显示的内容。淡出动画中途回来时要靠它判断该不该重新露脸。 */
    private var pending: String? = null

    private val hide = Runnable { animate().alpha(0f).setDuration(FADE_MS).withEndAction {
        // 动画结束后彻底收起来：VISIBLE 但全透明会继续吃触摸，挡住下面对码区的点击
        visibility = View.GONE
    }.start() }

    init {
        // 和码区同样的外观：底块只有字那么宽，半透明由 alpha 给
        setBackgroundColor(theme.barColor)
        setTextColor(theme.keyTextColor)
        textSize = 14f
        includeFontPadding = false
        setPadding(dp(8), dp(1), dp(8), dp(1))
        gravity = Gravity.CENTER
        alpha = 0.8f
        visibility = View.GONE
        isClickable = false
        // 提示只是提示，别把触摸从码区/候选栏上抢走
        isFocusable = false
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    /**
     * 闪一下。
     *
     * 同一个标识连着来（比如切换失败又切回来）也重新计时，
     * 不然第二次会看起来「没反应」。
     */
    fun show(badge: String) {
        if (badge.isEmpty()) {
            dismiss()
            return
        }
        pending = badge
        text = badge
        main.removeCallbacks(hide)
        // 圆角底块：文字短，靠 padding + cornerRadius 撑成一个小胶囊
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(theme.barColor)
        }
        visibility = View.VISIBLE
        animate().cancel()
        alpha = 0.8f
        main.postDelayed(hide, HOLD_MS)
    }

    /**
     * 立刻收起来。
     *
     * 真开始打字时调（码区要显示真编码了，提示留在这儿会叠成两层）。
     */
    fun dismiss() {
        pending = null
        main.removeCallbacks(hide)
        animate().cancel()
        visibility = View.GONE
    }

    /** 提示正在显示吗（码区要判断该不该让位）。 */
    fun showing(): Boolean = pending != null && visibility == View.VISIBLE

    /** 换皮肤后按新主题重刷一遍颜色。 */
    fun applyTheme(theme: Theme) {
        setTextColor(theme.keyTextColor)
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(theme.barColor)
        }
    }
}
