/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb.keyboard

import com.jimeng.ime.kb.core.KeyStates
import com.jimeng.ime.kb.core.KeySym
import com.jimeng.ime.kb.core.ScancodeMapping
import com.jimeng.ime.kb.picker.PickerWindow

sealed class KeyAction {

    data class FcitxKeyAction(
        val act: String,
        val code: Int = ScancodeMapping.charToScancode(act[0]),
        val states: KeyStates = KeyStates.Virtual
    ) : KeyAction()

    data class SymAction(val sym: KeySym, val states: KeyStates = KeyStates.Virtual) : KeyAction()

    data class CommitAction(val text: String) : KeyAction()

    data class CapsAction(val lock: Boolean) : KeyAction()

    data object QuickPhraseAction : KeyAction()

    data object UnicodeAction : KeyAction()

    data object LangSwitchAction : KeyAction()

    data object ShowInputMethodPickerAction : KeyAction()

    data class LayoutSwitchAction(val act: String = "") : KeyAction()

    data class MoveSelectionAction(val start: Int = 0, val end: Int = 0) : KeyAction()

    data class DeleteSelectionAction(val totalCnt: Int = 0) : KeyAction()

    data class PickerSwitchAction(val key: PickerWindow.Key? = null) : KeyAction()

    data object SpaceLongPressAction : KeyAction()

    /**
     * 进入「键盘调节」模式：在键盘四条边上显示拖拽手柄，直接拖着改尺寸。
     *
     * 菜单项本身不碰尺寸，它只是把调节层叫出来——真正的数值改动
     * 由 [com.jimeng.ime.view.KeyboardResizeOverlay] 拖出来，松手才写 prefs。
     */
    data object KeyboardResizeAction : KeyAction()
}
