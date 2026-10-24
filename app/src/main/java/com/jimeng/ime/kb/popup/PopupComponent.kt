/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb.popup

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jimeng.ime.kb.keyboard.KeyAction
import com.jimeng.ime.kb.keyboard.KeyDef
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList

/**
 * 气泡与长按面板的总管（照搬 fcitx5-android 的 PopupComponent）。
 *
 * 它维护一个覆盖整个输入法视图的 [root] 容器，所有气泡和面板都挂在这个容器里。
 * 关键点：**root 与 KeyView.bounds 用的是同一套窗口坐标**，
 * 所以定位只需要拿双方坐标互相减，不涉及任何 PopupWindow / showAtLocation。
 *
 * 与 fcitx5 的差异只有一处：原版靠 `manager.must()` 做依赖注入，
 * 这里改成构造函数直接收 context / theme。
 */
class PopupComponent(
    private val context: Context,
    private val theme: Theme,
    /**
     * 当前是不是全角标点模式。
     *
     * 长按九宫格里的标点得跟着这个开关走：全角显示 、，半角显示 \。
     * 用回调而不是传布尔值，因为用户在键盘上切全半角时，
     * PopupComponent 是复用的、不会重建，只有每次弹出时现读才是最新状态。
     */
    private val isFullShape: () -> Boolean = { false }
) {

    // ---- 尺寸（数值原样取自 fcitx5-android）----
    private val popupWidth by lazy { context.dp(38) }
    private val popupHeight by lazy { context.dp(116) }
    private val popupKeyHeight by lazy { context.dp(48) }
    private val popupRadius by lazy { context.dp(ThemeManager.prefs.keyRadius.getValue()).toFloat() }

    /** 键与键之间的竖直边距，气泡底边要减去它 */
    private val keyBottomMargin by lazy { context.dp(ThemeManager.prefs.keyVerticalMargin.getValue()) }

    /** 气泡至少停留 100ms，避免快速点按时闪一下 */
    private val hideThreshold = 100L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val showingEntryUi = HashMap<Int, PopupEntryUi>()
    private val dismissRunnables = HashMap<Int, Runnable>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<Int, PopupContainerUi>()

    // ---- root 的窗口坐标缓存 ----
    private val rootLocation = intArrayOf(0, 0)
    private val rootBounds = Rect()

    val root: FrameLayout by lazy {
        context.frameLayout {
            // 让 (0, 0) 落在左上角，避免 RTL 布局把坐标翻过来
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false
            // 气泡和面板可能要探出这一层的边界（比如第一行键的气泡要往上顶）
            clipChildren = false
            clipToPadding = false

            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                v.getLocationInWindow(rootLocation)
                val width = right - left
                val height = bottom - top
                rootBounds.set(
                    rootLocation[0],
                    rootLocation[1],
                    rootLocation[0] + width,
                    rootLocation[1] + height
                )
            }
        }
    }

    // ------------------------------------------------------------ 预览气泡

    /**
     * root 的位置会随输入法窗口移动（候选栏高度变化、旋转等），
     * 每次显示前实时取一次，避免用过期坐标。
     */
    private fun refreshRootBounds() {
        root.getLocationInWindow(rootLocation)
        rootBounds.set(
            rootLocation[0],
            rootLocation[1],
            rootLocation[0] + root.width,
            rootLocation[1] + root.height
        )
    }

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        refreshRootBounds()
        // 同一个键的气泡已经在显示 → 只更新文字和时间戳
        showingEntryUi[viewId]?.apply {
            dismissRunnables.remove(viewId)?.also { mainHandler.removeCallbacks(it) }
            lastShowTime = System.currentTimeMillis()
            setText(content)
            return
        }
        val popup = (freeEntryUi.poll()
            ?: PopupEntryUi(context, theme, popupKeyHeight, popupRadius)).apply {
            lastShowTime = System.currentTimeMillis()
            setText(content)
        }
        popup.root.layoutParams = FrameLayout.LayoutParams(popupWidth, popupHeight).apply {
            // 气泡底边对齐按键边框底边
            // 注意：bounds 和 rootBounds 都是窗口坐标，要减掉 root 自己的偏移
            topMargin = bounds.bottom - popupHeight - keyBottomMargin - rootBounds.top
            leftMargin = (bounds.left + bounds.right - popupWidth) / 2 - rootBounds.left
        }
        // 加进 root 之前先确保它没有别的父容器
        if (popup.root.parent == null) {
            root.addView(popup.root)
        } else if (popup.root.parent !== root) {
            (popup.root.parent as? ViewGroup)?.removeView(popup.root)
            root.addView(popup.root)
        }
        showingEntryUi[viewId] = popup
    }

    private fun updatePopup(viewId: Int, content: String) {
        showingEntryUi[viewId]?.setText(content)
    }

    // ------------------------------------------------------------ 九宫格小键盘

    private fun showKeyboard(viewId: Int, keyboard: KeyDef.Popup.Keyboard, bounds: Rect) {
        refreshRootBounds()
        val keys: Array<String>
        val labels: Array<String>
        when (keyboard) {
            is KeyDef.Popup.Keyboard.Preset -> {
                val preset = PopupPreset[keyboard.label] ?: return
                keys = preset
                // 九宫格里显示当前模式的标点（全角中文标点 / 半角原样），
                // 但按下去发的仍是半角字符，交给 rime 的 punctuator 决定最终上屏什么。
                // 对应 fcitx5 的 PunctuationComponent.transform + transformPunctuation 开关。
                labels = if (keyboard.transformPunctuation) {
                    val full = isFullShape()
                    Array(keys.size) { PunctuationMapping.transform(keys[it], full) }
                } else keys
            }
            is KeyDef.Popup.Keyboard.Explicit -> {
                keys = keyboard.items
                labels = keyboard.items
            }
        }
        // 先把预览气泡清空（保留一个空壳当定位基准），再在上方铺格子
        showingEntryUi[viewId]?.setText("") ?: showPopup(viewId, "", bounds)

        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            popupWidth,
            popupKeyHeight,
            popupHeight + keyBottomMargin,
            keys,
            labels
        )
        showPopupContainer(viewId, keyboardUi)
    }

    // ------------------------------------------------------------ 圆形菜单

    private fun showMenu(viewId: Int, menu: KeyDef.Popup.Menu, bounds: Rect) {
        refreshRootBounds()
        showingEntryUi[viewId]?.let {
            dismissPopupEntry(viewId, it)
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            menu.items
        )
        showPopupContainer(viewId, menuUi)
    }

    private fun showPopupContainer(viewId: Int, ui: PopupContainerUi) {
        root.apply {
            add(ui.root, lParams {
                leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left
                topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top
            })
        }
        showingContainerUi[viewId] = ui
    }

    // ------------------------------------------------------------ 手势接口

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean {
        return showingContainerUi[viewId]?.changeFocus(x, y) ?: false
    }

    private fun triggerFocused(viewId: Int): KeyAction? {
        return showingContainerUi[viewId]?.onTrigger()
    }

    // ------------------------------------------------------------ 收尾

    private fun dismissPopup(viewId: Int) {
        dismissPopupContainer(viewId)
        showingEntryUi[viewId]?.also {
            val timeLeft = it.lastShowTime + hideThreshold - System.currentTimeMillis()
            if (timeLeft <= 0L) {
                dismissPopupEntry(viewId, it)
            } else {
                val runnable = Runnable {
                    dismissPopupEntry(viewId, it)
                    dismissRunnables.remove(viewId)
                }
                dismissRunnables[viewId] = runnable
                mainHandler.postDelayed(runnable, timeLeft)
            }
        }
    }

    private fun dismissPopupContainer(viewId: Int) {
        showingContainerUi.remove(viewId)?.also {
            root.removeView(it.root)
        }
    }

    private fun dismissPopupEntry(viewId: Int, popup: PopupEntryUi) {
        showingEntryUi.remove(viewId)
        root.removeView(popup.root)
        freeEntryUi.add(popup)
    }

    // ------------------------------------------------------------ 对外监听器

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(viewId, menu, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }

}