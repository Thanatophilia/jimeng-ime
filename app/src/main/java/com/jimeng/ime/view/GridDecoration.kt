package com.jimeng.ime.view

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 照抄 fcitx5-android 的
 * input/candidates/expanded/decoration/GridDecoration.kt（原样移植）。
 *
 * 候选网格的分隔线：
 *   1. getItemOffsets：**除了每行最后一个**，右边都留出分隔线宽度；
 *   2. onDraw：在格子右侧画一条竖线，上下各缩 8dp（原版 vInset = 8dp）。
 *
 * 注意和 Flexbox 版（横排候选栏用）的区别：
 * 横排栏最后一项右边也画，因为那条是「候选」和「展开箭头」之间的线；
 * 网格这里每行最后一项**不画**，右边交给外层（屏幕边/滚动条）。
 */
class GridDecoration(private val drawable: Drawable) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val lp = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
        val layoutManager = parent.layoutManager as? GridLayoutManager ?: return
        // 每行除了最后一项，右边都留出线宽
        if (lp.spanIndex + lp.spanSize != layoutManager.spanCount) {
            when (parent.layoutDirection) {
                View.LAYOUT_DIRECTION_LTR -> outRect.right = drawable.intrinsicWidth
                View.LAYOUT_DIRECTION_RTL -> outRect.left = drawable.intrinsicWidth
                else -> outRect.set(0, 0, 0, 0)
            }
        } else {
            outRect.set(0, 0, 0, 0)
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val layoutManager = parent.layoutManager as? GridLayoutManager ?: return
        val adapter = parent.adapter ?: return
        // 竖线上下各缩 8dp，让线看着短一点（照原版 vInset）
        val vInset = (parent.resources.displayMetrics.density * 8f).toInt()
        for (i in 0 until layoutManager.childCount) {
            val view = parent.getChildAt(i) ?: continue
            val lp = view.layoutParams as? GridLayoutManager.LayoutParams ?: continue
            // 最后一项后面不画（照原版 `if (i == itemCount - 1) break`）
            if (i == adapter.itemCount - 1) break
            // 每行最后一项右边不画
            if (lp.spanIndex + lp.spanSize == layoutManager.spanCount) continue
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