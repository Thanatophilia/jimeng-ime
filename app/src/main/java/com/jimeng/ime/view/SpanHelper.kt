package com.jimeng.ime.view

import androidx.recyclerview.widget.GridLayoutManager
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 照抄 fcitx5-android 的
 * input/candidates/expanded/SpanHelper.kt（原样移植）。
 *
 * 它解决一件事：候选**不按等宽格子排**，而是按文字宽度比例占格。
 *
 * 宽度换算成「几个字宽」的倍数，再 `ceil(倍数 / 1.5)` 得格数：
 *   一两个字 → 1~2 格，三到五个字 → 2~4 格，长候选最多占满整行。
 *
 * 一行剩下的位置如果**塞不下下一个候选**，就把当前这个候选**拉伸填满行尾**
 * （原版注释叫 stretch this item to fill the line），
 * 这样每行右边都不会留一块莫名其妙的空白。
 *
 * 为什么用倍数而不是像素：换字号、换密度都不会重新算错。
 */
class SpanHelper(
    private val adapter: PagingCandidateAdapter,
    private val manager: GridLayoutManager
) : GridLayoutManager.SpanSizeLookup() {

    data class ItemLayout(val spanIndex: Int, val spanSize: Int, val groupIndex: Int)

    private val layout = ArrayList<ItemLayout>()

    private fun getMinSpanSize(position: Int) = min(
        // 大约三个字符或一个汉字占一格（照原版注释）
        // 至少给一格，防止 measureWidth 因为字体问题返回 0
        max(1, ceil(adapter.measureWidth(position) / 1.5).toInt()), manager.spanCount
    )

    /** 清掉已经算好的布局 */
    private fun invalidate() {
        layout.clear()
    }

    override fun invalidateSpanIndexCache() {
        invalidate()
        super.invalidateSpanIndexCache()
    }

    /** 保证 [position] 这一项的布局算过了，然后返回它 */
    private fun layoutItem(position: Int): ItemLayout {
        // 算过就直接给
        if (layout.size > position) return layout[position]
        val spanCount = manager.spanCount
        var span = 0
        var group = 0
        // 从最后一个算过的位置接着往后排
        layout.lastOrNull()?.also { last ->
            val lastSpan = last.spanIndex + last.spanSize
            if (lastSpan == spanCount) {
                // 上一项正好在行尾：从下一行开头算
                group = last.groupIndex + 1
            } else {
                // 从上一项结束的那一格接着算
                span = lastSpan
                group = last.groupIndex
            }
        }
        val itemCount = adapter.itemCount
        // 一次排一行
        val batchEnd = min(spanCount * (position / spanCount + 1), adapter.itemCount)
        for (i in layout.size until batchEnd) {
            var size = getMinSpanSize(i)
            val nextSize = if (i + 1 < itemCount) getMinSpanSize(i + 1) else null
            // 本行还有空位，但放不下下一项：把这一项拉伸到行尾
            if (nextSize != null && span + size + nextSize > spanCount) {
                size = spanCount - span
            }
            layout.add(ItemLayout(span, size, group))
            span += size
            if (span == spanCount) {
                span = 0
                group++
            }
        }
        return layout[position]
    }

    override fun getSpanIndex(position: Int, spanCount: Int): Int {
        return layoutItem(position).spanIndex
    }

    override fun getSpanGroupIndex(adapterPosition: Int, spanCount: Int): Int {
        return layoutItem(adapterPosition).groupIndex
    }

    override fun getSpanSize(position: Int): Int {
        return layoutItem(position).spanSize
    }
}