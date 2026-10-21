package com.jimeng.ime.kb.popup

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jimeng.ime.kb.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageResource

/**
 * 候选区工具栏「⋯」点开的菜单：竖排圆角面板，每行「图标 + 文字」。
 *
 * 配色走气泡家族（popupBackgroundColor / popupTextColor），
 * 按压高亮 keyPressHighlightColor；「设置」先收在这里。
 */
class ToolbarMenuUi(
    context: Context,
    private val theme: Theme,
    items: List<Item>,
    private val onItemClick: (Item) -> Unit,
    private val onDismissSelf: () -> Unit = {}
) : LinearLayout(context) {

    data class Item(val label: String, val iconRes: Int, val action: () -> Unit)

    /** 按压底色每行现做：同一个 StateListDrawable 不能被多个 View 共用 */
    private fun newRowBg() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            ColorDrawable(theme.keyPressHighlightColor)
        )
    }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = context.dp(12).toFloat()
            setColor(theme.popupBackgroundColor)
        }
        // 气泡总管的 root 会把整个 view 的触摸吃掉做手势分发，
        // 菜单要自己能点、能挡住下面的东西
        isClickable = true
        for (item in items) {
            addView(
                horizontalLayout {
                    gravity = Gravity.CENTER_VERTICAL
                    background = newRowBg()
                    setPadding(context.dp(14), 0, context.dp(14), 0)
                    minimumWidth = context.dp(150)
                    add(
                        ImageView(context).apply {
                            imageResource = item.iconRes
                            setColorFilter(theme.popupTextColor)
                        },
                        lParams(context.dp(20), context.dp(20))
                    )
                    add(
                        textView {
                            text = item.label
                            textSize = 16f
                            setTextColor(theme.popupTextColor)
                        },
                        lParams(wrapContent, wrapContent) { leftMargin = context.dp(10) }
                    )
                    setOnClickListener {
                        onItemClick(item)
                    }
                },
                lParams(LayoutParams.WRAP_CONTENT, context.dp(44))
            )
        }
    }

    /** 全屏透明拦截层：菜单外面点一下就收起（JimengIME 挂到气泡 root 上） */
    val scrim = View(context).apply {
        isClickable = true
        setOnClickListener { onDismissSelf() }
    }
}