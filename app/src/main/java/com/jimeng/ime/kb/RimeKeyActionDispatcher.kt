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
import com.jimeng.ime.kb.core.FcitxKeyMapping
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
 * @param beforeBackspace 按退格之前先调一次，返回 true 表示「已经处理完，别再退格了」。
 *        用来解除拼音段锁定，见 `JimengIME.releasePreeditLock`。
 */
class RimeKeyActionDispatcher(
    private val sessionProvider: () -> Long,
    private val processKey: (session: Long, keySym: Int, mask: Int) -> Boolean,
    private val commit: (String) -> Unit,
    private val inputConnection: () -> InputConnection?,
    private val onCapsChanged: (lock: Boolean) -> Unit = {},
    private val onLayoutSwitch: (String) -> Unit = {},
    private val onPickerSwitch: (PickerWindow.Key?) -> Unit = {},
    private val onShowImePicker: () -> Unit = {},
    /** 小地球短按：切引擎里的输入语言 */
    private val onSwitchLanguage: () -> Unit = {},
    private val onSpaceLongPress: () -> Unit = {},
    private val beforeBackspace: (session: Long) -> Boolean = { false },
) : KeyActionListener {

    override fun onKeyAction(action: KeyAction, source: KeyActionListener.Source) {
        val session = sessionProvider()

        // 九宫格（小键盘）单独走一条路，理由见文件末尾 kpToCharSym()。
        if (action is KeyAction.SymAction) {
            val ch = kpToCharSym(action.sym.sym)
            if (ch != 0) {
                // 数字：正在输编码时当候选选择键（和主键盘数字键一致），
                // 没有候选就按盘面字面输出。
                if (ch in '0'.code..'9'.code && session != 0L &&
                    processKey(session, ch, 0)
                ) return
                // 其余符号一律字面输出：这是数字键盘，1.5 就该是 1.5，
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
                // 退格前先给一次「解除拼音段锁定」的机会：
                // rime 的退格会先解锁、解开了就吃掉按键不删字母，
                // 主人点了拼音段之后得按好几下才删得掉，很难受。
                if (sym == FcitxKeyMapping.FcitxKey_BackSpace &&
                    session != 0L && beforeBackspace(session)
                ) return
                if (session != 0L && processKey(session, sym, action.states.toMask())) return
                handleSymFallback(sym, action.states)
            }

            // ---- 编辑操作 ----
            is KeyAction.CommitAction -> commit(action.text)

            is KeyAction.CapsAction -> onCapsChanged(action.lock)

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