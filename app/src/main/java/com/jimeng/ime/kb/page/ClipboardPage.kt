package com.jimeng.ime.kb.page

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
 * 剪贴板页：整块铺满键盘区的历史列表。
 *
 * 照 fcitx5-android 的 `input/clipboard/ClipboardWindow`：
 * 原版是「两块瀑布流 + 长按菜单 + 侧滑删除 + 撤销 Snackbar」，
 * 这里先做最实用的一层：**一条一行、点一下上屏、右上角清空**。
 *
 * 跟上一版的区别（主人说眼花缭乱的那个）：不再是贴在候选栏上方的小浮层，
 * 而是跟键盘一样大的整页，候选栏变成「← 剪贴板」，一眼就知道在哪、
 * 点返回就回键盘。
 */
class ClipboardPage(
    context: Context,
    theme: Theme,
    private val items: List<String>,
    private val onPick: (String) -> Unit,
    private val onClear: () -> Unit
) : InputPage(context, theme) {

    companion object {
        /** 一行的高度 */
        private const val ROW_HEIGHT_DP = 48
        private const val HEADER_HEIGHT_DP = 36
        private const val EMPTY_HEIGHT_DP = 120
    }

    override val title: String = "剪贴板"

    override val root: View = LinearLayout(context).also { column ->
        column.orientation = LinearLayout.VERTICAL
        column.setBackgroundColor(theme.barColor)

        // 顶部一行：条数 + 清空
        column.addView(
            buildHeader(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(HEADER_HEIGHT_DP)
            )
        )

        val listBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (items.isEmpty()) {
            listBox.addView(
                TextView(context).apply {
                    text = "还没有复制过东西\n复制一段文字，这里就会记住它"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(theme.candidateCommentColor)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dp(EMPTY_HEIGHT_DP)
                )
            )
        } else {
            items.forEach { text ->
                listBox.addView(
                    buildRow(text),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        context.dp(ROW_HEIGHT_DP)
                    )
                )
            }
        }

        column.addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(
                    listBox,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(16), 0, context.dp(8), 0)
        addView(
            TextView(context).apply {
                text =
                    if (items.isEmpty()) "还没有内容"
                    else "${items.size} 条记录 · 点一条就上屏"
                textSize = 12f
                setTextColor(theme.candidateCommentColor)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (items.isNotEmpty()) {
            addView(
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
        }
    }

    /** 一条历史：单行、超长省略，点一下上屏 */
    private fun buildRow(text: String): View = TextView(context).apply {
        setText(text)
        textSize = 15f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.keyTextColor)
        setPadding(context.dp(16), 0, context.dp(16), 0)
        background = rowBackground()
        isClickable = true
        setOnClickListener { onPick(text) }
    }

    /** 每行现做一份按压底色：一个 StateListDrawable 不该被多个 View 共用 */
    private fun rowBackground() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply {
                cornerRadius = context.dp(10).toFloat()
                setColor(theme.keyPressHighlightColor)
            }
        )
        addState(intArrayOf(), ColorDrawable(0))
    }
}
