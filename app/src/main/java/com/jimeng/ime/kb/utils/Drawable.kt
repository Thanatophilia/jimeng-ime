/*
 * 从 fcitx5-android 的 utils/Drawable.kt + utils/ScrollbarDrawable.kt 照抄来的几个 drawable。
 *
 * 为什么单独抄这几个：候选栏/展开页要「和原版一模一样」，
 * 差别最后常常就落在这 1dp 的线、按下时那层淡色块上。
 * 这些在原版是实现细节，自己凭感觉画永远差一点点。
 */
package com.jimeng.ime.kb.utils

import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Build
import android.view.Gravity
import android.view.View
import androidx.annotation.ColorInt

/**
 * 按下高亮：只有 pressed 状态显示一层颜色，其他状态透明。
 *
 * 照抄 fcitx5 的 `pressHighlightDrawable`。候选格、tab 格都用它，
 * 按下去整格浮一层淡淡的色，松开就没了。
 */
fun pressHighlightDrawable(@ColorInt color: Int): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(color))
}

/**
 * 只有一条边的边框。照抄 fcitx5 的 `singleSideBorderDrawable`。
 *
 * 用 LayerDrawable + 一层实色 GradientDrawable，把这一层「挤」到指定边上：
 *   - Gravity.RIGHT / LEFT（水平方向）→ 限制**宽度** = [width]，上下各缩 [inset]
 *   - Gravity.BOTTOM / TOP（垂直方向）→ 限制**高度** = [width]，左右各缩 [inset]
 *
 * 展开页左侧拼音列右边那条竖线就是它：宽 1dp、上下各缩 7dp（竖屏），
 * 所以线条不会顶到键盘上沿和下沿，看着才像原版。
 */
fun singleSideBorderDrawable(
    width: Int,
    @ColorInt stroke: Int,
    gravity: Int,
    inset: Int = 0
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply { setColor(stroke) }
    )
).apply {
    setLayerGravity(0, gravity)
    if (Gravity.isVertical(gravity)) {
        // 上下边框：控制高度，左右缩进
        setLayerHeight(0, width)
        setLayerInset(0, inset, 0, inset, 0)
    } else {
        // 左右边框：控制宽度，上下缩进
        setLayerWidth(0, width)
        setLayerInset(0, 0, inset, 0, inset)
    }
}

/**
 * 把滚动条的 thumb 换成指定颜色。照抄 fcitx5 的 `setVerticalScrollbarThumbColor`。
 *
 * 原版还留了一条 Android 10 以下的反射后路（mScrollCache → scrollBar → mVerticalThumb）。
 * 这里只保留 Q+ 的公开 API 那条：反射碰厂商 ROM 容易炸，不值得。
 * minSdk 是 26，所以低版本时什么都不做（滚动条用系统默认外观）。
 */
@SuppressLint("NewApi")
fun View.setVerticalScrollbarThumbColor(@ColorInt color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        verticalScrollbarThumbDrawable = ShapeDrawable(RectShape()).apply {
            paint.color = color
        }
    }
}