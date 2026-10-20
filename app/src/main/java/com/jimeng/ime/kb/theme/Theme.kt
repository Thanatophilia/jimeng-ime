/*
 * 从 fcitx5-android 的 data/theme 精简而来。
 *
 * 原版 Theme 是个 sealed class，带 Custom（背景图）和 Monet（动态取色）两套实现，
 * 还依赖 kotlinx.serialization + Parcelize。这里保留字段语义和 backgroundDrawable() 的
 * 行为，但只留内置主题——键盘要的是稳定可控的配色，不需要动态取色。
 *
 * 配色数值原样取自原版 ThemePreset.kt 的 PixelLight / PixelDark，
 * 这两套是 fcitx5 的默认主题，也就是主人看到的「原版样子」。
 */
package com.jimeng.ime.kb.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

data class Theme(
    val name: String,
    val isDark: Boolean,
    val backgroundColor: Int,
    val barColor: Int,
    val keyboardColor: Int,
    val keyBackgroundColor: Int,
    val keyTextColor: Int,
    val candidateTextColor: Int,
    val candidateLabelColor: Int,
    val candidateCommentColor: Int,
    val altKeyBackgroundColor: Int,
    val altKeyTextColor: Int,
    val accentKeyBackgroundColor: Int,
    val accentKeyTextColor: Int,
    val keyPressHighlightColor: Int,
    val keyShadowColor: Int,
    val popupBackgroundColor: Int,
    val popupTextColor: Int,
    val spaceBarColor: Int,
    val dividerColor: Int,
    val clipboardEntryColor: Int,
    val genericActiveBackgroundColor: Int,
    val genericActiveForegroundColor: Int,
) {
    fun backgroundDrawable(keyBorder: Boolean = false): Drawable =
        ColorDrawable(if (keyBorder) backgroundColor else keyboardColor)

    companion object {
        /** 原版 PixelLight */
        val PixelLight = Theme(
            name = "PixelLight",
            isDark = false,
            backgroundColor = 0xffeeeeee.toInt(),
            barColor = 0xffeeeeee.toInt(),
            keyboardColor = 0xfffafafa.toInt(),
            keyBackgroundColor = 0xffffffff.toInt(),
            keyTextColor = 0xff212121.toInt(),
            candidateTextColor = 0xff212121.toInt(),
            candidateLabelColor = 0xff212121.toInt(),
            candidateCommentColor = 0xff6e6e6e.toInt(),
            altKeyBackgroundColor = 0xffe1e1e1.toInt(),
            altKeyTextColor = 0xff6e6e6e.toInt(),
            accentKeyBackgroundColor = 0xff4285f4.toInt(),
            accentKeyTextColor = 0xffffffff.toInt(),
            keyPressHighlightColor = 0x1f000000,
            keyShadowColor = 0xffc2c2c2.toInt(),
            popupBackgroundColor = 0xffeeeeee.toInt(),
            popupTextColor = 0xff212121.toInt(),
            spaceBarColor = 0xffdbdbdb.toInt(),
            dividerColor = 0x1f000000,
            clipboardEntryColor = 0xffffffff.toInt(),
            genericActiveBackgroundColor = 0xff5e97f6.toInt(),
            genericActiveForegroundColor = 0xffffffff.toInt(),
        )

        /** 原版 PixelDark（也是原版默认主题） */
        val PixelDark = Theme(
            name = "PixelDark",
            isDark = true,
            backgroundColor = 0xff2d2d2d.toInt(),
            barColor = 0xff373737.toInt(),
            keyboardColor = 0xff2d2d2d.toInt(),
            keyBackgroundColor = 0xff464646.toInt(),
            keyTextColor = 0xfffafafa.toInt(),
            candidateTextColor = 0xfffafafa.toInt(),
            candidateLabelColor = 0xfffafafa.toInt(),
            candidateCommentColor = 0xffacacac.toInt(),
            altKeyBackgroundColor = 0xff373737.toInt(),
            altKeyTextColor = 0xffacacac.toInt(),
            accentKeyBackgroundColor = 0xff5e97f6.toInt(),
            accentKeyTextColor = 0xffffffff.toInt(),
            keyPressHighlightColor = 0x33ffffff,
            keyShadowColor = 0xff252525.toInt(),
            popupBackgroundColor = 0xff373737.toInt(),
            popupTextColor = 0xfffafafa.toInt(),
            spaceBarColor = 0xff4a4a4a.toInt(),
            dividerColor = 0x1fffffff,
            clipboardEntryColor = 0xff464646.toInt(),
            genericActiveBackgroundColor = 0xff5e97f6.toInt(),
            genericActiveForegroundColor = 0xfffafafa.toInt(),
        )

        /** 原版 MaterialLight，备用 */
        val MaterialLight = Theme(
            name = "MaterialLight",
            isDark = false,
            backgroundColor = 0xffeceff1.toInt(),
            barColor = 0xffe4e7e9.toInt(),
            keyboardColor = 0xffeceff1.toInt(),
            keyBackgroundColor = 0xfffbfbfc.toInt(),
            keyTextColor = 0xff37474f.toInt(),
            candidateTextColor = 0xff37474f.toInt(),
            candidateLabelColor = 0xff37474f.toInt(),
            candidateCommentColor = 0xff7a858a.toInt(),
            altKeyBackgroundColor = 0xffdfe2e4.toInt(),
            altKeyTextColor = 0xff7a858a.toInt(),
            accentKeyBackgroundColor = 0xff5cb5ab.toInt(),
            accentKeyTextColor = 0xffffffff.toInt(),
            keyPressHighlightColor = 0x1f000000,
            keyShadowColor = 0xffc0c3c4.toInt(),
            popupBackgroundColor = 0xffd9dbdd.toInt(),
            popupTextColor = 0xff37474f.toInt(),
            spaceBarColor = 0xffc9ced1.toInt(),
            dividerColor = 0x1f000000,
            clipboardEntryColor = 0xfffbfbfc.toInt(),
            genericActiveBackgroundColor = 0xff80cbc4.toInt(),
            genericActiveForegroundColor = 0xff37474f.toInt(),
        )

        /** 原版 MaterialDark，备用 */
        val MaterialDark = Theme(
            name = "MaterialDark",
            isDark = true,
            backgroundColor = 0xff263238.toInt(),
            barColor = 0xff21272b.toInt(),
            keyboardColor = 0xff263238.toInt(),
            keyBackgroundColor = 0xff404a50.toInt(),
            keyTextColor = 0xffd9dbdc.toInt(),
            candidateTextColor = 0xffd9dbdc.toInt(),
            candidateLabelColor = 0xffd9dbdc.toInt(),
            candidateCommentColor = 0xffadb1b3.toInt(),
            altKeyBackgroundColor = 0xff313c42.toInt(),
            altKeyTextColor = 0xffadb1b3.toInt(),
            accentKeyBackgroundColor = 0xff6eaca8.toInt(),
            accentKeyTextColor = 0xffffffff.toInt(),
            keyPressHighlightColor = 0x33ffffff,
            keyShadowColor = 0xff1f292e.toInt(),
            popupBackgroundColor = 0xff3c474c.toInt(),
            popupTextColor = 0xffffffff.toInt(),
            spaceBarColor = 0xff3b464c.toInt(),
            dividerColor = 0x1fffffff,
            clipboardEntryColor = 0xff404a50.toInt(),
            genericActiveBackgroundColor = 0xff4db6ac.toInt(),
            genericActiveForegroundColor = 0xffffffff.toInt(),
        )

        fun of(context: Context): Theme {
            val night = (context.resources.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (night) PixelDark else PixelLight
        }
    }
}