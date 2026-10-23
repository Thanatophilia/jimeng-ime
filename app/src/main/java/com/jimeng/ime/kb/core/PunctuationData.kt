/*
 * 标点映射（对应 fcitx5 的 PunctuationComponent）。
 *
 * 键盘上的符号角标要跟「真正打出来的字符」一致——显示 \ 却打出 、
 * 那种错位，用户一眼就看出来不对。
 *
 * fcitx5 原版的做法是从自己的标点插件读一张表，转写进键盘角标；
 * 本工程引擎是 librime，标点由方案里的 punctuator 决定，
 * 所以这两张表直接对齐 assets/rime/default.yaml 的 full_shape / half_shape。
 *
 * 换方案时如果那个方案的 punctuator 不一样，这两张表要跟着改。
 */
package com.jimeng.ime.kb.core

object PunctuationData {

    /**
     * 半角模式：符号键打出来就是它本身，一个字都不换。
     *
     * 空表不是「忘了写」，是**故意**的：半角就是半角，
     * 键上显示的 `,` 打出来就是 `,`。以前这里塞着半角→中文全角的映射，
     * 那是雾凇旧表的语义（旧的 half_shape 其实是「中文标点」），
     * 用户要真半角之后这张表就该空着。
     */
    val HalfShape: Map<String, String> = emptyMap()

    /**
     * 全角模式 → 中文标点。
     *
     * 和 default.yaml full_shape 一一对应；pair 类型（引号）取第一个，
     * 这与 fcitx5「use first entry as mapping value」的做法一致。
     * 值与原字符相同的项不用列。
     */
    val FullShape: Map<String, String> = mapOf(
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
        "`" to "｀",
        "!" to "！",
        "$" to "￥",
        "^" to "……",
        "(" to "（",
        ")" to "）",
        "_" to "——",
        "[" to "「",
        "]" to "」",
        "{" to "『",
        "}" to "』",
        "@" to "＠",
        "#" to "＃",
        "%" to "％",
        "&" to "＆",
        "*" to "＊",
        "-" to "－",
        "+" to "＋",
        "=" to "＝",
        "/" to "／",
        "~" to "～"
    )

    /** 按当前模式取表：全角给 [FullShape]，半角给 [HalfShape]（空表=原样） */
    fun forFullShape(fullShape: Boolean): Map<String, String> =
        if (fullShape) FullShape else HalfShape
}
