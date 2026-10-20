/*
 * 标点映射（长按九宫格里的标点显示用）。
 *
 * 对应 fcitx5-android 的 PunctuationComponent.transform()：
 * 长按字母键弹出的九宫格里，标点**显示**成当前语言的样子（中文态显示全角），
 * 但按下去拿到的仍然是半角字符本身，由引擎去决定最终上屏什么。
 *
 * 数据来源：本项目 assets/rime/default.yaml 的 punctuator.half_shape。
 * 那里是雾凇拼音的半角标点表——用户按半角键，rime 自己转成全角上屏。
 * 界面显示跟它对齐，才不会出现「看着是全角、按下去却是半角」的错位感。
 */
package com.jimeng.ime.kb.popup

object PunctuationMapping {

    /**
     * 半角 → 显示用（中文标点）。
     *
     * 只收 half_shape 里能一一对应的项。带 pair 的（引号 ' 和 "）
     * 不收，因为它们没有固定单值；带多个候选的（[ ] { } < > 等）
     * 取第一个，跟 fcitx5 的「use first entry as mapping value」一致。
     */
    private val halfShape: Map<String, String> = mapOf(
        "," to "，",
        "." to "。",
        "<" to "《",
        ">" to "》",
        "/" to "/",
        "?" to "？",
        ";" to "；",
        ":" to "：",
        "\\" to "、",
        "|" to "|",
        "`" to "·",
        "~" to "~",
        "!" to "！",
        "@" to "@",
        "#" to "#",
        "%" to "%",
        "$" to "¥",
        "^" to "……",
        "&" to "&",
        "*" to "*",
        "(" to "（",
        ")" to "）",
        "-" to "-",
        "_" to "——",
        "+" to "+",
        "=" to "=",
        "[" to "【",
        "]" to "】",
        "{" to "「",
        "}" to "」",
    )

    /** 没有映射就原样返回 */
    fun transform(text: String): String = halfShape[text] ?: text
}
