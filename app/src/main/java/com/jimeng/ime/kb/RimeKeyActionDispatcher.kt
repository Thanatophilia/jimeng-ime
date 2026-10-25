/*
 * librime 引擎适配层。
 *
 * fcitx5-android 的键盘层通过 KeyActionListener 把 KeyAction 交给
 * CommonKeyActionListener，再由它转给 fcitx5 的 C++ 引擎。
 *
 * 本工程引擎是 librime，所以这里实现一个等价的分发器：
 *   - FcitxKeyAction / SymAction  -> nativeProcessKey(session, keysym, mask)
 *   - 退格 / 回车 / 空格 / 光标移动 -> 同一条 nativeProcessKey 路径，
 *     引擎不吃时退回 InputConnection（删字、换行、插入空格）
 *   - CapsAction / LayoutSwitchAction / PickerSwitchAction -> 纯 UI 状态
 *   - CommitAction -> 直接上屏
 *
 * 与原版最大的语义差异：
 *   原版把 keysym 交给 fcitx5 引擎，引擎知道完整键盘布局；
 *   librime 只认「可打印字符的 keysym 就是字符码 + 修饰键 mask」，
 *   所以这里把 KeySym 直接当 keysym 传，正好对上。
 */
package com.jimeng.ime.kb

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.jimeng.ime.kb.core.KeyState
import com.jimeng.ime.kb.core.KeyStates
import com.jimeng.ime.kb.core.KeySym
import com.jimeng.ime.kb.keyboard.KeyAction
import com.jimeng.ime.kb.keyboard.KeyActionListener
import com.jimeng.ime.kb.picker.PickerWindow

/**
 * 键盘动作 -> 引擎 / 输入框。
 *
 * @param sessionProvider 提供当前 rime session
 * @param processKey 把 (keysym, mask) 喂给引擎，返回是否吃掉
 * @param commit 直接把文本送到输入框
 * @param inputConnection 供退格、回车这类需要真实键事件的操作
 * @param hasComposingPreedit 引擎当前 preedit 是否非空（有人正在打编码）。
 *   数字键盘要不要把数字喂给引擎当选择键，就靠它判断——和 fcitx5 的
 *   `CommonKeyActionListener.commitAndReset()` 里那句
 *   `clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()`
 *   是同一个判据，原版注释写得很清楚：「select 1st candidate, except
 *   prediction candidates」。preedit 空时挂着的候选只是预测，不是可选的编码候选。
 */
