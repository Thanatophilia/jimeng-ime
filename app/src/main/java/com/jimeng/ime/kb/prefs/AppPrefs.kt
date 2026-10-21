/*
 * 从 fcitx5-android 的 data/prefs 精简而来。
 * 原版的 AppPrefs 有 444 行、依赖一堆 Picker/Emoji/Candidate 配置，
 * 这里只保留键盘层实际读写的字段，值全部走 SharedPreferences，语义不变。
 *
 * 与 ThemePrefs 的分工也和原版一致：
 *   - 主题/按键外观 -> ThemePrefs
 *   - 输入行为（滑动手势、弹窗、震动）-> AppPrefs
 */
package com.jimeng.ime.kb.prefs

import android.content.Context
import android.content.SharedPreferences
import com.jimeng.ime.kb.keyboard.SwipeSymbolDirection

// ---------------------------------------------------------------- 基础框架

/** 对应原版 ManagedPreferenceEnum */
interface ManagedPreferenceEnum {
    val stringRes: Int
}

/**
 * 对应原版 ManagedPreference。
 * 保留了 getValue/setValue/属性委托/变更监听这套语义，去掉 UI 部分（ManagedPreferenceUi）。
 */
abstract class ManagedPreference<T : Any> : kotlin.properties.ReadOnlyProperty<Any?, T> {

    fun interface OnChangeListener<T : Any> {
        fun onChanged(pref: ManagedPreference<T>, newValue: T)
    }

    abstract fun getValue(): T
    abstract fun setValue(value: T)

    private val listeners = mutableSetOf<OnChangeListener<T>>()
    private var listenersInited = false

    fun registerOnChangeListener(listener: OnChangeListener<T>) {
        if (!listenersInited) listenersInited = true
        listeners.add(listener)
    }

    fun unregisterOnChangeListener(listener: OnChangeListener<T>) {
        listeners.remove(listener)
    }

    /**
     * 值写完之后通知所有监听者。
     *
     * 之前 setValue 只往 SharedPreferences 里写、从不回调，监听器注册了也白搭，
     * 设置页改完必须重开键盘才生效。照原版语义补上这一环：
     * 写完立刻 broadcast 给监听者，正在显示的键盘当场就能变。
     */
    protected fun notifyChanged(value: T) {
        if (listeners.isEmpty()) return
        // 复制一份再遍历：回调里可能反注册自己（键盘销毁），直接遍历会 ConcurrentModificationException
        listeners.toList().forEach { it.onChanged(this, value) }
    }

    override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = getValue()

    class PBool(
        private val sp: SharedPreferences,
        private val key: String,
        private val def: Boolean
    ) : ManagedPreference<Boolean>() {
        override fun getValue() = sp.getBoolean(key, def)
        override fun setValue(value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
            notifyChanged(value)
        }
    }

    class PInt(
        private val sp: SharedPreferences,
        private val key: String,
        private val def: Int
    ) : ManagedPreference<Int>() {
        override fun getValue() = sp.getInt(key, def)
        override fun setValue(value: Int) {
            sp.edit().putInt(key, value).apply()
            notifyChanged(value)
        }
    }

    class PString(
        private val sp: SharedPreferences,
        private val key: String,
        private val def: String
    ) : ManagedPreference<String>() {
        override fun getValue() = sp.getString(key, def) ?: def
        override fun setValue(value: String) {
            sp.edit().putString(key, value).apply()
            notifyChanged(value)
        }
    }

    /** 存成 String 的枚举 */
    class PEnum<E : Enum<E>>(
        private val sp: SharedPreferences,
        private val key: String,
        private val def: E,
        private val values: Array<E>
    ) : ManagedPreference<E>() {
        override fun getValue(): E {
            val name = sp.getString(key, def.name) ?: def.name
            return values.firstOrNull { it.name == name } ?: def
        }

        override fun setValue(value: E) {
            sp.edit().putString(key, value.name).apply()
            notifyChanged(value)
        }
    }
}

// ---------------------------------------------------------------- AppPrefs

/**
 * 键盘行为偏好。
 *
 * 原版里这些值大多跟着设置页走，这里全部给「用户日常最舒服」的默认值，
 * 同时保留 setValue 入口，将来接设置页时直接改就行了。
 */
