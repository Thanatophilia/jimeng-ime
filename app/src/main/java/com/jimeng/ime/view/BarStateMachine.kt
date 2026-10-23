package com.jimeng.ime.view

/**
 * 候选栏状态机。**照抄 fcitx5-android**
 * `app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarStateMachine.kt`。
 *
 * ── 为什么要有它 ────────────────────────────────────────────
 * 「候选栏现在该显示候选还是显示工具栏」这件事，在原版里**不是**由某个
 * if 判断临时算出来的，而是由一个三态机（原版还有 Title 态，对应换页，
 * 我们由 CandidateBar 的 pageMode 单独管，所以这里只留两态）维护的。
 *
 * 状态只由两个**互相独立**的布尔键驱动：
 *   · PreeditEmpty   —— 引擎当前有没有输入码
 *   · CandidateEmpty —— 引擎当前有没有候选（预测候选也算候选）
 *
 * 原版迁移表（一字不改）：
 *
 *   PreeditUpdated({
 *       from(Candidate) transitTo Idle      on  (PreeditEmpty to true)
 *       from(Idle)      transitTo Candidate onF { PreeditEmpty == false && CandidateEmpty == false }
 *   })
 *   CandidatesUpdated({
 *       from(Idle)      transitTo Candidate on  (CandidateEmpty to false)
 *       from(Candidate) transitTo Idle      onF { PreeditEmpty == true && CandidateEmpty == true }
 *   })
 *
 * ── 这个表为什么正好修掉用户的 bug ───────────────────────────
 * 预测候选的来源是 librime-predict 建的 `prediction` 段（predictor.cc /
 * predict_engine.cc:47-56 `CreatePredictSegment`），它挂在 composition 尾部，
 * 而 `Context::GetPreedit()` 对它取到的是空串（SimpleCandidate 的 preedit 为空，
 * composition.cc:66-67 走 raw input 分支，start==end 所以补空）。
 * 于是「预测词挂着」这个世界状态是：**PreeditEmpty = true、CandidateEmpty = false**。
 *
 *    · 这时靠 CandidatesUpdated 从 Idle 进 Candidate —— 预测词正常显示，
 *      和用户在 fcitx5 里看到的一样。
 *    · 数字键盘一按键 → reset 把预测清掉 → CandidateEmpty 变 true，
 *      配合本来就已经 true 的 PreeditEmpty，Candidate 转 Idle ——
 *      **候选栏回工具栏**，正是用户要的那一下。
 *
 * 而之前我们用的是一个 `toolbarMode: Boolean` 加上各处
 * `if (preedit.isEmpty() && candidates.isEmpty())` 的经验判断，
 * 两个维度被压成了一个布尔，预测这一支（preedit 空但候选非空）
 * 只能靠特例兜，兜漏了就是用户看到的老样子。
 *
 * @param onNewState 状态真的变了才回调（原版也是 `if (newState == currentState) return`）
 */
class BarStateMachine(private val onNewState: (State) -> Unit) {

    /** 原版 `KawaiiBarStateMachine.State` 去掉 Title（换页在 CandidateBar 里单独管） */
    enum class State { Idle, Candidate }

    var state: State = State.Idle
        private set

    private var preeditEmpty = true
    private var candidateEmpty = true

    /**
     * 照原版 `PreeditEmptyStateComponent.updatePreeditEmptyState()`
     * → `barStateMachine.push(PreeditUpdated, PreeditEmpty to empty)`。
     */
    fun onPreeditEmptyStateUpdate(empty: Boolean) {
        preeditEmpty = empty
        // 原版 push 时值没变也会跑一遍迁移表；表里条件不成立就不会转态，
        // 结果一样。这里保持同样写法，不去提前 return，免得以后加新迁移时踩空。
        val next = when (state) {
            State.Candidate -> if (preeditEmpty) State.Idle else State.Candidate
            State.Idle -> if (!preeditEmpty && !candidateEmpty) State.Candidate else State.Idle
        }
        transit(next)
    }

    /**
     * 照原版 `onCandidateUpdate(data)` → `push(CandidatesUpdated, CandidateEmpty to data.candidates.isEmpty())`。
     */
    fun onCandidateUpdate(candidates: List<String>) {
        candidateEmpty = candidates.isEmpty()
        val next = when (state) {
            State.Idle -> if (!candidateEmpty) State.Candidate else State.Idle
            State.Candidate -> if (preeditEmpty && candidateEmpty) State.Idle else State.Candidate
        }
        transit(next)
    }

    private fun transit(next: State) {
        if (next == state) return
        state = next
        onNewState(next)
    }
}
