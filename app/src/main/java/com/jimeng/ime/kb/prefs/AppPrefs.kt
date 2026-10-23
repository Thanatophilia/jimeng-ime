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

    /**
     * 空格键字体（[com.jimeng.ime.kb.keyboard.SpaceFont]）。
     *
     * 不能用 [PEnum]：内置字体可以是枚举，但「导入的字体」是运行时才出现的，
     * 用户的手机里有哪些 .ttf，代码编译时并不知道。所以这里存的是字符串
     * （内置存枚举名、导入存 "file:文件名.ttf"），读的时候现查现找。
     *
     * 存进去的字体要是后来被删了（文件没了 / 内置项被移除），[getValue]
     * 会退回「系统默认」——绝不把用户卡在一个不存在的字体上。
     */
    class PSpaceFont(
        private val ctx: Context,
        private val sp: SharedPreferences,
        private val key: String
    ) : ManagedPreference<com.jimeng.ime.kb.keyboard.SpaceFont>() {
        override fun getValue(): com.jimeng.ime.kb.keyboard.SpaceFont =
            com.jimeng.ime.kb.keyboard.SpaceFontLoader
                .resolve(ctx, sp.getString(key, "") ?: "")

        override fun setValue(value: com.jimeng.ime.kb.keyboard.SpaceFont) {
            sp.edit().putString(
                key,
                com.jimeng.ime.kb.keyboard.SpaceFontLoader.serialize(value)
            ).apply()
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

    /**
     * 留住 application context。
     *
     * [ManagedPreference.PSpaceFont] 要 Context 才能读 assets / files 里的字体，
     * 而 ctx 只是构造参数（不生成字段）。存成 private val 让 inner class 用得到。
     * 用 applicationContext 是防止把 Activity 泄漏在这个长命的单例里。
     */
    private val appCtx: Context = ctx.applicationContext

    private val sp: SharedPreferences =
        ctx.getSharedPreferences("jimeng_keyboard", Context.MODE_PRIVATE)

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

        /**
         * 空闲时候选栏右端那一格显示什么。
         *
         * true（默认）= 🎤 语音输入按钮；false = 一个更细更淡的 ⌄「收起键盘」箭头。
         *
         * 为什么要这个开关：语音输入还有人不装模型（模型几十 MB，
         * 得去扩展商店下）。没装的时候麦克风按下去会提示去下载，
         * 不想看到它的人可以换成「收起键盘」——那是每个输入法都该有的实用功能。
         * 模型装好、想用语音了，把这个开关打开就换回来。
         *
         * 注意只影响**空闲**（没在打字）时右端那一格。打字中右端永远是可展开的
         * 候选箭头，跟这个开关无关——那个不能动，动了就没法展开九宫格了。
         */
        val showVoiceInputButton =
            ManagedPreference.PBool(sp, "show_voice_input_button", true)

        /** 字母键是否始终大写显示 */
        val keepLettersUppercase = ManagedPreference.PBool(sp, "keep_letters_uppercase", true)

        // ---- 空格键外观（用户的小巧思）----

        /**
         * 空格键上显不显示字。
         *
         * true（默认）= 显示字（默认是当前方案名，比如「雾凇拼音」，也可以自定义）；
         * false = 纯空格键，键面上什么都不写，只有那条圆角底色。
         */
        val showSpaceLabel = ManagedPreference.PBool(sp, "show_space_label", true)

        /**
         * 空格键上想写的字。空串表示没自定义，还是用方案名。
         *
         * 上限 [com.jimeng.ime.kb.keyboard.SpaceLabel.MAX_LENGTH] 个字符，
         * 设置页的输入框和键盘端读的时候都会按这个截。
         */
        val spaceLabelText = ManagedPreference.PString(sp, "space_label_text", "")

        /** 空格键字的字体（见 SpaceFont：内置 + 导入两种来源）。默认「系统默认」，保持原有观感 */
        val spaceFont = ManagedPreference.PSpaceFont(appCtx, sp, "space_font")
        /**
         * 空格键上文字的字号，单位 dp。
         *
         * 默认 [com.jimeng.ime.kb.keyboard.SpaceLabel.IDLE_TEXT_SIZE]（13），
         * 也就是「功能关掉时键位定义里那个原始值」——用户让字回到默认大小就是这个意思：
         * 拉条不拖，空格键上的字和 fcitx5 原版看上去一样。
         *
         * 存 Int 不存 Float：拉条本来就是整数格，SharedPreferences 存浮点还会带
         * 0.30000000000000004 这种尾巴，读出来还得 round 一道，不如直接存整数 dp。
         */
        val spaceLabelTextSize = ManagedPreference.PInt(
            sp,
            "space_label_text_size",
            com.jimeng.ime.kb.keyboard.SpaceLabel.IDLE_TEXT_SIZE.toInt()
        )

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

    inner class Clipboard {
        /**
         * 剪贴板记录上限（条）。
         *
         * 默认 20。用户要的是「用户填多少就留多少」，所以这里不设硬上限，
         * 输入也不做范围夹取——填 5 就留 5 条，填 500 就留 500 条。
         * 只兜一个下限 1：填 0 或负数没意义（那等于不留记录），按 1 处理。
         */
        val historyLimit = ManagedPreference.PInt(sp, "clipboard_history_limit", 20)
    }

    inner class Output {
        /**
         * 输出繁体还是简体。
         *
         * 引擎里那个 `traditionalization` option 只活在内存，进程一死就没了，
         * 所以用户的选择要在这里存一份，下次起来 [RimeEngine.restoreOptions]
         * 再灌回去。默认 false = 简体，和方案里没写 `reset` 时的行为一致。
         */
        val traditional = ManagedPreference.PBool(sp, "output_traditional", false)

        /**
         * 下一词预测开不开。
         *
         * 和 [traditional] 一样，`prediction` option 只活在 librime 的内存里，
         * 这里存一份给 [RimeEngine.restoreOptions] 下次灌回去。
         * 默认 false = 关，不给用户塞没要过的行为。
         */
        val prediction = ManagedPreference.PBool(sp, "output_prediction", false)

        /**
         * 标点全角还是半角。
         *
         * 和 [traditional] 一样，`full_shape` option 只活在 librime 的内存里，
         * 这里存一份给 [com.jimeng.ime.rime.RimeEngine.restoreOptions] 下次灌回去。
         * 默认 false = 半角，跟方案里没写 `reset` 时的行为一致。
         */
        val fullShape = ManagedPreference.PBool(sp, "output_full_shape", false)

        /**
         * 字母数字是否全角化。
         *
         * 这个是**前端**转换（rime 不管这个），在 [com.jimeng.ime.JimengIME]
         * 上屏路径里做：开启时把引擎 commit 出来的 ASCII 字母数字
         * 转成全角（ＡＢＣ１２３），标点不动（标点归 fullShape 管）。
         * 剪贴板粘贴的原样内容不转换。
         */
        val fullwidthAscii = ManagedPreference.PBool(sp, "output_fullwidth_ascii", false)
    }

    val advanced = Advanced()
    val keyboard = Keyboard()
    val clipboard = Clipboard()
    val output = Output()

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
