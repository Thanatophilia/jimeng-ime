/*
 * 从 fcitx5-android 的 codegen/GenKeyMapping.kt 生成的 FcitxKeyMapping 等价实现。
 *
 * 原版由 KSP 在编译期生成，本工程不带 KSP，所以把生成结果直接落成源码。
 * 数据完全取自原版的 pairs 列表（133 条），语义逐条一致。
 */
package com.jimeng.ime.kb.core

import android.view.KeyEvent

object FcitxKeyMapping {

    const val FcitxKey_space = 0x0020
    const val FcitxKey_numbersign = 0x0023
    const val FcitxKey_apostrophe = 0x0027
    const val FcitxKey_asterisk = 0x002a
    const val FcitxKey_plus = 0x002b
    const val FcitxKey_comma = 0x002c
    const val FcitxKey_minus = 0x002d
    const val FcitxKey_period = 0x002e
    const val FcitxKey_slash = 0x002f
    const val FcitxKey_0 = 0x0030
    const val FcitxKey_1 = 0x0031
    const val FcitxKey_2 = 0x0032
    const val FcitxKey_3 = 0x0033
    const val FcitxKey_4 = 0x0034
    const val FcitxKey_5 = 0x0035
    const val FcitxKey_6 = 0x0036
    const val FcitxKey_7 = 0x0037
    const val FcitxKey_8 = 0x0038
    const val FcitxKey_9 = 0x0039
    const val FcitxKey_semicolon = 0x003b
    const val FcitxKey_equal = 0x003d
    const val FcitxKey_at = 0x0040
    const val FcitxKey_A = 0x0041
    const val FcitxKey_B = 0x0042
    const val FcitxKey_C = 0x0043
    const val FcitxKey_D = 0x0044
    const val FcitxKey_E = 0x0045
    const val FcitxKey_F = 0x0046
    const val FcitxKey_G = 0x0047
    const val FcitxKey_H = 0x0048
    const val FcitxKey_I = 0x0049
    const val FcitxKey_J = 0x004a
    const val FcitxKey_K = 0x004b
    const val FcitxKey_L = 0x004c
    const val FcitxKey_M = 0x004d
    const val FcitxKey_N = 0x004e
    const val FcitxKey_O = 0x004f
    const val FcitxKey_P = 0x0050
    const val FcitxKey_Q = 0x0051
    const val FcitxKey_R = 0x0052
    const val FcitxKey_S = 0x0053
    const val FcitxKey_T = 0x0054
    const val FcitxKey_U = 0x0055
    const val FcitxKey_V = 0x0056
    const val FcitxKey_W = 0x0057
    const val FcitxKey_X = 0x0058
    const val FcitxKey_Y = 0x0059
    const val FcitxKey_Z = 0x005a
    const val FcitxKey_bracketleft = 0x005b
    const val FcitxKey_backslash = 0x005c
    const val FcitxKey_bracketright = 0x005d
    const val FcitxKey_grave = 0x0060
    const val FcitxKey_a = 0x0061
    const val FcitxKey_b = 0x0062
    const val FcitxKey_c = 0x0063
    const val FcitxKey_d = 0x0064
    const val FcitxKey_e = 0x0065
    const val FcitxKey_f = 0x0066
    const val FcitxKey_g = 0x0067
    const val FcitxKey_h = 0x0068
    const val FcitxKey_i = 0x0069
    const val FcitxKey_j = 0x006a
    const val FcitxKey_k = 0x006b
    const val FcitxKey_l = 0x006c
    const val FcitxKey_m = 0x006d
    const val FcitxKey_n = 0x006e
    const val FcitxKey_o = 0x006f
    const val FcitxKey_p = 0x0070
    const val FcitxKey_q = 0x0071
    const val FcitxKey_r = 0x0072
    const val FcitxKey_s = 0x0073
    const val FcitxKey_t = 0x0074
    const val FcitxKey_u = 0x0075
    const val FcitxKey_v = 0x0076
    const val FcitxKey_w = 0x0077
    const val FcitxKey_x = 0x0078
    const val FcitxKey_y = 0x0079
    const val FcitxKey_z = 0x007a
    const val FcitxKey_F1 = 0xffbe
    const val FcitxKey_F2 = 0xffbf
    const val FcitxKey_F3 = 0xffc0
    const val FcitxKey_F4 = 0xffc1
    const val FcitxKey_F5 = 0xffc2
    const val FcitxKey_F6 = 0xffc3
    const val FcitxKey_F7 = 0xffc4
    const val FcitxKey_F8 = 0xffc5
    const val FcitxKey_F9 = 0xffc6
    const val FcitxKey_F10 = 0xffc7
    const val FcitxKey_F11 = 0xffc8
    const val FcitxKey_F12 = 0xffc9
    const val FcitxKey_Shift_L = 0xffe1
    const val FcitxKey_Shift_R = 0xffe2
    const val FcitxKey_Control_L = 0xffe3
    const val FcitxKey_Control_R = 0xffe4
    const val FcitxKey_Caps_Lock = 0xffe5
    const val FcitxKey_Meta_L = 0xffe7
    const val FcitxKey_Meta_R = 0xffe8
    const val FcitxKey_Alt_L = 0xffe9
    const val FcitxKey_Alt_R = 0xffea
    const val FcitxKey_Insert = 0xff63
    const val FcitxKey_Delete = 0xffff
    const val FcitxKey_Home = 0xff50
    const val FcitxKey_End = 0xff57
    const val FcitxKey_Page_Down = 0xff56
    const val FcitxKey_Page_Up = 0xff55
    const val FcitxKey_Tab = 0xff09
    const val FcitxKey_BackSpace = 0xff08
    const val FcitxKey_Return = 0xff0d
    const val FcitxKey_Escape = 0xff1b
    const val FcitxKey_Up = 0xff52
    const val FcitxKey_Down = 0xff54
    const val FcitxKey_Left = 0xff51
    const val FcitxKey_Right = 0xff53
    const val FcitxKey_KP_Divide = 0xffaf
    const val FcitxKey_KP_Multiply = 0xffaa
    const val FcitxKey_KP_Subtract = 0xffad
    const val FcitxKey_KP_7 = 0xffb7
    const val FcitxKey_KP_8 = 0xffb8
    const val FcitxKey_KP_9 = 0xffb9
    const val FcitxKey_KP_Add = 0xffab
    const val FcitxKey_KP_4 = 0xffb4
    const val FcitxKey_KP_5 = 0xffb5
    const val FcitxKey_KP_6 = 0xffb6
    const val FcitxKey_KP_1 = 0xffb1
    const val FcitxKey_KP_2 = 0xffb2
    const val FcitxKey_KP_3 = 0xffb3
    const val FcitxKey_KP_Enter = 0xff8d
    const val FcitxKey_KP_0 = 0xffb0
    const val FcitxKey_KP_Decimal = 0xffae
    const val FcitxKey_Eisu_toggle = 0xff30
    const val FcitxKey_Kana_Lock = 0xff2d
    const val FcitxKey_Hiragana_Katakana = 0xff27
    const val FcitxKey_Zenkaku_Hankaku = 0xff2a

