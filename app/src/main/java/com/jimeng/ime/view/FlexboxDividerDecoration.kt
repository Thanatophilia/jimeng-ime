package com.jimeng.ime.view

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import kotlin.math.roundToInt

/**
 * 照抄 fcitx5-android 的 FlexboxVerticalDecoration
 * （input/candidates/expanded/decoration/FlexboxVerticalDecoration.kt）。
 *
 * 它干两件事：
 *   1. getItemOffsets：每个格子在右边留出分隔线的宽度；
 *   2. onDraw：在格子右侧画一条竖线，上下各缩 8dp，不顶到栏的上下边。
 *
 * 注意每个格子右边都画，最后一个也不例外——那条正好是「最后一个候选」
 * 和「展开箭头」之间的分隔线，原版就是这么排的。
 */
class FlexboxDividerDecoration(private val drawable: Drawable) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        when (parent.layoutDirection) {
            View.LAYOUT_DIRECTION_LTR -> {
                outRect.right = drawable.intrinsicWidth
            }

            View.LAYOUT_DIRECTION_RTL -> {
                outRect.left = drawable.intrinsicWidth
            }

            else -> {
                outRect.set(0, 0, 0, 0)
            }
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val layoutManager = parent.layoutManager as? FlexboxLayoutManager ?: return
        // 竖线上下各缩 8dp，让线看着短一点（照原版 vInset）
        val vInset = (parent.resources.displayMetrics.density * 8f).roundToInt()
        for (i in 0 until layoutManager.childCount) {
            val view = parent.getChildAt(i) ?: continue
            val lp = view.layoutParams as? FlexboxLayoutManager.LayoutParams ?: continue
            val left: Int
            val right: Int
            when (parent.layoutDirection) {
                View.LAYOUT_DIRECTION_LTR -> {
                    left = view.right + lp.rightMargin
                    right = left + drawable.intrinsicWidth
                }

                View.LAYOUT_DIRECTION_RTL -> {
                    right = view.left + lp.leftMargin
                    left = right - drawable.intrinsicWidth
                }

                else -> {
                    left = view.left
                    right = left + drawable.intrinsicWidth
                }
            }
            val top = view.top - lp.topMargin
            val bottom = view.bottom + lp.bottomMargin
            drawable.setBounds(left, top + vInset, right, bottom - vInset)
            drawable.draw(c)
        }
    }
}