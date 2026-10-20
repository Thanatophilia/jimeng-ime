/*
 * 从 fcitx5-android 的 codegen/GenScancodeMapping.kt 生成的 ScancodeMapping 等价实现。
 *
 * scancode 用的是 Linux evdev 的 keycode（KEY_*），定义见
 * https://github.com/torvalds/linux/blob/v6.10/include/uapi/linux/input-event-codes.h
 *
 * 原版由 KSP 生成，这里直接落成源码，数据取自原版的 SCANCODE_MAP（249 条）
 * 与 CHAR_MAP（0 条），语义逐条一致。
 */
package com.jimeng.ime.kb.core

import android.view.KeyEvent

object ScancodeMapping {

    const val KEY_RESERVED = 0
    const val KEY_ESC = 1
    const val KEY_1 = 2
    const val KEY_2 = 3
    const val KEY_3 = 4
    const val KEY_4 = 5
    const val KEY_5 = 6
    const val KEY_6 = 7
    const val KEY_7 = 8
    const val KEY_8 = 9
    const val KEY_9 = 10
    const val KEY_0 = 11
    const val KEY_MINUS = 12
    const val KEY_EQUAL = 13
    const val KEY_BACKSPACE = 14
    const val KEY_TAB = 15
    const val KEY_Q = 16
    const val KEY_W = 17
    const val KEY_E = 18
    const val KEY_R = 19
    const val KEY_T = 20
    const val KEY_Y = 21
    const val KEY_U = 22
    const val KEY_I = 23
    const val KEY_O = 24
    const val KEY_P = 25
    const val KEY_LEFTBRACE = 26
    const val KEY_RIGHTBRACE = 27
    const val KEY_ENTER = 28
    const val KEY_LEFTCTRL = 29
    const val KEY_A = 30
    const val KEY_S = 31
    const val KEY_D = 32
    const val KEY_F = 33
    const val KEY_G = 34
    const val KEY_H = 35
    const val KEY_J = 36
    const val KEY_K = 37
    const val KEY_L = 38
    const val KEY_SEMICOLON = 39
    const val KEY_APOSTROPHE = 40
    const val KEY_GRAVE = 41
    const val KEY_LEFTSHIFT = 42
    const val KEY_BACKSLASH = 43
    const val KEY_Z = 44
    const val KEY_X = 45
    const val KEY_C = 46
    const val KEY_V = 47
    const val KEY_B = 48
    const val KEY_N = 49
    const val KEY_M = 50
    const val KEY_COMMA = 51
    const val KEY_DOT = 52
    const val KEY_SLASH = 53
    const val KEY_RIGHTSHIFT = 54
    const val KEY_KPASTERISK = 55
    const val KEY_LEFTALT = 56
    const val KEY_SPACE = 57
    const val KEY_CAPSLOCK = 58
    const val KEY_F1 = 59
    const val KEY_F2 = 60
    const val KEY_F3 = 61
    const val KEY_F4 = 62
    const val KEY_F5 = 63
    const val KEY_F6 = 64
    const val KEY_F7 = 65
    const val KEY_F8 = 66
    const val KEY_F9 = 67
    const val KEY_F10 = 68
    const val KEY_NUMLOCK = 69
    const val KEY_SCROLLLOCK = 70
    const val KEY_KP7 = 71
    const val KEY_KP8 = 72
    const val KEY_KP9 = 73
    const val KEY_KPMINUS = 74
    const val KEY_KP4 = 75
    const val KEY_KP5 = 76
    const val KEY_KP6 = 77
    const val KEY_KPPLUS = 78
    const val KEY_KP1 = 79
    const val KEY_KP2 = 80
    const val KEY_KP3 = 81
    const val KEY_KP0 = 82
    const val KEY_KPDOT = 83
    const val KEY_ZENKAKUHANKAKU = 85
    const val KEY_102ND = 86
    const val KEY_F11 = 87
    const val KEY_F12 = 88
    const val KEY_RO = 89
    const val KEY_KATAKANA = 90
    const val KEY_HIRAGANA = 91
    const val KEY_HENKAN = 92
    const val KEY_KATAKANAHIRAGANA = 93
    const val KEY_MUHENKAN = 94
    const val KEY_KPJPCOMMA = 95
    const val KEY_KPENTER = 96
    const val KEY_RIGHTCTRL = 97
    const val KEY_KPSLASH = 98
    const val KEY_SYSRQ = 99
    const val KEY_RIGHTALT = 100
    const val KEY_LINEFEED = 101
    const val KEY_HOME = 102
    const val KEY_UP = 103
    const val KEY_PAGEUP = 104
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_END = 107
    const val KEY_DOWN = 108
    const val KEY_PAGEDOWN = 109
    const val KEY_INSERT = 110
    const val KEY_DELETE = 111
    const val KEY_MACRO = 112
    const val KEY_MUTE = 113
    const val KEY_VOLUMEDOWN = 114
    const val KEY_VOLUMEUP = 115
    const val KEY_POWER = 116
    const val KEY_KPEQUAL = 117
    const val KEY_KPPLUSMINUS = 118
    const val KEY_PAUSE = 119
    const val KEY_SCALE = 120
    const val KEY_KPCOMMA = 121
    const val KEY_HANGEUL = 122
    const val KEY_HANGUEL = 122
    const val KEY_HANJA = 123
    const val KEY_YEN = 124
    const val KEY_LEFTMETA = 125
    const val KEY_RIGHTMETA = 126
    const val KEY_COMPOSE = 127
    const val KEY_STOP = 128
    const val KEY_AGAIN = 129
    const val KEY_PROPS = 130
    const val KEY_UNDO = 131
    const val KEY_FRONT = 132
    const val KEY_COPY = 133
    const val KEY_OPEN = 134
    const val KEY_PASTE = 135
    const val KEY_FIND = 136
    const val KEY_CUT = 137
    const val KEY_HELP = 138
    const val KEY_MENU = 139
    const val KEY_CALC = 140
    const val KEY_SETUP = 141
    const val KEY_SLEEP = 142
    const val KEY_WAKEUP = 143
    const val KEY_FILE = 144
    const val KEY_SENDFILE = 145
    const val KEY_DELETEFILE = 146
    const val KEY_XFER = 147
    const val KEY_PROG1 = 148
    const val KEY_PROG2 = 149
    const val KEY_WWW = 150
    const val KEY_MSDOS = 151
    const val KEY_COFFEE = 152
    const val KEY_SCREENLOCK = 152
    const val KEY_ROTATE_DISPLAY = 153
    const val KEY_DIRECTION = 153
    const val KEY_CYCLEWINDOWS = 154
    const val KEY_MAIL = 155
    const val KEY_BOOKMARKS = 156
    const val KEY_COMPUTER = 157
    const val KEY_BACK = 158
    const val KEY_FORWARD = 159
    const val KEY_CLOSECD = 160
    const val KEY_EJECTCD = 161
    const val KEY_EJECTCLOSECD = 162
    const val KEY_NEXTSONG = 163
    const val KEY_PLAYPAUSE = 164
    const val KEY_PREVIOUSSONG = 165
    const val KEY_STOPCD = 166
    const val KEY_RECORD = 167
    const val KEY_REWIND = 168
    const val KEY_PHONE = 169
    const val KEY_ISO = 170
    const val KEY_CONFIG = 171
    const val KEY_HOMEPAGE = 172
    const val KEY_REFRESH = 173
    const val KEY_EXIT = 174
    const val KEY_MOVE = 175
    const val KEY_EDIT = 176
    const val KEY_SCROLLUP = 177
    const val KEY_SCROLLDOWN = 178
    const val KEY_KPLEFTPAREN = 179
    const val KEY_KPRIGHTPAREN = 180
    const val KEY_NEW = 181
    const val KEY_REDO = 182
    const val KEY_F13 = 183
    const val KEY_F14 = 184
    const val KEY_F15 = 185
    const val KEY_F16 = 186
    const val KEY_F17 = 187
    const val KEY_F18 = 188
    const val KEY_F19 = 189
    const val KEY_F20 = 190
    const val KEY_F21 = 191
    const val KEY_F22 = 192
    const val KEY_F23 = 193
    const val KEY_F24 = 194
    const val KEY_PLAYCD = 200
    const val KEY_PAUSECD = 201
    const val KEY_PROG3 = 202
    const val KEY_PROG4 = 203
    const val KEY_ALL_APPLICATIONS = 204
    const val KEY_DASHBOARD = 204
    const val KEY_SUSPEND = 205
    const val KEY_CLOSE = 206
    const val KEY_PLAY = 207
    const val KEY_FASTFORWARD = 208
    const val KEY_BASSBOOST = 209
    const val KEY_PRINT = 210
    const val KEY_HP = 211
    const val KEY_CAMERA = 212
    const val KEY_SOUND = 213
    const val KEY_QUESTION = 214
    const val KEY_EMAIL = 215
    const val KEY_CHAT = 216
    const val KEY_SEARCH = 217
    const val KEY_CONNECT = 218
    const val KEY_FINANCE = 219
    const val KEY_SPORT = 220
    const val KEY_SHOP = 221
    const val KEY_ALTERASE = 222
    const val KEY_CANCEL = 223
    const val KEY_BRIGHTNESSDOWN = 224
    const val KEY_BRIGHTNESSUP = 225
    const val KEY_MEDIA = 226
    const val KEY_SWITCHVIDEOMODE = 227
    const val KEY_KBDILLUMTOGGLE = 228
    const val KEY_KBDILLUMDOWN = 229
    const val KEY_KBDILLUMUP = 230
    const val KEY_SEND = 231
    const val KEY_REPLY = 232
    const val KEY_FORWARDMAIL = 233
    const val KEY_SAVE = 234
    const val KEY_DOCUMENTS = 235
    const val KEY_BATTERY = 236
    const val KEY_BLUETOOTH = 237
    const val KEY_WLAN = 238
    const val KEY_UWB = 239
    const val KEY_UNKNOWN = 240
    const val KEY_VIDEO_NEXT = 241
    const val KEY_VIDEO_PREV = 242
    const val KEY_BRIGHTNESS_CYCLE = 243
    const val KEY_BRIGHTNESS_AUTO = 244
    const val KEY_BRIGHTNESS_ZERO = 244
    const val KEY_DISPLAY_OFF = 245
    const val KEY_WWAN = 246
    const val KEY_WIMAX = 246
    const val KEY_RFKILL = 247
    const val KEY_MICMUTE = 248

