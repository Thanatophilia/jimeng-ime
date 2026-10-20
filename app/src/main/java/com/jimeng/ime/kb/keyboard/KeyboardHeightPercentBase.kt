/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package com.jimeng.ime.kb.keyboard

import com.jimeng.ime.R
import com.jimeng.ime.kb.prefs.ManagedPreferenceEnum

enum class KeyboardHeightPercentBase(override val stringRes: Int) : ManagedPreferenceEnum {
    DisplayMetrics(R.string.display_metrics),
    RealSize(R.string.real_size)
}
