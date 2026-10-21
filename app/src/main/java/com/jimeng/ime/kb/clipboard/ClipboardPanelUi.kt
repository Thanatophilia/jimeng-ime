package com.jimeng.ime.kb.clipboard

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme
import splitties.dimensions.dp

/**
 * 剪贴板历史面板：圆角浮层，顶部「剪贴板 / 清空」，下面逐条列历史文本。
 *
 * 照 fcitx5 的 `input/clipboard/ClipboardUi` 的骨架（列表 + 清空），
 * 但原版是整块盖住键盘的页面，我们这里是挂在候选栏上方的浮层，
 * 所以做成小面板：最多露 4 行，多了在里面滚。
 *
 * 配色走气泡家族（popupBackgroundColor / popupTextColor），
 * 行按压高亮 keyPressHighlightColor；条目底色 clipboardEntryColor
 * （这个字段是从原版主题抄来的，一直没人用，正好给它派上用场）。
 */
class ClipboardPanelUi(
    context: Context,
    private val theme: Theme,
    items: List<String>,
    private val onPick: (String) -> Unit,
    private val onClear: () -> Unit,
    private val onDismissSelf: () -> Unit = {}
) : LinearLayout(context) {

    companion object {
        /** 面板宽度，比工具栏菜单宽一点，能多看几个字 */
        private const val WIDTH_DP = 240f

        /** 行高 */
        private const val ROW_HEIGHT_DP = 48f

        /** 最多露几行，多了滚动 */
        private const val MAX_ROWS = 4
    }

    /** 全屏透明拦截层：面板外面点一下就收起 */
    val scrim = View(context).apply {
        isClickable = true
        setOnClickListener { onDismissSelf() }
    }

    private fun dp(v: Float) = context.dp(v)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = context.dp(12).toFloat()
            setColor(theme.popupBackgroundColor)
        }
        // 面板要自己能点、能吃掉触摸，别漏到下面的键盘
        isClickable = true

        addView(
            buildHeader(context),
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40f).toInt())
        )

        val listBox = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        if (items.isEmpty()) {
            listBox.addView(
                TextView(context).apply {
                    text = "还没有复制过东西"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(theme.candidateCommentColor)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP).toInt())
            )
        } else {
            items.forEach { text ->
                listBox.addView(
                    buildRow(context, text),
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP).toInt())
                )
            }
        }

        // 行数少就按行数算高，多了封顶 MAX_ROWS 行、里面滚
        val rows = items.size.coerceIn(1, MAX_ROWS)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                listBox,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }
        addView(
            scroll,
            LayoutParams(dp(WIDTH_DP).toInt(), dp(ROW_HEIGHT_DP * rows).toInt())
        )
    }

    /** 顶部一行：「剪贴板」标题 + 右侧「清空」 */
    private fun buildHeader(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), 0, context.dp(8), 0)
        }
        row.addView(
            TextView(context).apply {
                text = "剪贴板"
                textSize = 13f
                setTextColor(theme.candidateCommentColor)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(context).apply {
                text = "清空"
                textSize = 13f
                setTextColor(theme.accentKeyBackgroundColor)
                gravity = Gravity.CENTER
                setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
                isClickable = true
                setOnClickListener { onClear() }
            }
        )
        return row
    }

    /** 一条历史：单行、超长省略，点一下上屏 */
    private fun buildRow(context: Context, text: String): View =
        TextView(context).apply {
            setText(text)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(theme.popupTextColor)
            setPadding(context.dp(14), 0, context.dp(14), 0)
            background = rowBackground()
            isClickable = true
            setOnClickListener { onPick(text) }
        }

    /**
     * 每行现做一份按压底色。
     * 一个 StateListDrawable 只该被一个 View 用（共用的话按压态会串行连坐）。
     */
    private fun rowBackground() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            ColorDrawable(theme.keyPressHighlightColor)
        )
        addState(
            intArrayOf(),
            ColorDrawable(theme.clipboardEntryColor)
        )
    }
}