class RimeKeyActionDispatcher(
    private val sessionProvider: () -> Long,
    private val processKey: (session: Long, keySym: Int, mask: Int) -> Boolean,
    private val commit: (String) -> Unit,
    private val inputConnection: () -> InputConnection?,
    private val onLayoutSwitch: (String) -> Unit = {},
    private val onPickerSwitch: (PickerWindow.Key?) -> Unit = {},
    private val onShowImePicker: () -> Unit = {},
    /** 小地球短按：切引擎里的输入语言 */
    private val onSwitchLanguage: () -> Unit = {},
    private val onSpaceLongPress: () -> Unit = {},
    /** 键盘上发生了任何一次操作（按键 / 候选 / 切页……）：用来作废剪贴板快捷粘贴 */
    private val onAnyKey: (() -> Unit)? = null,
    /** 键盘当前是否有非空的 preedit（正在打编码）。判断依据见类注释 */
    private val hasComposingPreedit: () -> Boolean = { false },

    /**
     * 当前键盘是不是数字键盘（`?123` 那套）。
     *
     * 需求来源：fcitx5 里数字键盘按任意键，上一轮的预测都会消失、候选栏回工具栏。
     * 它靠的是「按键进引擎 → 引擎的 context 变了 → 候选/预测跟着重算」这条链，
     * 我们这边引擎不一定吃数字键，所以得显式收一次（见 [onKeyAction] 开头）。
     */
    private val isNumberKeyboard: () -> Boolean = { false },

    /**
     * 引擎当前有没有候选（预测候选也算）。
     * 和 [hasComposingPreedit] 一起区分「只剩预测」这一支：
     *   preedit 空 + 有候选  →  只是预测挂在候选栏上
     *   preedit 非空 + 有候选 →  真的在打编码，数字键该选候选
     */
    private val hasCandidates: () -> Boolean = { false },

    /**
     * 字面提交前的收尾动作。
     *
     * 照 fcitx5 的 `CommonKeyActionListener.kt`：
     *   is CommitAction -> service.postFcitxJob {
     *       commitAndReset()               // ← 先 reset
     *       service.lifecycleScope.launch { service.commitText(action.text) }
     *   }
     * 而 commitAndReset() 的最后一句就是 `reset()`。
     *
     * 效果就是用户看到的那样：数字键盘按任意键，上一轮的预测候选立刻消失、
     * 候选栏回到工具栏，然后数字才字面落进输入框。
     */
    private val onLiteralCommit: () -> Unit = {},

    /**
     * 翻译栏是否开着。开着时退格 / 空格作用在栏里，不碰应用输入框。
     */
    private val onTranslateBarActive: () -> Boolean = { false },

    /**
     * 栏里有字就删掉栏里最后一个字，返回 true；栏里空的返回 false。
     *
     * 返回 false 的意义：空栏时退格要能往下走，去删输入框里已有的文字
     * ——用户刚打开栏、想改上一句刚打的字，这时候拦着不让删很别扭。
     */
    private val onTranslateBarBackspaceIfNotEmpty: () -> Boolean = { false },
) : KeyActionListener {

    override fun onKeyAction(action: KeyAction, source: KeyActionListener.Source) {
        val session = sessionProvider()

        // 键盘上**任何一次操作**到这里就算数：用户的剪贴板快捷粘贴从这一刻退休。
        // 放在最前面，保证选候选、切页、长按这些早退分支也一并算进去。
        onAnyKey?.invoke()

        // ---- 数字键盘：按任何一个**输入键**都先把「只剩预测」的那一屏收掉 ----
        //
        // 这是用户的需求：「数字键盘按下任意键，那之前的候选栏预测就没了，
        // 回到工具栏」。
        //
        // 判据用 fcitx5 那两个独立维度，缺一不可：
        //   · 当前是数字键盘
        //   · preedit 为空（没在打编码 → 候选只是预测）
        //   · 但有候选（预测确实挂着）
        // 三条同时成立才收。这样「正在打拼音时按数字选候选」完全不受影响。
        //
        // **导航键不算**：`ABC`（回打字键盘）和 `!?#`（开符号面板）是切布局，
        // 照 fcitx5 的 KeyboardWindow.switchLayout —— 切布局只换键位、不碰引擎，
        // 预测该原样挂着。要是把它们也算进来，按 `ABC` 回拼音页时预测就平白没了。
        //
        // 为什么要放在进入各分支之前：空格、退格、回车不经过下面的 kpToCharSym
        // 那条字面提交路（空格走 handleSymFallback，退格/回车走引擎），
        // 只在字面提交前收一次的话，这三个键就漏了。
        var predictionCleared = false
        val isNavKey = action is KeyAction.LayoutSwitchAction ||
            action is KeyAction.PickerSwitchAction
        if (!isNavKey && isNumberKeyboard() && !hasComposingPreedit() && hasCandidates()) {
            onLiteralCommit()
            predictionCleared = true
        }

        // 九宫格（小键盘）单独走一条路，理由见文件末尾 kpToCharSym()。
        if (action is KeyAction.SymAction) {
            val ch = kpToCharSym(action.sym.sym)
            if (ch != 0) {
                // 数字键要不要喂给引擎，照 fcitx5 的判据来。
                //
                // fcitx5 的 CommonKeyActionListener.commitAndReset() 原话：
                //   // Chinese: select 1st candidate, except prediction candidates
                //   if (clientPreeditCached.isNotEmpty() ||
                //       inputPanelCached.preedit.isNotEmpty()) {
                //       // preedit not empty, maybe there are candidates to select ...
                //       select(0)
                //   }
                //
                // 也就是说：**只有当 preedit 非空（确实在打编码）时，数字键才
                // 承担选候选的职责**；preedit 为空时，候选栏里挂的只是预测词
                // （predict_translator 建的、tag 为 "prediction" 的段），
                // 它不该被数字键选中，数字键该老老实实上屏数字。
                //
                // 这正是用户遇到的 bug：预测字挂在候选栏时按 1-6，被当成选择键。
                if (ch in '0'.code..'9'.code && session != 0L && hasComposingPreedit()) {
                    if (processKey(session, ch, 0)) return
                }
                // 字面提交前先 reset —— 照 fcitx5 的 CommitAction 分支：
                //   is CommitAction -> { commitAndReset(); commitText(action.text) }
                // 效果：数字键盘一按键，上一轮的预测候选立刻消失、候选栏回到工具栏，
                // 然后数字才落进输入框。
                //
                // 上面那段已经收过一次的话就跳过，别重复送 Escape（虽然无害，
                // 但多打一次 JNI 没必要）。
                if (!predictionCleared) onLiteralCommit()
                // 其余情况一律字面输出：这是数字键盘，1.5 就该是 1.5，
                // 不能过一遍中文标点映射变成 1。5
                commit(ch.toChar().toString())
                return
            }
        }

        when (action) {
            // ---- 交给引擎，引擎不吃就直出 ----
            is KeyAction.FcitxKeyAction -> {
                val sym = action.act.codePointAt(0)
                // 引擎在输入中：吃掉这个键
                if (session != 0L && processKey(session, sym, action.states.toMask())) return
                // 引擎不管（没有编码在输入）：把字符直接送进输入框
                commit(action.act)
            }

            is KeyAction.SymAction -> {
                val sym = action.sym.sym
                if (session != 0L && processKey(session, sym, action.states.toMask())) return
                handleSymFallback(sym, action.states)
            }

            // ---- 编辑操作 ----
            is KeyAction.CommitAction -> commit(action.text)

            is KeyAction.CapsAction -> Unit // Caps 状态由 TextKeyboard 自己维护

            is KeyAction.LayoutSwitchAction -> onLayoutSwitch(action.act)

            is KeyAction.PickerSwitchAction -> onPickerSwitch(action.key)

            is KeyAction.QuickPhraseAction,
            is KeyAction.UnicodeAction -> Unit // 暂未接入，留空不崩

            is KeyAction.LangSwitchAction -> onSwitchLanguage()

            is KeyAction.ShowInputMethodPickerAction -> onShowImePicker()

            is KeyAction.SpaceLongPressAction -> onSpaceLongPress()

            // ---- 选区类，由 InputConnection 处理 ----
            is KeyAction.MoveSelectionAction -> handleMoveSelection(action)

            is KeyAction.DeleteSelectionAction -> handleDeleteSelection(action)
        }
    }

    /** 引擎不吃这个功能键时，退回 Android 原生行为 */
    private fun handleSymFallback(sym: Int, states: KeyStates) {
        // 注意：**不在这里拦翻译栏。**
        //
        // 这一层是「引擎把键吐回来、没人处理」的兜底。栏开着时如果在这儿
        // 把退格/空格吃掉，代价是：
        //   · 空格 → 再也选不了首候选（打拼音时空格上屏是最常用的操作）
        //   · 退格 → 打错拼音想退回来改，变成删栏里的字
        // 也就是说「文字上屏被 commitText 收进栏里」这套机制，
        // 会和「栏里也想用拼音打中文」直接打架。
        //
        // 引擎吃掉键的时候根本走不到这层；能走到这里的，是引擎不认识、
        // 本来要落进输入框的键。栏开着时它们该去哪？
        // → 交给下面的正常处理：commit(" ") 会经 [commit] 落到 commitText，
        //   那条路径已经把栏开着的情况接管了（文字进栏、不碰输入框）。
        // 退格这里要特殊一点：栏里有字就删栏里的，否则才删输入框。
        if (onTranslateBarActive() && sym == 0xff08 && onTranslateBarBackspaceIfNotEmpty()) return
        when (sym) {
            0xff08 -> { // BackSpace
                val ic = inputConnection() ?: return
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    val before = ic.getTextBeforeCursor(1, 0)
                    if (!before.isNullOrEmpty()) {
                        ic.deleteSurroundingText(1, 0)
                    }
                }
            }
            0xff0d -> { // Return
                val ic = inputConnection() ?: return
                // 搜狗式行为：文本框是单行时直接发 Enter 让 App 自己处理（比如发送消息）
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            0x0020 -> commit(" ") // space
            0xff51 -> moveCursor(-1) // Left
            0xff53 -> moveCursor(1)  // Right
            else -> KeySym.toChar(sym)?.let { commit(it.toString()) }
        }
    }

    private fun moveCursor(delta: Int) {
        val ic = inputConnection() ?: return
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(delta)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    /**
     * 退格左右滑：按住退格滑动可以连续删/选。
     * 原版用 CommonKeyActionListener 的 BackspaceSwipeState 做选区，
     * 这里简化成「有选中就删掉，否则按方向逐字删」。
     */
    private fun handleMoveSelection(action: KeyAction.MoveSelectionAction) {
        val ic = inputConnection() ?: return
        val count = action.end - action.start
        if (count < 0) {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) ic.commitText("", 1)
            else repeat(-count) { ic.deleteSurroundingText(1, 0) }
        } else if (count > 0) {
            moveCursor(count)
        }
    }

    private fun handleDeleteSelection(action: KeyAction.DeleteSelectionAction) {
        val ic = inputConnection() ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) return
        ic.commitText("", 1)
    }
}

