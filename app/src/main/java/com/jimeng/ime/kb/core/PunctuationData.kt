/*
 * 标点映射（对应 fcitx5 的 PunctuationComponent）。
 *
 * 键盘上的符号角标是「半角原字符」，但中文态下引擎会把它们换成全角。
 * 如果键盘照原字符显示，就会出现「显示 \ 却打出 、」这种错位。
 *
 * fcitx5 原版的做法是从自己的标点插件读一张表，转写进键盘角标；
 * 本工程引擎是 librime，标点由方案里的 punctuator 决定，
 * 所以这张表直接对齐 assets/rime/default.yaml 的 half_shape。
 *
 * 换方案时如果那个方案的 half_shape 不一样，这张表要跟着改。
 */
package com.jimeng.ime.kb.core

object PunctuationData {

    /**
     * 半角 -> 中文态的显示字符。
     *
     * 和 default.yaml half_shape 一一对应；pair 类型（引号）取第一个，
     * 这与 fcitx5「use first entry as mapping value」的做法一致。
     * 值与原字符相同的项不用列。
     */
    val HalfShape: Map<String, String> = mapOf(
        "," to "，",
        "." to "。",
        "<" to "《",
        ">" to "》",
        "?" to "？",
        ";" to "；",
        ":" to "：",
        "'" to "‘",
        "\"" to "“",
        "\\" to "、",
        "`" to "·",
        "!" to "！",
        "$" to "¥",
        "^" to "……",
        "(" to "（",
        ")" to "）",
        "_" to "——",
        "[" to "【",
        "]" to "】",
        "{" to "「",
        "}" to "」"
    )
}