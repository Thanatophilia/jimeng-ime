/*
 * 键盘上方那条翻译栏。
 *
 * ── 它长什么样 ────────────────────────────────
 *   ┌──────────────────────────────────────────────┐
 *   │  待翻译的文字│          中英互译  ✕   翻译   │
 *   └──────────────────────────────────────────────┘
 *
 * 左边是文字区（不是真的 EditText，理由见下），
 * 右边依次是：语言对（点了循环切换）、✕ 关闭、翻译。
 *
 * ── 为什么不用 EditText ───────────────────────
 * 用 EditText 看着最省事，但在输入法自己身上会出问题：
 * 它一获得焦点，系统可能把输入法的目标输入框切到它身上，
 * 原本正在聊天的应用就收不到字了；切回来又是一堆
 * onStartInput / onFinishInput 的连锁反应。输入法里再嵌一个
 * 可聚焦的输入框，等于让输入法给自己当输入目标，坑比收益多。
 *
 * 所以这里就是一个普通 View：文字由 InputMethodService 那边
 * 通过 [append] / [backspace] 维护，光标永远在末尾（输入法场景够用）。
 *
 * ── 光标是手画的 ──────────────────────────────
 * 没有 EditText 就没有系统光标。可光标不能省：没有它，用户根本
 * 看不出这条栏「能不能打字、打的字去哪了」——这是最要紧的一处提示。
 * 所以文字后面挂一个 1dp 宽的小竖线，跟着闪烁。
 *
 * 闪烁用 ValueAnimator 改 alpha：比 Handler 定时切可见性省电，
 * 也不会在栏收起后留野回调（视图一走 onDetachedFromWindow 就 cancel）。
 *
 * ── 为什么不显示 librime 的候选 ────────────────
 * 拼音候选是打在「应用输入框」里的东西，这条栏拿的是
 * 「已经上屏的字」。所以这里不接候选、不做组字，只做一件事：
 * 把键盘打出来的字先攒在这儿，等用户点翻译。
 */

package com.jimeng.ime.translate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme

/**
 * 键盘上方的翻译栏。
 *
 * 只负责画和点，文字状态的真相在 [com.jimeng.ime.JimengIME] 那边
 * （[setText] 进来、按钮回调出去），避免两边各存一份对不上。
 */