/**
 * 九宫格（小键盘）键值 → 普通 ASCII 字符码，转不了返回 0。
 *
 * 数字键盘上的键发的是 X11 小键盘 keysym（KP_0 = 0xffb0 … KP_9 = 0xffb9，
 * KP_Add = 0xffab、KP_Subtract = 0xffad、KP_Multiply = 0xffaa、
 * KP_Divide = 0xffaf、KP_Decimal = 0xffae、KP_Enter = 0xff8d）。
 *
 * 这些码 librime 是认识的（key_table.cc 里有名字），但「认识」不等于「能用」：
 *   - speller 只认 alphabet 里的字母，KP 数字不在里面，编码阶段直接丢；
 *   - selector 认 KP_0..KP_9，但只在「已经有候选」时才走到那一段；
 *   - punctuator 的映射表是按 ASCII 符号字面写的（',' -> '，'），
 *     KP 的逗号句号对不上。
 * 结果就是：九宫格一半的键按下去什么都没发生。
 *
 * 所以这里先把 KP_* 统一压成等价的 ASCII 字符，再交给引擎 —— 之后
 * 「有候选时当选字键、没候选时直接上屏字符」两条路都和主键盘完全一致。
 *
 * KP_Enter 不在这里处理：它走的本来就是回车路径，交给上层。
 */
