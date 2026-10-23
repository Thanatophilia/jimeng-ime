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

}
