/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package com.jimeng.ime.kb.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import com.jimeng.ime.R
import com.jimeng.ime.kb.AutoScaleTextView
import com.jimeng.ime.kb.core.InputMethodEntry
import com.jimeng.ime.kb.core.KeyState
import com.jimeng.ime.kb.core.KeyStates
import com.jimeng.ime.kb.popup.PopupAction
import com.jimeng.ime.kb.core.PunctuationData
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.prefs.ManagedPreference
import com.jimeng.ime.kb.theme.Theme
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, Layout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("Q", "1"),
                AlphabetKey("W", "2"),
                AlphabetKey("E", "3"),
                AlphabetKey("R", "4"),
                AlphabetKey("T", "5"),
                AlphabetKey("Y", "6"),
                AlphabetKey("U", "7"),
                AlphabetKey("I", "8"),
                AlphabetKey("O", "9"),
                AlphabetKey("P", "0")
            ),
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")")
            ),
            listOf(
                CapsKey(),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            ),
            listOf(
                LayoutSwitchKey("?123", ""),
                CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative),
                LanguageKey(),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                ReturnKey()
            )
        )
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val quickphrase: ImageKeyView by lazy { findViewById(R.id.button_quickphrase) }
    val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    // ---- 空格键外观（用户的小巧思）----
    private val showSpaceLabel = AppPrefs.getInstance().keyboard.showSpaceLabel
    private val spaceLabelText = AppPrefs.getInstance().keyboard.spaceLabelText
    private val spaceFont = AppPrefs.getInstance().keyboard.spaceFont
    /** 空格键文字字号（dp），由设置页的拉条控制 */
    private val spaceLabelTextSize = AppPrefs.getInstance().keyboard.spaceLabelTextSize

    /**
     * 最近一次拿到的方案名（比如「雾凇拼音」）。
     *
     * 为什么要存下来：开关 / 自定义文字 / 字体这三项一改，空格键上该显示什么就得重算，
     * 但那时候没人再给我们传 [InputMethodEntry] 了。所以把算过一次的方案名留住，
     * 重算时拿它当兜底 —— 自定义文字清空了也能立刻退回方案名，不用等切方案。
     */
    private var lastImeLabel: String = ""

    @Keep
    private val spaceLabelListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        updateSpaceLabel()
    }

    @Keep
    private val spaceLabelTextListener = ManagedPreference.OnChangeListener<String> { _, _ ->
        updateSpaceLabel()
    }

    @Keep
    private val spaceFontListener =
        ManagedPreference.OnChangeListener<SpaceFont> { _, _ -> updateSpaceLabel() }

    @Keep
    private val spaceLabelTextSizeListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        updateSpaceLabel()
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        // 空格键三项：设置页一改，正在显示的键盘当场重画，不用重开键盘
        showSpaceLabel.registerOnChangeListener(spaceLabelListener)
        spaceLabelText.registerOnChangeListener(spaceLabelTextListener)
        spaceFont.registerOnChangeListener(spaceFontListener)
        spaceLabelTextSize.registerOnChangeListener(spaceLabelTextSizeListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = PunctuationData.HalfShape
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction ->
                action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction ->
                action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> {
                when (val kb = action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = kb.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(keyboard = kb.copy(label = transformAlphabet(label)))
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    override fun onAttach() {
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
        // 角标要和实际打出来的字符一致（中文态下 \ 打出的是 、）
        updatePunctuationKeys()
        // 空格键的字和字体：开关 / 自定义文字是存在 prefs 里的，
        // 键盘重建后得自己读一遍，不能等 onInputMethodUpdate（那时候 lastImeLabel 才有值）
        updateSpaceLabel()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        lastImeLabel = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
        updateSpaceLabel()
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    /**
     * 重算空格键上该显示什么、用什么字体。这是这个功能唯一的总入口。
     *
     * 三层判断，顺序不能换：
     *   1. 开关关了 → 纯空格，字和字体的痕迹都不留（连自定义字体也要清掉，
     *      否则虽然看不到字符，字体对象还挂着，白白多占一份内存）
     *   2. 开关开着 + 有自定义文字 → 用自定义的（按 [SpaceLabel.MAX_LENGTH] 截断）
     *   3. 开关开着 + 没自定义 → 退回方案名，和改造前一模一样
     *
     * 注意 trim 过再判空：用户只在输入框里敲了几个空格，应该算「没写自定义」，
     * 而不是把空格键变成一个看起来空白的键。
     */
    private fun updateSpaceLabel() {
        val custom = spaceLabelText.getValue().trim()
        val show = showSpaceLabel.getValue()
        // 先定缩放模式，再动文字/字号/字体：
        // 后三者每个都会把 AutoScaleTextView 的测量缓存置脏，
        // 顺序这么排就能保证 scaleMode 改完一定被重新算进 transform 里。
        // Horizontal 只压 X 轴：字大了万一超出空格键宽度（8 个中文字 / 窄屏 /
        // 系统字号放大），字形高度不变、只是横向挤一点，比 Mode.None 直接裁掉强。
        space.mainText.scaleMode = AutoScaleTextView.Mode.Horizontal
        space.mainText.text = when {
            !show -> " "
            custom.isNotEmpty() -> custom.take(SpaceLabel.MAX_LENGTH)
            else -> lastImeLabel
        }
        // 字号：只有真的在显示文字时才用用户的设定值。关掉开关就退回原始的 13dp，
        // 让空格键回到改造前的样子（那时候它只有一个看不见的空格符号）。
        // 值来自设置页的拉条；范围在这里再夹一道，防 prefs 被外部改坏。
        val size = spaceLabelTextSize.getValue().coerceIn(
            SpaceLabel.MIN_TEXT_SIZE,
            SpaceLabel.MAX_TEXT_SIZE
        )
        space.mainText.setTextSize(
            TypedValue.COMPLEX_UNIT_DIP,
            (if (show) size else SpaceLabel.IDLE_TEXT_SIZE.toInt()).toFloat()
        )
        // 默认字体（或开关关掉）就传 null，回到 TextView 原来的系统字体
        space.mainText.typeface = if (show) {
            spaceFont.getValue().typeface(context)
        } else {
            null
        }
    }

    private fun switchCapsState(lock: Boolean = false) {
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            if (it.def !is KeyDef.Appearance.AltText) return
            it.mainText.text = it.def.displayText.let { str ->
                if (str.length != 1 || !str[0].isLetter()) return@forEach
                if (keepLettersUppercase) str.uppercase() else transformAlphabet(str)
            }
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = transformPunctuation(it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                it.mainText.text = it.def.displayText.let { str ->
                    if (str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}