    /** 字符 -> Linux evdev scancode */
    fun charToScancode(ch: Char): Int = when (ch) {
        '`' -> KEY_GRAVE
        '1' -> KEY_1
        '2' -> KEY_2
        '3' -> KEY_3
        '4' -> KEY_4
        '5' -> KEY_5
        '6' -> KEY_6
        '7' -> KEY_7
        '8' -> KEY_8
        '9' -> KEY_9
        '0' -> KEY_0
        '-' -> KEY_MINUS
        '=' -> KEY_EQUAL
        'Q' -> KEY_Q
        'W' -> KEY_W
        'E' -> KEY_E
        'R' -> KEY_R
        'T' -> KEY_T
        'Y' -> KEY_Y
        'U' -> KEY_U
        'I' -> KEY_I
        'O' -> KEY_O
        'P' -> KEY_P
        '[' -> KEY_LEFTBRACE
        ']' -> KEY_RIGHTBRACE
        '\\' -> KEY_BACKSLASH
        'A' -> KEY_A
        'S' -> KEY_S
        'D' -> KEY_D
        'F' -> KEY_F
        'G' -> KEY_G
        'H' -> KEY_H
        'J' -> KEY_J
        'K' -> KEY_K
        'L' -> KEY_L
        ';' -> KEY_SEMICOLON
        '\'' -> KEY_APOSTROPHE
        'Z' -> KEY_Z
        'X' -> KEY_X
        'C' -> KEY_C
        'V' -> KEY_V
        'B' -> KEY_B
        'N' -> KEY_N
        'M' -> KEY_M
        ',' -> KEY_COMMA
        '.' -> KEY_DOT
        '/' -> KEY_SLASH
        else -> KEY_RESERVED
    }

