/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * 照抄自 fcitx5-android:
 *   app/src/main/java/org/fcitx/fcitx5/android/core/FcitxEvent.kt
 * 原版权：Copyright 2021-2023 Fcitx5 for Android Contributors
 *
 * 只搬 InputPanelEvent.Data 这一个，因为 PreeditUi.kt 只依赖它。
 * 字段名、构造器、equals/hashCode 全部保留原样，
 * 唯一差异：去掉 tabs: Array<CandidateAction>（本项目 rime 侧没有候选动作标签）。
 */
package com.jimeng.ime.core

/**
 * 照抄自 fcitx5-android `core/FcitxEvent.kt`。
 *
 * 原版是 `open class FcitxEvent<T>(override val data: T)`，继承自更上层的
 * `FcitxEventBase`；本项目没有那一层基类，`data` 直接就是本类字段，
 * 所以 `InputPanelEvent` 里的 `override` 必须去掉。
 * 其余（Data 的三个字段、equals/hashCode）与原版一致。
 */
open class FcitxEvent<T>(val data: T) {

    /* 原版继承自 FcitxEventBase，data 是 override；本项目没有基类，
       所以这里用 val panel 承载，父类泛型写全限定名（原版也是这么写的：
       `: FcitxEvent<FcitxEvent.InputPanelEvent.Data>(data)`）。 */
    data class InputPanelEvent(val panel: Data) : FcitxEvent<FcitxEvent.InputPanelEvent.Data>(panel) {

        data class Data(
            val preedit: FormattedText,
            val auxUp: FormattedText,
            val auxDown: FormattedText
        ) {
            constructor() : this(
                FormattedText.Empty,
                FormattedText.Empty,
                FormattedText.Empty
            )

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Data

                if (preedit != other.preedit) return false
                if (auxUp != other.auxUp) return false
                if (auxDown != other.auxDown) return false

                return true
            }

            override fun hashCode(): Int {
                var result = preedit.hashCode()
                result = 31 * result + auxUp.hashCode()
                result = 31 * result + auxDown.hashCode()
                return result
            }
        }
    }
}