class TranslateBarView(
    context: Context,
    theme: Theme,
    /** 点 ✕：关闭翻译 */
    private val onClose: () -> Unit,
    /** 点「翻译」：把当前文字翻掉 */
    private val onTranslate: () -> Unit,
    /** 点语言对：切换到下一对，参数是新的一对 */
    private val onPairChanged: (TranslatePair) -> Unit
) : LinearLayout(context) {

    /** 文字区 */
    private val textView: TextView

    /** 光标（手画的竖线） */
    private val caretView: View

    /** 语言对按钮，显示当前这一对 */
    private val pairView: TextView

    /** 「翻译」按钮。翻译期间要能变成「翻译中…」并禁用 */
    private val actionView: TextView

    private val theme = theme

    /** 光标闪烁动画。视图离树时 cancel，别留野回调 */
    private var caretAnimator: ValueAnimator? = null

    /** 当前语言对，存名字是为了跟外部（prefs）对齐时不用来回转 */
    private var currentPairName: String = TranslatePair.ZhEn.name

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 上下 padding 压到 5dp：这条栏每多 1dp，键盘就少 1dp。
        // 它只是「打个短句去翻」的临时输入条，不需要多舒展。
        setPadding(dp(8), dp(5), dp(6), dp(5))

        // 整条做成圆角胶囊，浮在键盘上方
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(theme.barColor)
        }

        // ---- 文字区 ----
        textView = TextView(context).apply {
            textSize = 14f
            // 单行：这条栏高度有限，多行会把键盘顶上去。
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }

        // ---- 光标 ----
        // 1dp 宽、17dp 高的小竖线，颜色跟着文字走
        caretView = View(context).apply {
            setBackgroundColor(theme.candidateTextColor)
        }

        val textWrap = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                textView,
                LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                caretView,
                LayoutParams(dp(1), dp(17)).also { it.leftMargin = dp(1) }
            )
        }
        addView(
            textWrap,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        // ---- 语言对 ----
        pairView = TextView(context).apply {
            text = TranslatePair.ZhEn.label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(theme.candidateCommentColor)
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(if (theme.isDark) 0x33FFFFFF else 0x14000000)
                setStroke(dp(1), if (theme.isDark) 0x33FFFFFF else 0x22000000)
            }
            isClickable = true
            setOnClickListener {
                // 循环切：中英 → 中日 → 中韩 → 中英
                val next = when (TranslatePair.fromName(currentPairName)) {
                    TranslatePair.ZhEn -> TranslatePair.ZhJa
                    TranslatePair.ZhJa -> TranslatePair.ZhKo
                    TranslatePair.ZhKo -> TranslatePair.ZhEn
                }
                currentPairName = next.name
                text = next.label
                onPairChanged(next)
            }
        }
        addView(
            pairView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.leftMargin = dp(4) }
        )

        // ---- ✕ 关闭 ----
        val closeView = TextView(context).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(theme.candidateCommentColor)
            setPadding(dp(9), dp(5), dp(4), dp(5))
            isClickable = true
            setOnClickListener { onClose() }
        }
        addView(closeView)

        // ---- 翻译 ----
        actionView = TextView(context).apply {
            text = "翻译"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(theme.accentKeyTextColor)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(theme.accentKeyBackgroundColor)
            }
            isClickable = true
            setOnClickListener { onTranslate() }
        }
        addView(
            actionView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.leftMargin = dp(4) }
        )

        // 走一遍 setText，把提示语的颜色定下来
        setText("")
        startCaretBlink()
    }

    companion object {
        /** 没输入时的提示语 */
        const val HINT = "在这里输入要翻译的内容"
    }

    /** 当前文字（不含提示语） */
    var content: String = ""
        private set

    /** 外部告诉这条栏「现在用的是哪一对」 */
    fun setPair(pair: TranslatePair) {
        currentPairName = pair.name
        pairView.text = pair.label
    }

    /**
     * 换掉全部文字。
     *
     * 文字为空时回落到提示语、并且灰掉；非空时用正常颜色。
     */
    fun setText(text: String) {
        content = text
        if (text.isEmpty()) {
            textView.text = HINT
            textView.setTextColor(
                if (theme.isDark) 0xFF8A8F98.toInt() else 0xFFB0B4BB.toInt()
            )
        } else {
            textView.text = text
            textView.setTextColor(theme.candidateTextColor)
        }
    }

    /** 键盘打进来一个字 */
    fun append(s: String) {
        if (s.isEmpty()) return
        setText(content + s)
    }

    /** 退格删一个字（按码位删，别把 emoji 劈成半个） */
    fun backspace() {
        if (content.isEmpty()) return
        val end = content.offsetByCodePoints(content.length, -1)
        setText(content.substring(0, end))
    }

    /**
     * 翻译进行中：按钮变「翻译中…」并禁用，避免连点。
     *
     * 光标在这时候停掉——它表示「你可以打字」，
     * 翻译中的时候不该还这么暗示。
     */
    fun setBusy(busy: Boolean) {
        actionView.isEnabled = !busy
        actionView.text = if (busy) "翻译中…" else "翻译"
        actionView.alpha = if (busy) 0.6f else 1f
        if (busy) stopCaretBlink() else startCaretBlink()
    }

    // ------------------------------------------------------------ 光标

    /**
     * 开始闪。已经在闪就不重开。
     *
     * 明暗节奏照系统光标来：亮 500ms、暗 500ms，无限循环，
     * 起手先亮 400ms，免得一打开就在暗的那一半。
     */
    private fun startCaretBlink() {
        if (caretAnimator != null) return
        caretView.alpha = 1f
        caretAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 500
            startDelay = 400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { caretView.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopCaretBlink() {
        caretAnimator?.cancel()
        caretAnimator = null
        caretView.alpha = 0f
    }

    /**
     * 离开窗口就把动画停掉。
     *
     * 不停的话，栏关掉之后 ValueAnimator 还攥着这个 View，
     * 每 500ms 醒一次做一次无用的重绘——输入法长期跑在后台，
     * 这种小漏电攒一天也能看出来。
     */
    override fun onDetachedFromWindow() {
        stopCaretBlink()
        super.onDetachedFromWindow()
    }

    /** 重新挂上去时恢复闪烁（换皮肤重建视图会走这条） */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startCaretBlink()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}