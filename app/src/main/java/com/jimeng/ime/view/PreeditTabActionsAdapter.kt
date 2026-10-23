package com.jimeng.ime.view

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jimeng.ime.kb.theme.Theme

/**
 * 展开页左侧一列里的一项。
 *
 * @param id 点下去时通过 [PreeditTabActionsAdapter.onTriggerTabAction] 上报的编号，
 *           含义由使用方约定（当前音节 / 第几个谐音变体 / 单字开关）
 * @param isCheckable 能不能处于「选中」态（只有能选中的才画选中色块）
 * @param isChecked 当前是否选中
 */
data class CandidateTab(
    val id: Int,
    val text: String,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false
)

/**
 * 展开页左侧一列 tab 的适配器。照抄 fcitx5-android 的
 * input/candidates/expanded/CandidateTabActionsAdapter.kt。
 *
 * 原版左侧列放的是引擎给的 tab action（简繁转换之类），rime 没有这个概念，
 * 所以这里装的是用户要的：**当前音节 + 它的谐音变体**（可滚动那份），
 * 以及底部固定的「单字」（pinned 那份，indicatorStyle = true）。
 *
 * 列的行为和原版一致：竖着排、每格 36dp、可点、可滚，底部 pinned 那份不滚。
 */
abstract class PreeditTabActionsAdapter(
    val theme: Theme,
    val indicatorStyle: Boolean = false
) : RecyclerView.Adapter<PreeditTabActionsAdapter.TabViewHolder>() {

    private var tabs = listOf<CandidateTab>()

    @SuppressLint("NotifyDataSetChanged")
    fun updateTabs(newTabs: List<CandidateTab>) {
        if (tabs == newTabs) return
        tabs = newTabs
        notifyDataSetChanged()
    }

    /** 点了第 id 项 */
    abstract fun onTriggerTabAction(id: Int)

    override fun getItemCount() = tabs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val ui = PreeditTabView(parent.context, theme, indicatorStyle)
        // 照原版：宽 matchParent、高 wrapContent（由 36dp 的 label 撑起来）
        ui.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return TabViewHolder(ui)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabs.getOrNull(position) ?: return
        holder.update(tab)
    }

    override fun onViewRecycled(holder: TabViewHolder) {
        holder.clear()
    }

    inner class TabViewHolder(val ui: PreeditTabView) : RecyclerView.ViewHolder(ui) {

        fun update(tab: CandidateTab) {
            ui.update(tab)
            ui.setOnClickListener {
                onTriggerTabAction(tab.id)
            }
        }

        fun clear() {
            ui.setOnClickListener(null)
        }
    }
}