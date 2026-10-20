/*
 * 从 fcitx5-android 的 data/theme/ThemePrefs.kt 精简而来。
 *
 * 保留键盘实际读取的按键外观参数（边距、圆角、边框、水波纹、标点位置），
 * 去掉所有 ManagedPreferenceUi / PreferenceScreen 相关代码。
 */
package com.jimeng.ime.kb.theme

import android.content.Context
import android.content.SharedPreferences
import com.jimeng.ime.kb.prefs.ManagedPreference
import com.jimeng.ime.kb.prefs.ManagedPreferenceEnum

class ThemePrefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.getSharedPreferences("jimeng_theme", Context.MODE_PRIVATE)

    // ---- 按键外观：默认值全部对齐 fcitx5 原版 ----

    /** 按下时显示按键边框 */
    val keyBorder = ManagedPreference.PBool(sp, "key_border", false)

    /** 边框用描边（而不是整块色） */
    val keyBorderStroke = ManagedPreference.PBool(sp, "key_border_stroke", false)

    /** 水波纹按压效果。开了会更接近 Material 手感 */
    val keyRippleEffect = ManagedPreference.PBool(sp, "key_ripple_effect", true)

    /** 键的水平间距（dp），原版默认 3 */
    val keyHorizontalMargin = ManagedPreference.PInt(sp, "key_horizontal_margin", 3)
    val keyHorizontalMarginLandscape = ManagedPreference.PInt(sp, "key_horizontal_margin_landscape", 3)

    /** 键的垂直间距（dp），原版默认 7 */
    val keyVerticalMargin = ManagedPreference.PInt(sp, "key_vertical_margin", 7)
    val keyVerticalMarginLandscape = ManagedPreference.PInt(sp, "key_vertical_margin_landscape", 4)

    /** 键的圆角（dp），原版默认 4 */
    val keyRadius = ManagedPreference.PInt(sp, "key_radius", 4)

    /** 标点（角标）在字母键上的位置，原版默认 Bottom */
    val punctuationPosition = ManagedPreference.PEnum(
        sp, "punctuation_position", PunctuationPosition.Bottom,
        PunctuationPosition.entries.toTypedArray()
    )

    enum class PunctuationPosition : ManagedPreferenceEnum {
        None, Bottom, TopRight;

        // 本工程没有对应的 string 资源（原版用于设置页标题），
        // 保留字段是为了兼容接口签名
        override val stringRes: Int get() = 0
    }
}