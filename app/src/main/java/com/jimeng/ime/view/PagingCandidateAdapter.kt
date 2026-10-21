package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jimeng.ime.kb.theme.Theme

/**
 * 展开页候选网格的适配器。照抄 fcitx5-android 的
 * input/candidates/expanded/GridPagingCandidateViewAdapter.kt
 * + PagingCandidateViewAdapter.kt（原样移植，去掉 Paging 那一层）。
 *
 * 和原版的差别只有一处：原版用 `androidx.paging` 分页加载，
 * rime 一次就把候选全给了（几十条），不需要分页，
 * 所以这里直接持有整张表。
 *
 * 两件事和原版一模一样：
 *   1. 每格 `GridLayoutManager.LayoutParams(MATCH_PARENT, 40dp)`；
 *   2. [measureWidth] 给 [SpanHelper] 用——返回「文字宽度是字号的几倍」，
 *      用倍数量而不是像素，换字号/换密度都不会算错。
 */
open class PagingCandidateAdapter(val theme: Theme) :
    RecyclerView.Adapter<CandidateViewHolder>() {

    companion object {
        /** 只是拿来算比例用的字号，和候选实际字号无关（照原版注释） */
        private const val TEXT_SIZE = 20f

        /** 每格高度 40dp（照原版 `dp(40)`） */
        const val CELL_HEIGHT_DP = 40f
    }

    /** 当前这一屏要显示的候选（「单字」筛选后可能只是整张表的一部分） */
    private var candidates: List<String> = emptyList()

    /** 点了某个候选。参数是它在**整张候选表**里的下标 */
    var onClick: ((Int) -> Unit)? = null

    /**
     * 每个格子在**整张候选表**里的原始下标。
     * 开了「单字」筛选后，点第 i 格必须选 [globalIndexes][i]，
     * 否则选出来的是另一个词。
     */
    private var globalIndexes: IntArray = IntArray(0)

    /**
     * 量过的文字宽度缓存。key 是候选文字，value 是「宽度 ÷ 字号」的倍数。
     * 照原版：LruCache(200)，用一份共用的 Paint 量。
     */
    private val measuredWidths = object : LruCache<String, Float>(200) {
        private val cachedPaint = Paint().apply { textSize = TEXT_SIZE }
        private val cachedRect = Rect()
        override fun create(key: String): Float {
            cachedPaint.getTextBounds(key, 0, key.length, cachedRect)
            return cachedRect.width() / TEXT_SIZE
        }
    }

    /**
     * 第 [position] 项的宽度倍数，[SpanHelper] 靠它算这一项占几格。
     * 照原版 `measureWidth`：返回的是倍数，不是像素。
     */
    fun measureWidth(position: Int): Float {
        val word = candidates.getOrNull(position) ?: return 0f
        return measuredWidths[word] ?: 0f
    }

    /**
     * 换一批候选。
     *
     * @param list 每项是 (候选文字, 它在整张候选表里的下标)
     */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Pair<String, Int>>) {
        candidates = list.map { it.first }
        globalIndexes = IntArray(list.size) { list[it].second }
        notifyDataSetChanged()
    }

    override fun getItemCount() = candidates.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemView(parent.context, theme)
        val density = parent.resources.displayMetrics.density
        ui.layoutParams = GridLayoutManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (CELL_HEIGHT_DP * density).toInt()
        )
        val holder = CandidateViewHolder(ui)
        // 照原版 bindCandidateUiViewHolder：点一下就选这个候选
        ui.setOnClickListener { onClick?.invoke(holder.idx) }
        return holder
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val word = candidates.getOrNull(position) ?: ""
        // idx 用的是**整张候选表**里的下标，不是网格里的位置
        holder.update(globalIndexes.getOrElse(position) { position }, word)
    }
}