    /** Android keycode -> Linux evdev scancode */
    fun keyCodeToScancode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_UNKNOWN -> KEY_RESERVED
        KeyEvent.KEYCODE_ESCAPE -> KEY_ESC
        KeyEvent.KEYCODE_1 -> KEY_1
        KeyEvent.KEYCODE_2 -> KEY_2
        KeyEvent.KEYCODE_3 -> KEY_3
        KeyEvent.KEYCODE_4 -> KEY_4
        KeyEvent.KEYCODE_5 -> KEY_5
        KeyEvent.KEYCODE_6 -> KEY_6
        KeyEvent.KEYCODE_7 -> KEY_7
        KeyEvent.KEYCODE_8 -> KEY_8
        KeyEvent.KEYCODE_9 -> KEY_9
        KeyEvent.KEYCODE_0 -> KEY_0
        KeyEvent.KEYCODE_MINUS -> KEY_MINUS
        KeyEvent.KEYCODE_EQUALS -> KEY_EQUAL
        KeyEvent.KEYCODE_DEL -> KEY_BACKSPACE
        KeyEvent.KEYCODE_TAB -> KEY_TAB
        KeyEvent.KEYCODE_Q -> KEY_Q
        KeyEvent.KEYCODE_W -> KEY_W
        KeyEvent.KEYCODE_E -> KEY_E
        KeyEvent.KEYCODE_R -> KEY_R
        KeyEvent.KEYCODE_T -> KEY_T
        KeyEvent.KEYCODE_Y -> KEY_Y
        KeyEvent.KEYCODE_U -> KEY_U
        KeyEvent.KEYCODE_I -> KEY_I
        KeyEvent.KEYCODE_O -> KEY_O
        KeyEvent.KEYCODE_P -> KEY_P
        KeyEvent.KEYCODE_LEFT_BRACKET -> KEY_LEFTBRACE
        KeyEvent.KEYCODE_RIGHT_BRACKET -> KEY_RIGHTBRACE
        KeyEvent.KEYCODE_ENTER -> KEY_ENTER
        KeyEvent.KEYCODE_CTRL_LEFT -> KEY_LEFTCTRL
        KeyEvent.KEYCODE_A -> KEY_A
        KeyEvent.KEYCODE_S -> KEY_S
        KeyEvent.KEYCODE_D -> KEY_D
        KeyEvent.KEYCODE_F -> KEY_F
        KeyEvent.KEYCODE_G -> KEY_G
        KeyEvent.KEYCODE_H -> KEY_H
        KeyEvent.KEYCODE_J -> KEY_J
        KeyEvent.KEYCODE_K -> KEY_K
        KeyEvent.KEYCODE_L -> KEY_L
        KeyEvent.KEYCODE_SEMICOLON -> KEY_SEMICOLON
        KeyEvent.KEYCODE_APOSTROPHE -> KEY_APOSTROPHE
        KeyEvent.KEYCODE_GRAVE -> KEY_GRAVE
        KeyEvent.KEYCODE_SHIFT_LEFT -> KEY_LEFTSHIFT
        KeyEvent.KEYCODE_BACKSLASH -> KEY_BACKSLASH
        KeyEvent.KEYCODE_Z -> KEY_Z
        KeyEvent.KEYCODE_X -> KEY_X
        KeyEvent.KEYCODE_C -> KEY_C
        KeyEvent.KEYCODE_V -> KEY_V
        KeyEvent.KEYCODE_B -> KEY_B
        KeyEvent.KEYCODE_N -> KEY_N
        KeyEvent.KEYCODE_M -> KEY_M
        KeyEvent.KEYCODE_COMMA -> KEY_COMMA
        KeyEvent.KEYCODE_PERIOD -> KEY_DOT
        KeyEvent.KEYCODE_SLASH -> KEY_SLASH
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KEY_RIGHTSHIFT
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> KEY_KPASTERISK
        KeyEvent.KEYCODE_ALT_LEFT -> KEY_LEFTALT
        KeyEvent.KEYCODE_SPACE -> KEY_SPACE
        KeyEvent.KEYCODE_CAPS_LOCK -> KEY_CAPSLOCK
        KeyEvent.KEYCODE_F1 -> KEY_F1
        KeyEvent.KEYCODE_F2 -> KEY_F2
        KeyEvent.KEYCODE_F3 -> KEY_F3
        KeyEvent.KEYCODE_F4 -> KEY_F4
        KeyEvent.KEYCODE_F5 -> KEY_F5
        KeyEvent.KEYCODE_F6 -> KEY_F6
        KeyEvent.KEYCODE_F7 -> KEY_F7
        KeyEvent.KEYCODE_F8 -> KEY_F8
        KeyEvent.KEYCODE_F9 -> KEY_F9
        KeyEvent.KEYCODE_F10 -> KEY_F10
        KeyEvent.KEYCODE_NUM_LOCK -> KEY_NUMLOCK
        KeyEvent.KEYCODE_SCROLL_LOCK -> KEY_SCROLLLOCK
        KeyEvent.KEYCODE_NUMPAD_7 -> KEY_KP7
        KeyEvent.KEYCODE_NUMPAD_8 -> KEY_KP8
        KeyEvent.KEYCODE_NUMPAD_9 -> KEY_KP9
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> KEY_KPMINUS
        KeyEvent.KEYCODE_NUMPAD_4 -> KEY_KP4
        KeyEvent.KEYCODE_NUMPAD_5 -> KEY_KP5
        KeyEvent.KEYCODE_NUMPAD_6 -> KEY_KP6
        KeyEvent.KEYCODE_NUMPAD_ADD -> KEY_KPPLUS
        KeyEvent.KEYCODE_NUMPAD_1 -> KEY_KP1
        KeyEvent.KEYCODE_NUMPAD_2 -> KEY_KP2
        KeyEvent.KEYCODE_NUMPAD_3 -> KEY_KP3
        KeyEvent.KEYCODE_NUMPAD_0 -> KEY_KP0
        KeyEvent.KEYCODE_NUMPAD_DOT -> KEY_KPDOT
        KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> KEY_ZENKAKUHANKAKU
        KeyEvent.KEYCODE_F11 -> KEY_F11
        KeyEvent.KEYCODE_F12 -> KEY_F12
        KeyEvent.KEYCODE_RO -> KEY_RO
        KeyEvent.KEYCODE_HENKAN -> KEY_HENKAN
        KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> KEY_KATAKANAHIRAGANA
        KeyEvent.KEYCODE_MUHENKAN -> KEY_MUHENKAN
        KeyEvent.KEYCODE_NUMPAD_ENTER -> KEY_KPENTER
        KeyEvent.KEYCODE_CTRL_RIGHT -> KEY_RIGHTCTRL
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> KEY_KPSLASH
        KeyEvent.KEYCODE_SYSRQ -> KEY_SYSRQ
        KeyEvent.KEYCODE_ALT_RIGHT -> KEY_RIGHTALT
        KeyEvent.KEYCODE_MOVE_HOME -> KEY_HOME
        KeyEvent.KEYCODE_DPAD_UP -> KEY_UP
        KeyEvent.KEYCODE_PAGE_UP -> KEY_PAGEUP
        KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT
        KeyEvent.KEYCODE_MOVE_END -> KEY_END
        KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN
        KeyEvent.KEYCODE_PAGE_DOWN -> KEY_PAGEDOWN
        KeyEvent.KEYCODE_INSERT -> KEY_INSERT
        KeyEvent.KEYCODE_FORWARD_DEL -> KEY_DELETE
        KeyEvent.KEYCODE_VOLUME_MUTE -> KEY_MUTE
        KeyEvent.KEYCODE_VOLUME_DOWN -> KEY_VOLUMEDOWN
        KeyEvent.KEYCODE_VOLUME_UP -> KEY_VOLUMEUP
        KeyEvent.KEYCODE_POWER -> KEY_POWER
        KeyEvent.KEYCODE_NUMPAD_EQUALS -> KEY_KPEQUAL
        KeyEvent.KEYCODE_BREAK -> KEY_PAUSE
        KeyEvent.KEYCODE_NUMPAD_COMMA -> KEY_KPCOMMA
        KeyEvent.KEYCODE_YEN -> KEY_YEN
        KeyEvent.KEYCODE_META_LEFT -> KEY_LEFTMETA
        KeyEvent.KEYCODE_META_RIGHT -> KEY_RIGHTMETA
        KeyEvent.KEYCODE_COPY -> KEY_COPY
        KeyEvent.KEYCODE_PASTE -> KEY_PASTE
        KeyEvent.KEYCODE_CUT -> KEY_CUT
        KeyEvent.KEYCODE_HELP -> KEY_HELP
        KeyEvent.KEYCODE_MENU -> KEY_MENU
        KeyEvent.KEYCODE_CALCULATOR -> KEY_CALC
        KeyEvent.KEYCODE_SLEEP -> KEY_SLEEP
        KeyEvent.KEYCODE_WAKEUP -> KEY_WAKEUP
        KeyEvent.KEYCODE_EXPLORER -> KEY_WWW
        KeyEvent.KEYCODE_ENVELOPE -> KEY_MAIL
        KeyEvent.KEYCODE_BOOKMARK -> KEY_BOOKMARKS
        KeyEvent.KEYCODE_MEDIA_NEXT -> KEY_NEXTSONG
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> KEY_PLAYPAUSE
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> KEY_PREVIOUSSONG
        KeyEvent.KEYCODE_REFRESH -> KEY_REFRESH
        KeyEvent.KEYCODE_SEARCH -> KEY_SEARCH
        KeyEvent.KEYCODE_MUTE -> KEY_MICMUTE
        else -> KEY_RESERVED
    }
}
