package com.jimeng.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService.Insets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jimeng.ime.kb.InputFeedbacks
import com.jimeng.ime.kb.RimeKeyActionDispatcher
import com.jimeng.ime.kb.core.LanguageData
import com.jimeng.ime.kb.core.LanguageData.toEntry
import com.jimeng.ime.kb.keyboard.BaseKeyboard
import com.jimeng.ime.kb.keyboard.KeyAction
import com.jimeng.ime.kb.keyboard.NumberKeyboard
import com.jimeng.ime.kb.keyboard.TextKeyboard
import com.jimeng.ime.kb.picker.PickerWindow
import com.jimeng.ime.kb.popup.PopupComponent
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.kb.utils.AppHolder
import com.jimeng.ime.rime.RimeEngine
import com.jimeng.ime.rime.RimeNative
import com.jimeng.ime.view.CandidateBar
import com.jimeng.ime.view.PreeditBar
import com.jimeng.ime.view.PickerPanel

/**
 * 霁梦输入法服务。
 *
 * 键盘层 = fcitx5-android 原版实现（BaseKeyboard / KeyView / TextKeyboard /
 * NumberKeyboard / CustomGestureView / Popup 体系），一整套都是真 View 树：
 *   - 每键带右上角数字角标（Q 上面是 1、W 上面是 2…）
 *   - 上滑出符号（按住字母往上滑就是那个角标符号）
 *   - 长按弹预览气泡 + 九宫格选字面板
 *   - RippleDrawable 按压反馈、StateListDrawable 状态色
 *   - 双击 / 长按 Caps 三态（无 / 一次 / 锁定）
 *   - 按住退格连发、空格左右滑移动光标
 *   - percentWidth 百分比布局，自适应任何屏宽
 *
 * 按键走 [RimeKeyActionDispatcher]：FcitxKeyAction/SymAction -> librime 的
 * nativeProcessKey，引擎不吃就退回 InputConnection。
 */