    /** keysym -> Android keycode */
    fun symToKeyCode(sym: Int): Int? = when (sym) {
        FcitxKey_space -> KeyEvent.KEYCODE_SPACE
        FcitxKey_numbersign -> KeyEvent.KEYCODE_POUND
        FcitxKey_apostrophe -> KeyEvent.KEYCODE_APOSTROPHE
        FcitxKey_asterisk -> KeyEvent.KEYCODE_STAR
        FcitxKey_plus -> KeyEvent.KEYCODE_PLUS
        FcitxKey_comma -> KeyEvent.KEYCODE_COMMA
        FcitxKey_minus -> KeyEvent.KEYCODE_MINUS
        FcitxKey_period -> KeyEvent.KEYCODE_PERIOD
        FcitxKey_slash -> KeyEvent.KEYCODE_SLASH
        FcitxKey_0 -> KeyEvent.KEYCODE_0
        FcitxKey_1 -> KeyEvent.KEYCODE_1
        FcitxKey_2 -> KeyEvent.KEYCODE_2
        FcitxKey_3 -> KeyEvent.KEYCODE_3
        FcitxKey_4 -> KeyEvent.KEYCODE_4
        FcitxKey_5 -> KeyEvent.KEYCODE_5
        FcitxKey_6 -> KeyEvent.KEYCODE_6
        FcitxKey_7 -> KeyEvent.KEYCODE_7
        FcitxKey_8 -> KeyEvent.KEYCODE_8
        FcitxKey_9 -> KeyEvent.KEYCODE_9
        FcitxKey_semicolon -> KeyEvent.KEYCODE_SEMICOLON
        FcitxKey_equal -> KeyEvent.KEYCODE_EQUALS
        FcitxKey_at -> KeyEvent.KEYCODE_AT
        FcitxKey_A -> KeyEvent.KEYCODE_A
        FcitxKey_B -> KeyEvent.KEYCODE_B
        FcitxKey_C -> KeyEvent.KEYCODE_C
        FcitxKey_D -> KeyEvent.KEYCODE_D
        FcitxKey_E -> KeyEvent.KEYCODE_E
        FcitxKey_F -> KeyEvent.KEYCODE_F
        FcitxKey_G -> KeyEvent.KEYCODE_G
        FcitxKey_H -> KeyEvent.KEYCODE_H
        FcitxKey_I -> KeyEvent.KEYCODE_I
        FcitxKey_J -> KeyEvent.KEYCODE_J
        FcitxKey_K -> KeyEvent.KEYCODE_K
        FcitxKey_L -> KeyEvent.KEYCODE_L
        FcitxKey_M -> KeyEvent.KEYCODE_M
        FcitxKey_N -> KeyEvent.KEYCODE_N
        FcitxKey_O -> KeyEvent.KEYCODE_O
        FcitxKey_P -> KeyEvent.KEYCODE_P
        FcitxKey_Q -> KeyEvent.KEYCODE_Q
        FcitxKey_R -> KeyEvent.KEYCODE_R
        FcitxKey_S -> KeyEvent.KEYCODE_S
        FcitxKey_T -> KeyEvent.KEYCODE_T
        FcitxKey_U -> KeyEvent.KEYCODE_U
        FcitxKey_V -> KeyEvent.KEYCODE_V
        FcitxKey_W -> KeyEvent.KEYCODE_W
        FcitxKey_X -> KeyEvent.KEYCODE_X
        FcitxKey_Y -> KeyEvent.KEYCODE_Y
        FcitxKey_Z -> KeyEvent.KEYCODE_Z
        FcitxKey_bracketleft -> KeyEvent.KEYCODE_LEFT_BRACKET
        FcitxKey_backslash -> KeyEvent.KEYCODE_BACKSLASH
        FcitxKey_bracketright -> KeyEvent.KEYCODE_RIGHT_BRACKET
        FcitxKey_grave -> KeyEvent.KEYCODE_GRAVE
        FcitxKey_a -> KeyEvent.KEYCODE_A
        FcitxKey_b -> KeyEvent.KEYCODE_B
        FcitxKey_c -> KeyEvent.KEYCODE_C
        FcitxKey_d -> KeyEvent.KEYCODE_D
        FcitxKey_e -> KeyEvent.KEYCODE_E
        FcitxKey_f -> KeyEvent.KEYCODE_F
        FcitxKey_g -> KeyEvent.KEYCODE_G
        FcitxKey_h -> KeyEvent.KEYCODE_H
        FcitxKey_i -> KeyEvent.KEYCODE_I
        FcitxKey_j -> KeyEvent.KEYCODE_J
        FcitxKey_k -> KeyEvent.KEYCODE_K
        FcitxKey_l -> KeyEvent.KEYCODE_L
        FcitxKey_m -> KeyEvent.KEYCODE_M
        FcitxKey_n -> KeyEvent.KEYCODE_N
        FcitxKey_o -> KeyEvent.KEYCODE_O
        FcitxKey_p -> KeyEvent.KEYCODE_P
        FcitxKey_q -> KeyEvent.KEYCODE_Q
        FcitxKey_r -> KeyEvent.KEYCODE_R
        FcitxKey_s -> KeyEvent.KEYCODE_S
        FcitxKey_t -> KeyEvent.KEYCODE_T
        FcitxKey_u -> KeyEvent.KEYCODE_U
        FcitxKey_v -> KeyEvent.KEYCODE_V
        FcitxKey_w -> KeyEvent.KEYCODE_W
        FcitxKey_x -> KeyEvent.KEYCODE_X
        FcitxKey_y -> KeyEvent.KEYCODE_Y
        FcitxKey_z -> KeyEvent.KEYCODE_Z
        FcitxKey_F1 -> KeyEvent.KEYCODE_F1
        FcitxKey_F2 -> KeyEvent.KEYCODE_F2
        FcitxKey_F3 -> KeyEvent.KEYCODE_F3
        FcitxKey_F4 -> KeyEvent.KEYCODE_F4
        FcitxKey_F5 -> KeyEvent.KEYCODE_F5
        FcitxKey_F6 -> KeyEvent.KEYCODE_F6
        FcitxKey_F7 -> KeyEvent.KEYCODE_F7
        FcitxKey_F8 -> KeyEvent.KEYCODE_F8
        FcitxKey_F9 -> KeyEvent.KEYCODE_F9
        FcitxKey_F10 -> KeyEvent.KEYCODE_F10
        FcitxKey_F11 -> KeyEvent.KEYCODE_F11
        FcitxKey_F12 -> KeyEvent.KEYCODE_F12
        FcitxKey_Shift_L -> KeyEvent.KEYCODE_SHIFT_LEFT
        FcitxKey_Shift_R -> KeyEvent.KEYCODE_SHIFT_RIGHT
        FcitxKey_Control_L -> KeyEvent.KEYCODE_CTRL_LEFT
        FcitxKey_Control_R -> KeyEvent.KEYCODE_CTRL_RIGHT
        FcitxKey_Caps_Lock -> KeyEvent.KEYCODE_CAPS_LOCK
        FcitxKey_Meta_L -> KeyEvent.KEYCODE_META_LEFT
        FcitxKey_Meta_R -> KeyEvent.KEYCODE_META_RIGHT
        FcitxKey_Alt_L -> KeyEvent.KEYCODE_ALT_LEFT
        FcitxKey_Alt_R -> KeyEvent.KEYCODE_ALT_RIGHT
        FcitxKey_Insert -> KeyEvent.KEYCODE_INSERT
        FcitxKey_Delete -> KeyEvent.KEYCODE_FORWARD_DEL
        FcitxKey_Home -> KeyEvent.KEYCODE_MOVE_HOME
        FcitxKey_End -> KeyEvent.KEYCODE_MOVE_END
        FcitxKey_Page_Down -> KeyEvent.KEYCODE_PAGE_DOWN
        FcitxKey_Page_Up -> KeyEvent.KEYCODE_PAGE_UP
        FcitxKey_Tab -> KeyEvent.KEYCODE_TAB
        FcitxKey_BackSpace -> KeyEvent.KEYCODE_DEL
        FcitxKey_Return -> KeyEvent.KEYCODE_ENTER
        FcitxKey_Escape -> KeyEvent.KEYCODE_ESCAPE
        FcitxKey_Up -> KeyEvent.KEYCODE_DPAD_UP
        FcitxKey_Down -> KeyEvent.KEYCODE_DPAD_DOWN
        FcitxKey_Left -> KeyEvent.KEYCODE_DPAD_LEFT
        FcitxKey_Right -> KeyEvent.KEYCODE_DPAD_RIGHT
        FcitxKey_KP_Divide -> KeyEvent.KEYCODE_NUMPAD_DIVIDE
        FcitxKey_KP_Multiply -> KeyEvent.KEYCODE_NUMPAD_MULTIPLY
        FcitxKey_KP_Subtract -> KeyEvent.KEYCODE_NUMPAD_SUBTRACT
        FcitxKey_KP_7 -> KeyEvent.KEYCODE_NUMPAD_7
        FcitxKey_KP_8 -> KeyEvent.KEYCODE_NUMPAD_8
        FcitxKey_KP_9 -> KeyEvent.KEYCODE_NUMPAD_9
        FcitxKey_KP_Add -> KeyEvent.KEYCODE_NUMPAD_ADD
        FcitxKey_KP_4 -> KeyEvent.KEYCODE_NUMPAD_4
        FcitxKey_KP_5 -> KeyEvent.KEYCODE_NUMPAD_5
        FcitxKey_KP_6 -> KeyEvent.KEYCODE_NUMPAD_6
        FcitxKey_KP_1 -> KeyEvent.KEYCODE_NUMPAD_1
        FcitxKey_KP_2 -> KeyEvent.KEYCODE_NUMPAD_2
        FcitxKey_KP_3 -> KeyEvent.KEYCODE_NUMPAD_3
        FcitxKey_KP_Enter -> KeyEvent.KEYCODE_NUMPAD_ENTER
        FcitxKey_KP_0 -> KeyEvent.KEYCODE_NUMPAD_0
        FcitxKey_KP_Decimal -> KeyEvent.KEYCODE_NUMPAD_DOT
        FcitxKey_Eisu_toggle -> KeyEvent.KEYCODE_EISU
        FcitxKey_Kana_Lock -> KeyEvent.KEYCODE_KANA
        FcitxKey_Hiragana_Katakana -> KeyEvent.KEYCODE_KATAKANA_HIRAGANA
        FcitxKey_Zenkaku_Hankaku -> KeyEvent.KEYCODE_ZENKAKU_HANKAKU
        else -> null
    }