class AppPrefs private constructor(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.getSharedPreferences("jimeng_keyboard", Context.MODE_PRIVATE)

    inner class Internal {
        val lastSymbolLayout = ManagedPreference.PString(sp, "last_symbol_layout", "Symbol")
        val lastPickerType = ManagedPreference.PString(sp, "last_picker_type", "Emoji")
    }

    inner class Advanced {
        // 原版用来绕过 vivo 的按键丢事件问题，ColorOS 上不需要，留个开关
        val vivoKeypressWorkaround =
            ManagedPreference.PBool(sp, "vivo_keypress_workaround", false)
    }

    inner class Keyboard {
        // ---- 弹窗 / 手势 ----
        /** 按键按下时是否弹预览气泡。原版默认关 */
        val popupOnKeyPress = ManagedPreference.PBool(sp, "popup_on_keypress", true)

        /** 扩大按键热区（吸附到最近的键） */
        val expandKeypressArea = ManagedPreference.PBool(sp, "expand_keypress_area", true)

        /** 上滑符号方向：上 / 下 */
        val swipeSymbolDirection =
            ManagedPreference.PEnum(sp, "swipe_symbol_direction", SwipeSymbolDirection.Up, SwipeSymbolDirection.entries.toTypedArray())

        /** 空格左右滑移动光标 */
        val spaceSwipeMoveCursor = ManagedPreference.PBool(sp, "space_swipe_move_cursor", true)

        /** 长按触发的延迟（毫秒）。照 fcitx5 原版：默认 300，范围 100~700，步进 10 */
        val longPressDelay = ManagedPreference.PInt(sp, "long_press_delay", 300)

        /** 是否显示中英切换键 */
        val showLangSwitchKey = ManagedPreference.PBool(sp, "show_lang_switch_key", true)

        /** 字母键是否始终大写显示 */
        val keepLettersUppercase = ManagedPreference.PBool(sp, "keep_letters_uppercase", true)

        /** 按住重复输入时是否震动 */
        val hapticOnRepeat = ManagedPreference.PBool(sp, "haptic_on_repeat", false)

        // ---- 输入反馈（对应 InputFeedbacks）----
        val hapticOnKeyPress = ManagedPreference.PEnum(
            sp, "haptic_on_keypress", InputFeedbackMode.FollowingSystem,
            InputFeedbackMode.entries.toTypedArray()
        )
        val hapticOnKeyUp = ManagedPreference.PBool(sp, "haptic_on_keyup", false)
        val soundOnKeyPress = ManagedPreference.PEnum(
            sp, "sound_on_keypress", InputFeedbackMode.FollowingSystem,
            InputFeedbackMode.entries.toTypedArray()
        )
        /** 0 表示跟随系统音量 */
        val soundOnKeyPressVolume = ManagedPreference.PInt(sp, "sound_on_keypress_volume", 0)

        /** 0 表示走系统默认震动，不用自定义时长 */
        val buttonPressVibrationMilliseconds =
            ManagedPreference.PInt(sp, "button_vibration_press_ms", 0)
        val buttonLongPressVibrationMilliseconds =
            ManagedPreference.PInt(sp, "button_vibration_long_press_ms", 0)
        val buttonPressVibrationAmplitude =
            ManagedPreference.PInt(sp, "button_vibration_press_amp", 0)
        val buttonLongPressVibrationAmplitude =
            ManagedPreference.PInt(sp, "button_vibration_long_press_amp", 0)

        // ---- 键盘尺寸（对应原版 InputView 的 keyboardHeightPx）----
        /** 竖屏键盘高度，屏高的百分比 */
        val keyboardHeightPercent = ManagedPreference.PInt(sp, "keyboard_height_percent", 34)

        /** 横屏键盘高度，屏高的百分比 */
        val keyboardHeightPercentLandscape =
            ManagedPreference.PInt(sp, "keyboard_height_percent_landscape", 49)

        /** 键盘左右留白（dp） */
        val keyboardSidePadding = ManagedPreference.PInt(sp, "keyboard_side_padding", 0)
        val keyboardSidePaddingLandscape =
            ManagedPreference.PInt(sp, "keyboard_side_padding_landscape", 0)

        /** 键盘底部安全区留白（dp），全面屏手势条处 */
        val keyboardBottomPadding = ManagedPreference.PInt(sp, "keyboard_bottom_padding", 0)
        val keyboardBottomPaddingLandscape =
            ManagedPreference.PInt(sp, "keyboard_bottom_padding_landscape", 0)
    }

    val internal = Internal()
    val advanced = Advanced()
    val keyboard = Keyboard()

    companion object {
        @Volatile
        private var instance: AppPrefs? = null

        fun getInstance(): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(AppHolder2.ctx).also { instance = it }
            }

        fun init(ctx: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        AppHolder2.ctx = ctx.applicationContext
                        instance = AppPrefs(ctx.applicationContext)
                    }
                }
            }
        }
    }
}

/** 避免和 utils.AppHolder 循环依赖，单独放一个持有者 */
internal object AppHolder2 {
    lateinit var ctx: Context
}
