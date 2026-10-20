/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb.keyboard

import com.jimeng.ime.R
import com.jimeng.ime.kb.prefs.ManagedPreferenceEnum

enum class LangSwitchBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    Enumerate(R.string.space_behavior_enumerate),
    ToggleActivate(R.string.space_behavior_activate),
    NextInputMethodApp(R.string.lang_switch_behavior_next_ime_app);
}
