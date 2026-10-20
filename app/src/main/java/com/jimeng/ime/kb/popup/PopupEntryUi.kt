/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb.popup

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import com.jimeng.ime.kb.AutoScaleTextView
import com.jimeng.ime.kb.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.gravityCenter

/**
 * 按键预览气泡。
 *
 * 结构和 fcitx5-android 完全一致：
 * - 整体高度 = popupHeight（116dp），宽度 popupWidth（38dp）
 * - 文字区只有 keyHeight（48dp），贴着气泡**顶部**
 * - 气泡底部对齐按键底部
 *
 * 于是文字浮在手指上方很远的地方，气泡下半段是一条"柄"，
 * 看起来像气泡从键里长出来，这正是原版的手感来源。
 */
class PopupEntryUi(
    override val ctx: Context,
    theme: Theme,
    keyHeight: Int,
    radius: Float
) : Ui {

    var lastShowTime = -1L

    val textView = view(::AutoScaleTextView) {
        textSize = 23f
        gravity = gravityCenter
        setTextColor(theme.popupTextColor)
    }

    override val root = constraintLayout {
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.popupBackgroundColor)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        add(textView, lParams(matchParent, keyHeight) {
            topOfParent()
            centerHorizontally()
        })
    }

    fun setText(text: String) {
        textView.text = text
    }
}