package com.jimeng.ime.view

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.jimeng.ime.kb.theme.Theme

/**
 * 一个候选格。照抄 fcitx5-android 的
 * input/candidates/CandidateItemUi.kt + CandidateViewHolder.kt。
 *
 * 原版结构：
 *   CustomGestureView（root，background = pressHighlightDrawable，
 *                      每格最小宽 40dp、左右内边距 10dp）
 *     └─ AutoScaleTextView（wrapContent × matchParent，gravity = center，20sp，Proportional）
 *
 * 这里 root 用普通 FrameLayout 顶上（CustomGestureView 是 fcitx5 自己的手势基类，
 * 带着协程和 lifecycle，候选格用不上），按下高亮用同一个 StateListDrawable，
 * 效果和原版一样：按下整格变成 theme.keyPressHighlightColor。
 */
class CandidateItemView(context: Context, theme: Theme) : FrameLayout(context) {

    /** 候选文字：20sp、单行、居中，装不下时等比缩（AutoScaleTextView.Mode.Proportional） */
    val text = AutoScaleTextView(context).apply {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = 20f
        isSingleLine = true
        gravity = Gravity.CENTER
        setTextColor(theme.candidateTextColor)
    }

    init {
        // 照原版：background = pressHighlightDrawable(theme.keyPressHighlightColor)
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                ColorDrawable(theme.keyPressHighlightColor)
            )
        }
        addView(
            text,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    fun updateCandidate(word: String) {
        if (text.text.toString() != word) text.text = word
    }

    fun clear() {
        text.text = ""
    }
}

/**
 * 照抄 fcitx5-android 的 CandidateViewHolder。
 * 自己记住「我是第几个候选」，点选时不用去问 RecyclerView 要位置。
 */
class CandidateViewHolder(val ui: CandidateItemView) : RecyclerView.ViewHolder(ui) {

    var idx = -1
        private set

    private var word: String = ""

    fun update(newIndex: Int, newWord: String) {
        idx = newIndex
        if (word != newWord) {
            word = newWord
            ui.updateCandidate(newWord)
        }
    }

    fun clear() {
        update(-1, "")
    }
}