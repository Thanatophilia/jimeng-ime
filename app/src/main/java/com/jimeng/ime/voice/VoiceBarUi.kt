/*
 * 语音输入条：录音时的界面，直接住在候选栏里。
 *
 * 用户说「那个录音弹窗能不能放到候选栏里」——原来是飘在键盘上方的一块卡片，
 * 挡视线也挡应用内容。现在改成候选栏中间那一格横着排：
 *
 *   [ 波形 ][ 状态 / 识别中的字 ······ ][ ✕ ]
 *
 * 高度就是候选栏的 40dp：不额外占窗口高度，也不会盖住上面的应用。
 * 右端那一格的麦克风**不动**：录音中再按一下就是「说完了」，这个语义不变。
 *
 * 状态和识别文字共用一行——40dp 里塞不下两行，而识别出来的字
 * 比「正在听…」有用得多，所以有字就显示字，没字才显示状态。
 */
package com.jimeng.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme

@SuppressLint("ViewConstructor")
class VoiceBarUi(
    context: Context,
    private val theme: Theme,
) : LinearLayout(context) {

    /** 用户点了取消。 */
    var onCancelClicked: (() -> Unit)? = null

    /**
     * 露脸 / 收起变了。
     *
     * 键盘拿它切换候选栏模式：语音条一露脸，中间那块候选区就要让位。
     * 只在这一层上报，是因为「什么时候该显示」全由语音状态机决定，
     * 外面再记一份状态迟早会两边对不上。
     */
    var onVisibleChanged: ((Boolean) -> Unit)? = null

    private var shown = false

    /** 状态短语（正在听 / 正在识别 / 出错原因） */
    private var status = ""

    /** 已经识别出来的字 */
    private var body = ""

    private val wave = SoundWaveView(context).apply {
        // 候选栏里这块窄，柱子少一点、矮一点，不然会顶出上下边
        volumeCount = 9
        volumeBarHalfWidth = dp(1.2f)
        volumeBarMargin = dp(2.2f)
        minVolume = 4
        maxVolume = 35
        minVolumeBarHeight = dp(3f)
        maxVolumeBarHeight = dp(18f)
        maxIdleHeight = dp(8f)
        volumeBarColor = theme.candidateTextColor
        setIdleHeightGetter { x -> (x + 2).toFloat() }
    }

    private val message = TextView(context).apply {
        textSize = 13f
        setTextColor(theme.candidateTextColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
    }

    private val cancel = TextView(context).apply {
        text = "✕"
        textSize = 15f
        setTextColor(theme.candidateCommentColor)
        gravity = Gravity.CENTER
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                ColorDrawable(theme.keyPressHighlightColor)
            )
        }
        setOnClickListener { onCancelClicked?.invoke() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        setPadding(dp(6), 0, 0, 0)

        addView(wave, LayoutParams(dp(46), dp(24)))
        addView(
            message,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) }
        )
        addView(cancel, LayoutParams(dp(34), LayoutParams.MATCH_PARENT))
    }

    /** 开始录音：露脸，波形进待机，状态换成「正在听」。 */
    fun showListening() {
        status = STATUS_LISTENING
        body = ""
        message.setTextColor(theme.candidateTextColor)
        message.alpha = 0.7f
        wave.stopDance()
        wave.visibility = View.VISIBLE
        setVisible(true)
        refresh()
    }

    /** 只换状态短语（「正在加载语音模型…」「正在识别…」）。 */
    fun setStatus(text: String) {
        status = text
        refresh()
    }

    /** 实时识别结果，整段替换。 */
    fun setText(full: String) {
        body = full
        refresh()
    }

    fun setLevel(db: Float) {
        // dBFS(-80..-6) → 波形条的整数音量刻度(0..35)
        val norm = ((db + 80f) / 74f).coerceIn(0f, 1f)
        wave.handleVolume((norm * wave.maxVolume).toInt())
    }

    /** 出错 / 没权限 / 没模型：显示原因，波形停。 */
    fun showError(text: String) {
        status = text
        body = ""
        message.setTextColor(ERROR_COLOR)
        message.alpha = 1f
        wave.stopDance()
        wave.visibility = View.GONE
        setVisible(true)
        refresh()
    }

    /** 等权限：露脸但波形停着，别让人以为在偷听。 */
    fun showWaiting(text: String) {
        status = text
        body = ""
        message.setTextColor(theme.candidateTextColor)
        message.alpha = 0.7f
        wave.stopDance()
        wave.visibility = View.GONE
        setVisible(true)
        refresh()
    }

    fun hideOverlay() {
        wave.stopDance()
        status = ""
        body = ""
        message.text = ""
        setVisible(false)
    }

    /** 有识别结果就显示结果，没有才显示状态短语。 */
    private fun refresh() {
        val text = if (body.isNotBlank()) body else status
        if (message.text == text) return
        message.text = text
        message.alpha = if (body.isNotBlank()) 1f else 0.7f
    }

    private fun setVisible(value: Boolean) {
        if (shown == value) return
        shown = value
        visibility = if (value) VISIBLE else GONE
        // 先改自己的可见性再通知：回调里会去动候选栏的布局
        onVisibleChanged?.invoke(value)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v,
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val STATUS_LISTENING = "正在听…"
        /** 出错用的红：浅色底深色底都看得清，不跟皮肤走反而更稳。 */
        private val ERROR_COLOR = Color.parseColor("#E5484D")
    }
}
