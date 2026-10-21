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

    /**
     * 图标旋转角（度）。菜单按钮用它把 ⌄ 扫成 ›/‹（照 fcitx5 的
     * ToolButton.iconRotation + iconAnimate().rotation()），其他按钮保持 0。
     */
    var iconRotation = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
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
        // 图标绕按钮中心转；菜单按钮的 ⌄ 靠它扫成 ›/‹
        canvas.save()
        canvas.rotate(iconRotation, width / 2f, height / 2f)
        icon.draw(canvas)
        canvas.restore()
    }
}