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
import com.jimeng.ime.kb.core.FcitxKeyMapping
import com.jimeng.ime.kb.core.KeyState
import com.jimeng.ime.kb.core.KeySym
import com.jimeng.ime.kb.core.LanguageData
import com.jimeng.ime.kb.core.LanguageData.toEntry
import com.jimeng.ime.kb.keyboard.BaseKeyboard
import com.jimeng.ime.kb.keyboard.KeyAction
import com.jimeng.ime.kb.keyboard.KeyActionListener
import com.jimeng.ime.kb.keyboard.NumberKeyboard
import com.jimeng.ime.kb.keyboard.TextKeyboard
import com.jimeng.ime.kb.picker.PickerWindow
import com.jimeng.ime.kb.popup.PopupComponent
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.kb.utils.AppHolder
import com.jimeng.ime.kb.utils.parsePreedit
import com.jimeng.ime.rime.RimeEngine
import com.jimeng.ime.rime.RimeNative
import com.jimeng.ime.core.FcitxEvent
import com.jimeng.ime.core.FormattedText
import com.jimeng.ime.core.TextFormatFlag
import com.jimeng.ime.input.preedit.PreeditUi
import com.jimeng.ime.view.CandidateBar
import com.jimeng.ime.view.ExpandedCandidateView
import com.jimeng.ime.view.PickerPanel
// splitties 的 backgroundColor 扩展（fcitx5 的 PreeditComponent 里也用它设底色）
import splitties.views.backgroundColor

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

        /**
         * Shift 修饰位。
         *
         * 用来给引擎送 Shift+Left（rime 的 navigator 把它绑成 left_by_syllable）。
         * 值来自 [KeyState.Shift] 的那一位，和键盘自己算出来的 mask 是同一套。
         */
        private val SHIFT_MASK = KeyState.Shift.state.toInt()

        /**
         * 设置页改了按键外观（边框 / 描边 / 水波纹）时发这个广播。
         *
         * KeyView 的这三个属性是构造时读一次的，改了必须重建视图才能看到效果。
         * 设置页（[SetupActivity]）和输入法服务在同一个进程里，但用户可能
         * 正开着别的应用的键盘，直接改 View 不现实，所以走广播让服务自己重建。
         */
        const val ACTION_RELOAD_KEY_APPEARANCE = "com.jimeng.ime.action.RELOAD_KEY_APPEARANCE"
    }

    // 这三个都不能用 lateinit：onStartInput 可能早于 onCreateInputView
    private var candidateBar: CandidateBar? = null

    /** 输入码显示区（拼音串那一行），浮在候选栏上方。照 fcitx5 的 PreeditUi */
    private var preeditUi: PreeditUi? = null
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

    /**
     * 候选展开页，平时 GONE。
     *
     * 和表情面板一样挂在 keyboardHost 里、盖住键盘，
     * 高度和键盘一样，所以窗口高度不变、不会跳。
     */
    private var expandedCandidates: ExpandedCandidateView? = null
    /** 展开页是否正盖着键盘 */
    private var expandShowing = false

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

    /**
     * 接收设置页发来的按键外观变更广播，让键盘立刻换样子。
     *
     * 只在 [onCreate] 到 [onDestroy] 之间活着；键盘没在显示时收到的广播
     * 也不需要处理——下次弹出键盘时 [KeyView] 自然按新的 prefs 构造。
     */
    private val keyAppearanceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != ACTION_RELOAD_KEY_APPEARANCE) return
            // 键盘还没建出来（视图没挂上）就不用管
            if (keyboardHost == null) return
            // 先按 prefs 重排尺寸（高度、左右/底部留白、码区浮层位置），
            // 再重建键盘。顺序不能反：重建只换键盘那一层，
            // 外层容器的高度得先改对，否则键盘会按老高度均分行高。
            applyKeyboardSizeChange()
            rebuildKeyboard()
        }
    }

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

        // 设置页改按键外观后要能立刻重建键盘。In-memory 注册，进程活着就有效。
        // RECEIVER_NOT_EXPORTED：只收本应用发的，别的应用发同 action 无效。
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            keyAppearanceReceiver,
            android.content.IntentFilter(ACTION_RELOAD_KEY_APPEARANCE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
        // 这一块是照 fcitx5-android 抄来的：
        //   input/preedit/PreeditUi.kt        —— 结构（两个 wrap_content 的 TextView）
        //   input/preedit/PreeditComponent.kt —— 外观三件套
        //
        // 外观严格照 PreeditComponent：
        //   bkgColor = theme.barColor               → TextView 自己的背景（块只有文字那么宽）
        //   horizontalPadding = dp(8)               → 左右内边距
        //   root.alpha = 0.8f                       → 半透明来自容器 alpha，字和底一起柔和
        //
        // 注意：**它不放进 root 这个 LinearLayout 里**。
        // 放进去的话，它 VISIBLE/GONE 会改变 root 高度，进而改变窗口高度，
        // 键盘就会跟着跳，而且 GONE 之后窗口还留着一条纯白空带。
        // 所以这里只是创建，真正 addView 在下面 holder 那一层做（浮层，不参与布局高度）。
        // 主人要：底色块保留，但高度压薄一点。
        // 块高度 = 字体行高 + 上下 padding（PreeditUi 里 TextView 是 wrap_content，
        // 所以高度完全由这两项决定）。压薄就要动这两项：
        //   textSize 16f → 14f          （行高跟着降，块自然变薄）
        //   includeFontPadding = false  （去掉字体内边距，再省 3~4px）
        //   上下 padding 给 1dp         （留一点呼吸，别让字贴死块的边）
        val preeditUi = PreeditUi(
            this,
            theme,
            setupTextView = {
                backgroundColor = theme.barColor
                textSize = 14f
                includeFontPadding = false
                setPadding(dp(8), dp(1), dp(8), dp(1))
            }
        )
        preeditUi.root.alpha = 0.8f
        // 没输入码就整行 GONE（照 fcitx5 的 visible = hasUp || hasDown）
        preeditUi.root.visibility = View.GONE
        this.preeditUi = preeditUi

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
        // 首次建视图时按 [currentLayout] 造对应键盘，不能一律 TextKeyboard：
        // onStartInput 可能早于 onCreateInputView（数字框尤其常见），
        // 那时 currentLayout 已经改成 Number 了，这里再造文字键盘就会对不上。
        val kb = createKeyboardFor(currentLayout, theme).apply {
            keyActionListener = dispatcher
        }
        kbHost.addView(
            kb,
            keyboardInsets(
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
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
            keyboardInsets(
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        )
        pickerPanel = picker

        // ---- 候选展开页（平时隐藏，点候选栏右侧 ⌄ 时盖上来）----
        val expanded = ExpandedCandidateView(this, theme).apply {
            visibility = View.GONE
            listener = object : ExpandedCandidateView.Listener {
                override fun onPickCandidate(globalIndex: Int) = pickExpandedCandidate(globalIndex)
                override fun onBackspace() = dispatchExpandedBackspace()
                override fun onReturn() = dispatchReturn()

                override fun onPickSyllable(index: Int) {
                    // 点音节格：切锁定态（点同一个 = 解锁）
                    expandedCandidates?.toggleSyllableLock(index)
                }

                override fun onPickFuzzyVariant(variant: String) = pickFuzzyVariant(variant)
            }
        }
        kbHost.addView(
            expanded,
            keyboardInsets(
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        )
        expandedCandidates = expanded

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
            // ---- 码区浮到候选栏上方（B 方案）----
            //
            // 为什么不放进 root：root 是 VERTICAL 的 LinearLayout，
            // 码区一进一出就会改变 root 高度 → 窗口高度 → 键盘跳动，
            // 而且 GONE 之后窗口仍留着一段纯白空带（主人看到的那条）。
            //
            // 挂到 holder（FrameLayout）上、用 GRAVITY_BOTTOM + bottomMargin 定位：
            //   bottomMargin = 键盘高度 + 候选栏高度
            // 让它正好压在候选栏上沿之上，**完全不参与窗口高度计算**。
            // 这跟 fcitx5 的 CandidatesView 用 translationY 浮在上面是同一个思路。
            holder.addView(
                preeditUi.root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    bottomMargin = keyboardHeightPx + dp(CandidateBar.HEIGHT_DP.toInt())
                }
            )
        }
        // 键盘接上气泡总管
        (keyboard as? BaseKeyboard)?.popupActionListener = pc.listener
        return holder
    }

    /**
     * 把 prefs 里的当前尺寸应用到视图层（高度、左右内缩、底部留白、码区浮层位置）。
     *
     * 设置页改了尺寸广播过来时用：值都已经在 prefs 里，这里只负责重排视图，
     * 不重建键盘——重建由广播接收器里接下来的 [rebuildKeyboard] 做。
     */
    private fun applyKeyboardSizeChange() {
        val host = keyboardHost
        if (host != null) {
            // 高度：kbHost 就是键盘本体的高度（底部留白在 holder 层，见下）
            (host.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (lp.height != keyboardHeightPx) {
                    lp.height = keyboardHeightPx
                    host.layoutParams = lp
                }
            }
            // 左右：键盘本体、面板、展开页三层的内缩一起更新
            val side = dp(keyboardSidePaddingDp)
            for (i in 0 until host.childCount) {
                val child = host.getChildAt(i)
                val lp = child.layoutParams as? FrameLayout.LayoutParams ?: continue
                if (lp.leftMargin != side || lp.rightMargin != side) {
                    lp.leftMargin = side
                    lp.rightMargin = side
                    child.layoutParams = lp
                }
            }
            host.requestLayout()
        }
        // 底部留白加在 holder（最外层 FrameLayout）上：root 整块被抬起来，
        // 留出来的那截是透明窗口，能透出应用界面——照 fcitx5 的 bottomPaddingSpace。
        // 若加在 kbHost 里面，那块会露着键盘底色，看起来像坏了一条缝。
        rootView?.let { root ->
            (root.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val target = dp(keyboardBottomPaddingDp)
                if (lp.bottomMargin != target) {
                    lp.bottomMargin = target
                    root.layoutParams = lp
                }
            }
        }
        // 码区浮层贴在「键盘高度 + 候选栏 + 底部留白」之上，键盘动了它也要跟着挪
        preeditUi?.root?.let { root ->
            (root.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = keyboardHeightPx + dp(CandidateBar.HEIGHT_DP.toInt()) +
                    dp(keyboardBottomPaddingDp)
                root.layoutParams = lp
            }
        }
    }

    /**
     * 给挂在 [keyboardHost] 里的一层加上「左右」内缩。
     *
     * 照 fcitx5 的 InputView：边距不是给键盘加 padding，而是让键盘本身变窄，
     * 露出来的那块是窗口的透明区。用 margin 而不是 padding，
     * 因为键盘那几行是按父容器宽度等分的，padding 会把键帽也挤窄，
     * margin 让键盘整块缩进去、键帽比例不变。
     *
     * 底部留白不在这儿：它加在 holder（最外层）上，见 [applyKeyboardSizeChange]。
     */
    private fun keyboardInsets(base: FrameLayout.LayoutParams): FrameLayout.LayoutParams {
        val side = dp(keyboardSidePaddingDp)
        base.leftMargin = side
        base.rightMargin = side
        return base
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
                    // 窗口高度 = popupReservePx + 候选栏 + 键盘 + 底部留白。
                    // 这一块是透明的、且落在 visibleTopInsets 之上（触摸穿透给应用），
                    // 平时什么也看不见，只在气泡弹出来时用它。
                    // 不给这块空间，气泡一探出键盘上沿就会被窗口裁掉。
                    topMargin = popupReservePx
                    // 底部留白：把键盘整块从窗口底抬起来，留出的那截是透明窗口，
                    // 能透出应用界面（照 fcitx5 的 bottomPaddingSpace 思路）。
                    bottomMargin = dp(keyboardBottomPaddingDp)
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
        // 换了个输入框，展开页不该还盖着
        if (expandShowing) hideExpanded()
        // 符号 / Emoji 面板同理：那是上一轮留下的状态，新输入框要看到键盘
        hidePicker()
        // 每次开始输入都回到「打字界面」。
        //
        // 照 fcitx5 的 KeyboardWindow.onStartInput：它按输入框类型决定该用哪个
        // 布局，然后 switchLayout(target, remember = false) 强制切过去——
        // remember = false 表示**不改**「上次用的符号页」那条记忆，
        // 只把当前这一层换掉。所以主人上次停在 ?123 或符号页，
        // 下次呼出一定会回到打字界面，但 `?123` 再按一下还是能回上次那个符号页。
        //
        // 数字框（验证码、金额）和电话号框仍然给数字键盘：那不是「上次留下的
        // 状态」，是这个输入框本来就该用数字键盘。
        switchLayout(startLayoutFor(editorInfo), remember = false)
        syncStatus()
        refreshCandidates()
    }

    /**
     * 键盘每次**弹出**时都会走这里（收起再弹出也走，同一个输入框也走）。
     *
     * 这是「每次呼出都回到打字界面」真正的落点：`onStartInput` 只保证在
     * 开始输入时调用一次，键盘收起再弹出时 Android **不保证**再调一次它，
     * 但 [onStartInputView] 一定会调。
     *
     * 照 fcitx5 的链路也一样：
     *   FcitxInputMethodService.onStartInputView
     *     → InputView.startInput
     *     → broadcaster.onStartInput
     *     → KeyboardWindow.onStartInput（按输入框类型 switchLayout）
     * 它把「该用哪套键盘」的决定放在 onStartInputView 这一侧，不是 onStartInput。
     *
     * 所以上次停在 `?123` 或符号面板，键盘收起来、再点一下输入框，
     * 这里就会把布局强制换回打字界面；`remember = false` 保证
     * 「上次用的符号页」那条记忆不被覆盖，`?123` 按下去还能回原来的符号页。
     */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 展开页盖着键盘时收起来——弹出键盘是要打字，不是看候选网格
        if (expandShowing) hideExpanded()
        // 符号 / Emoji 面板也要收：面板是「上一轮留在这里的状态」，
        // 不收的话即使键盘换成文字键盘，面板还盖在上面，主人看到的依旧是符号页。
        hidePicker()
        switchLayout(startLayoutFor(info), remember = false)
    }

    /**
     * 开始输入时该用哪套键盘。
     *
     * 照 fcitx5 的 `KeyboardWindow.onStartInput` 原样翻译：
     * 数字类输入框给数字键盘，其余（文本、密码、搜索、多行……）一律文字键盘。
     *
     * 为什么数字框要特殊对待：验证码、金额、电话号码这些框用户期待的就是
     * 数字键，弹成拼音键盘反而要多按一次 `?123`。这是输入框自身的类型，
     * 不是「上次停留在哪一页」，所以这里不受「每次呼出回打字界面」影响。
     */
    private fun startLayoutFor(editorInfo: EditorInfo): String {
        val cls = editorInfo.inputType and android.text.InputType.TYPE_MASK_CLASS
        return when (cls) {
            android.text.InputType.TYPE_CLASS_NUMBER,
            android.text.InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
            else -> TextKeyboard.Name
        }
    }

    override fun onFinishInput() {
        val session = RimeEngine.sessionId
        if (session != 0L) RimeNative.nativeClearComposition(session)
        if (expandShowing) hideExpanded()
        refreshCandidates()
        super.onFinishInput()
    }

    override fun onDestroy() {
        // 先注销广播，免得销毁过程中还收到重建立
        try {
            unregisterReceiver(keyAppearanceReceiver)
        } catch (_: IllegalArgumentException) {
            // 没注册成功过就算了
        }
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

    /**
     * 按布局名造一套键盘。
     *
     * 两个地方要用：[onCreateInputView] 首次建视图、[rebuildKeyboard] 换布局。
     * 放成一个函数，是为了「哪个名字对应哪套键盘」只有一处说法——
     * 以前这两处各写一遍，加布局时漏改一处就会造错键盘。
     */
    private fun createKeyboardFor(layout: String, theme: com.jimeng.ime.kb.theme.Theme): BaseKeyboard =
        if (layout == NumberKeyboard.Name) NumberKeyboard(this, theme)
        else TextKeyboard(this, theme)

    /**
     * 换键盘布局。
     *
     * @param to 目标布局名。空串 = 回到上次用过的符号布局（`?123` 的行为）；
     *           是 PickerWindow 的面板名 → 交给 [onPickerSwitch]
     * @param remember 要不要记住「这是符号布局」。
     *
     *   `remember = true`（默认）—— 主人亲手按 `?123` 时用。切到符号布局就
     *   记进 [lastSymbolLayout]，下次再按 `?123` 回到同一个符号页。
     *
     *   `remember = false` —— 键盘每次呼出时用（见 [onStartInput]）。只把当前这层
     *   换回打字界面，**不动**那条记忆。照 fcitx5 `KeyboardWindow.switchLayout`
     *   的 remember 参数：主人的习惯要留着，但这次不是主人按的，不许改它。
     */
    private fun switchLayout(to: String, remember: Boolean = true) {
        // 对应 fcitx5 KeyboardWindow.switchLayout：
        //   to 为空 -> 回到上次用过的符号布局（默认 Number）
        //   to 是 Symbol / Emoji / Emoticon -> 那是面板，交给 onPickerSwitch
        val target = to.ifEmpty { lastSymbolLayout }
        PickerWindow.Key.entries.firstOrNull { it.name == target }?.let { key ->
            onPickerSwitch(key)
            return
        }
        if (target != TextKeyboard.Name && target != NumberKeyboard.Name) return
        if (target == currentLayout) {
            // 已经在目标布局上了，不必重建。面板要收起来（比如从面板按「ABC」回来）。
            hidePicker()
            // 但「呼出回到打字界面」这一路仍然要保证键盘真的是那一套：
            // 键盘视图还没建出来（onCreateInputView 之前）时 [keyboard] 是空的，
            // 这时候虽然 currentLayout 名义上对了，实际一个键盘都没有。
            if (keyboard == null) rebuildKeyboard()
            return
        }
        hidePicker()
        val session = RimeEngine.sessionId
        if (session != 0L) RimeNative.nativeClearComposition(session)
        currentLayout = target
        rebuildKeyboard()
        // 记住这次用的符号布局，下次 `?123` 会回到这里。
        // 键盘自己呼出时（remember = false）不算主人的选择，不改这条记忆。
        if (remember && target != TextKeyboard.Name) lastSymbolLayout = target
        resetCaps()
        refreshCandidates()
    }

    /**
     * 按 [currentLayout] 把键盘那一层整个重建一遍。
     *
     * 两个地方要用它：
     *   1. [switchLayout]——文字键盘 ⇄ 符号键盘
     *   2. 收到 [ACTION_RELOAD_KEY_APPEARANCE]——设置页改了按键外观
     *
     * 第 2 种为什么非要重建：[KeyView] 的 bordered / borderStroke / rippled
     * 三个属性是在构造时从 ThemePrefs 读一次的（照 fcitx5 的写法），
     * 运行中改 prefs 不会自己生效，只能让键帽重新构造一遍。
     *
     * 只动键盘这一层，不重建整个输入视图：候选栏、码区浮层、气泡总管
     * 都保持原样，用户正在打的字和这些视图的状态都不会丢。
     */
    private fun rebuildKeyboard() {
        val host = keyboardHost ?: return
        val theme = ThemeManager.activeTheme
        val newKb: BaseKeyboard = createKeyboardFor(currentLayout, theme)
        host.removeAllViews()
        newKb.keyActionListener = dispatcher
        newKb.popupActionListener = popupComponent?.listener
        host.addView(
            newKb,
            keyboardInsets(
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        )
        // 表情面板也要重新挂回来（removeAllViews 把它一起拿掉了），
        // 顺序保持「键盘 → 面板」，面板才能盖在键盘上面
        pickerPanel?.let { panel ->
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
            host.addView(
                panel,
                keyboardInsets(
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            )
        }
        keyboard = newKb
        newKb.onAttach()
        newKb.onInputMethodUpdate(LanguageData.of(RimeEngine.currentSchema).toEntry())
        // 展开页同样要挂回来。重建后如果它还开着，就收起来——
        // 新键盘刚上来，用户要看到键盘而不是盖着一层候选网格。
        expandedCandidates?.let { panel ->
            hideExpanded()
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
            host.addView(
                panel,
                keyboardInsets(
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            )
        }
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
        // 表情面板要盖在键盘上，展开页得先让开（它俩同层，展开页在上）
        if (expandShowing) hideExpanded()
        panel.showKey(target)
        if (!pickerShowing) {
            // 面板盖上来时先收掉主键盘的按压状态
            keyboard?.onDetach()
            panel.visibility = View.VISIBLE
            pickerShowing = true
        }
    }

    /** 从表情面板退回键盘 */
    private fun hidePicker() {
        if (!pickerShowing) return
        pickerPanel?.visibility = View.GONE
        pickerShowing = false
        keyboard?.onAttach()
    }

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

    /**
     * 在 [fullInput] 里找出末尾那串 [raw] 字母的**起始下标**，找不到返回 -1。
     *
     * 为什么不能直接 `endsWith`：雾凇的 speller/delimiter 是空格和单引号，
     * `input()` 里会原样保留分隔符（`bu hao wan`、`xi'an`）。而 [raw] 是
     * 去掉分隔符、统一小写之后的纯字母串，直接比对长度一定对不上。
     *
     * 做法：从末尾往前，把 [raw] 的字母一个个对回 [fullInput]，
     * 中途遇到的非字母（分隔符）跳过；直到 [raw] 用完，
     * 那一刻停在 [fullInput] 的下标就是起点。
     *
     * 这样 `fullInput` 里 raw 之前的部分（已经变成汉字的那几节字母，
     * 比如 `bu`）会被完整保留下来，喂给 set_input 时一个字都不少。
     */
    private fun findRawTailStart(fullInput: String, raw: String): Int {
        if (raw.isEmpty() || fullInput.isEmpty()) return -1
        var i = fullInput.length - 1
        var j = raw.length - 1
        while (i >= 0 && j >= 0) {
            val fc = fullInput[i]
            if (fc !in 'a'..'z' && fc !in 'A'..'Z') {
                // 分隔符：跳过，不算进 raw
                i--
                continue
            }
            if (fc.lowercaseChar() != raw[j]) return -1
            i--
            j--
        }
        if (j >= 0) return -1
        return i + 1
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

    override fun onExpand() {
        if (expandShowing) {
            hideExpanded()
        } else {
            showExpanded()
        }
    }

    /** 展开页：整张候选表交给网格（不受当前页限制） */
    private fun showExpanded() {
        val panel = expandedCandidates ?: return
        val session = RimeEngine.sessionId
        if (session == 0L) return
        val all = try {
            RimeNative.nativeGetAllCandidates(session)
        } catch (t: Throwable) {
            Log.e(TAG, "getAllCandidates 失败", t)
            null
        }
        if (all.isNullOrEmpty()) return
        val ctx = RimeNative.getContext(session)
        panel.update(all.toList(), ctx?.preedit ?: "")
        // 面板要盖住键盘：先把键盘的按压状态收掉（和表情面板同一个做法）
        keyboard?.onDetach()
        panel.visibility = View.VISIBLE
        expandShowing = true
        candidateBar?.setExpanded(true)
    }

    /** 收起展开页 */
    private fun hideExpanded() {
        if (!expandShowing && expandedCandidates?.visibility != View.VISIBLE) return
        expandedCandidates?.visibility = View.GONE
        expandShowing = false
        candidateBar?.setExpanded(false)
        // 注意：这里**不能**调 keyboard.onAttach()。
        // TextKeyboard.onAttach() 会把 capsState 重置成 None，
        // 主人正开着大写锁定时收起展开页，Caps 就丢了。
    }

    /** 展开页里点某个候选：全局下标，直接交给引擎 */
    private fun pickExpandedCandidate(globalIndex: Int) {
        val session = RimeEngine.sessionId
        if (session == 0L) return
        try {
            RimeNative.nativeSelectCandidateGlobal(session, globalIndex)
        } catch (t: Throwable) {
            Log.e(TAG, "selectCandidateGlobal 失败", t)
        }
        drainCommit(session)
        refreshCandidates()
        // 收起与否交给 refreshCandidates：还有候选就继续开着，没候选才收
    }

    /**
     * 点展开页左侧的谐音变体：把**当前正在选的那个音节**换成 [variant]，
     * 让候选按新拼法重来。
     *
     * 难点在「串里已经有汉字了」。比如打了 buhaowan、选了「不」之后，
     * preedit 是 `不haowan`（librime 的 `Composition::GetPreedit` 直接拼接，
     * 中间没有分隔符），而 rime 的输入缓存 input_ 里只有 `buhaowan`，
     * 汉字只是「这一段已选」的显示结果。
     *
     * 所以做法是：
     *   1. End 把光标送到整串末尾；
     *   2. 一下一下按 Shift+Left，直到 `caret_pos == sel_start`——
     *      这时光标正好停在「还没转换的那串字母」的起点（见下面注释）；
     * 3. 用 Delete 把这串字母整个删掉（`Editor::DeleteChar` → `Context::DeleteInput`
     *      删的是光标右边，碰不到左边已经选好的汉字）；
     *   4. 把「这一节前面 + [variant] + 这一节后面」重新打进去。
     *
     * 改的是**哪一节**：主人在左列锁了哪个音节，就改哪个（见
     * [ExpandedCandidateView.lockedSyllableIndex]）；没锁就改最前面那个，
     * 也就是引擎正在转换的那一段。
     *
     * 为什么删的是**整串**而不是只删当前音节：音节表可能有歧义
     * （`fangan` 可能是 fang+an，也可能被引擎切 fan+gan）。删整串、
     * 再把 `raw` 里减掉当前音节后剩下的部分原样接回去，就不会因为
     * 谁切得对而少打一个字母。
     *
     * 为什么不用「清空 composition 整串重打」：`nativeClearComposition`
     * 走的是 `Context::AbortComposition`，会把已经选好的字一起丢掉
     * （旧版本就是这么写的，`splitPreedit` 还按空格切——可 preedit 里
     * 压根没空格，整串被当成一段字母重新喂进引擎，汉字就没了）。
     *
     * 为什么不改 schema 开模糊音：雾凇方案里模糊音那几十行全是注释掉的，
     * 开了会让整张候选表的排序全变（主人熟悉的候选顺序会跑掉）。
     * 这里只让「左边的谐音按钮」临时换拼法，不动方案，也不触发重新部署。
     */
    private fun pickFuzzyVariant(variant: String) {
        if (variant.isEmpty() || variant.any { it !in 'a'..'z' }) return
        val session = RimeEngine.sessionId
        if (session == 0L) return
        val ctx = RimeNative.getContext(session) ?: return
        val parsed = parsePreedit(ctx.preedit)
        val raw = parsed.raw
        // 整串字母交给展开页算：用**左列正在显示的那份分段**（主人在左列点了
        // 哪个音节就换哪个），这样 `fui` 这种不是标准拼音的拼法也不会被
        // 音节表拆成 `fu`+`i`，连续换两次谐音也不会错位。
        // 返回 null = 对不上（全成汉字了 / 切分有歧义），什么都不做，
        // 宁可什么都不发生，也不能把主人的字弄丢。
        val rebuilt = expandedCandidates?.planFuzzyPick(variant, raw) ?: return

        // 首选路线：**直接设输入串**（照 fcitx5 的思路，不删字母、不重打）。
        //
        // librime 的 Context::set_input 只改 input_ 和 caret_pos_，
        // 不碰 composition_，所以：
        //   · 已经选成汉字的「不」一个字都不会丢；
        //   · 没有「删光字母→再一个个打回去」的中间状态，
        //     界面看不到任何中间帧，编码区就不会跳、不会乱。
        //
        // 注意 set_input 收的是**整串**输入（含已转汉字那段的字母）：
        // 打 buhaowan 选掉「不」之后，preedit 是「不haowan」，
        // 而 input() 仍然是 buhaowan。少掉 bu 前缀，Segmentation::Reset
        // 从头比对 diff_pos=0，已确认的段会被全部清掉，所以这里必须用整串。
        val fullInput = try {
            RimeNative.nativeGetInput(session)
        } catch (t: Throwable) {
            null
        } ?: ""
        // 从末尾往前把 raw 那串字母在 fullInput 里对齐，允许中间夹分隔符
        // （雾凇的 delimiter 是空格和单引号，input() 里会原样留着）。
        val tailStart = findRawTailStart(fullInput, raw)
        if (tailStart >= 0) {
            val converted = fullInput.substring(0, tailStart)
            if (RimeNative.nativeSetInput(session, converted + rebuilt)) {
                drainCommit(session)
                refreshCandidates()
                return
            }
        }

        // 回退路线：这台机器的 librime 没导出 set_input（老版本），
        // 只能走原来的「跳到字母起点 → 删干净 → 重新打一遍」。
        // 第一步：光标送到整串末尾（navigator 的 End = set_caret_pos(input.length())）
        if (!sendKey(session, FcitxKeyMapping.FcitxKey_End, 0)) return

        // 第二步：往回跳到「还没转换的那串字母」的起点。
        //
        // 判据用引擎自己给的：`Composition::GetPreedit` 里，光标落在某段起点时
        // 会把 caret_pos 记成那一刻的 preedit 长度；而最后一段（高亮段）开始时
        // sel_start 也正好是那一刻的长度。所以 **caret_pos == sel_start**
        // 就是「光标正好停在未转换字母的开头」。
        //
        // 为什么不数着跳固定次数：`left_by_syllable` 按引擎自己的音节切分跳
        // （候选是整词时还会按词里的音节跳），一次可能直接跳过 hao 落到 wan，
        // 数次数一定会数错。所以这里每跳一次都读一遍引擎状态来确认。
        //
        // 跳过头也有保护：`Navigator::JumpLeft` 的 new_pos 取
        // `max(confirmed_pos, ...)`，光标绝不会越到已经选好的字里。
        var guard = parsed.syllables.size + 2
        var reached = false
        while (guard-- > 0) {
            val now = RimeNative.getContext(session) ?: return
            if (now.caretPos >= 0 && now.caretPos == now.selStart) {
                reached = true
                break
            }
            // 已经在最前面了还跳不动，就别再试
            if (!sendKey(session, FcitxKeyMapping.FcitxKey_Left, SHIFT_MASK)) break
        }
        if (!reached) return

        // 第三步：把这串还没转换的字母整个删掉。
        // Delete = `Editor::DeleteChar` → `Context::DeleteInput`（删光标右边），
        // 已选的汉字都在光标左边，一个都不会动。
        //
        // 为什么按「删到没字母为止」而不是数 raw.length 次：input_ 里可能还留着
        // 分隔符（主人打了 bu'haowan 这种），raw 去掉分隔符后就比实际字符少，
        // 数次数会删不干净。这里每删一次都读一遍引擎状态来收尾。
        var guardDel = raw.length + 8
        while (guardDel-- > 0) {
            val now = RimeNative.getContext(session) ?: return
            if (parsePreedit(now.preedit).raw.isEmpty()) break
            if (!sendKey(session, FcitxKeyMapping.FcitxKey_Delete, 0)) return
        }

        // 第四步：把算好的整串字母重新打进去
        //
        // 整串（前面几节 + 新拼法 + 后面几节）已经在展开页那边按
        // 「左列正在显示的分段」算好了，这里只管照着打。
        for (ch in rebuilt) {
            if (!sendKey(session, ch.code, 0)) return
        }

        drainCommit(session)
        refreshCandidates()
    }

    /** 往引擎送一个按键，吞掉异常。成功（引擎吃了这个键）返回 true */
    private fun sendKey(session: Long, sym: Int, mask: Int): Boolean = try {
        RimeNative.nativeProcessKey(session, sym, mask)
    } catch (t: Throwable) {
        Log.e(TAG, "送按键失败 sym=$sym mask=$mask", t)
        false
    }

    /**
     * 展开页退格。
     *
     * 主人要的手感：**第一下只解锁，不删字母**。
     *   - 左列有音节被锁着 → 这一下只解除高亮，拼音一个字母都不动；
     *   - 否则 → 和主键盘一样，正常交给引擎退格。
     *
     * 为什么不用 rime 原生的 `Editor::BackToPreviousInput`：
     * 那是 rime 自己记的「刚刚选了哪个字」，和主人在左列亲手点的锁不是一回事。
     * 主人在左列点了 hao 想改它，退格第一下就该只把 hao 放开，
     * 不该悄悄去动别的地方。
     */
    private fun dispatchExpandedBackspace() {
        val panel = expandedCandidates
        if (panel != null && panel.releaseSyllableLock()) {
            // 这一下只解锁，不删任何字母
            return
        }
        dispatchBackspace()
    }

    /** 展开页退格：和主键盘走同一条分发器 */
    private fun dispatchBackspace() {
        dispatcher.onKeyAction(
            KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)),
            KeyActionListener.Source.Keyboard
        )
    }

    /** 展开页回车：和主键盘同一个分发器 */
    private fun dispatchReturn() {
        dispatcher.onKeyAction(
            KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return)),
            KeyActionListener.Source.Keyboard
        )
    }


    /** 从引擎读上下文刷新候选栏 */
    private fun refreshCandidates() {
        val bar = candidateBar ?: return
        val session = RimeEngine.sessionId
        if (session == 0L) {
            updatePreedit("")
            bar.clear()
            lastCandidateCount = 0
            if (expandShowing) hideExpanded()
            return
        }
        val ctx = RimeNative.getContext(session)
        if (ctx == null || (ctx.preedit.isEmpty() && ctx.candidates.isEmpty())) {
            updatePreedit("")
            bar.clear()
            lastCandidateCount = 0
            if (expandShowing) hideExpanded()
            return
        }
        val preedit = ctx.preedit
        val candidates = ctx.candidates
        // 输入码交给码区。只要文字 + 底块，不要高亮，
        // 所以 sel_start/sel_end 在这里用不上（JNI 仍然会返回，留给以后做分段高亮用）。
        updatePreedit(preedit)

        val menu = try {
            RimeNative.nativeGetMenuInfo(session)
        } catch (t: Throwable) {
            null
        }
        val count = menu?.getOrNull(2) ?: candidates.size
        lastCandidateCount = count
        bar.update(candidates)
        // 展开页开着的时候，跟着引擎的候选表一起变（选词、翻页、退格后都要同步）
        if (expandShowing) {
            val all = try {
                RimeNative.nativeGetAllCandidates(session)
            } catch (t: Throwable) {
                Log.e(TAG, "getAllCandidates 失败", t)
                null
            }
            if (all.isNullOrEmpty()) {
                hideExpanded()
            } else {
                expandedCandidates?.update(all.toList(), preedit)
            }
        }
    }

    /**
     * rime 上下文 → fcitx5 的 [FcitxEvent.InputPanelEvent.Data] → [PreeditUi.update]。
     *
     * 主人要求：**保留底色块，但不要高亮**。
     * 所以整串只做一段 NoFlag，不打 HighLight flag ——
     * `FormattedText.toSpannedString()` 就不会套任何 BackgroundColorSpan，
     * 只剩 TextView 自己那层 barColor 底块（那是外层的，不是 span）。
     *
     * 显示与否照 `PreeditUi` 的 `visible = hasUp || hasDown`：
     * 没输入码 → 整行 GONE，什么都不留。
     */
    private fun updatePreedit(preedit: String) {
        val ui = preeditUi ?: return
        if (preedit.isEmpty()) {
            ui.update(FcitxEvent.InputPanelEvent.Data())
            ui.root.visibility = View.GONE
            return
        }
        // 整串一段 NoFlag：只要底块，不要 HighLight 的深色 span
        val text = FormattedText(
            arrayOf(preedit),
            intArrayOf(TextFormatFlag.NoFlag.flag),
            -1
        )
        ui.update(
            FcitxEvent.InputPanelEvent.Data(
                preedit = text,
                auxUp = FormattedText.Empty,
                auxDown = FormattedText.Empty
            )
        )
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.GONE
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

    /** 当前方向下键盘左右留白（dp） */
    private val keyboardSidePaddingDp: Int
        get() {
            val prefs = AppPrefs.getInstance().keyboard
            val landscape =
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            return if (landscape) prefs.keyboardSidePaddingLandscape.getValue()
            else prefs.keyboardSidePadding.getValue()
        }

    /** 当前方向下键盘底部留白（dp） */
    private val keyboardBottomPaddingDp: Int
        get() {
            val prefs = AppPrefs.getInstance().keyboard
            val landscape =
                resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            return if (landscape) prefs.keyboardBottomPaddingLandscape.getValue()
            else prefs.keyboardBottomPadding.getValue()
        }
}

