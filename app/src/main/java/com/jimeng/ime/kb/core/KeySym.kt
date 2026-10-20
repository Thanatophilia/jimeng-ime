/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb.core

import android.view.KeyCharacterMap
import android.view.KeyEvent

@JvmInline
value class KeySym(val sym: Int) {

    val keyCode get() = FcitxKeyMapping.symToKeyCode(sym) ?: KeyEvent.KEYCODE_UNKNOWN

    override fun toString() = "0x" + sym.toString(16).padStart(4, '0')

    companion object {
        fun fromKeyEvent(event: KeyEvent): KeySym? {
            val charCode = event.unicodeChar
            // try charCode first, allow upper and lower case characters generating different KeySym
            if (charCode != 0 &&
                // skip \t, because it's charCode is different from KeySym
                charCode != '\t'.code &&
                // skip \n, because fcitx wants \r for return
                charCode != '\n'.code &&
                // skip Android's private-use character
                charCode != KeyCharacterMap.HEX_INPUT.code &&
                charCode != KeyCharacterMap.PICKER_DIALOG_INPUT.code
            ) {
                return KeySym(charCode)
            }
            return KeySym(FcitxKeyMapping.keyCodeToSym(event.keyCode) ?: return null)
        }

        /**
         * keysym -> 可打印字符。
         *
         * keysym 里可打印 ASCII 直接就是字符码（0x20..0x7E），
         * Latin-1 区（0xA0..0xFF）也和 Unicode 码点一致。
         * 其余（功能键 0xFF00 以上）返回 null。
         */
        fun toChar(sym: Int): Char? = when {
            sym in 0x20..0x7e -> sym.toChar()
            sym in 0xa0..0xff -> sym.toChar()
            else -> null
        }
    }
}