    /**
     * Android keycode -> keysym
     *
     * 有意排除大写拉丁字母区间（0x41..0x5A）：Android 没有大小写各自独立的 keycode，
     * 而 ASCII 可打印字符的 keysym 就是字符本身，按住 Shift 时会走 unicodeChar 路径。
     */
    fun keyCodeToSym(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_SPACE -> FcitxKey_space
        KeyEvent.KEYCODE_POUND -> FcitxKey_numbersign
        KeyEvent.KEYCODE_APOSTROPHE -> FcitxKey_apostrophe
        KeyEvent.KEYCODE_STAR -> FcitxKey_asterisk
        KeyEvent.KEYCODE_PLUS -> FcitxKey_plus
        KeyEvent.KEYCODE_COMMA -> FcitxKey_comma
        KeyEvent.KEYCODE_MINUS -> FcitxKey_minus
        KeyEvent.KEYCODE_PERIOD -> FcitxKey_period
        KeyEvent.KEYCODE_SLASH -> FcitxKey_slash
        KeyEvent.KEYCODE_0 -> FcitxKey_0
        KeyEvent.KEYCODE_1 -> FcitxKey_1
        KeyEvent.KEYCODE_2 -> FcitxKey_2
        KeyEvent.KEYCODE_3 -> FcitxKey_3
        KeyEvent.KEYCODE_4 -> FcitxKey_4
        KeyEvent.KEYCODE_5 -> FcitxKey_5
        KeyEvent.KEYCODE_6 -> FcitxKey_6
        KeyEvent.KEYCODE_7 -> FcitxKey_7
        KeyEvent.KEYCODE_8 -> FcitxKey_8
        KeyEvent.KEYCODE_9 -> FcitxKey_9
        KeyEvent.KEYCODE_SEMICOLON -> FcitxKey_semicolon
        KeyEvent.KEYCODE_EQUALS -> FcitxKey_equal
        KeyEvent.KEYCODE_AT -> FcitxKey_at
        KeyEvent.KEYCODE_LEFT_BRACKET -> FcitxKey_bracketleft
        KeyEvent.KEYCODE_BACKSLASH -> FcitxKey_backslash
        KeyEvent.KEYCODE_RIGHT_BRACKET -> FcitxKey_bracketright
        KeyEvent.KEYCODE_GRAVE -> FcitxKey_grave
        KeyEvent.KEYCODE_A -> FcitxKey_a
        KeyEvent.KEYCODE_B -> FcitxKey_b
        KeyEvent.KEYCODE_C -> FcitxKey_c
        KeyEvent.KEYCODE_D -> FcitxKey_d
        KeyEvent.KEYCODE_E -> FcitxKey_e
        KeyEvent.KEYCODE_F -> FcitxKey_f
        KeyEvent.KEYCODE_G -> FcitxKey_g
        KeyEvent.KEYCODE_H -> FcitxKey_h
        KeyEvent.KEYCODE_I -> FcitxKey_i
        KeyEvent.KEYCODE_J -> FcitxKey_j
        KeyEvent.KEYCODE_K -> FcitxKey_k
        KeyEvent.KEYCODE_L -> FcitxKey_l
        KeyEvent.KEYCODE_M -> FcitxKey_m
        KeyEvent.KEYCODE_N -> FcitxKey_n
        KeyEvent.KEYCODE_O -> FcitxKey_o
        KeyEvent.KEYCODE_P -> FcitxKey_p
        KeyEvent.KEYCODE_Q -> FcitxKey_q
        KeyEvent.KEYCODE_R -> FcitxKey_r
        KeyEvent.KEYCODE_S -> FcitxKey_s
        KeyEvent.KEYCODE_T -> FcitxKey_t
        KeyEvent.KEYCODE_U -> FcitxKey_u
        KeyEvent.KEYCODE_V -> FcitxKey_v
        KeyEvent.KEYCODE_W -> FcitxKey_w
        KeyEvent.KEYCODE_X -> FcitxKey_x
        KeyEvent.KEYCODE_Y -> FcitxKey_y
        KeyEvent.KEYCODE_Z -> FcitxKey_z
        KeyEvent.KEYCODE_F1 -> FcitxKey_F1
        KeyEvent.KEYCODE_F2 -> FcitxKey_F2
        KeyEvent.KEYCODE_F3 -> FcitxKey_F3
        KeyEvent.KEYCODE_F4 -> FcitxKey_F4
        KeyEvent.KEYCODE_F5 -> FcitxKey_F5
        KeyEvent.KEYCODE_F6 -> FcitxKey_F6
        KeyEvent.KEYCODE_F7 -> FcitxKey_F7
        KeyEvent.KEYCODE_F8 -> FcitxKey_F8
        KeyEvent.KEYCODE_F9 -> FcitxKey_F9
        KeyEvent.KEYCODE_F10 -> FcitxKey_F10
        KeyEvent.KEYCODE_F11 -> FcitxKey_F11
        KeyEvent.KEYCODE_F12 -> FcitxKey_F12
        KeyEvent.KEYCODE_SHIFT_LEFT -> FcitxKey_Shift_L
        KeyEvent.KEYCODE_SHIFT_RIGHT -> FcitxKey_Shift_R
        KeyEvent.KEYCODE_CTRL_LEFT -> FcitxKey_Control_L
        KeyEvent.KEYCODE_CTRL_RIGHT -> FcitxKey_Control_R
        KeyEvent.KEYCODE_CAPS_LOCK -> FcitxKey_Caps_Lock
        KeyEvent.KEYCODE_META_LEFT -> FcitxKey_Meta_L
        KeyEvent.KEYCODE_META_RIGHT -> FcitxKey_Meta_R
        KeyEvent.KEYCODE_ALT_LEFT -> FcitxKey_Alt_L
        KeyEvent.KEYCODE_ALT_RIGHT -> FcitxKey_Alt_R
        KeyEvent.KEYCODE_INSERT -> FcitxKey_Insert
        KeyEvent.KEYCODE_FORWARD_DEL -> FcitxKey_Delete
        KeyEvent.KEYCODE_MOVE_HOME -> FcitxKey_Home
        KeyEvent.KEYCODE_MOVE_END -> FcitxKey_End
        KeyEvent.KEYCODE_PAGE_DOWN -> FcitxKey_Page_Down
        KeyEvent.KEYCODE_PAGE_UP -> FcitxKey_Page_Up
        KeyEvent.KEYCODE_TAB -> FcitxKey_Tab
        KeyEvent.KEYCODE_DEL -> FcitxKey_BackSpace
        KeyEvent.KEYCODE_ENTER -> FcitxKey_Return
        KeyEvent.KEYCODE_ESCAPE -> FcitxKey_Escape
        KeyEvent.KEYCODE_DPAD_UP -> FcitxKey_Up
        KeyEvent.KEYCODE_DPAD_DOWN -> FcitxKey_Down
        KeyEvent.KEYCODE_DPAD_LEFT -> FcitxKey_Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> FcitxKey_Right
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> FcitxKey_KP_Divide
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> FcitxKey_KP_Multiply
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> FcitxKey_KP_Subtract
        KeyEvent.KEYCODE_NUMPAD_7 -> FcitxKey_KP_7
        KeyEvent.KEYCODE_NUMPAD_8 -> FcitxKey_KP_8
        KeyEvent.KEYCODE_NUMPAD_9 -> FcitxKey_KP_9
        KeyEvent.KEYCODE_NUMPAD_ADD -> FcitxKey_KP_Add
        KeyEvent.KEYCODE_NUMPAD_4 -> FcitxKey_KP_4
        KeyEvent.KEYCODE_NUMPAD_5 -> FcitxKey_KP_5
        KeyEvent.KEYCODE_NUMPAD_6 -> FcitxKey_KP_6
        KeyEvent.KEYCODE_NUMPAD_1 -> FcitxKey_KP_1
        KeyEvent.KEYCODE_NUMPAD_2 -> FcitxKey_KP_2
        KeyEvent.KEYCODE_NUMPAD_3 -> FcitxKey_KP_3
        KeyEvent.KEYCODE_NUMPAD_ENTER -> FcitxKey_KP_Enter
        KeyEvent.KEYCODE_NUMPAD_0 -> FcitxKey_KP_0
        KeyEvent.KEYCODE_NUMPAD_DOT -> FcitxKey_KP_Decimal
        KeyEvent.KEYCODE_EISU -> FcitxKey_Eisu_toggle
        KeyEvent.KEYCODE_KANA -> FcitxKey_Kana_Lock
        KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> FcitxKey_Hiragana_Katakana
        KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> FcitxKey_Zenkaku_Hankaku
        else -> null
    }
}
