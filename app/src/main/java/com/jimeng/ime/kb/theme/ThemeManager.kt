/*
 * 从 fcitx5-android 的 data/theme/ThemeManager.kt 精简而来。
 *
 * 原版负责：内置主题注册、自定义主题文件加载、Monet 动态取色、主题切换的事件广播。
 * 这里只保留「当前主题 + 主题偏好 + 变更监听」，主题由系统深色模式决定。
 */
package com.jimeng.ime.kb.theme

import android.content.Context

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    private val listeners = mutableSetOf<OnThemeChangeListener>()

    @Volatile
    private var _prefs: ThemePrefs? = null

    val prefs: ThemePrefs
        get() = _prefs ?: throw IllegalStateException("ThemeManager 未初始化")

    @Volatile
    private var _activeTheme: Theme? = null

    var activeTheme: Theme
        get() = _activeTheme ?: throw IllegalStateException("ThemeManager 未初始化")
        set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            listeners.forEach { it.onThemeChange(value) }
        }

    fun init(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (_prefs == null) {
            synchronized(this) {
                if (_prefs == null) _prefs = ThemePrefs(appCtx)
            }
        }
        if (_activeTheme == null) {
            _activeTheme = Theme.of(appCtx)
        }
    }

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        listeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        listeners.remove(listener)
    }

    /** 屏幕旋转 / 深色模式切换时调用 */
    fun updateFromContext(ctx: Context) {
        activeTheme = Theme.of(ctx)
    }
}