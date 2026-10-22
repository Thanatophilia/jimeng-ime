package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.jimeng.ime.R
import com.jimeng.ime.kb.theme.Theme

/**
 * 候选栏上的「快捷粘贴」胶囊。
 *
 * 主人要的流程：
 *   复制的文字 → 呼出键盘，候选栏出现这一条 → 点一下直接上屏。
 *
 * 它只在**空闲**（没在打字）时露脸，顶替候选区的位置。
 * 右端的 ⌄ / 🎤 / 收键盘那一格、左端的 › 工具栏开关都不动，
 * 所以这个胶囊只是把中间那块候选区借过来用一下，不改变任何现有结构。
 *
 * 为什么不做成「候选格里的一条」：候选格是 RecyclerView 的内容，
 * 归引擎的候选表管（下标直接对应 nativeSelectCandidate），
 * 混一条剪贴板进去会让点选下标错位。独立一个 View 盖在候选区上最干净。
 */
class ClipboardQuickPasteView(context: Context, private val theme: Theme) : FrameLayout(context) {

    companion object {
        /** 胶囊内左右内边距 */
        private const val PADDING_H_DP = 10f

        /** 图标尺寸，比工具栏的 24dp 小一档，跟候选文字同一视觉量级 */
        private const val ICON_DP = 16f

        /** 图标和文字之间的间距 */
        private const val ICON_GAP_DP = 6f

        /** 预览文字字号：和候选的 20sp 比小一点，让它一眼看出来是「一条内容」不是候选 */
        private const val TEXT_SIZE_SP = 14f

        /** 胶囊左右各留一点空，别贴死候选栏的两端 */
        private const val OUTER_MARGIN_DP = 6f
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_clipboard_24)?.mutate()

    private val label = android.widget.TextView(context).apply {
        // 用普通 TextView 而不是 AutoScaleTextView：
        // 剪贴板内容常常很长，缩字会缩成一团糊，省略号才是「这是一条内容」的正确观感。
        // （AutoScaleTextView 是为「候选词的 6 个格子必须都装下」设计的，用途不同。）
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(theme.candidateTextColor)
        gravity = Gravity.CENTER_VERTICAL
    }

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(PADDING_H_DP).toInt(), 0, dp(PADDING_H_DP).toInt(), 0)
        // 图标用 ImageView：比「空 View + background」干净，
        // 尺寸由 LayoutParams 定死，不依赖 drawable 的 intrinsic size
        addView(
            android.widget.ImageView(context).apply {
                setImageDrawable(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(theme.altKeyTextColor)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(ICON_DP).toInt(), dp(ICON_DP).toInt()).apply {
                rightMargin = dp(ICON_GAP_DP).toInt()
            }
        )
        addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
    }

    init {
        // 胶囊底：用剪贴板条目的底色（跟剪贴板页里的卡片同一套观感）
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18f)
            setColor(theme.clipboardEntryColor)
            setStroke(maxOf(1, dp(1f).toInt()), theme.dividerColor)
        }
        // 胶囊本身可点：整块区域都吃掉触摸，不会漏到下层应用
        isClickable = true
        isFocusable = false
        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                leftMargin = dp(OUTER_MARGIN_DP).toInt()
                rightMargin = dp(OUTER_MARGIN_DP).toInt()
            }
        )
    }

    /**
     * 塞一条待粘贴的内容。
     *
     * 换行会被压成空格——候选栏只有 40dp 高，多行显示不了，
     * 与其显示成一堆换行符，不如让主人一眼看到内容开头。
     */
    @SuppressLint("SetTextI18n")
    fun bind(text: String) {
        label.text = text.replace('\n', ' ').replace('\r', ' ')
    }
}
