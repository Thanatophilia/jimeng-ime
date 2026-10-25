package com.jimeng.ime
import android.content.Intent

import android.inputmethodservice.InputMethodService
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
import com.jimeng.ime.kb.clipboard.ClipboardHistory
import com.jimeng.ime.kb.page.ClipboardPage
import com.jimeng.ime.kb.page.InputPage
import com.jimeng.ime.kb.page.MenuPage
import com.jimeng.ime.kb.page.TextEditingPage
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
import com.jimeng.ime.kb.theme.SkinCache
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.kb.utils.AppHolder
import com.jimeng.ime.kb.utils.parsePreedit
import com.jimeng.ime.rime.RimeEngine
import com.jimeng.ime.rime.RimeNative
import com.jimeng.ime.rime.SchemeManager
import com.jimeng.ime.translate.TranslateBarView
import com.jimeng.ime.core.FcitxEvent
import com.jimeng.ime.core.FormattedText
import com.jimeng.ime.core.TextFormatFlag
import com.jimeng.ime.input.preedit.PreeditUi
import com.jimeng.ime.view.BarStateMachine
import com.jimeng.ime.view.CandidateBar
import com.jimeng.ime.view.ExpandedCandidateView
import com.jimeng.ime.view.PickerPanel
import com.jimeng.ime.voice.VoiceAsrEngine
import com.jimeng.ime.voice.VoiceInputController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /**
     * 候选栏状态机。**照抄 fcitx5-android 的 `KawaiiBarStateMachine`**
     * （见 [com.jimeng.ime.view.BarStateMachine] 里的迁移表）。
     *
     * 为什么要它：原来的做法是「`refreshCandidates()` 里 if 一下 preedit 和
     * candidates 都空就 `setToolbarMode(true)`」，而 `resetCandidatesAndPrediction()`
     * 里又直接 `setToolbarMode(true)`——**两个地方各自凭经验判断同一件事**，
     * 一旦某条路径漏掉判断（预测这一支就是），候选栏状态就和引擎对不上。
     *
     * 原版没有这种「两处各判一次」的写法：状态机是唯一权威，
     * 由 `PreeditUpdated` / `CandidatesUpdated` 两个事件推动，UI 只跟着状态变。
     * 这里原样照抄，让它成为「候选栏该显示候选还是工具栏」的唯一出处。
     *
     * 初始化在字段上（不依赖 View）：`onStartInput` 可能早于 `onCreateInputView`，
     * 那时也要能接事件；[applyBarState] 里对 `candidateBar == null` 直接跳过。
     */
    private val barStateMachine = BarStateMachine { state -> applyBarState(state) }

    /**
     * 正在跑的那一次翻译。
     *
     * 翻译是异步的（网络请求在 IO 线程），用户可能连着点几遍翻译键。
     * 后一次点了就把前一次取消掉，否则几次请求的结果会一个接一个往
     * 输入框里塞，越塞越乱。
     *
     * 注意跟语音不是一回事——语音走 VoiceInputController，用 Handler 驱动，
     * 没有协程，两者互不干扰。
     */
    private var translateJob: kotlinx.coroutines.Job? = null

    /**
     * 键盘上方那条翻译栏（null = 现在没开）。
     *
     * 打开后键盘上打的字不再上屏，而是落到这条栏里的输入框；
     * 点栏上的「翻译」才把译文上屏，点「X」关掉。
     *
     * 为什么必须拦截：输入法里的按键本来由 librime 直接 commit 到应用的输入框，
     * 不经过任何 View。想在键盘自己上面先输入一段话再统一上屏，
     * 只能把「上屏」这个动作接管过来（见 commitText / processKey / handleSymFallback）。
     */
    private var translateBar: TranslateBarView? = null

    /** 翻译栏里显示的那行文字（真正上屏的也是它） */
    private var translateBarText: String = ""
    /**
     * 翻译栏当前用的语言对。
     *
     * 缓一份在内存里，是因为栏上的按钮要立刻反映「点了一下变成下一对」，
     * 每次都去读 SharedPreferences（磁盘）没必要，也会让点击有点延迟感。
     * prefs 仍然是真相来源，打开栏和翻译时都会以它为准。
     */
    private var translatePair: com.jimeng.ime.translate.TranslatePair =
        com.jimeng.ime.translate.TranslatePair.ZhEn

    /**
     * 码区那块「落脚高度」（像素）：未测量出来时用的估算值。
     *
     * 翻译栏要落在码区**上方**，所以它下边得空出码区那一截。
     * 码区是浮层、高度 wrap_content，正常显示时能直接量；
     * 但刚打开翻译栏时码区往往是 GONE 的（还没开始打字），量出来是 0，
     * 那就用这个估算值兜住，免得翻译栏落下来把码区的位置占了。
     *
     * 按 PreeditUi 那套配置估：textSize 14sp 的行高 ≈ 17dp，上下 padding 各 1dp
     * （见 buildInputContents 里 setupTextView 的说明），取整 20dp 再留两像素余量。
     */
    private val preeditReservePx: Int
        get() = preeditUi?.root?.height?.takeIf { it > 0 } ?: dp(20)

    /**
     * 照 fcitx5 的 `KawaiiBarComponent.switchUiByState(state)`：
     * 状态变了才动 UI，UI 只认状态，不再自己算。
     */
    private fun applyBarState(state: BarStateMachine.State) {
        val bar = candidateBar ?: return
        when (state) {
            // 空闲：整条候选区让位给工具栏（原版 IdleUi）
            BarStateMachine.State.Idle -> bar.setToolbarMode(true)
            // 有候选：候选区回来（原版 CandidateUi）
            BarStateMachine.State.Candidate -> bar.setToolbarMode(false)
        }
    }

    /** 输入码显示区（拼音串那一行），浮在候选栏上方。照 fcitx5 的 PreeditUi */
    private var preeditUi: PreeditUi? = null

    /**
     * 切语言时在码区位置闪一下的「拼 / En / あ」。
     *
     * 和码区同位置、同一个底块样式，但只在 switchLanguage 那一刻露面：
     * 键盘呼出、开始输入这些都不闪。平时 GONE，不占窗口高度，也不吃触摸。
     */
    private var languageBadge: com.jimeng.ime.view.LanguageBadgeView? = null

    private var keyboard: BaseKeyboard? = null
    private var statusText: TextView? = null
    private var rootView: LinearLayout? = null
    private var keyboardHost: FrameLayout? = null

    /**
     * 语音输入控制器（麦克风 → 录音 → 识别 → 上屏）。
     *
     * 跟视图同生命周期：换皮肤时 [rebuildInputView] 会把浮层一起重搭，
     * 所以它跟着候选栏那些一起在 [buildInputContents] 里重建。
     */
    private var voiceController: VoiceInputController? = null

    /**
     * 窗口的根视图：整个输入法视图最外层那个 FrameLayout。
     *
     * 它一辈子只建一次（[onCreateInputView]），换皮肤时清空它里面的内容再重搭，
     * 不换它本身——键盘正显示时窗口已经挂着它了，把根视图换掉系统不会跟着换。
     */
    private var inputHolder: FrameLayout? = null

    /**
     * 当前这套视图是按哪一套主题搭出来的。
     *
     * 换皮肤广播来的时候拿它和 ThemeManager.activeTheme 比一比：
     * 不一样说明换的是皮肤（整套配色都变了），得整块视图重搭；
     * 一样说明只是改了按键边框 / 尺寸这类外观，重建键盘那一层就够。
     */
    private var builtTheme: Theme? = null
    /** 气泡 / 长按面板总管，root 覆盖整个输入法视图 */
    private var popupComponent: PopupComponent? = null

    /** 候选区工具栏的弹出菜单（⋯ 打开，设置收在里面） */

    /** 剪贴板历史面板（工具栏剪贴板按钮打开） */

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
    /** 引擎是否可用 */
    private var engineReady = false

    /** 当前布局：Text / Number（对应 fcitx5 的 currentKeyboardName） */
    private var currentLayout = TextKeyboard.Name

    /** 上次用过的符号布局，`?123`（to 为空）时回到这里 */
    private var lastSymbolLayout = NumberKeyboard.Name

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
            // 换的是皮肤（配色整套变了）还是只动了按键外观，处理方式不一样：
            //   · 皮肤变了：视图里每个控件都把颜色抄在构造时，只能整块重搭
            //   · 只是边框/描边/水波纹/尺寸：重建键盘那一层就够
            // 用 Theme 的值相等来判断——它是 data class，所有配色和图都在比较范围里。
            if (ThemeManager.activeTheme != builtTheme) {
                rebuildInputView()
                return
            }
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
        // 剪贴板历史：挂系统剪贴板监听，之后在工具栏按钮里翻
        ClipboardHistory.init(this)
        // 复制了新东西 / 待粘贴被作废 -> 立刻刷新候选栏上的快捷粘贴
        ClipboardHistory.onPendingChanged = { refreshQuickPaste() }

        dispatcher = RimeKeyActionDispatcher(
            sessionProvider = { RimeEngine.sessionId },
            processKey = { session, sym, mask -> processKey(session, sym, mask) },
            commit = { text -> commitText(text) },
            inputConnection = { currentInputConnection },
            onLayoutSwitch = { to -> switchLayout(to) },
            onPickerSwitch = { key -> onPickerSwitch(key) },
            onShowImePicker = { showImePicker() },
            onSwitchLanguage = { switchLanguage() },
            // fcitx5 原版默认 SpaceLongPressBehavior.None：长按空格不做事
            onSpaceLongPress = { },
            // 键盘上任何一次操作 -> 剪贴板快捷粘贴退休
            onAnyKey = { onKeyboardTouched() },
            // 数字键要不要当选择键，照 fcitx5 的判据：preedit 非空才算在打编码。
            // 读引擎当前上下文，preedit 非空 = 正在输入编码，数字键才有选候选的职责；
            // preedit 空但候选栏有字 = 只是预测候选，数字键必须老实上屏数字。
            hasComposingPreedit = {
                val sid = RimeEngine.sessionId
                sid != 0L && (RimeNative.getContext(sid)?.preedit?.isNotEmpty() == true)
            },
            // 当前是不是数字键盘（`?123` 那套）。切布局时 currentLayout 就是权威。
            isNumberKeyboard = { currentLayout == NumberKeyboard.Name },
            // 引擎当前有没有候选（预测候选也算）。
            // 和上面 hasComposingPreedit 一起构成「只剩预测」这一支的判据：
            // preedit 空 + 有候选 = 只是预测挂着，数字键盘按键就该把它收掉。
            hasCandidates = {
                val sid = RimeEngine.sessionId
                sid != 0L && (RimeNative.getContext(sid)?.candidates?.isNotEmpty() == true)
            },
            // 数字键盘字面提交前先 reset —— 照 fcitx5 的
            // `is CommitAction -> { commitAndReset(); commitText(...) }`：
            // 上一轮的预测候选立刻消失、候选栏回到工具栏，然后数字才落进输入框。
            onLiteralCommit = { resetCandidatesAndPrediction() },
            // 翻译栏开着时，退格要作用在栏里，不能去删应用里的字。
            // 只给「栏里有字」这一种情况：空栏时退格照旧删输入框，
            // 用户刚打开栏想改上一句刚打的字，这时候拦着不让删很别扭。
            onTranslateBarActive = { translateBarActive() },
            onTranslateBarBackspaceIfNotEmpty = {
                val bar = translateBar
                if (bar != null && bar.content.isNotEmpty()) {
                    bar.backspace()
                    translateBarText = bar.content
                    true
                } else {
                    false
                }
            },
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
        // 换语言前先清干净。用 resetCandidatesAndPrediction 而不是裸的
        // nativeClearComposition：后者走 Context::Clear()，会触发
        // update_notifier_ → Predictor 重算预测（predictor.cc:23），
        // 等于「清一下反而把预测救回来」。详见那个函数的注释。
        resetCandidatesAndPrediction()
        if (RimeEngine.switchLanguage(schemaId)) {
            applyLanguageToKeyboard()
            refreshCandidates()
            // 从系统选择器切过来的也算「切换语言」，同样闪一下
            flashLanguageBadge(LanguageData.of(schemaId))
        }
    }

    // ------------------------------------------------------------ 视图

    override fun onCreateInputView(): View {
        // 最外层 holder 是窗口的根视图。换成皮肤时[rebuildInputView]会清空它、
        // 按新主题重挂里面所有东西，但 holder 本身一直是这一个。
        val holder = FrameLayout(this).apply {
            // 输入法窗口现在是全屏，这一层必须透明，
            // 否则键盘底色会糊满整个屏幕。视觉上的底色由里面的 root 提供。
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 气泡会画到键盘上方，FrameLayout 默认会裁剪，这里放开
            clipChildren = false
            clipToPadding = false
        }
        inputHolder = holder
        buildInputContents(holder)
        return holder
    }

    /**
     * 把输入视图的内容搭出来，挂进 [holder]：主视图、气泡层、码区浮层。
     *
     * ── 为什么从 [onCreateInputView] 里抽出来 ──────────────────
     * 换皮肤时整块内容要按新主题重搭一遍（见 [rebuildInputView]）。
     *
     * 视图里几乎所有控件的颜色都是在**构造时**从主题里抄进自己字段的：
     *   · CandidateBar    → 底色 / 背景图 / 候选词文字色 / 图标色 / 分隔线 / 按压高亮
     *   · PreeditUi       → 码区底块颜色
     *   · PickerPanel     → 表情面板整片配色
     *   · ExpandedCandidateView → 展开页整片配色
     *   · PopupComponent  → 长按九宫格配色
     * 换皮肤时只改 ThemeManager.activeTheme 是刷不到它们的——它们手上还攥着旧值。
     * 逐处写刷新方法又长又容易漏，漏一处就是「底色换了、字还是旧色」这种半新半旧。
     * 所以统一按新主题重新构造一遍，一次对齐。
     */
    private fun buildInputContents(holder: FrameLayout) {
        val theme = ThemeManager.activeTheme

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 底色不在这里设：统一由 applyContainerBackground 铺（见下方）。
            // 建视图和换皮肤走同一份逻辑，才不会出现「底色留在上一套」。
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
        // 用户要：底色块保留，但高度压薄一点。
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
        val bar = CandidateBar(this).apply {
            listener = this@JimengIME
            // 点 ⋯ / 剪贴板 -> 整块换页（照 fcitx5 的 attachWindow），候选栏变「← 标题」
            onMoreClicked = { showMenuPage() }
            onToolbarModeChanged = { showing ->
                if (!showing) detachPage()
            }
            onTextEditingClicked = { showTextEditingPage() }
            onClipboardClicked = { showClipboardPage() }
            // 候选栏最右端的常驻麦克风：接语音转文本
            // 一次按下 = 一个语义：没在录就开麦，正在录就收尾上屏
            onMicClicked = { voiceController?.toggle() }
            // 右端「收起键盘」箭头（设置里关掉语音按钮时出现）：直接收键盘。
            // 只是暂时隐藏，输入会话和已打的字都还在，再点输入框就回来——
            // 跟 fcitx5 的 hideKeyboardButton 行为一致。
            onHideKeyboardClicked = { requestHideSelf(0) }
            // 候选栏左上角的返回箭头：从换页退回键盘
            onBackClicked = { detachPage() }
            // 点快捷粘贴胶囊：把这条内容直接上屏，然后这条就作废了
            onQuickPasteClicked = { quickPasteNow() }
        }
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        candidateBar = bar
        // 视图刚建出来：把状态机当前的状态同步到新的候选栏上。
        //
        // 必须显式同步，因为 [applyBarState] 只在**状态发生变化**时才回调：
        // 状态机在 onCreateInputView 之前就已经被 onStartInput / reset 推过
        // （那时 candidateBar 还是 null，回调被跳过），如果此后状态一直没变，
        // 新建出来的 bar 就会停在默认的「候选模式」——
        // 明明该显示工具栏，用户看到的却是一条空候选栏。
        // 照 fcitx5：`KawaiiBarComponent` 的 switchUiByState 在建视图时
        // 由 barStateMachine 的初始状态推一次，之后才只跟变化。
        applyBarState(barStateMachine.state)

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
        // 皮肤的背景图铺满整个键盘区域。没有图就由键盘自己填纯色，
        // 这里不设背景不会盖掉下面的内容。
        // （换皮肤时由 applyContainerBackground 再刷一次，见 rebuildKeyboard）
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

        // 底色和背景图统一在这里铺一次。换皮肤时 rebuildKeyboard 会再调一次，
        // 两处共用同一份逻辑，避免「建视图时设了、换皮肤时忘了」这种错位。
        applyContainerBackground(theme)

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
        val pc = PopupComponent(this, theme) { RimeEngine.isFullShape() }
        popupComponent = pc
        holder.addView(
            root,
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
        // 而且 GONE 之后窗口仍留着一段纯白空带（用户看到的那条）。
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

        // ---- 切语言提示（和码区重合的那一层）----
        // 和码区放同一个重力、同一个 bottomMargin：切换语言时它就在码区的位置露一下，
        // 视觉上像是码区自己换了内容，不像凭空冒出一个新东西。
        // 它同样不参与窗口高度计算，所以键盘不会因为它出现而动。
        // 只在 switchLanguage 里 show() —— 需求是「只在切换时出现一下」。
        val badge = com.jimeng.ime.view.LanguageBadgeView(this, theme)
        languageBadge = badge
        holder.addView(
            badge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = keyboardHeightPx + dp(CandidateBar.HEIGHT_DP.toInt())
            }
        )
        // 键盘接上气泡总管
        (keyboard as? BaseKeyboard)?.popupActionListener = pc.listener

        // ---- 语音输入条 ----
        // 用户要的是「录音时别弹窗，就放候选栏里」。所以它不再挂在 holder 上
        // 飘在键盘上方，而是塞进候选栏中间那一格（见 CandidateBar.attachVoiceBar）：
        //   · 高度就是候选栏的 40dp，不额外吃窗口高度，键盘不会跳
        //   · 不盖住上面的应用，也不挡候选栏右端的麦克风
        // 顺序要在 PopupComponent 之前建，长按气泡始终盖在最上面。
        val voice = VoiceInputController(this, theme).apply {
            onCommit = { text -> commitText(text) }
            onOpenPermissionSettings = { openAppSettings() }
            // 没下模型：把用户送到扩展商店，那儿有下载按钮。
            // 用 NEW_TASK 是因为输入法服务不是 Activity 栈里的常态成员，
            // 不带这个 flag 在部分 ROM 上会直接被拒。
            onModelMissing = { openStore() }
            // 录音条一露脸，候选区就得让位；收起后按当前状态还回去
            onBarVisibleChanged = { visible -> candidateBar?.setVoiceMode(visible) }
        }
        voiceController = voice
        // 塞进候选栏的语音槽位（同一格、同一高度，盖在候选区之上）
        candidateBar?.attachVoiceBar(voice.bar)
        // 键盘一露面就后台预热模型，等用户按麦克风时通常已经热了。
        // 没有模型就别白忙 —— preload 自己会很快失败返回，这里先挡一下更清楚
        if (VoiceAsrEngine.hasModel(this)) VoiceAsrEngine.preload(this)

        // 记下这套视图是按哪套主题搭的。换皮肤广播靠它判断要不要整块重搭
        builtTheme = theme
    }

    /**
     * 换皮肤：按新主题把整个输入视图重搭一遍，然后换上。
     *
     * ── 为什么不能只刷背景色 ────────────────────────────────
     * 候选栏的问题不是「底色没换」，而是**整条候选栏都在用旧主题的颜色**：
     *   · 底色 / 背景图        CandidateBar.init
     *   · 候选词文字色         CandidateItemView 构造时 setTextColor
     *   · 图标色 / 按压高亮    ToolbarButtonView、ExpandButtonView 构造时读
     *   · 分隔线               CandidateBar 构造时 paint.color
     *   · 码区底块 / 表情面板 / 展开页 / 长按九宫格 —— 同样是构造时抄一份
     * 只改背景色的话，换到深色皮肤会变成「浅色底 + 近白字」（Pixel 暗色
     * 的 candidateTextColor 是 #FAFAFA），候选词直接看不清。
     *
     * ── 状态怎么处理 ────────────────────────────────────────
     * 旧视图上挂着的临时状态先收干净再换：
     *   · 换页（剪贴板 / 文本编辑 / 菜单）→ detachPage 还回键盘本体
     *   · 展开候选页 → 收起
     *   · 表情 / 符号面板 → 收起
     * 这几个都是「上一套视图上的东西」，留着也没有意义。
     *
     * 换完以后 [buildInputView] 里已经把该接的都接上了：键盘按 [currentLayout]
     * 造好、语言标签、状态条、候选表、码区都会走一遍（它自己末尾就调了
     * applyLanguageToKeyboard / syncStatus / refreshCandidates）。
     */
    private fun rebuildInputView() {
        val holder = inputHolder ?: return
        // 翻译栏也挂在 holder 里面，`removeAllViews` 会把它的视图一起摘掉。
        // 这里先记下它开没开，重建完再挂回去——换皮肤是低频动作，
        // 但用户正打到一半的字不该因为换了张皮就丢了（[translateBarText] 兜住内容）。
        val barWasOpen = translateBar != null
        // 旧视图上的临时页先收掉：它们挂在旧视图上，换了新视图就成孤儿了
        detachPage()
        if (expandedCandidates?.visibility == View.VISIBLE) hideExpanded()
        hidePicker()
        // 语音浮层也要跟着旧视图一起下掉，否则那边还在录音、
        // 这边新浮层已经建好，两边状态就对不上了
        voiceController?.shutdown()
        voiceController = null
        // 旧内容整体摘掉，再按新主题重新搭一遍挂回去。
        // 摘下来的旧 View 会走 onDetachedFromWindow，把 prefs 监听自己摘掉
        // （CandidateBar 就是这么做的），不会留下抢着改旧图的野监听。
        holder.removeAllViews()
        // 上面这一行已经把翻译栏的视图摘下来了，字段不能还攥着那个旧 view，
        // 否则下次打开会因为 `if (translateBar != null) return` 直接跳过，
        // 表现为「点了快捷翻译没反应」。
        translateBar = null
        buildInputContents(holder)
        // 重建会把 root 的 topMargin 重新按 popupReservePx 设一遍，
        // 翻译栏要恢复的话得在重建之后——openTranslateBar 会自己再让一次高度。
        if (barWasOpen) openTranslateBar()
        // 新视图建完，把状态机当前状态再推一次（见 buildInputContents 里的说明：
        // applyBarState 只在状态变化时回调，重建视图时状态往往没变，
        // 不补这一下，新候选栏就会停在默认模式上）。
        applyBarState(barStateMachine.state)
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
                var changed = false
                val target = dp(keyboardBottomPaddingDp)
                if (lp.bottomMargin != target) {
                    lp.bottomMargin = target
                    changed = true
                }
                // 上方预留区要跟着重算：键盘变高以后，留给气泡的空间必须变小，
                // 否则窗口总高（预留区 + 候选栏 + 键盘 + 底部留白）会超过屏幕，
                // 窗口底部那段被挤到屏幕外，最底下那排键就按不到了。
                // 翻译栏开着的话，它占掉的那一行也在 root 里，同样要扣掉
                // ——否则键盘一改尺寸（比如拉高度），这里的重算会把
                // openTranslateBar 让出去的那截又加回来，窗口就顶出屏幕。
                val barH = translateBar?.height ?: 0
                val reserve = (popupReservePx - barH).coerceAtLeast(0)
                if (lp.topMargin != reserve) {
                    lp.topMargin = reserve
                    changed = true
                }
                if (changed) root.layoutParams = lp
            }
        }
        // 码区浮层贴在「键盘高度 + 候选栏 + 底部留白」之上，键盘动了它也要跟着挪。
        // 翻译栏的位置也由这个函数顺手刷（它挂在 root 里，位置不跟键盘高度走，
        // 但要跟码区高度走，见 applyFloatingBottomMargin 的说明）。
        applyFloatingBottomMargin()
    }

    /**
     * 把码区和切语言提示这两个浮层的 bottomMargin 刷成当前该有的值。
     *
     * 两者永远叠在同一处（切语言提示正好盖在码区位置上），
     * 所以用同一个函数算，避免哪天只改了一边、错开一条缝。
     *
     * 基准 = 键盘高度 + 候选栏高度 + 底部留白。
     *
     * **码区永远贴着候选栏**，不随翻译栏让位。翻译栏是**浮在码区上方**的
     * （见 [openTranslateBar]），靠 root 里那行的 bottomMargin 让出码区那一截，
     * 所以这里只需要管码区自己的位置，不用再加什么抬升量。
     */
    private fun applyFloatingBottomMargin() {
        val target = keyboardHeightPx + dp(CandidateBar.HEIGHT_DP.toInt()) +
            dp(keyboardBottomPaddingDp)
        preeditUi?.root?.let { r ->
            (r.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.bottomMargin != target) {
                    lp.bottomMargin = target
                    r.layoutParams = lp
                }
            }
        }
        languageBadge?.let { b ->
            (b.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.bottomMargin != target) {
                    lp.bottomMargin = target
                    b.layoutParams = lp
                }
            }
        }
        // 翻译栏的空档跟着码区高度走：码区一显示（有了真实高度）
        // 翻译栏就往上让，免得压在码区上。
        syncTranslateBarBottomMargin()
    }

    /**
     * 给 root 里那行翻译栏留出「码区的高度」，让它浮在码区上方。
     *
     * 翻译栏是 root（竖排 LinearLayout）的第 0 个子 View，下面依次是
     * 码区（浮层，贴着候选栏）→ 候选栏 → 键盘。它要落在码区上方，
     * 就得用 bottomMargin 把码区那一截空出来——视觉顺序变成：
     * 翻译栏 → 码区 → 候选栏 → 键盘。
     *
     * 码区没显示（GONE，高度 0）时用 [preeditReservePx] 的估算值兜住，
     * 这样开始打字、码区冒出来时也不会把它顶掉。
     */
    private fun syncTranslateBarBottomMargin() {
        val bar = translateBar ?: return
        val lp = bar.layoutParams as? LinearLayout.LayoutParams ?: return
        val target = preeditReservePx + dp(2)
        if (lp.bottomMargin != target) {
            lp.bottomMargin = target
            bar.layoutParams = lp
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

    /**
     * 气泡 / 长按面板向上探出所需的预留空间。
     *
     * 取够用的最大值：九宫格最多 5 行时，容器上沿大约要探出
     * `popupHeight + keyHeight × (行数 - 1)` ≈ 254dp，减去候选栏的 44dp，
     * 约 210dp。这里取 240dp 留点余量。
     *
     * **但必须给屏幕高度让路。**
     *
     * 窗口高度 = 预留区 + 候选栏 + 键盘 + 底部留白。预留区是个定值时，
     * 键盘一拉高这几段合起来就会超过屏幕：窗口撑出屏幕下沿，
     * 键盘最底下那一截（调整键盘页的把手就在那儿）跟着出界——
     * 用户遇到过：拉高之后把手看不见了，在键盘里没法再往下拖回来，
     * 只能跑去 App 设置里改。
     *
     * 所以这里改成动态的：先让候选栏和键盘吃饱，剩下的才归气泡。
     * 键盘高到没余量时预留区归零，气泡就探不出去了——这是应该的，
     * 键盘都快占满屏幕了，本来也没地方给它探。
     *
     * **基准高度不是「屏幕高」。**
     *
     * 输入法窗口的 fitSides 是 LEFT TOP RIGHT——顶部要避开状态栏，
     * 那 40dp 根本不归我们。按屏幕高（792dp）算出来的可用量比真实值
     * 多 40dp，键盘拖到 70% 时窗口仍然会超，表现就是「改了没用」。
     * 必须扣掉状态栏高度。
     */
    private val popupReservePx: Int
        get() {
            val screen = resources.displayMetrics.heightPixels - statusBarHeightPx()
            // 窗口里除了预留区之外的固定开销：候选栏 + 键盘本体 + 底部留白
            val used = dp(CandidateBar.HEIGHT_DP.toInt()) + keyboardHeightPx +
                dp(keyboardBottomPaddingDp)
            // 再留 16dp 余量：状态栏高度在个别机型上会有一两像素偏差，
            // 卡到极限时窗口仍可能被切掉一条——留点缝比正好贴边稳。
            // 键盘拉到 70% 时预留区会被压到 150dp 上下，气泡仍能往上探一截。
            val avail = screen - used - dp(16)
            return dp(240).coerceAtMost(avail.coerceAtLeast(0))
        }

    /**
     * 状态栏高度（像素）。
     *
     * 输入法窗口顶部避开状态栏，算窗口可用高度时必须把这截扣掉。
     * 拿不到系统资源时按 24dp 兜底（绝大多数机型的量级）。
     */
    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    // ------------------------------------------------------------ 生命周期

    override fun onStartInput(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        val session = RimeEngine.ensureSession()
        engineReady = session != 0L
        // **不要用 nativeClearComposition 清这里。**
        //
        // Context::Clear() 末尾会调 update_notifier_()（librime src/rime/context.cc:106），
        // 而 Predictor 正好挂在这个通知上（librime-predict src/predictor.cc:23）。
        // 于是「清 composition」这个动作本身会触发它按 commit_history 重新预测一遍，
        // 把上一轮的预测段现场建回来 —— 越清越有，就是之前呼出还看见旧预测的原因。
        //
        // 正确顺序在 resetCandidatesAndPrediction() 里：先送 Escape 把 Predictor
        // 的 last_action_ 置成 kDelete（之后 OnContextUpdate 直接 return，不再预测），
        // 再清 composition。清完引擎里是真空的，refreshCandidates 也读不出东西。
        resetCandidatesAndPrediction()
        // 换了个输入框，展开页不该还盖着
        if (expandShowing) hideExpanded()
        // 工具栏按钮排也收回收起态（没打字时整条仍以工具栏待命）
        candidateBar?.setToolbarExpanded(false)
        // 符号 / Emoji 面板同理：那是上一轮留下的状态，新输入框要看到键盘
        hidePicker()
        // 每次开始输入都回到「打字界面」。
        //
        // 照 fcitx5 的 KeyboardWindow.onStartInput：它按输入框类型决定该用哪个
        // 布局，然后 switchLayout(target, remember = false) 强制切过去——
        // remember = false 表示**不改**「上次用的符号页」那条记忆，
        // 只把当前这一层换掉。所以用户上次停在 ?123 或符号页，
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
        // 键盘每次弹出：先把上一轮留在引擎和候选栏里的东西清掉。
        //
        // 必须在这里清，因为这是「收起再呼出」唯一保证会走到的回调
        // （onStartInput 只在开始输入时走一次，收起来再点同一个输入框不会再走）。
        // 也必须在 switchLayout 之前清：switchLayout 在布局没变时直接 return，
        // 不会刷新候选栏，所以别指望它把旧候选刷掉。
        resetCandidatesAndPrediction()
        // 展开页盖着键盘时收起来——弹出键盘是要打字，不是看候选网格
        if (expandShowing) hideExpanded()
        // 换页也不留着跨输入轮（回到键盘本体）
        detachPage()
        // 符号 / Emoji 面板也要收：面板是「上一轮留在这里的状态」，
        // 不收的话即使键盘换成文字键盘，面板还盖在上面，用户看到的依旧是符号页。
        hidePicker()
        switchLayout(startLayoutFor(info), remember = false)
        // 补读一次系统剪贴板：键盘收着的时候复制的东西，监听器没听见。
        // 补读会把内容变成「待粘贴」，候选栏才冒得出快捷粘贴。
        ClipboardHistory.syncFromSystem()
        refreshQuickPaste()
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

    /**
     * 清掉上一轮留下的候选/预测状态。
     *
     * 照 fcitx5-android 的两层做法：
     *   1. 引擎层：FcitxInputMethodService.onFinishInputView(finishingInput = true)
     *      → `reset()`，注释原话「clear fcitx-side input panel state (preedit +
     *      candidate list), otherwise the stale candidates would be re-pushed to
     *      the UI the next time the keyboard is shown」。
     *      我们这边对应 librime-predict 的 predictor.cc:36-45：
     *      收到 XK_Escape 会 `predict_engine_->Clear()`、把 last_action_ 置成
     *      kDelete（之后不再重新预测），并把带 "prediction" tag 的段清掉。
     *      所以送一个 Escape 就是 librime 版的 reset。
     *   2. UI 层：HorizontalCandidateComponent.onFinishInputView
     *      → `adapter.updateCandidates(emptyArray(), 0)` 清候选栏。
     *
     * 为什么必须先清引擎再清 UI：只清 UI 的话，下次键盘呼出时引擎里
     * 那些预测候选还在，refreshCandidates 会把它们重新推回候选栏。
     */
    private fun resetCandidatesAndPrediction() {
        val session = RimeEngine.sessionId
        if (session != 0L) {
            // Escape：清预测引擎 + 清预测段（predictor.cc 的官方开关）
            try {
                RimeNative.nativeProcessKey(
                    session,
                    com.jimeng.ime.kb.core.FcitxKeyMapping.FcitxKey_Escape,
                    0
                )
            } catch (t: Throwable) {
                Log.w(TAG, "reset prediction 失败", t)
            }
            // 再把剩下的 composition 清干净（Escape 只清预测段，
            // 正在打的拼音编码由这条兜底）
            RimeNative.nativeClearComposition(session)
        }
        // UI 层：照 fcitx5 的 HorizontalCandidateComponent.onFinishInputView
        // `adapter.updateCandidates(emptyArray(), 0)`，清候选栏。
        //
        // 这里也走状态机（原版 onCandidateUpdate 的入口就是 broadcaster 广播
        // 到 KawaiiBarComponent.onCandidateUpdate，同样落到状态机），
        // 而不是直接 setToolbarMode：候选栏长什么样永远只由状态机决定。
        barStateMachine.onPreeditEmptyStateUpdate(true)
        barStateMachine.onCandidateUpdate(emptyList())
        candidateBar?.clear()
        updatePreedit("")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 照 fcitx5 的 FcitxInputMethodService.onFinishInputView：
        //   currentInputConnection?.apply {
        //       finishComposingText()
        //       monitorCursorAnchor(false)
        //   }
        // 键盘收起时把输入框里的 composing 段收尾——否则下次呼出，
        // 输入框那边可能还记着一段「正在输入」，和我们清空的引擎对不上。
        try {
            currentInputConnection?.finishComposingText()
        } catch (t: Throwable) {
            Log.w(TAG, "finishComposingText 失败", t)
        }
        // 键盘被隐藏时要清掉引擎侧的 input panel state（preedit + 候选列表），
        // 否则「残留候选会在下次键盘显示时被重新推给 UI」。
        //
        // **不能加 `if (finishingInput)`**：收起键盘（点返回 / 划下去）时
        // 这个参数常常是 false，加了门就等于从来不清理——之前那版就是这么失效的。
        // 清的是「上一轮留下的候选」，跟 finishing 与否无关，无条件清。
        resetCandidatesAndPrediction()
        // 翻译栏也一起收。
        //
        // 为什么是「收」而不是「留着下次接着打」：这条栏的生命周期
        // 应当跟这一次输入走。用户切去别的应用、点返回收键盘，
        // 下次弹出键盘时顶着一句上回没翻完的话，那既挡键盘又让人莫名
        // —— 而且它占着 rootView 的一行，键盘高度会一直多出这一截。
        // 内容也不留：栏关掉就是「这次翻译结束了」。
        closeTranslateBar()
    }

    override fun onFinishInput() {
        // 换输入框/收键盘就把麦克风彻底放掉，别留后台一直在录
        voiceController?.shutdown()
        if (expandShowing) hideExpanded()
        // 键盘收起：清候选栏 + 清引擎侧预测状态（照 fcitx5 的两层做法）
        resetCandidatesAndPrediction()
        super.onFinishInput()
    }

    override fun onDestroy() {
        // 先注销广播，免得销毁过程中还收到重建立
        try {
            unregisterReceiver(keyAppearanceReceiver)
        } catch (_: IllegalArgumentException) {
            // 没注册成功过就算了
        }
        // 正在翻的那次也停掉：服务没了，结果塞不进任何输入框
        translateJob?.cancel()
        translateJob = null
        // 翻译栏从视图树上摘下来。onDestroy 之后 inputHolder 会被系统回收，
        // 但先把我们这边的引用放掉，免得留着指向已销毁 Context 的 View
        (translateBar?.parent as? ViewGroup)?.removeView(translateBar)
        translateBar = null
        translateBarText = ""
        RimeEngine.callback = null
        RimeEngine.releaseSession()
        // 剪贴板监听也摘掉，服务销毁了不该再收广播
        ClipboardHistory.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 照 fcitx5 的 FcitxInputMethodService.onConfigurationChanged：
        //   override fun onConfigurationChanged(newConfig: Configuration) {
        //       postFcitxJob { reset() }
        //       ...
        //   }
        // 配置一变（旋转、窗口尺寸、字体缩放……）就把候选/预测清掉，
        // 否则那些按旧配置算出来的候选会留着，UI 却是按新配置重排的。
        resetCandidatesAndPrediction()
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
                // 换方案后部署的可能是白霜/万象/墨奇，不能一直写「雾凇拼音」
                val name = SchemeManager.active()?.name ?: "雾凇拼音"
                status.text = "正在部署$name，首次需要几十秒…"
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
     *   `remember = true`（默认）—— 用户亲手按 `?123` 时用。切到符号布局就
     *   记进 [lastSymbolLayout]，下次再按 `?123` 回到同一个符号页。
     *
     *   `remember = false` —— 键盘每次呼出时用（见 [onStartInput]）。只把当前这层
     *   换回打字界面，**不动**那条记忆。照 fcitx5 `KeyboardWindow.switchLayout`
     *   的 remember 参数：用户的习惯要留着，但这次不是用户按的，不许改它。
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
        // **切布局不碰引擎。**
        //
        // 照 fcitx5 的 `KeyboardWindow.switchLayout`：它只做
        // detachCurrentLayout / attachLayout，最后 `notifyBarLayoutChanged()`
        // —— 只是通知候选栏「布局变了」，引擎侧的 context 一个字节都不动。
        // （见 KeyboardWindow.kt:119-139，里面没有任何 reset / clear 调用。）
        //
        // 切布局是纯导航动作，不是输入动作：预测、候选、编码全都该原样留着，
        // 等用户在新布局里真的按下一个输入键，才谈得上「这一轮结束了」。
        //
        // 以前这里写的是 nativeClearComposition（走 Context::Clear()），
        // 那条路还会通过 update_notifier_ 触发 Predictor 重算（predictor.cc:23），
        // 等于「切个布局，预测反而被重新算出来一遍」—— 既然不清了，这个坑也一并没了。
        currentLayout = target
        rebuildKeyboard()
        // 记住这次用的符号布局，下次 `?123` 会回到这里。
        // 键盘自己呼出时（remember = false）不算用户的选择，不改这条记忆。
        if (remember && target != TextKeyboard.Name) lastSymbolLayout = target
        refreshCandidates()
    }

    /**
     * 给最外层容器铺上当前皮肤的底色和背景图。
     *
     * ── 为什么要抽成一个函数 ──────────────────────────────────
     * 这两层东西画在容器上，不在键盘里：
     *   · root.setBackgroundColor(keyboardColor)   → 整个输入法视图的底
     *   · kbHost.background = backgroundImage      → 键盘那一块的底图
     * 建视图时设一次，换皮肤时（[rebuildKeyboard]）必须再设一次，
     * 否则键帽换了新皮肤的配色、底色还留着上一套，看起来就像底色被钉死。
     *
     * 视图还没建出来时（rootView / keyboardHost 是 null）直接跳过。
     */
    private fun applyContainerBackground(theme: Theme) {
        rootView?.setBackgroundColor(theme.keyboardColor)
        val host = keyboardHost ?: return
        // 没有背景图时必须显式清掉旧的，否则上一套皮肤的图会一直贴在下面
        host.background = theme.backgroundImage?.let { img ->
            SkinCache.drawable(this, theme.skinId, img)
        }
    }

    /**
     * 按 [currentLayout] 把键盘那一层整个重建一遍。
     *
     * 两个地方要用它：
     *   1. [switchLayout]——文字键盘 ⇄ 符号键盘
     *   2. 收到 [ACTION_RELOAD_KEY_APPEARANCE]——设置页改了按键外观 / 换了皮肤
     *
     * 第 2 种为什么非要重建：[KeyView] 的 bordered / borderStroke / rippled
     * 三个属性是在构造时从 ThemePrefs 读一次的（照 fcitx5 的写法），
     * 运行中改 prefs 不会自己生效，只能让键帽重新构造一遍。
     *
     * 只动键盘这一层，不重建整个输入视图：候选栏、码区浮层、气泡总管
     * 都保持原样，用户正在打的字和这些视图的状态都不会丢。
     * 外层容器的底色 / 背景图由 [applyContainerBackground] 单独刷。
     */
    private fun rebuildKeyboard() {
        val host = keyboardHost ?: return
        val theme = ThemeManager.activeTheme

        // ---- 先把外层容器的底色和背景图刷成新皮肤的 ----
        // 这两层本来只在 onCreateInputView 里设过一次：
        //   root.setBackgroundColor(theme.keyboardColor)
        //   kbHost.background = backgroundImage
        // 换皮肤时走的正是这里（只重建键盘那一层），不补的话键帽颜色换
        // 成了新皮肤的，底色却还留着上一套——用户会以为「底色被固定住了」。
        applyContainerBackground(theme)

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
        if (RimeEngine.sessionId == 0L) return
        val next = LanguageData.next(RimeEngine.currentSchema)
        // 切换会清掉正在输入的编码，这是引擎行为，先清干净避免残留。
        // 同样走 resetCandidatesAndPrediction：裸的 nativeClearComposition
        // 会通过 Context::Clear() 的 update_notifier_ 把预测重新算回来。
        resetCandidatesAndPrediction()
        if (!RimeEngine.switchLanguage(next.schemaId)) {
            // 切不过去（比如方案没进 schema_list）：留在原语言，别把界面刷成错的
            refreshCandidates()
            return
        }
        // 告诉系统「语言变了」：状态栏标志、系统选择器里的当前项都靠这个更新
        notifySubtypeChanged(next.schemaId)
        applyLanguageToKeyboard()
        refreshCandidates()
        // 码区位置闪一下「拼 / En / あ」，一眼能看到切到哪门语言。
        // 只改语言不清编码，所以提示不会和真编码打架；真开始打字时 updatePreedit 会让位。
        flashLanguageBadge(next)
    }

    /**
     * 在码区位置闪一下这次切到的语言标识。
     *
     * **只在切语言时调**——键盘呼出、开始输入这些都不调，
     * 需求就是「切换时出现一下」，不是常驻标签。
     *
     * 标识本身不是写死的表：由 [LanguageData.badgeOf] 按语言代码推出来，
     * 以后加新语言（韩语、俄语…）只要 languageCode 给对就自动有标识。
     */
    private fun flashLanguageBadge(lang: LanguageData.Lang) {
        val badge = languageBadge ?: return
        // 码区正在显示真编码时不抢它的位置：这一刻更该看到自己打的编码。
        // 看 root 的实际可见性，不信 visible 这个中间状态。
        if (preeditUi?.root?.visibility == View.VISIBLE) return
        badge.show(lang.badge)
    }

    // ------------------------------------------------------------ 翻译栏

    /**
     * 打开键盘上方那条翻译栏。
     *
     * 打开之后，键盘打出来的字不再进应用输入框，而是攒在这条栏里
     * （拦截逻辑在 [commitText]）。
     *
     * 位置：插在 rootView 的 **index 0**（root 是竖排的），但**不是**直接贴
     * 候选栏——它下面还要留出码区那一截（bottomMargin = [preeditReservePx]）。
     * 视觉顺序从上到下是：
     *
     *     翻译栏 → 码区（贴候选栏）→ 候选栏 → 键盘
     *
     * 也就是说它浮在码区**上方**，码区本身不动、始终贴着候选栏。
     *
     * 为什么不把翻译栏也做成浮层（挂 holder、GRAVITY_BOTTOM）：
     * 那样它会落到 [onComputeInsets] 报的 visibleTopInsets 之上，
     * 那块区域系统算作「应用内容」，触摸会穿透过去，
     * 栏上的语言对按钮和 ✕ 就点不到了。留在 root 里才在可见区内。
     */
    private fun openTranslateBar() {
        if (translateBar != null) return
        val root = rootView ?: return
        val t = ThemeManager.activeTheme
        // 每次打开都以 prefs 为准：用户可能刚在设置页改过语言对，
        // 而内存里这份是上一次的。prefs 是磁盘读，一次几十微秒，不心疼。
        translatePair = com.jimeng.ime.translate.TranslateConfigStore.settings(this).pair
        val v = TranslateBarView(
            this,
            t,
            onClose = { closeTranslateBar() },
            onTranslate = { doTranslateBar() },
            onPairChanged = { pair ->
                // 存下来，设置页和下一次翻译都用这一对
                com.jimeng.ime.translate.TranslateConfigStore.savePair(this, pair)
                translatePair = pair
            }
        )
        // 已经挂着字就沿用（关掉再开不该丢内容），语言对也跟上 prefs
        v.setText(translateBarText)
        v.setPair(translatePair)
        root.addView(
            v,
            0,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.leftMargin = dp(8)
                it.rightMargin = dp(8)
                // 上边留一点：它上面是窗口预留区（透明），贴太紧显得糊。
                // 下边那截空出来给码区——见 syncTranslateBarBottomMargin。
                it.topMargin = dp(6)
                it.bottomMargin = preeditReservePx + dp(2)
            }
        )
        translateBar = v
        // 让出一截气泡预留区给它，窗口总高度不变。
        //
        // 不加这一句会出真问题：窗口高度 = 预留区 + 候选栏 + 键盘 + 留白，
        // 且窗口贴屏幕底部、向上生长。翻译栏插在 root 顶部会让 root 变高
        // 约 40dp，窗口就顶着屏幕往上再长 40dp；键盘拉到 70% 时预留区
        // 早就被压到很薄了，再多这 40dp 会把最下面那排键挤出屏幕。
        //
        // 预留区本来就是「透明、平时看不见」的空间，让一截给翻译栏正合适：
        // 代价是气泡能往上探的距离短了一点，换来键盘稳稳贴在原位。
        v.post {
            val h = v.height
            if (h > 0) shiftRootForTranslateBar(-h)
            // 码区这时候可能还没显示（还没打字），高度量不准，重新对一次空档
            syncTranslateBarBottomMargin()
        }
        // 打字走引擎，先把引擎里残留的编码清掉，
        // 免得刚打开就顶着一串拼音候选
        resetCandidatesAndPrediction()
        refreshCandidates()
    }

    /**
     * 平移 root 的 topMargin（也就是气泡预留区的高度）。
     *
     * 翻译栏开/关时用它把「多出来的一行」从预留区里扣掉/还回去，
     * 保证窗口总高度恒定，键盘不会被挤出屏幕。
     */
    private fun shiftRootForTranslateBar(delta: Int) {
        val root = rootView ?: return
        val lp = root.layoutParams as? FrameLayout.LayoutParams ?: return
        val target = (lp.topMargin + delta).coerceAtLeast(0)
        if (lp.topMargin == target) return
        lp.topMargin = target
        root.layoutParams = lp
    }

    /**
     * 关掉翻译栏，把攒下的字清掉。
     *
     * 码区和切语言提示的位置不用还原：它们从来就没为翻译栏让过位
     * （一直是贴着候选栏的），让位的是翻译栏自己。
     */
    private fun closeTranslateBar() {
        val root = rootView
        val bar = translateBar
        if (root != null && bar != null) {
            // 把它占走的那截预留区还回去（和 openTranslateBar 里的 -h 对称）。
            // 必须用摘下来之前的高度：removeView 之后 bar.height 可能已经归 0。
            val h = bar.height
            root.removeView(bar)
            if (h > 0) shiftRootForTranslateBar(h)
        }
        translateBar = null
        translateBarText = ""
        translateJob?.cancel()
        translateJob = null
        refreshCandidates()
    }

    /**
     * 点「翻译」：把栏里的字翻掉，译文直接上屏。
     *
     * 上屏用的是 [commitTextDirect]——绕过 [commitText] 里的翻译栏拦截，
     * 否则译文又被攒回栏里，转圈了。
     */
    private fun doTranslateBar() {
        val bar = translateBar ?: return
        val text = bar.content.trim()
        if (text.isEmpty()) {
            showTranslateToast("先输入要翻译的内容")
            return
        }
        val endpoint = com.jimeng.ime.translate.TranslateConfigStore.resolve(this)
        if (endpoint == null) {
            showTranslateToast("还没有可用的翻译服务，先到设置里配一条")
            return
        }
        val settings = com.jimeng.ime.translate.TranslateConfigStore.settings(this)

        bar.setBusy(true)
        translateJob?.cancel()
        translateJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val translated = com.jimeng.ime.translate.TranslationService.translate(
                    text = text,
                    // 用栏上当前这一对，而不是 prefs 里的：
                    // 用户可能刚在栏上点了两下切到中日，就该按中日翻
                    pair = translatePair,
                    endpoint = endpoint,
                    timeoutSeconds = settings.timeoutSeconds
                )
                // 译文上屏，然后清空栏里的原文，准备下一句
                commitTextDirect(translated)
                bar.setText("")
                translateBarText = ""
                showTranslateToast("已翻译并上屏")
            } catch (t: Throwable) {
                val msg = when (t) {
                    is com.jimeng.ime.translate.TranslateError.Http ->
                        if (t.code == 401 || t.code == 403) "Key 无效或无权限"
                        else "翻译失败（${t.code}）"
                    is com.jimeng.ime.translate.TranslateError.Network -> "网络不通"
                    else -> t.message?.take(40) ?: "翻译失败"
                }
                Log.w(TAG, "翻译栏翻译失败", t)
                showTranslateToast(msg)
            } finally {
                bar.setBusy(false)
            }
        }
    }

    /** 翻译栏现在开着吗 */
    private fun translateBarActive(): Boolean = translateBar != null

    // ------------------------------------------------------------ 翻译

    /**
     * 快捷翻译。
     *
     * 两种走法，按配置自动选：
     * 1. 配了 API（内置或外接）→ 直接调接口，译文直接替换输入框里的原文
     * 2. 一条都没配 → 退回原来的做法，交给系统翻译 App（ACTION_PROCESS_TEXT）
     *
     * 取文字的优先级：
     * - 输入框里选中的那一段（用户先选中再点翻译，最准）
     * - 没选中就取光标前面那一段，截到最后一个换行为止（也就是当前这句）
     *
     * 为什么替换而不是只显示：输入法场景下用户点翻译，多半是「我要把这段
     * 换成另一种语言发出去」，让他再手动复制一遍是多余的。
     *
     * 取文字的优先级：
     * 1. 输入框里选中的那一段（用户先选中再点翻译，最准）
     * 2. 没选中就取光标前面那一段，截到最后一个换行为止（也就是当前这句）
     *
     * 为什么要交出去：项目里没有内置翻译引擎（没有 INTERNET 权限，也没有接任何
     * 翻译 API），硬做一个只能瞎编。`ACTION_PROCESS_TEXT` 是 Android 给「把这段
     * 文字交给别的 App 处理」定的标准动作，系统翻译 / 谷歌翻译都认它。
     */
    private fun quickTranslate() {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "快捷翻译：没有输入连接")
            return
        }
        // 1) 优先用选中的
        val selected = try {
            ic.getSelectedText(0)?.toString()
        } catch (t: Throwable) {
            null
        }
        // 2) 没选中就取光标前那段，只要最后一句
        val hasSelection = !selected.isNullOrBlank()
        // 2) 没选中就取光标前那段，只要最后一句
        val text = if (hasSelection) {
            selected!!
        } else {
            val before = try {
                ic.getTextBeforeCursor(200, 0)?.toString()
            } catch (t: Throwable) {
                null
            }
            before?.substringAfterLast('\n')?.trim()
        }
        if (text.isNullOrBlank()) {
            Log.w(TAG, "快捷翻译：没取到要翻的文字")
            return
        }
        val endpoint = com.jimeng.ime.translate.TranslateConfigStore.resolve(this)
        if (endpoint == null) {
            // 没配 API，走系统翻译，跟以前一样
            handOffToSystemTranslate(text)
            return
        }

        // 配了 API：直接翻，翻完替换
        translateInPlace(text, hasSelection, endpoint)
    }

    /**
     * 调接口翻译并替换原文。
     *
     * 全程在协程里做，不碰主线程——网络请求在主线程会直接 NetworkOnMainThreadException。
     * 翻译期间如果用户继续打字，替换会作用在「当前光标位置」上，
     * 这可能跟用户预期的位置不同。所以只替换「选中的那段」，或者
     * 「光标前刚取的那句」长度——用一个固定长度删除，不依赖光标漂移。
     */
    private fun translateInPlace(
        text: String,
        hadSelection: Boolean,
        endpoint: com.jimeng.ime.translate.ResolvedEndpoint
    ) {
        val settings = com.jimeng.ime.translate.TranslateConfigStore.settings(this)
        translateJob?.cancel()
        translateJob = CoroutineScope(Dispatchers.Main).launch {
            showTranslateToast("翻译中…")
            try {
                val translated = com.jimeng.ime.translate.TranslationService.translate(
                    text = text,
                    pair = settings.pair,
                    endpoint = endpoint,
                    timeoutSeconds = settings.timeoutSeconds
                )
                val ic = currentInputConnection
                if (ic == null) {
                    showTranslateToast("输入框已关闭，译文：${translated.take(30)}")
                    return@launch
                }
                // 先删原文再插译文，两件事要在同一个 beginBatchEdit 里，
                // 否则中间那一帧输入框会闪一下空白
                ic.beginBatchEdit()
                try {
                    if (hadSelection) {
                        ic.commitText(translated, 1)
                    } else {
                        // 光标前这段是我们刚读的 text，长度对得上
                        ic.deleteSurroundingText(text.length, 0)
                        ic.commitText(translated, 1)
                    }
                } finally {
                    ic.endBatchEdit()
                }
                showTranslateToast("已翻译")
            } catch (t: Throwable) {
                val msg = when (t) {
                    is com.jimeng.ime.translate.TranslateError.Http ->
                        if (t.code == 401 || t.code == 403) "Key 无效或无权限"
                        else "翻译失败（${t.code}）"
                    is com.jimeng.ime.translate.TranslateError.Network -> "网络不通"
                    else -> t.message?.take(40) ?: "翻译失败"
                }
                Log.w(TAG, "翻译失败", t)
                showTranslateToast(msg)
            }
        }
    }

    /** 键盘上没有 Toast 上下文的时候用它：借 applicationContext 弹一下 */
    private fun showTranslateToast(msg: String) {
        android.os.Handler(mainLooper).post {
            android.widget.Toast.makeText(
                applicationContext, msg, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 原来的做法：把文字交给系统里能「处理文本」的 App */
    private fun handOffToSystemTranslate(text: String) {
        val process = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            // 只读：翻译结果不需要回填到输入框
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(
                Intent.createChooser(process, "翻译").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "打开翻译失败", t)
        }
    }

    /** 把当前语言同步到键盘（空格键上的名字）和表情面板 */
    private fun applyLanguageToKeyboard() {
        val entry = LanguageData.of(RimeEngine.currentSchema).toEntry()
        keyboard?.onInputMethodUpdate(entry)
        pickerPanel?.onInputMethodUpdate(entry)
        // 符号键的角标得跟着「标点全半角」走：全角模式下键上要显示 ，。？
        // 半角模式下显示 , . ?，否则看着全角、打出来半角，反着也一样错位。
        keyboard?.onPunctuationUpdate(
            com.jimeng.ime.kb.core.PunctuationData.forFullShape(RimeEngine.isFullShape())
        )
    }


    // ------------------------------------------------------------ 引擎按键

    /** 喂一个键给 rime，返回引擎是否吃掉 */
    private fun processKey(session: Long, keySym: Int, mask: Int): Boolean {
        if (session == 0L) return false
        // **这里刻意不拦翻译栏。**
        //
        // 试过在这一层把字母直接塞进栏里（栏里要「原样的原文」，听起来合理），
        // 结果是栏里永远打不出中文：第一个键被拦走，引擎一个编码都收不到，
        // 拼音组字从根上就没发生。而「中文译成外文」正是这条栏的主用法。
        //
        // 所以按键照常喂给 librime，组字、候选、选词全都正常走，
        // 只在上屏那一刻截住（见 [commitText]）。这样两种方向都能用：
        //   · 拼音方案下打中文 → 选词上屏 → 落进栏里
        //   · 小地球键切到 English（melt_eng）打英文 → 落进栏里
        // 数字、标点走的是同一条上屏路径，一并覆盖。
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
     * 把「引擎切出来的每一节」映射到**按键串** `input()` 上，得到每节的字母区间。
     *
     * ### 为什么不能靠光标跳（上一版实现踩过的坑，也是用户提出的问题）
     *
     * 上一版想用 `Shift+Left`（`left_by_syllable`）当尺子，从末尾一步步往左量。
     * 查了 `Navigator::BeginMove`（navigator.cc:194-208）才明白这条走不通：
     * `spans_` 里只装了**已经选中候选**的词内音节边界（`phrase->spans()`）
     * 和已确定段的起止（`AddSpan(seg.start, seg.end)`），
     * **还没转换的字母根本不在 `spans_` 里**。
     *
     * 于是 `JumpLeft`（navigator.cc:210-236）第一句判断就是：
     *
     *     new_pos = (span_start_pos > end_of_translation) ? end_of_translation : ...
     *
     * 光标落在未翻译区 → **一次直接跳到已翻译部分的末尾**，不会逐音节挪。
     * 所以单音节碰巧跳一次就到 0（能用），双音节以上全崩。
     * 这条路是死的，不是哪段代码把它遮住了。
     *
     * ### 真正靠得住的东西：引擎自己的分隔符
     *
     * `ScriptSyllabifier::GetPreeditString`（script_translator.cc:417-438）
     * 每换一个音节就补一个 `delimiters.at(0)`，而段边界来自
     * `Syllabifier::BuildSyllableGraph` 拿词库查出来的真切分。
     * 所以 `preedit` 里「每段有  几个字母」就是引擎的答案。
     *
     * 剩下的只是把「字母个数」当尺子，在按键串上**从末尾往前**量：
     * 最后一段一定顶着串尾，往前数它的字母数就是它的起点，再往前推……
     *
     * 为什么从末尾往前而不是从头往后：整串开头可能有一段**已经转成汉字的
     * 字母**（打 buhaowan 选掉「不」之后，`input()` 仍是 buhaowan），
     * 那段不该算进任何一节。从末尾对齐就自然绕开了它，不用猜前缀有多长。
     *
     * 举个对的例子（`input()` 是按键串，不含引擎自动插的空格）：
     *     preedit = "shan gou"  → 各节 ["shan","gou"] 长度 [4,3]
     *     input   = "shangou"   → 区间 [0,4)="shan"、[4,7)="gou"   ✔ 不吞 g
     *
     * 对不上的情况一律返回 null（双拼的显示串比按键串长，就对不上）：
     * 宁可这次点了不动作，也不能把用户的字弄丢。
     *
     * @return 每节在 [input] 上的区间 `[first, last)`，顺序和 [syllables] 一致；
     *         对不齐时返回 null。
     */
    private fun mapSyllablesToInput(
        input: String,
        syllables: List<String>
    ): List<IntArray>? {
        if (syllables.isEmpty() || input.isEmpty()) return null

        val spans = ArrayList<IntArray>(syllables.size)
        var pos = input.length
        // 从最后一段往前推：最后一段一定顶着串尾
        for (i in syllables.indices.reversed()) {
            val need = syllables[i].length
            if (need <= 0) return null
            var start = pos
            var got = 0
            // 往前数 need 个字母，途中遇到的非字母（分隔符）不算数
            while (start > 0 && got < need) {
                start--
                if (input[start].isAsciiLetter()) got++
            }
            // 数不够 = 这个显示串和按键串不是一个坐标系（双拼就是这样）
            if (got < need) return null
            // 区间只覆盖字母本身：两头可能挂着分隔符（用户手打的单引号）
            var first = start
            while (first < pos && !input[first].isAsciiLetter()) first++
            var last = pos
            while (last > first && !input[last - 1].isAsciiLetter()) last--
            if (last <= first) return null
            spans.add(intArrayOf(first, last))
            pos = start
        }
        spans.reverse()
        // 再核一遍：把各节字母数加起来，必须正好等于引擎给的 raw 长度。
        // 这一条挡住「显示串被 preedit_format 改长/改短」的残留风险。
        val total = syllables.sumOf { it.length }
        val rawLen = input.count { it.isAsciiLetter() }
        if (total > rawLen) return null
        return spans
    }

    /** 是不是 ASCII 字母（拼音里会出现的那种） */
    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** 把引擎里待上屏的文本取出来交给输入框 */
    private fun drainCommit(session: Long) {
        if (session == 0L) return
        val commit = try {
            RimeNative.nativeGetCommit(session)
        } catch (t: Throwable) {
            Log.e(TAG, "getCommit 失败", t)
            null
        }
        if (!commit.isNullOrEmpty()) commitText(commit, convertFullwidthAscii = true)
    }

    private fun commitText(text: String, convertFullwidthAscii: Boolean = false) {
        if (text.isEmpty()) return
        val out = if (convertFullwidthAscii && AppPrefs.getInstance().output.fullwidthAscii.getValue()) {
            text.toFullwidthAscii()
        } else {
            text
        }
        if (out.isEmpty()) return
        // 翻译栏开着：字不进应用输入框，先攒在栏里。
        //
        // 这是整个翻译栏的关键一步。键盘打出来的字本来由 librime 直接
        // commit 到应用的输入框，中间没有 View 可以拦；想让用户先在键盘
        // 上方攒一句话、点翻译再上屏，只能在这里把「上屏」截下来。
        val bar = translateBar
        if (bar != null) {
            bar.append(out)
            translateBarText = bar.content
            return
        }
        currentInputConnection?.commitText(out, 1)
    }

    /**
     * 真的上屏，不经过翻译栏。
     *
     * 翻译栏的译文要用这个：走 [commitText] 的话译文会被
     * 又攒回栏里，等于自己吞掉自己。
     */
    private fun commitTextDirect(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * 把 ASCII 字母数字转成全角（ＡＢＣ１２３）。
     *
     * 只转字母和数字，**不转标点**——标点归 rime 的 `full_shape` 开关管
     * （`full_shape` 开着时引擎上屏的标点本来就是全角，再转一遍会重复，
     * 而且半角状态下用户可能正好想要半角标点）。
     *
     * 为什么不做成通用 ASCII→Fullwidth：全角字母数字在 Unicode 里是
     * FF21–FF5A（大写）、FF41–FF5A（小写）、FF10–FF19（数字），
     * 与半角正好相差 0xFEE0，直接加偏移即可，省一张映射表。
     */
    private fun String.toFullwidthAscii(): String {
        if (isEmpty()) return this
        val sb = StringBuilder(length)
        for (c in this) {
            sb.append(
                when {
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ->
                        (c.code + 0xFEE0).toChar()
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /**
     * 麦克风权限被用户在系统层面关掉后没法再弹框，只能把他引到应用详情页。
     *
     * 输入法里 startActivity 必须带 NEW_TASK（调用方是 Service 上下文），
     * 并且要兜住 ActivityNotFoundException —— 个别精简系统没有设置详情页。
     */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "open app settings failed", t)
        }
    }

    /**
     * 跳到扩展商店。
     *
     * 从输入法服务里拉起 Activity 必须带 NEW_TASK —— 输入法不是 Activity，
     * 没有任务栈可挂，不带这个 flag 会抛 AndroidRuntimeException。
     */
    private fun openStore() {
        try {
            startActivity(
                Intent(this, StoreActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "open store failed", t)
        }
    }

    // ------------------------------------------------------------ 候选栏

    override fun onPickCandidate(index: Int) {
        // 点候选也是键盘操作：快捷粘贴退休
        onKeyboardTouched()
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
        // 换页里（剪贴板 / 更多）不让展开候选网格，否则会盖在页面上
        if (currentPage != null) return
        // 展开 / 收起候选页也算键盘操作
        onKeyboardTouched()
        if (expandShowing) {
            hideExpanded()
        } else {
            showExpanded()
        }
    }
    override fun onToolbarToggle(expand: Boolean) {
        // 点工具栏开关也是键盘操作
        onKeyboardTouched()
        // 只管把按钮排展开 / 收起来；剪贴板等按钮先摆样子，功能以后接
        candidateBar?.setToolbarExpanded(expand)
    }
    // ------------------------------------------------------------ 整块换页
    //
    // 照 fcitx5-android 的 InputWindowManager.attachWindow：
    // 键盘区就是一整块，点剪贴板 / 更多时**整块换成新页面**，
    // 候选栏同时变成「← 标题」，点返回回键盘。
    // 上一版做的是浮层（贴在候选栏上方的小面板），用户说眼花缭乱——
    // 因为浮层跟候选、码区、键盘抢视线，还容易叠在一起。换页就没有这个问题。

    /** 当前显示的页面（null = 键盘本体） */
    private var currentPage: InputPage? = null

    /** 切到一页：把键盘区整块换成 page.root */
    private fun attachPage(page: InputPage) {
        val host = keyboardHost ?: return
        // 同一页再点一次：当返回用
        if (currentPage === page) {
            detachPage()
            return
        }
        // 先把旧的收掉
        detachPage(keepBar = true)
        // 表情面板、展开页都得让位（跟换页不能同时存在）
        hidePicker()
        if (expandShowing) hideExpanded()
        // 页面盖住键盘时把主键盘摘下来，免得底下还在收按键
        keyboard?.onDetach()
        host.addView(
            page.root,
            keyboardInsets(
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        )
        // 页面根布局必须自己吃掉触摸：否则点在页面的空白处，
        // 事件会穿透到下面被盖住的键盘，用户就莫名其妙打出一个字来。
        page.root.isClickable = true
        currentPage = page
        // 候选栏换成「← 标题」，页面自己的操作按钮挂到右端扩展位
        candidateBar?.setPage(page.title, page.barExtension)
        page.onAttached()
    }

    /** 从换页退回键盘本体 */
    private fun detachPage(keepBar: Boolean = false) {
        val page = currentPage ?: return
        currentPage = null
        // 文本编辑页收起来时把它的状态清干净：下次进来是全新的
        if (page === textEditingPage) {
            textEditingPage = null
            selectMode = false
        }
        page.onDetached()
        (page.root.parent as? ViewGroup)?.removeView(page.root)
        if (!keepBar) {
            // 键盘挂回来（attachPage 时摘下过）
            keyboard?.onAttach()
            candidateBar?.clearPage()
            // 回键盘后该显示工具栏还是候选区 —— 照 fcitx5 的
            // `KawaiiBarComponent.onWindowDetached { push(WindowDetached) }`：
            // 它重新跑一遍迁移表（`from(Title) transitTo Candidate on
            // (CandidateEmpty to false)`、`from(Title) transitTo Idle`），
            // 也就是「有候选 → 候选区，没候选 → 空闲」。
            // 我们这里没有 Title 态（换页由 CandidateBar.pageMode 单独管），
            // 所以直接按引擎现状重新推一遍两个事件，让状态机自己决定落点。
            //
            // **不能用 sessionId != 0 判断**——会话一旦建立就一直存在，
            // 那样永远会被判成「打字中」，工具栏（menuButton + 按钮排）就回不来了，
            // 必须再按一次键触发 refreshCandidates 才恢复（用户报的那个 bug）。
            val (preedit, candidates) = readBarStateInput()
            barStateMachine.onPreeditEmptyStateUpdate(preedit.isEmpty())
            barStateMachine.onCandidateUpdate(candidates)
        }
    }

    /**
     * 读一次引擎现状，交给状态机用。
     *
     * 返回两个**互相独立**的值，对应 fcitx5 的两个独立数据源：
     *   · preedit    ← PreeditEmptyStateComponent 的 `inputPanelCached.preedit`
     *   · candidates ← `FcitxEvent.CandidateListEvent.Data.candidates`
     *
     * **刻意不把两者合成为一个 Boolean**（比如「都空才空闲」）——
     * 预测词挂着时正是「preedit 空 + candidates 非空」，合成一个布尔就会
     * 把这一支压成「空」，候选栏就被当成空闲、预测词被工具栏盖掉，
     * 或者反过来怎么都退不回工具栏。这正是用户这次三个 bug 的共同根。
     *
     * 没有会话 / 拿不到上下文时按「真空」返回，状态机会落到 Idle（工具栏）。
     */
    private fun readBarStateInput(): Pair<String, List<String>> {
        val session = RimeEngine.sessionId
        if (session == 0L) return "" to emptyList()
        val ctx = try {
            RimeNative.getContext(session)
        } catch (t: Throwable) {
            Log.w(TAG, "getContext 失败", t)
            null
        } ?: return "" to emptyList()
        return ctx.preedit to ctx.candidates
    }

    /** 工具栏点剪贴板：整块换成剪贴板页 */
    private fun showClipboardPage() {
        // 服务在后台时听不到剪贴板广播，进页前补读一次系统剪贴板
        ClipboardHistory.syncFromSystem()
        attachPage(
            ClipboardPage(
                this,
                ThemeManager.activeTheme,
                ClipboardHistory.entries(),
                onPick = { text ->
                    detachPage()
                    commitText(text)
                },
                onClear = { skipPinned ->
                    // 照原版 deleteAll(skipPinned)：有未置顶的就只删未置顶的，
                    // 一条未置顶都没有时（提示框那时问的是「已置顶」），整批都删
                    if (skipPinned) ClipboardHistory.clearUnpinned() else ClipboardHistory.clear()
                    // 删完重建一次页面，让列表和空状态立刻刷新
                    detachPage()
                    showClipboardPage()
                },
                onDelete = { text ->
                    // 滑动划走或长按菜单里的删除：直接删，不弹提示。
                    // 页面自己已经把卡片摘掉了，这里只删数据，不再重建整页
                    // （重建会把列表滚动位置弹回顶部）
                    ClipboardHistory.remove(text)
                },
                onPin = { text, pinned ->
                    // 长按菜单的「置顶」/「取消置顶」：置顶的排最前、清空时不会被删
                    ClipboardHistory.setPinned(text, pinned)
                    // 顺序变了，重建一次页面
                    detachPage()
                    showClipboardPage()
                },
                onEdit = { text ->
                    // 长按菜单的「编辑」：跳编辑页（照原版 launchClipboardEdit）
                    try {
                        startActivity(ClipboardEditActivity.intent(this, text))
                    } catch (t: Throwable) {
                        Log.e(TAG, "打开剪贴板编辑页失败", t)
                    }
                },
                onShare = { text ->
                    // 长按菜单的「分享」：走系统分享（照原版 ACTION_SEND）
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        val chooser = Intent.createChooser(send, null).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(chooser)
                    } catch (t: Throwable) {
                        Log.e(TAG, "分享失败", t)
                    }
                }
            )
        )
    }

    // ============================================================
    //  文本编辑页（照 fcitx5 的 TextEditingWindow）
    //
    //  fcitx5 的 cursorMoveButton 点了之后 attachWindow(TextEditingWindow()),
    //  整块换成一张「方向键 + 选择 + 全选/剪切/复制/粘贴 + 删除」的编辑页。
    //  我们也照做：工具栏那个光标图标 -> 整页换过去，操作直接作用在当前输入框。
    // ============================================================

    /** 当前显示着的文本编辑页（用来在选区变化时刷新按钮） */
    private var textEditingPage: TextEditingPage? = null

    /** 工具栏点光标图标：整块换成文本编辑页 */
    private fun showTextEditingPage() {
        // 每次进页都从「没选中、不在选择模式」开始，状态别跨页残留
        selectMode = false
        val page = TextEditingPage(
            this,
            ThemeManager.activeTheme,
            onDirectionKey = { keyCode -> sendDirectionKey(keyCode) },
            onSelectToggle = {
                // 照 fcitx5 的 selectButton：点一下开「选择模式」，
                // 之后方向键就带着 Shift 扩选区（sendDirectionKey 里判断 hasSelection）
                selectMode = !selectMode
                textEditingPage?.updateSelection()
            },
            onSelectAll = {
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                // 全选后进入选择模式，接着按方向键就能调边界
                selectMode = true
                textEditingPage?.updateSelection()
            },
            onCut = {
                currentInputConnection?.performContextMenuAction(android.R.id.cut)
                selectMode = false
                textEditingPage?.updateSelection()
            },
            onCopy = {
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
                selectMode = false
                textEditingPage?.updateSelection()
            },
            onPaste = {
                currentInputConnection?.performContextMenuAction(android.R.id.paste)
            },
            onBackspace = {
                selectMode = false
                sendKeyEventPair(android.view.KeyEvent.KEYCODE_DEL)
                textEditingPage?.updateSelection()
            },
            // 右上角剪贴板：照原版 TextEditingUi.extension 的 clipboardButton，跳过去
            onOpenClipboard = { showClipboardPage() },
            hasSelection = { selectMode || hasSelection() }
        )
        textEditingPage = page
        attachPage(page)
    }

    /** 「选择」按钮开的选择模式：开了之后方向键带 Shift，可以扩选区 */
    private var selectMode = false

    /**
     * 发一个方向键。
     *
     * 照 fcitx5 的 `sendCombinationKeyEvents(keyCode, shift = hasSelection || userSelection)`：
     * **有选中时自动带上 Shift**，光标就会「扩大选区」而不是取消选择，
     * 这是文本编辑页最核心的手感——先选一段，再用方向键调边界。
     */
    private fun sendDirectionKey(keyCode: Int) {
        sendKeyEventPair(keyCode, shift = selectMode || hasSelection())
    }

    /** 成对发 DOWN/UP，可按需要带 Shift */
    private fun sendKeyEventPair(keyCode: Int, shift: Boolean = false) {
        val ic = currentInputConnection ?: return
        val meta = if (shift) android.view.KeyEvent.META_SHIFT_ON else 0
        ic.sendKeyEvent(
            android.view.KeyEvent(
                0L, 0L,
                android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta
            )
        )
        ic.sendKeyEvent(
            android.view.KeyEvent(
                0L, 0L,
                android.view.KeyEvent.ACTION_UP, keyCode, 0, meta
            )
        )
    }

    /**
     * 选区变了：文本编辑页开着的话，那一格在「全选 / 剪切」之间跟着换。
     *
     * 照 fcitx5 的 TextEditingWindow.onSelectionUpdate -> ui.updateSelection()：
     * 有选中就把「全选」换成「剪切」，没选中再换回来。
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        textEditingPage?.updateSelection()
    }

    /** 输入框里现在有没有真的选中一段文字 */
    private fun hasSelection(): Boolean {
        val ic = currentInputConnection ?: return false
        return !ic.getSelectedText(0).isNullOrEmpty()
    }

    /** 工具栏点 ⋯：整块换成菜单页 */
    private fun showMenuPage() {
        val items = mutableListOf<MenuPage.Item>()
        // 调色板：外观。直接跳到「键盘管理」页——键边框、描边、水波纹
        // 都已经搬过去了，在那儿还能顺手调键盘尺寸。
        items += MenuPage.Item("外观", R.drawable.ic_palette_24) {
            startActivity(
                Intent(this, KeyboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        // 语言切换：切 rime 方案（中文/英文），跟键盘上那个小地球是同一个动作。
        // 图标用键盘上那颗地球，两处统一了用户才不会觉得是两个不同的功能。
        // 点完**留在菜单里**：用户常常要连着调好几项，退出去还得再点进来太麻烦。
        items += MenuPage.Item("语言切换", R.drawable.ic_baseline_language_24) {
            switchLanguage()
            // 重建菜单：切完方案后图标和标签都可能变，让用户当场看到。
            showMenuPage()
        }
        // 简繁切换：写 rime 的 traditionalization option，候选立刻变简/繁。
        // 图标直接画字：「简」「繁」本身就是最好的图标，下面一条下划线托住。
        // 注意：这一项**永远显示**。菜单排版要两种语言下位置一致，
        // 就靠每一项都固定占一格——不再按方案支持与否隐藏。
        run {
            val traditionalOn = RimeEngine.isTraditional()
            items += MenuPage.Item(
                label = "简繁切换",
                textIcon = if (traditionalOn) "繁" else "简"
            ) {
                RimeEngine.setTraditional(!RimeEngine.isTraditional())
                // 候选重新算一遍再走：退回键盘时列表已经是新简繁的了。
                // 不用 detachPage —— 用户可能还要接着调下一项。
                refreshCandidates()
                // 标签跟着换（「简」↔「繁」），让用户当场看到状态变了
                showMenuPage()
            }
        }
        // 下一词预测：写 rime 的 prediction option，上屏后按词预测下一个词。
        // 永远显示，理由同上：格子数量固定，位置才不会挪。
        run {
            val predictionOn = RimeEngine.isPrediction()
            items += MenuPage.Item(
                label = "预测开关",
                iconRes = R.drawable.ic_eye_24,
                // 开着时图标底下垫一块圆底——比在标签后面加「· 开」干净，
                // 标签长度不变，4 列网格不会挤歪，状态一眼可见
                active = predictionOn
            ) {
                RimeEngine.setPrediction(!RimeEngine.isPrediction())
                // 留在菜单里：候选已经重算好，退出去就是新状态
                refreshCandidates()
                // 重建菜单，让圆底当场出现/消失
                showMenuPage()
            }
        }
        // 标点全半角：写 rime 的 full_shape option，中文态标点按 punctuator 表
        // 全角（，。？）或半角（,.?）上屏。永远显示，理由同上。
        run {
            val fullShapeOn = RimeEngine.isFullShape()
            items += MenuPage.Item(
                // 名字跟着当前模式走：半角就「半角标点」，全角就「全角标点」，
                // 用户一眼看到的就是现在正在用的那个
                label = if (fullShapeOn) "全角标点" else "半角标点",
                textIcon = if (fullShapeOn) "全" else "半",
                active = fullShapeOn
            ) {
                RimeEngine.setFullShape(!RimeEngine.isFullShape())
                // 键盘上的符号角标要立刻跟着换（半角 , ↔ 全角 ，），
                // 不然角标还是旧的，看着和打出来的不一致
                applyLanguageToKeyboard()
                // 留在菜单里：候选已经重算好，退出去就是新状态
                refreshCandidates()
                // 重建菜单，让圆底和标签当场变化
                showMenuPage()
            }
        }
        // 字母数字全角：前端转换（rime 不管这个），上屏时把 ASCII 字母数字
        // 转成全角（ＡＢＣ１２３）。标点不归它管——那是上面「标点全半角」的事。
        run {
            val asciiFullwidthOn =
                com.jimeng.ime.kb.prefs.AppPrefs.getInstance().output.fullwidthAscii.getValue()
            items += MenuPage.Item(
                // 名字跟着当前模式走：半角就「半角字符」，全角就「全角字符」
                label = if (asciiFullwidthOn) "全角字符" else "半角字符",
                textIcon = if (asciiFullwidthOn) "Ａ" else "A",
                active = asciiFullwidthOn
            ) {
                com.jimeng.ime.kb.prefs.AppPrefs.getInstance().output.fullwidthAscii.setValue(
                    !asciiFullwidthOn
                )
                // 重建菜单，让圆底和标签当场变化
                showMenuPage()
            }
        }
        // 快捷翻译：在键盘上方开一条翻译栏，打的字先攒在栏里，
        // 点栏上的「翻译」才把译文上屏。开着的时候这里变成「关闭翻译」。
        items += MenuPage.Item(
            if (translateBarActive()) "关闭翻译" else "快捷翻译",
            R.drawable.ic_translate_24
        ) {
            if (translateBarActive()) closeTranslateBar() else openTranslateBar()
            detachPage()
        }
        // 调整键盘：跳到「键盘管理」页，那里能改高度 / 边距，也能改空格键上的字
        items += MenuPage.Item("调整键盘", R.drawable.ic_resize_24) {
            startActivity(
                Intent(this, KeyboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        items += MenuPage.Item("设置", R.drawable.ic_settings_24) {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        items += MenuPage.Item("回到键盘", R.drawable.ic_baseline_arrow_back_24) {
            detachPage()
        }
        attachPage(MenuPage(this, ThemeManager.activeTheme, items))
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
        // 用户正开着大写锁定时收起展开页，Caps 就丢了。
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
        // 收起与否交给 refreshCandidates 统一判断
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
     * 改的是**哪一节**：用户在左列锁了哪个音节，就改哪个（见
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
     * 开了会让整张候选表的排序全变（用户熟悉的候选顺序会跑掉）。
     * 这里只让「左边的谐音按钮」临时换拼法，不动方案，也不触发重新部署。
     */
    private fun pickFuzzyVariant(variant: String) {
        if (variant.isEmpty() || variant.any { it !in 'a'..'z' }) return
        val session = RimeEngine.sessionId
        if (session == 0L) return
        val ctx = RimeNative.getContext(session) ?: return

        // 【根治要点】换谐音必须在**按键串** `input()` 的坐标系里做，
        // 不能用显示串 `preedit` 推出来的字母。
        //
        // `preedit` 会被 `preedit_format` 改写（雾凇 `jv`→`ju`，双拼整套
        // xform 把按键改写成全拼），而 `set_input` 收的是按键串。
        // 以前拿显示串的字母去对齐按键串（findRawTailStart），
        // 只要引擎改写过字母就必然错位 —— 后鼻音吞字母、以及用户说的
        // 「不是很好修的小瑕疵」，根子都在这。
        val fullInput = try {
            RimeNative.nativeGetInput(session)
        } catch (t: Throwable) {
            Log.e(TAG, "pickFuzzy: getInput 失败", t)
            null
        } ?: return
        if (fullInput.isEmpty()) return

        // 问引擎要「还没转换的那几节」在 input() 上的真实区间。
        //
        // 这里用的尺子是**引擎自己插的分隔符**（`ScriptSyllabifier::GetPreeditString`
        // 在段间补 delimiters.at(0)，段边界是词库查出来的真切分），
        // 不是光标跳 —— `Navigator::JumpLeft` 在未翻译区会一次跳到
        // 已翻译部分末尾（navigator.cc:218），逐音节量根本量不出来。
        //
        // 拿不到区间就什么都不做：宁可这次点了没反应，也不能把用户的字弄丢。
        val pending = expandedCandidates?.pendingSyllables() ?: return
        if (pending.isEmpty()) return
        val spans = mapSyllablesToInput(fullInput, pending) ?: return
        val targetIdx = expandedCandidates?.lockedSyllableIndex() ?: 0
        if (targetIdx !in spans.indices) return

        // 整串字母交给展开页算：区间是真的，不是猜的。
        // 它会顺手在每两节之间插入引擎认得的硬边界（单引号），
        // 否则换完字母引擎会跨节重切（`san`+`gou` → `sang`+`ou`，
        // 第一格从 san 变 sang，整行重排——就是用户踩到的那个 bug）。
        val rebuilt = expandedCandidates?.planFuzzyPick(variant, fullInput, spans, targetIdx)
            ?: return

        // 一步到位直接设输入串（照 fcitx5 的思路，不删字母、不重打）。
        //
        // librime 的 Context::set_input 只改 input_ 和 caret_pos_，
        // 不碰 composition_，所以：
        //   · 已经选成汉字的「不」一个字都不会丢；
        //   · 没有「删光字母→再一个个打回去」的中间状态，
        //     界面看不到任何中间帧，编码区就不会跳、不会乱。
        if (RimeNative.nativeSetInput(session, rebuilt)) {
            drainCommit(session)
            refreshCandidates()
            return
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
        var guard = parsePreedit(ctx.preedit).syllables.size + 2
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
        // 分隔符（用户打了 bu'haowan 这种），去掉分隔符后就比实际字符少，
        // 数次数会删不干净。这里每删一次都读一遍引擎状态来收尾。
        var guardDel = fullInput.length + 8
        while (guardDel-- > 0) {
            val now = RimeNative.getContext(session) ?: return
            if (parsePreedit(now.preedit).raw.isEmpty()) break
            if (!sendKey(session, FcitxKeyMapping.FcitxKey_Delete, 0)) return
        }

        // 第四步：把算好的整串字母重新打进去
        //
        // 整串（前面几节 + 新拼法 + 后面几节）已经在展开页那边按
        // 引擎量出来的区间算好了，这里只管照着打。
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
     * 用户要的手感：**第一下只解锁，不删字母**。
     *   - 左列有音节被锁着 → 这一下只解除高亮，拼音一个字母都不动；
     *   - 否则 → 和主键盘一样，正常交给引擎退格。
     *
     * 为什么不用 rime 原生的 `Editor::BackToPreviousInput`：
     * 那是 rime 自己记的「刚刚选了哪个字」，和用户在左列亲手点的锁不是一回事。
     * 用户在左列点了 hao 想改它，退格第一下就该只把 hao 放开，
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


    // ------------------------------------------------------------ 剪贴板快捷粘贴

    /**
     * 刷新候选栏上的快捷粘贴。
     *
     * **只有当键盘处于空闲（没在打字）时才露出来**——正在打字时那块地方
     * 要留给候选，剪贴板插进去会让点选错位。
     *
     * 谁来调：
     *   - 复制了新东西（[ClipboardHistory.onPendingChanged]）
     *   - 待粘贴被作废（同上）
     *   - 候选表刷新（打字中 / 打完字回到空闲）
     *   - 键盘每次弹出（[onStartInputView]）
     */
    private fun refreshQuickPaste() {
        val bar = candidateBar ?: return
        // 没有待粘贴就直接收起来，别为它打一次 JNI 查引擎状态
        if (ClipboardHistory.pendingText() == null) {
            bar.setQuickPaste(null)
            return
        }
        // 有候选在显示时不给快捷粘贴腾地方：候选优先。
        // 判据直接问状态机——「候选栏现在是空闲还是候选态」本来就只有它知道，
        // 照 fcitx5 的 IdleUi：快捷粘贴属于 Idle 那一族（原版是 Clipboard 态）。
        val showing = barStateMachine.state != BarStateMachine.State.Idle
        bar.setQuickPaste(if (showing) null else ClipboardHistory.pendingText())
    }

    /**
     * 键盘上发生了**任何操作**——作废待粘贴。
     *
     * 用户的原话：「除非在键盘上有所任何操作之后」。
     * 所以按键、点候选、展开候选页、展开工具栏、开换页，
     * 全都调这一个方法，语义只有一条：碰过键盘了，这条快捷粘贴就退休。
     */
    private fun onKeyboardTouched() {
        // 没有待粘贴就直接走——每次按键都会经过这里，别为它多打一次 JNI
        if (ClipboardHistory.pendingText() == null) return
        ClipboardHistory.consumePending()
        // consumePending 里已经通过 onPendingChanged 回调刷新过一次，
        // 但那条回调可能因为还没建视图而空转，这里再兜一次
        refreshQuickPaste()
    }

    /**
     * 点快捷粘贴：把内容送进输入框，然后这条退休。
     *
     * 用 [commitText] 而不是自己拼 InputConnection——
     * 它已经处理了「引擎里还残留着编码要先清掉」这类情况。
     */
    private fun quickPasteNow() {
        val text = ClipboardHistory.pendingText() ?: return
        commitText(text)
        ClipboardHistory.consumePending()
        refreshQuickPaste()
    }

    /** 从引擎读上下文刷新候选栏 */
    private fun refreshCandidates() {
        val bar = candidateBar ?: return
        val session = RimeEngine.sessionId
        // ---- 先把引擎的现状读出来 ----
        // 一次 JNI 读两样，照 fcitx5 的两个独立数据源：
        //   PreeditEmptyStateComponent  —— clientPreedit / inputPanel.preedit 是否为空
        //   onCandidateUpdate(data)     —— data.candidates 是否为空
        // 我们只有一个引擎，client preedit 的概念不存在（rime 不把编码写进输入框），
        // 所以「输入码空」就看引擎 preedit。合不合成一个布尔，见 [readBarStateInput]。
        val (preedit, candidates) = readBarStateInput()

        // ---- 两个事件推给状态机（顺序也照原版：Preedit 先，Candidates 后）----
        // **这里不再自己 if 判断该显示候选还是工具栏。**
        // 原版 KawaiiBarComponent 也是只 push 事件，转不转态由状态机说了算；
        // UI 侧 switchUiByState(state) 只跟着状态走。
        // 以前在刷新这边自己判一次、reset 那边又判一次，两处一旦不同步，
        // 就会出现「引擎空了候选栏还挂着」或者「有预测却被当空闲」。
        barStateMachine.onPreeditEmptyStateUpdate(preedit.isEmpty())
        barStateMachine.onCandidateUpdate(candidates)

        // ---- 再把内容画上去 ----
        updatePreedit(preedit)
        if (candidates.isEmpty()) {
            bar.clear()
            if (expandShowing) hideExpanded()
        } else {
            bar.update(candidates)
            // 展开页开着的时候，跟着引擎的候选表一起变（选词、翻页、退格后都要同步）
            if (expandShowing) {
                // 拼音全部变成字之后（输入码空了），展开页该退场——对齐 fcitx5。
                //
                // 不能只看 candidates 空不空：预测开着时 librime-predict 会在
                // composition 尾部挂一个 prediction 段，输入码清空后候选依然非空，
                // 「没候选才收」就永远不成立，整页网格一直盖着键盘。
                // 这里要的是「还在不在打字」，所以判据用 preedit。
                if (preedit.isEmpty()) {
                    hideExpanded()
                } else {
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
        }

        // 候选表刷新完，快捷粘贴该露脸了（打字完回到空闲）——放在最后：
        // 它要问状态机现在是空闲还是候选态，得等状态机这一轮转完。
        refreshQuickPaste()
    }

    /**
     * rime 上下文 → fcitx5 的 [FcitxEvent.InputPanelEvent.Data] → [PreeditUi.update]。
     *
     * 用户要求：**保留底色块，但不要高亮**。
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
            // 码区收起来了，翻译栏下面那截空档要重新对一次
            // （量不到真实高度了，会退回估算值）
            syncTranslateBarBottomMargin()
            return
        }
        // 真编码出来了：切语言的提示要让位。两者位置完全重合，
        // 留着会叠成「あ bu」这种两层字，看着像乱码。
        languageBadge?.let { if (it.showing()) it.dismiss() }
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
        // 码区高度这会儿才算定了（从 GONE 变 VISIBLE），
        // 翻译栏下面那截空档按真实高度重对一次
        syncTranslateBarBottomMargin()
    }

    // ------------------------------------------------------------ 硬件键盘

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 换页开着（剪贴板 / 更多）时，返回键先回键盘
        if (keyCode == KeyEvent.KEYCODE_BACK && currentPage != null) {
            detachPage()
            return true
        }
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