private fun kpToCharSym(sym: Int): Int = when (sym) {
    0xffb0 -> '0'.code
    0xffb1 -> '1'.code
    0xffb2 -> '2'.code
    0xffb3 -> '3'.code
    0xffb4 -> '4'.code
    0xffb5 -> '5'.code
    0xffb6 -> '6'.code
    0xffb7 -> '7'.code
    0xffb8 -> '8'.code
    0xffb9 -> '9'.code
    0xffab -> '+'.code  // KP_Add
    0xffad -> '-'.code  // KP_Subtract
    0xffaa -> '*'.code  // KP_Multiply
    0xffaf -> '/'.code  // KP_Divide
    0xffae -> '.'.code  // KP_Decimal
    0xffbd -> '='.code  // KP_Equal
    0xffac -> ','.code  // 九宫格里那个逗号键
    else -> 0
}

/**
 * KeyStates -> librime 的 modifier mask
 *
 * librime（RimeKeyEvent）用的位定义和 fcitx 一致的部分：
 *   Shift = 1<<0, Lock = 1<<1, Control = 1<<2, Mod1(Alt) = 1<<3
 *   以及 Release = 1<<30
 * NumLock 在 librime 里是 1<<4，和 fcitx 也一致。
 */
fun KeyStates.toMask(): Int {
    var mask = 0
    if (has(KeyState.Shift)) mask = mask or (1 shl 0)
    if (has(KeyState.CapsLock)) mask = mask or (1 shl 1)
    if (has(KeyState.Ctrl)) mask = mask or (1 shl 2)
    if (has(KeyState.Alt)) mask = mask or (1 shl 3)
    if (has(KeyState.NumLock)) mask = mask or (1 shl 4)
    return mask
}