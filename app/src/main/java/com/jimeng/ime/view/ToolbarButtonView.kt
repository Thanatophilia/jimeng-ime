package com.jimeng.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.core.content.ContextCompat
import com.jimeng.ime.R
import com.jimeng.ime.kb.theme.Theme

/**
 * 候选区工具栏上的按钮：40dp 方块 + 居中 24dp 图标。
 *
 * 观感照 [ExpandButtonView]：按压高亮 keyPressHighlightColor，
 * 图标配色 altKeyTextColor、按压时 accentKeyBackgroundColor。
 * 图标走 res/drawable 里的 vector（主人给的 Material Symbols 图形）。
 */
class ToolbarButtonView(
    context: Context,
    private val theme: Theme,
    iconRes: Int,
    private val onClick: () -> Unit
) : View(context) {

    companion object {
        /** 按钮宽度，和展开箭头一样 40dp，一排排开 */
        const val WIDTH_DP = 40f
    }

    private val icon: Drawable = checkNotNull(ContextCompat.getDrawable(context, iconRes)).mutate()

    init {
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                ColorDrawable(theme.keyPressHighlightColor)
            )
        }
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        icon.setTint(if (isPressed) theme.accentKeyBackgroundColor else theme.altKeyTextColor)
        val size = resources.displayMetrics.density * 24f
        icon.setBounds(
            ((width - size) / 2f).toInt(),
            ((height - size) / 2f).toInt(),
            ((width + size) / 2f).toInt(),
            ((height + size) / 2f).toInt()
        )
        icon.draw(canvas)
    }
}