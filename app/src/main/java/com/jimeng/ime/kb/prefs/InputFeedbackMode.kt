package com.jimeng.ime.kb.prefs

import com.jimeng.ime.R

/**
 * 对应原版 fcitx5-android data/InputFeedbacks.kt 里的 InputFeedbackMode。
 * 原版嵌在 InputFeedbacks 对象里，因为本工程要给 AppPrefs 引用，单独抽出来。
 */
enum class InputFeedbackMode(override val stringRes: Int) : ManagedPreferenceEnum {
    FollowingSystem(R.string.following_system_settings),
    Enabled(R.string.enabled),
    Disabled(R.string.disabled);
}