class JimengIME : InputMethodService(),
    CandidateBar.Listener,
    RimeEngine.Callback {

    companion object {
        private const val TAG = "JimengIME"
    }

    // 这三个都不能用 lateinit：onStartInput 可能早于 onCreateInputView
    private var candidateBar: CandidateBar? = null

    /** 输入码显示区（拼音串那一行），浮在候选栏上方 */
    private var preeditBar: PreeditBar? = null
    private var keyboard: BaseKeyboard? = null
    private var statusText: TextView? = null
    private var rootView: LinearLayout? = null
    private var keyboardHost: FrameLayout? = null
    /** 气泡 / 长按面板总管，root 覆盖整个输入法视图 */
    private var popupComponent: PopupComponent? = null

    /**
     * 引擎语言（rime 方案）→ 系统 subtype。
     *
     * 系统只认自己注册过的 subtype；不认识的话，状态栏/导航栏那个语言标志
     * 就永远显示同一个样子，系统选择器里也只有一个条目。
     * 这里把 method.xml 里声明的 subtype 按语言对上号，切语言时通知系统。
     */
    private var subtypeMap: Map<String, android.view.inputmethod.InputMethodSubtype> = emptyMap()

    /**
     * 自己刚通知过系统的那次 subtype 变更。
     *
     * switchInputMethod 会回调 onCurrentInputMethodSubtypeChanged，
     * 不挡住的话会「切语言 → 回调 → 再切一次」，来回打转。
     */
    private var skipNextSubtypeChange: String? = null
    /** 表情 / 颜文字面板，平时 GONE */
    private var pickerPanel: PickerPanel? = null
    /** 面板是否正盖在键盘上 */
    private var pickerShowing = false
    /** 上次用过的面板，对齐 fcitx5 的 lastPickerType */
    private var lastPickerKey: PickerWindow.Key? = null

    private val main = Handler(Looper.getMainLooper())

    /** 引擎是否可用 */
    private var engineReady = false

    /** 上次取到的候选数，用于判断是否需要刷新 UI */
    private var lastCandidateCount = 0

    /** 当前布局：Text / Number（对应 fcitx5 的 currentKeyboardName） */
    private var currentLayout = TextKeyboard.Name

    /** 上次用过的符号布局，`?123`（to 为空）时回到这里 */
    private var lastSymbolLayout = NumberKeyboard.Name

    /** Caps 状态 */
    private var capsLock = false
    private var capsOnce = false

    private lateinit var dispatcher: RimeKeyActionDispatcher

    override fun onCreate() {
        super.onCreate()

        // 全局 Context + 偏好 + 主题初始化（移植层要用）
        AppHolder.init(this)
        AppPrefs.init(this)
        ThemeManager.init(this)
        InputFeedbacks.syncSystemPrefs()

        dispatcher = RimeKeyActionDispatcher(
            sessionProvider = { RimeEngine.sessionId },
            processKey = { session, sym, mask -> processKey(session, sym, mask) },
            commit = { text -> commitText(text) },
            inputConnection = { currentInputConnection },
            onCapsChanged = { lock -> onCapsChanged(lock) },
            onLayoutSwitch = { to -> switchLayout(to) },
            onPickerSwitch = { key -> onPickerSwitch(key) },
            onShowImePicker = { showImePicker() },
            onSwitchLanguage = { switchLanguage() },
            // fcitx5 原版默认 SpaceLongPressBehavior.None：长按空格不做事
            onSpaceLongPress = { },
        )

        RimeEngine.bind(this)
        RimeEngine.callback = this
        RimeEngine.ensureInitialized()
        buildSubtypeMap()
    }

    /**
     * 把 method.xml 里声明的 subtype 抓出来，按语言建表。
     *
     * method.xml 里现在写着「拼音」和「English」两个 subtype。系统只有在
     * 输入法主动 setAdditionalInputMethodSubtypes / switchInputMethod 之后，
     * 才知道这个输入法下面有第二种语言；不主动说，系统就当成只有一个，
     * 状态栏一直是同一种语言标志、系统选择器里也只有一个条目。
     */
    private fun buildSubtypeMap() {
        subtypeMap = try {
            val imm = getSystemService(INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager ?: return
            val me = android.content.ComponentName(this, JimengIME::class.java)
            // InputMethodInfo 没有 subtypes 属性，subtype 是按下标取的
            val info = imm.inputMethodList.firstOrNull { it.component == me } ?: return
            val list = ArrayList<android.view.inputmethod.InputMethodSubtype>(info.subtypeCount)
            for (i in 0 until info.subtypeCount) list.add(info.getSubtypeAt(i))
            val map = HashMap<String, android.view.inputmethod.InputMethodSubtype>()
            for (lang in LanguageData.All) {
                // subtype 用 locale 前缀对上语言：zh_CN -> 拼音，en_US -> English
                val prefix = lang.languageCode.substringBefore('_')
                val st = list.firstOrNull { sub ->
                    sub.locale.substringBefore('_').equals(prefix, ignoreCase = true)
                } ?: continue
                map[lang.schemaId] = st
            }
            // 光在 method.xml 里写 subtype 还不够。系统对「没被显式启用的 subtype」
            // 只会默认启用第一个，第二个就等于不存在：状态栏标志不变、
            // 系统选择器里看不到、switchInputMethod 也切不过去。
            // fcitx5 的 SubtypeManager 同样调这个方法把 subtype 显式启用。
            if (android.os.Build.VERSION.SDK_INT >= 33 && list.isNotEmpty()) {
                try {
                    imm.setExplicitlyEnabledInputMethodSubtypes(
                        info.id, list.map { it.hashCode() }.toIntArray()
                    )
                    Log.i(TAG, "已显式启用 subtype: ${list.size} 个")
                } catch (t: Throwable) {
                    Log.w(TAG, "显式启用 subtype 失败", t)
                }
            }
            Log.i(TAG, "subtype 表: " + map.entries.joinToString { "${it.key}=${it.value.locale}" })
            map
        } catch (t: Throwable) {
            Log.w(TAG, "读取 subtype 失败", t)
            emptyMap()
        }
    }

    /**
     * 通知系统「本输入法的语言变了」。
     *
     * 不调这个，系统那边永远认为你还是第一种语言：状态栏的标志不变、
     * 系统输入法选择器里也看不出切换过。
     */
    private fun notifySubtypeChanged(schemaId: String) {
        val st = subtypeMap[schemaId] ?: return
        skipNextSubtypeChange = schemaId
        try {
            // switchInputMethod(String, subtype)：第一个参数是输入法 ID（"包名/.服务名"）
            val id = android.content.ComponentName(this, JimengIME::class.java)
                .flattenToShortString()
            switchInputMethod(id, st)
        } catch (t: Throwable) {
            skipNextSubtypeChange = null
            Log.w(TAG, "switchInputMethod(subtype) 失败", t)
        }
    }

    /**
     * 用户在系统里手动换了语言（系统输入法选择器 / 语言标志）。
     *
     * 这时要把引擎也切到对应语言，否则界面和引擎会对不上。
     */
    override fun onCurrentInputMethodSubtypeChanged(
        newSubtype: android.view.inputmethod.InputMethodSubtype
    ) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val schemaId = subtypeMap.entries.firstOrNull { it.value == newSubtype }?.key
            ?: subtypeMap.entries.firstOrNull { it.value.locale == newSubtype.locale }?.key
            ?: return
        // 这次变更如果是自己刚发出去的，忽略，否则会来回打转
        if (skipNextSubtypeChange == schemaId) {
            skipNextSubtypeChange = null
            return
        }
        skipNextSubtypeChange = null
        if (schemaId == RimeEngine.currentSchema) return
        val session = RimeEngine.sessionId
        if (session != 0L) RimeNative.nativeClearComposition(session)
        if (RimeEngine.switchLanguage(schemaId)) {
            applyLanguageToKeyboard()
            refreshCandidates()
        }
    }

    // ------------------------------------------------------------ 视图

    override fun onCreateInputView(): View {
        val theme = ThemeManager.activeTheme

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.keyboardColor)
            clipChildren = false
            clipToPadding = false
        }

        // ---- 输入码显示区（preedit）----
        // 单独一行，浮在候选栏上方。fcitx5-android 的 PreeditUi 就是这个位置，
        // Gboard 也把拼音串放在这一条。拆出来后候选栏的横向空间全归候选词，
        // 拼音再长也不会把候选挤掉。
        val preeditBar = PreeditBar(this)
        root.addView(
            preeditBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        this.preeditBar = preeditBar

        // ---- 候选栏 ----
        val bar = CandidateBar(this).apply { listener = this@JimengIME }
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        candidateBar = bar

        // ---- 状态提示条（部署中/失败时才显示）----
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(theme.candidateCommentColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        statusText = status

        // ---- 键盘叠层 ----
        // keyboardHost 是键盘的固定高度容器：键盘 4 行本身不带高度，
        // 全靠这个父容器的高度均分。
        val kbHost = FrameLayout(this).apply { clipChildren = false }

        val kb = TextKeyboard(this, theme).apply {
            keyActionListener = dispatcher
        }
        kbHost.addView(
            kb,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        keyboard = kb
        // ---- 表情/颜文字面板（平时隐藏，长按逗号选 Emoji 时盖上来）----
        val picker = PickerPanel(this, theme, keyboardHeightPx).apply {
            visibility = View.GONE
            onCommit = { text -> commitText(text) }
            // 底部小键盘按 ABC / 数字键盘图标：先收面板，再走主键盘的 switchLayout
            onLayoutSwitch = { to -> switchLayout(to) }
            onPickerSwitch = { key -> onPickerSwitch(key) }
        }
        kbHost.addView(
            picker,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        pickerPanel = picker

        root.addView(
            kbHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                keyboardHeightPx
            )
        )
        keyboardHost = kbHost

        rootView = root

        // 面板底部那行小键盘用和主键盘同一个分发器
        picker.bindListeners(dispatcher)
        // 首次建视图也要跑一遍 onAttach:它负责 Caps 归零、字母大小写、标点角标
        kb.onAttach()

        // 通知键盘当前语言（会显示在空格键上），面板也同步
        applyLanguageToKeyboard()

        syncStatus()
        refreshCandidates()
        // ---- 气泡 / 长按面板层 ----
        // 必须在最外层 holder 上、且最后添加，才能盖住键盘并允许气泡探出键盘上沿
        val holder = wrapInFrame(root)
        val pc = PopupComponent(this, ThemeManager.activeTheme)
        popupComponent = pc
        if (holder is FrameLayout) {
            holder.addView(
                pc.root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        // 键盘接上气泡总管
        (keyboard as? BaseKeyboard)?.popupActionListener = pc.listener
        return holder
    }

    private fun wrapInFrame(view: View): View {
        val theme = ThemeManager.activeTheme
        val holder = FrameLayout(this).apply {
            // 输入法窗口现在是全屏，这一层必须透明，
            // 否则键盘底色会糊满整个屏幕。视觉上的底色由里面的 root 提供。
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 气泡会画到键盘上方，FrameLayout 默认会裁剪，这里放开
            clipChildren = false
            clipToPadding = false
            addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ).apply {
                    // 气泡 / 长按九宫格要往上探，必须在这儿留出空间。
                    //
                    // 窗口高度 = popupReservePx + 候选栏 + 键盘。
                    // 这一块是透明的、且落在 visibleTopInsets 之上（触摸穿透给应用），
                    // 平时什么也看不见，只在气泡弹出来时用它。
                    // 不给这块空间，气泡一探出键盘上沿就会被窗口裁掉。
                    topMargin = popupReservePx
                }
            )
        }
        return holder
    }

    /**
     * 气泡 / 长按面板向上探出所需的预留空间。
     *
     * 取够用的最大值：九宫格最多 5 行时，容器上沿大约要探出
     * `popupHeight + keyHeight × (行数 - 1)` ≈ 254dp，减去候选栏的 44dp，
     * 约 210dp。这里取 240dp 留点余量。
     */
    private val popupReservePx: Int get() = dp(240)

    // ------------------------------------------------------------ 生命周期

    override fun onStartInput(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        val session = RimeEngine.ensureSession()
        engineReady = session != 0L
        if (engineReady) RimeNative.nativeClearComposition(session)
        resetCaps()
        syncStatus()
        refreshCandidates()
    }

    override fun onFinishInput() {
        val session = RimeEngine.sessionId
        if (session != 0L) RimeNative.nativeClearComposition(session)
        refreshCandidates()
        super.onFinishInput()
    }

    override fun onDestroy() {
        RimeEngine.callback = null
        RimeEngine.releaseSession()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.updateFromContext(this)
    }

    // ------------------------------------------------------------ 引擎回调

    override fun onStateChanged(state: RimeEngine.State, message: String?) {
        if (state == RimeEngine.State.Ready) {
            RimeEngine.ensureSession()
            engineReady = RimeEngine.sessionId != 0L
        }
        syncStatus()
    }

    override fun onReady() {
        engineReady = RimeEngine.sessionId != 0L
        syncStatus()
        refreshCandidates()
    }

    override fun onSelfTest(report: RimeEngine.SelfTestReport?) = Unit

    private fun syncStatus() {
        val status = statusText ?: return
        val session = RimeEngine.sessionId
        engineReady = session != 0L
        when (RimeEngine.state) {
            RimeEngine.State.Ready -> status.visibility = View.GONE
            RimeEngine.State.Deploying -> {
                status.visibility = View.VISIBLE
                status.text = "正在部署雾凇拼音，首次需要几十秒…"
            }
            RimeEngine.State.Failed -> {
                status.visibility = View.VISIBLE
                status.text = "词库部署失败：${RimeEngine.lastError ?: "未知错误"}"
            }
            RimeEngine.State.Idle -> {
                status.visibility = View.VISIBLE
                status.text = "引擎未启动"
            }
        }
    }

    // ------------------------------------------------------------ 键盘状态

    private fun onCapsChanged(lock: Boolean) {
        // Caps 状态由 TextKeyboard 内部维护（点按/双击/长按三态），
        // 这里只记录，供硬件键盘或后续功能参考。
        capsLock = lock
        capsOnce = !lock
    }

    private fun resetCaps() {
        capsLock = false
        capsOnce = false
    }

    private fun switchLayout(to: String) {
        // 对应 fcitx5 KeyboardWindow.switchLayout：
        //   to 为空 -> 回到上次用过的符号布局（默认 Number）
        //   to 是 Symbol / Emoji / Emoticon -> 那是面板，交给 onPickerSwitch
        //   to == 当前 -> 收起面板即可（比如从面板按「ABC」回来）
        val target = to.ifEmpty { lastSymbolLayout }
        PickerWindow.Key.entries.firstOrNull { it.name == target }?.let { key ->
            onPickerSwitch(key)
            return
        }
        if (target != TextKeyboard.Name && target != NumberKeyboard.Name) return
        if (target == currentLayout) {
            hidePicker()
            return
        }
        hidePicker()
        val theme = ThemeManager.activeTheme
        val host = keyboardHost ?: return
        val session = RimeEngine.sessionId
        if (session != 0L) RimeNative.nativeClearComposition(session)

        val newKb: BaseKeyboard = if (target == NumberKeyboard.Name) {
            NumberKeyboard(this, theme)
        } else {
            TextKeyboard(this, theme)
        }
        host.removeAllViews()
        newKb.keyActionListener = dispatcher
        newKb.popupActionListener = popupComponent?.listener
        host.addView(
            newKb,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // 表情面板也要重新挂回来（removeAllViews 把它一起拿掉了），
        // 顺序保持「键盘 → 面板」，面板才能盖在键盘上面
        pickerPanel?.let { panel ->
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
            host.addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        keyboard = newKb
        newKb.onAttach()
        newKb.onInputMethodUpdate(LanguageData.of(RimeEngine.currentSchema).toEntry())
        currentLayout = target
        // 记住这次用的符号布局，下次 `?123` 会回到这里
        if (target != TextKeyboard.Name) lastSymbolLayout = target
        resetCaps()
        refreshCandidates()
    }

    private fun onPickerSwitch(key: PickerWindow.Key?) {
        // 对应 fcitx5 CommonKeyActionListener 的 PickerSwitchAction：
        //   key 有值 -> 记住它并打开
        //   key 为空 -> 回到上次用过的面板，没有就用表情
        // 表情 / 颜文字面板之外（emoji 以外的 Symbol）暂时没做，忽略。
        val target = key?.also { lastPickerKey = it }
            ?: lastPickerKey
            ?: PickerWindow.Key.Emoji
        val panel = pickerPanel ?: return
        panel.showKey(target)
        if (!pickerShowing) {
            // 面板盖上来时先收掉主键盘的按压状态
            keyboard?.onDetach()
            panel.visibility = View.VISIBLE
            pickerShowing = true
        }
        lastPickerKey = target
    }

    /** 从表情面板退回键盘 */
    private fun hidePicker() {
        if (!pickerShowing) return
        pickerPanel?.visibility = View.GONE
        pickerShowing = false
        keyboard?.onAttach()
    }

    /** 面板是否在显示；给返回键判断用 */
    fun isPickerShowing(): Boolean = pickerShowing
    private fun showImePicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.showInputMethodPicker()
    }

    /**
     * 小地球短按：在语言表里循环切换。
     *
     * 一个语言就是一个 rime 方案，切过去是引擎层的动作（nativeSelectSchema），
     * 不是换输入法 App。以后往 [LanguageData.All] 里加一项，
     * 这里就会自动多一个档位，不用改这段逻辑。
     */
    private fun switchLanguage() {
        val session = RimeEngine.sessionId
        if (session == 0L) return
        val next = LanguageData.next(RimeEngine.currentSchema)
        // 切换会清掉正在输入的编码，这是引擎行为，先清干净避免残留
        RimeNative.nativeClearComposition(session)
        if (!RimeEngine.switchLanguage(next.schemaId)) {
            // 切不过去（比如方案没进 schema_list）：留在原语言，别把界面刷成错的
            refreshCandidates()
            return
        }
        // 告诉系统「语言变了」：状态栏标志、系统选择器里的当前项都靠这个更新
        notifySubtypeChanged(next.schemaId)
        applyLanguageToKeyboard()
        refreshCandidates()
    }

    /** 把当前语言同步到键盘（空格键上的名字）和表情面板 */
    private fun applyLanguageToKeyboard() {
        val entry = LanguageData.of(RimeEngine.currentSchema).toEntry()
        keyboard?.onInputMethodUpdate(entry)
        pickerPanel?.onInputMethodUpdate(entry)
    }


    // ------------------------------------------------------------ 引擎按键

    /** 喂一个键给 rime，返回引擎是否吃掉 */
    private fun processKey(session: Long, keySym: Int, mask: Int): Boolean {
        if (session == 0L) return false
        val handled = try {
            RimeNative.nativeProcessKey(session, keySym, mask)
        } catch (t: Throwable) {
            Log.e(TAG, "processKey 失败 sym=$keySym", t)
            false
        }
        drainCommit(session)
        refreshCandidates()
        return handled
    }

    /** 把引擎里待上屏的文本取出来交给输入框 */
    private fun drainCommit(session: Long) {
        if (session == 0L) return
        val commit = try {
            RimeNative.nativeGetCommit(session)
        } catch (t: Throwable) {
            Log.e(TAG, "getCommit 失败", t)
            null
        }
        if (!commit.isNullOrEmpty()) commitText(commit)
    }

    private fun commitText(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
    }

    // ------------------------------------------------------------ 候选栏

    override fun onPickCandidate(index: Int) {
        val session = RimeEngine.sessionId
        if (session == 0L) return
        try {
            RimeNative.nativeSelectCandidate(session, index)
        } catch (t: Throwable) {
            Log.e(TAG, "selectCandidate 失败", t)
        }
        drainCommit(session)
        refreshCandidates()
    }

    override fun onPage(backward: Boolean) {
        val session = RimeEngine.sessionId
        if (session == 0L) return
        try {
            RimeNative.nativeChangePage(session, backward)
        } catch (t: Throwable) {
            Log.e(TAG, "changePage 失败", t)
        }
        refreshCandidates()
    }

    override fun onExpand() {
        // 展开面板下一轮做
        refreshCandidates()
    }

    /** 从引擎读上下文刷新候选栏 */
    private fun refreshCandidates() {
        val bar = candidateBar ?: return
        val session = RimeEngine.sessionId
        if (session == 0L) {
            preeditBar?.clear()
            bar.clear()
            lastCandidateCount = 0
            return
        }
        val ctx = try {
            RimeNative.nativeGetContext(session)
        } catch (t: Throwable) {
            Log.e(TAG, "getContext 失败", t)
            null
        }
        if (ctx == null || ctx.isEmpty()) {
            preeditBar?.clear()
            bar.clear()
            lastCandidateCount = 0
            return
        }
        val preedit = ctx[0] ?: ""
        val candidates = ctx.drop(1).filterNotNull().filter { it.isNotEmpty() }
        // 输入码单独一行交给码区，候选只管候选
        preeditBar?.update(preedit)

        val menu = try {
            RimeNative.nativeGetMenuInfo(session)
        } catch (t: Throwable) {
            null
        }
        val page = menu?.getOrNull(0) ?: 0
        val lastPage = (menu?.getOrNull(1) ?: 1) != 0
        val count = menu?.getOrNull(2) ?: candidates.size
        lastCandidateCount = count
        if (preedit.isEmpty() && candidates.isEmpty()) {
            bar.clear()
        } else {
            bar.update(candidates, page, lastPage, page > 0)
        }
    }

    // ------------------------------------------------------------ 硬件键盘

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 面板开着的时候，返回键先收面板，而不是直接收输入法
        if (keyCode == KeyEvent.KEYCODE_BACK && pickerShowing) {
            hidePicker()
            return true
        }
        val session = RimeEngine.sessionId
        // 硬件键盘也走引擎，中文态下 a-z 才能打拼音
        if (session != 0L && event.unicodeChar != 0) {
            val sym = event.unicodeChar
            if (sym in 0x20..0x7e) {
                val mask = if (event.isShiftPressed) 1 else 0
                if (processKey(session, sym, mask)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ============================================================
    //  让气泡/长按面板能画出键盘区域（照搬 fcitx5-android）
    //
    //  默认输入法窗口只有「键盘那么高」，气泡往上探到候选栏以外时
    //  会被窗口裁掉。fcitx5 的解法是把整个输入法窗口撑成全屏：
    //    1) setInputView: inputArea 与 InputView 都改成 MATCH_PARENT
    //    2) onConfigureWindow: 窗口本身 MATCH_PARENT
    //    3) onComputeInsets: 告诉应用「键盘上沿在这里」，
    //       应用就不会把内容区让到全屏，视觉上仍然只有键盘那么高
    // ============================================================

    private var inputViewLocation = IntArray(2)

    override fun setInputView(view: View) {
        super.setInputView(view)
        // 高度保持 WRAP_CONTENT：窗口高度 = 气泡预留空间 + 候选栏 + 键盘。
        //
        // 这里不能撑成全屏。系统只在窗口尺寸变化时才重算 onComputeInsets，
        // 高度一旦固定，候选栏出现/消失时 insets 不会更新，候选栏就会被
        // 划到触摸区之外，点候选词反而点到被遮住的应用界面。
        view.layoutParams = (view.layoutParams ?: ViewGroup.LayoutParams(0, 0)).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        // 宽度铺满，高度随内容（wrap_content）。见 setInputView 的说明。
        win.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onComputeInsets(outInsets: Insets) {
        // 注意：这里必须用「候选栏 + 键盘」整块的 top，而不是键盘本体的 top。
        //
        // 输入法窗口现在是全屏，系统靠这个 insets 判断哪块区域归输入法、
        // 哪块区域算应用内容。如果只报到键盘顶，上方的候选栏就落在
        // 「可见区」之外，触摸会直接穿透到被遮住的应用界面，点候选词
        // 反而点到屏幕上的按钮。
        val content = rootView
        if (content != null && content.height > 0) {
            // 用窗口坐标，和 AOSP 默认实现、fcitx5-android 保持一致
            content.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                // TOUCHABLE_INSETS_VISIBLE：可见区内的触摸归输入法，
                // 可见区上方（全屏窗口的空白部分）继续穿透给应用
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            super.onComputeInsets(outInsets)
        }
    }


    /**
     * 键盘高度 = 屏幕高度 × 百分比（对应 fcitx5 的 InputView.keyboardHeightPx）。
     *
     * 这一层必须有固定高度：BaseKeyboard 里的 4 行用约束彼此 in一切，
     * 行本身不带高度，靠父容器的确定高度均分。
     */
    private val keyboardHeightPx: Int
        get() {
            val prefs = AppPrefs.getInstance().keyboard
            val landscape =
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val percent = if (landscape) prefs.keyboardHeightPercentLandscape.getValue()
            else prefs.keyboardHeightPercent.getValue()
            val base = resources.displayMetrics.heightPixels
            return base * percent / 100
        }
}