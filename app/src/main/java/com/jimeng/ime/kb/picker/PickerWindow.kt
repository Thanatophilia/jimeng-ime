/*
 * 从 fcitx5-android 的 input/picker/PickerWindow.kt 精简而来。
 *
 * 原版是个完整的符号/表情选择窗口（InputWindow 子类，带窗口管理器、主题、候选联动）。
 * 键盘层只用到它的 Key 枚举（LayoutSwitchKey 用它标记要切到哪个面板），
 * 所以这里只保留枚举定义，真正的面板由 JimengIME 自己实现。
 */
package com.jimeng.ime.kb.picker

object PickerWindow {
    enum class Key {
        Symbol,
        Emoji,
        Emoticon
    }
}