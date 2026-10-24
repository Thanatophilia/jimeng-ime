/*
 * 标点映射（长按九宫格里的标点显示用）。
 *
 * 对应 fcitx5-android 的 PunctuationComponent.transform()：
 * 长按字母键弹出的九宫格里，标点**显示**成当前模式的样子，
 * 但按下去拿到的仍然是半角字符本身，由引擎去决定最终上屏什么。
 *
 * 关键一点：显示必须跟着「标点全半角」开关走。
 * 全角模式下 `\` 显示成 `、`，半角模式下就该原样显示 `\`——
 * 半角模式的含义就是「显示什么、打出来就是什么」，
 * 这时候再把它画成中文标点，就成了「看着是 、，按下去却是 \」的错位。
 */
package com.jimeng.ime.kb.popup

import com.jimeng.ime.kb.core.PunctuationData

object PunctuationMapping {
    /**
     * 全角模式下的显示替换表。
     *
     * 和键盘角标用的是同一份数据（[PunctuationData.FullShape]），
     * 这样长按九宫格和键上角标永远一致，不会各说各话。
     */
    private val fullShapeMap: Map<String, String> = PunctuationData.FullShape

    /**
     * @param fullShape 当前是不是全角标点模式。
     *   半角模式直接原样返回——半角就是半角，不换任何字。
     *   全角模式按 [PunctuationData.FullShape] 换成中文标点，表里没有的原样返回。
     */
    fun transform(text: String, fullShape: Boolean): String =
        if (fullShape) fullShapeMap[text] ?: text else text
}