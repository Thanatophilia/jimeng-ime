/*
 * 从 fcitx5-android 的 core/InputMethodEntry.kt 精简而来。
 *
 * 原版是从 fcitx5 守护进程拿到的输入法条目（含唯一名字、显示名、语言、子模式等），
 * 键盘层只用到 displayName 和 subMode（显示在空格键上）。
 * 本工程引擎是 librime，一个方案就是一个输入法，所以这里用最小字段表达。
 */
package com.jimeng.ime.kb.core

data class InputMethodEntry(
    /** 引擎里的内部标识，如 "wanxiang" */
    val name: String,
    /** 显示名，如 "万象拼音" */
    val displayName: String,
    /** 子模式，如简体/繁体；空格键上括号里那个 */
    val subMode: SubMode = SubMode.None,
    /** 语言代码，如 "zh_CN" */
    val languageCode: String = "",
) {
    /** 对应原版 InputMethodEntry.SubMode */
    data class SubMode(
        val name: String = "",
        val label: String = "",
    ) {
        companion object {
            val None = SubMode()
        }
    }
}