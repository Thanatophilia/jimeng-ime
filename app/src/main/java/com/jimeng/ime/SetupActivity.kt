package com.jimeng.ime

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.InputFeedbacks
import com.jimeng.ime.kb.clipboard.ClipboardHistory
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.SkinManager
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.kb.keyboard.SpaceFont
import com.jimeng.ime.kb.keyboard.SpaceFontLoader
import com.jimeng.ime.kb.keyboard.SpaceLabel
import com.jimeng.ime.kb.prefs.InputFeedbackMode
import com.jimeng.ime.rime.RimeEngine
import com.jimeng.ime.view.KeyboardPreviewView

/**
 * 设置/引导页。
 *
 * 顶上一个「输入法」底座：启用、选择、试打三样挨在一起，
 * 下面依次是主题与定制、按键外观、按键行为、键盘尺寸、剪贴板。
 *
 * 引擎的部署进度和自检结果不在这一页显示了——部署进度键盘上有，
 * 自检失败写 logcat，看完排查就够。
 */
class SetupActivity : Activity() {

    companion object {
        /**
         * 进来就滚到「按键外观」分区。
         *
         * 键盘的「更多」菜单里有「外观」和「设置」两个入口，都跳这个页面；
         * 带这个 extra 的从外观区开始看，不带的照旧从顶部开始。
         */
        const val EXTRA_SCROLL_TO_APPEARANCE = "scroll_to_appearance"

        /** 进来就滚到「键盘尺寸」分区（键盘「更多 → 调整键盘」用）。 */
        const val EXTRA_SCROLL_TO_KEYBOARD_SIZE = "scroll_to_keyboard_size"

        /** 「导入字体」用 SAF 挑文件时的 requestCode */
        const val REQ_PICK_FONT = 1001

        /** 「导入皮肤」用 SAF 挑 .jmskin 时的 requestCode */
        const val REQ_PICK_SKIN = 1002
    }

    /**
     * 「按键外观」分区的标题 View。
     *
     * 从键盘的「更多」菜单点「外观」进来时（[EXTRA_SCROLL_TO_APPEARANCE]），
     * 要让页面直接停在这一块，而不是从最顶上开始。
     */
    private var appearanceAnchor: android.view.View? = null

    /** 「键盘尺寸」分区标题，从「更多 → 调整键盘」进来时滚到这里 */
    private var keyboardSizeAnchor: android.view.View? = null

    /** 开关行标题 -> 同步勾选状态的函数（switchRow 里登记，refreshAppearance 里用） */
    private val switchRows = LinkedHashMap<String, (Boolean) -> Unit>()

    /** 滑块行标题 -> 同步滑块位置的函数（seekRow 里登记，refreshAppearance 里用） */
    private val seekRows = LinkedHashMap<String, (Int) -> Unit>()

    /** 可选行标题 -> 同步右侧当前值的函数（pickRow 里登记，refreshAppearance 里用） */
    private val pickRows = LinkedHashMap<String, (String) -> Unit>()

    /**
     * 正在把勾选状态往界面上「刷」的时候为 true。
     *
     * 没有它的话：程序改 [android.widget.Switch.isChecked] → 触发用户监听 → 再写一次
     * prefs → 再发一次广播 → 再刷一次界面，白绕一圈。刷的时候先压住，刷完放开。
     */
    private var syncingSwitches = false

    /**
     * 刷新「空格键」那一块的预览 / 字体行 / 导入行。
     *
     * onCreate 里造控件时赋值，之后由输入框、开关、导入字体共用。
     * 存在成员上而不是只在 onCreate 里当局部变量，是因为 [onActivityResult]
     * （导入字体回来）和 [refreshAppearance] 在 onCreate 外面也得刷。
     */
    private var spacePreviewRefresh: () -> Unit = {}

    /**
     * 当前皮肤的缩略预览（[KeyboardPreviewView]）。
     *
     * 就是「主题与定制」入口行左边那一小块，显示的是真键盘缩小版。
     * 皮肤在别页换完回来要跟着变，所以 onResume 里再 setTheme 一次。
     */
    private var entryPreview: KeyboardPreviewView? = null

    /** 入口行里「当前：xxx」那行字，换皮肤回来要刷新 */
    private var skinLabelView: TextView? = null

    /** 皮肤分区标题，从键盘「更多 → 皮肤」进来时滚到这里 */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这个页要直接读写 ThemePrefs，得先把 ThemeManager 初始化起来。
        // （输入法服务那边在 onCreate 里自己会 init；设置页是独立入口，不能指望它）
        ThemeManager.init(this)
        // 同理：AppPrefs 平时由 JimengIME 初始化，设置页可能比输入法先打开，自己补一次
        AppPrefs.init(this)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(28), pad, dp(24))
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        root.addView(title("霁梦输入法"))
        root.addView(subtitle("fcitx5 键位 + librime 雾凇拼音"))

        // ---- 输入法：启用 / 选择 / 试打 ----
        // 三样合成一个白底座，中间画线隔开，看起来像一个整体。
        // 原来那些「已启用」「引擎：就绪」「引擎自检」全去掉了——
        // 状态是需要的时候去按键盘看的，不该占着设置页最上面一屏。
        root.addView(sectionTitle("输入法"), marginTop(dp(26)))
        val imeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        imeBox.addView(navRow("启用霁梦输入法") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        imeBox.addView(divider())
        imeBox.addView(navRow("选择霁梦输入法") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        })
        imeBox.addView(divider())
        // 试打框放在底座最下面：打完字键盘就是从这里弹出来的
        val edit = EditText(this).apply {
            hint = "在这里打几个字试试"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(12), dp(12), dp(12))
            // 底座本身是白的，输入框得用淡灰底才看得出边界
            setBackgroundColor(0xFFF4F6F9.toInt())
            setTextColor(0xFF202124.toInt())
            setHintTextColor(0xFFB0B4BB.toInt())
            textSize = 16f
        }
        imeBox.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = dp(6)
                it.bottomMargin = dp(14)
            }
        )
        root.addView(imeBox, marginTop(dp(8)))

        // ---- 主题与定制：入口行，点开进独立的皮肤页 ----
        val skinTitle = sectionTitle("主题与定制")
        root.addView(skinTitle, marginTop(dp(26)))
        root.addView(subtitle("换皮肤、导入皮肤包都在这一页"), marginTop(dp(4)))

        // 入口行：左边当前键盘缩小预览，右边文字 + 箭头，整行可点
        val entryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@SetupActivity, SkinActivity::class.java))
            }
        }
        // 缩略预览：真键盘缩小版，一眼看到现在键盘长什么样
        entryPreview = KeyboardPreviewView(this).apply {
            ThemeManager.activeTheme.let { setTheme(it) }
        }
        entryRow.addView(
            entryPreview,
            LinearLayout.LayoutParams(dp(96), dp(58))
        )
        val entryTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        entryTexts.addView(TextView(this).apply {
            text = "皮肤"
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        entryTexts.addView(TextView(this).apply {
            text = "当前：${currentSkinLabel()}"
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), 0, 0)
            skinLabelView = this
        })
        entryRow.addView(
            entryTexts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        entryRow.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(0xFFC4C8CE.toInt())
        })
        root.addView(entryRow, marginTop(dp(10)))

        // ---- 插件 ----
        // 样式跟上面「输入法」那个底座一致：白色圆角 + 细线隔开，
        // 这样整页从上到下是「输入法 / 主题与定制 / 插件 / 按键外观…」一串一样的块。
        root.addView(sectionTitle("插件"), marginTop(dp(26)))
        val pluginBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        pluginBox.addView(navRow("扩展商店") {
            startActivity(Intent(this@SetupActivity, StoreActivity::class.java))
        })
        pluginBox.addView(divider())
        // 插件管理：商店只管下载，开不开在这里
        pluginBox.addView(navRow("插件管理") {
            startActivity(Intent(this@SetupActivity, PluginManagerActivity::class.java))
        })
        pluginBox.addView(divider())
        // 模型管理：看已经下好的模型、删、重新加载引擎
        pluginBox.addView(navRow("模型管理") {
            startActivity(Intent(this@SetupActivity, ModelManagerActivity::class.java))
        })
        root.addView(pluginBox, marginTop(dp(8)))

        // ---- 按键外观 ----
        val appearanceTitle = sectionTitle("按键外观")
        appearanceAnchor = appearanceTitle
        root.addView(appearanceTitle, marginTop(dp(26)))
        root.addView(subtitle("改完立刻生效，不用重开键盘"), marginTop(dp(4)))

        val appearance = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        val prefs = ThemeManager.prefs

        appearance.addView(
            switchRow(
                "启用按键边框",
                "每个按键显示独立的底块，键与键之间自然分出边",
                prefs.keyBorder.getValue()
            ) { on ->
                prefs.keyBorder.setValue(on)
                applyKeyAppearanceChange()
                refreshAppearance()
            }
        )
        appearance.addView(
            switchRow(
                "启用按键边框描边",
                "边框画成线条，而不是带阴影的底块（需先启用按键边框）",
                prefs.keyBorderStroke.getValue()
            ) { on ->
                prefs.keyBorderStroke.setValue(on)
                // 开描边时顺手把边框也打开，否则看不出效果
                if (on && !prefs.keyBorder.getValue()) prefs.keyBorder.setValue(true)
                applyKeyAppearanceChange()
                refreshAppearance()
            }
        )
        appearance.addView(
            switchRow(
                "启用按键水波纹特效",
                "按下时从手指位置扩散一圈波纹，更接近 Material 手感",
                prefs.keyRippleEffect.getValue()
            ) { on ->
                prefs.keyRippleEffect.setValue(on)
                applyKeyAppearanceChange()
            }
        )

        root.addView(
            appearance,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )

        // ---- 按键行为 ----
        root.addView(sectionTitle("按键行为"), marginTop(dp(26)))
        root.addView(subtitle("拖完松手立刻生效，不用重开键盘"), marginTop(dp(4)))

        val behavior = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        val kbPrefs = AppPrefs.getInstance().keyboard
        behavior.addView(
            seekRow(
                "长按延迟",
                "按住键多久弹出气泡 / 触发长按（范围 100~700，原版默认 300）",
                min = 100, max = 700, step = 10,
                value = kbPrefs.longPressDelay.getValue(),
                unit = "ms"
            ) { v ->
                // CustomGestureView 里是每次用的时候现读 prefs，所以写完就直接生效
                kbPrefs.longPressDelay.setValue(v)
            }
        )
        // 按键预览气泡（对应 fcitx5-android 的 popupOnKeyPress）。
        // 关掉后长按菜单、上滑打符号都照旧，只是按住键时不再弹那个小气泡。
        // 键盘那边是每次手势事件现读 prefs 的，所以改完当场生效，不用重开键盘。
        behavior.addView(
            switchRow(
                "按键预览",
                "按住键时在键上方弹出预览气泡；关掉后长按菜单和上滑符号照常，只是不弹气泡",
                kbPrefs.popupOnKeyPress.getValue()
            ) { on ->
                kbPrefs.popupOnKeyPress.setValue(on)
            }
        )
        // 语言切换键（小地球 ⌯）显示开关。
        // 关掉后那个键走 GONE，空格键的 percentWidth 是 0（= 撑满剩余），
        // ConstraintLayout 的 chain 会自动把空出来的那一格全给空格，
        // 所以不需要手动改空格宽度。
        behavior.addView(
            switchRow(
                "显示语言切换键",
                "关掉后键盘上的小地球消失，空格键自动加长填满那一格",
                kbPrefs.showLangSwitchKey.getValue()
            ) { on ->
                kbPrefs.showLangSwitchKey.setValue(on)
                // 键盘那边注册了 OnChangeListener，这里写完键就当场变，不用重开
            }
        )
        // 语言输入（语音）按钮开关。
        // 打开：空闲时候选栏右端是 🎤；关闭：换成更细更淡的 ⌄「收起键盘」箭头。
        // 打字中右端永远是候选展开箭头，跟这个开关无关。
        behavior.addView(
            switchRow(
                "显示语言输入按钮",
                "打开时空闲显示麦克风；关掉改成收起键盘的箭头（打字时的候选展开箭头不受影响）",
                kbPrefs.showVoiceInputButton.getValue()
            ) { on ->
                kbPrefs.showVoiceInputButton.setValue(on)
                // 键盘那边注册了 OnChangeListener，当场换图标，不用重开键盘
            }
        )

        // ---- 输入反馈：按键震动 / 按键音效 ----
        // 两个纯开关，默认都是关的。
        // 底层存的仍是三态枚举（InputFeedbacks 要区分「听系统的」和「强行开」），
        // 但界面上只给开 / 关：开 = Enabled（不管系统设置都给），关 = Disabled。
        // 键盘端每次按键现读 prefs，改完当场生效，不用重开键盘。
        behavior.addView(
            switchRow(
                "按键震动",
                "",
                kbPrefs.hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled
            ) { on ->
                kbPrefs.hapticOnKeyPress.setValue(
                    if (on) InputFeedbackMode.Enabled else InputFeedbackMode.Disabled
                )
                // 打开时当场震一下，让用户立刻知道自己刚打开的是什么手感
                if (on) InputFeedbacks.hapticFeedback(window.decorView)
            }
        )
        behavior.addView(
            switchRow(
                "按住连发震动",
                "删字这类按住会连发的键，每重复一次都震一下",
                kbPrefs.hapticOnRepeat.getValue()
            ) { on ->
                kbPrefs.hapticOnRepeat.setValue(on)
            }
        )
        // 震动时长 / 幅度：0 都表示「不自定义，走系统默认震动」。
        // 取值区间照搬 fcitx5-android 原版：时长 0~100ms，幅度 0~255。
        // 幅度只有 Android 8.0+ 且马达支持振幅控制时才真正起作用，
        // 不支持时 InputFeedbacks 会自动退回系统默认幅度，所以这里不做隐藏。
        behavior.addView(
            seekRow(
                "震动时长",
                "按一下键震多久；0 表示跟随系统默认",
                min = 0, max = 100, step = 5,
                value = kbPrefs.buttonPressVibrationMilliseconds.getValue(),
                unit = "ms",
                labelFor = { v -> if (v == 0) "跟随系统" else "$v ms" }
            ) { v ->
                kbPrefs.buttonPressVibrationMilliseconds.setValue(v)
            }
        )
        behavior.addView(
            seekRow(
                "震动幅度",
                "震动强度；0 表示跟随系统默认（部分机型不支持调节）",
                min = 0, max = 255, step = 5,
                value = kbPrefs.buttonPressVibrationAmplitude.getValue(),
                unit = "",
                labelFor = { v -> if (v == 0) "跟随系统" else "$v" }
            ) { v ->
                kbPrefs.buttonPressVibrationAmplitude.setValue(v)
            }
        )
        behavior.addView(
            switchRow(
                "按键音效",
                "",
                kbPrefs.soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
            ) { on ->
                kbPrefs.soundOnKeyPress.setValue(
                    if (on) InputFeedbackMode.Enabled else InputFeedbackMode.Disabled
                )
                // 打开时当场响一下
                if (on) InputFeedbacks.soundEffect(InputFeedbacks.SoundEffect.Standard)
            }
        )
        // 音效音量：0 表示跟随系统媒体音量（原版语义），非 0 时按百分比播放。
        behavior.addView(
            seekRow(
                "音效音量",
                "按键音的响度；0 表示跟随系统媒体音量",
                min = 0, max = 100, step = 5,
                value = kbPrefs.soundOnKeyPressVolume.getValue(),
                unit = "%",
                labelFor = { v -> if (v == 0) "跟随系统" else "$v %" }
            ) { v ->
                kbPrefs.soundOnKeyPressVolume.setValue(v)
            }
        )

        // ---- 空格键上写字（用户的小巧思）----
        // 三件套：开关控制显隐、输入框控制内容、字体行控制字形。
        // 开关关掉时输入框和字体行都 GONE，因为那两项只对「显示字」有意义。
        val spaceLabelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 先占个位：输入框的 TextWatcher 要一边打一边刷预览，
        // 但预览那几行控件还没造出来。用这个可变量兜一下，
        // 真正实现放在下面造好控件之后再赋值。
        // 同时把它存到成员上，导入字体（onActivityResult，在 onCreate 外面）回来也要刷。
        var refreshSpacePreview: () -> Unit = {}
        spacePreviewRefresh = { refreshSpacePreview() }
        val spaceLabelInput = EditText(this).apply {
            hint = "想写在空格键上的字（最多 ${SpaceLabel.MAX_LENGTH} 个）"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFF202124.toInt())
            setHintTextColor(0xFFB0B4BB.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFF4F6F9.toInt())
            setText(kbPrefs.spaceLabelText.getValue())
            // 一边打一边写 prefs：这样键盘上的字是跟着手指实时变的，
            // 用户不用退出设置页去试。字数上限在 setText 里自己截。
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val raw = s?.toString() ?: ""
                    // 超长就截断并回写输入框，否则光标会跑到前面去
                    if (raw.length > SpaceLabel.MAX_LENGTH) {
                        val cut = raw.take(SpaceLabel.MAX_LENGTH)
                        s?.replace(0, raw.length, cut)
                        return
                    }
                    kbPrefs.spaceLabelText.setValue(raw)
                    // 内容和字体一起刷新，预览条、字体名右侧的值都跟着变
                    refreshSpacePreview()
                }
            })
        }

        // 字体行 + 一个「预览」条：让用户改完就看见效果，不用切出去试
        val spaceFontRow = pickRow(
            "空格键字体",
            "选个写法：楷书、行书、圆体…点开每一项都用它自己的字体显示",
            current = { kbPrefs.spaceFont.getValue().label }
        ) {
            showFontDialog { font ->
                kbPrefs.spaceFont.setValue(font)
                refreshSpacePreview()
            }
        }
        // 导入字体：从手机的「文件」里挑一个 .ttf/.otf，拷进 App 自己的目录。
        // 这样换字体不用重新编译、不用重装，APK 也不会因为字体变大。
        val importFontRow = pickRow(
            "导入字体",
            "从手机里选一个 .ttf / .otf 文件，选完立刻出现在上面的字体列表里",
            current = {
                val n = SpaceFontLoader.imported(this).size
                if (n == 0) "去导入" else "已导入 $n 个"
            }
        ) {
            pickFontFile()
        }

        val spacePreview = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF202124.toInt())
            setPadding(0, dp(14), 0, dp(14))
            setBackgroundColor(0xFFF4F6F9.toInt())
        }

        // 字号拉条：用户自己拖到舒服为止，不用猜数字。
        // 放在预览条上面，拖的时候预览条当场变大小，眼睛能立刻看到效果。
        val spaceSizeSeek = seekRow(
            "空格键字号",
            "拖动调整空格键上文字的大小，上面的预览条会跟着变",
            SpaceLabel.MIN_TEXT_SIZE,
            SpaceLabel.MAX_TEXT_SIZE,
            SpaceLabel.TEXT_SIZE_STEP,
            kbPrefs.spaceLabelTextSize.getValue()
                .coerceIn(SpaceLabel.MIN_TEXT_SIZE, SpaceLabel.MAX_TEXT_SIZE),
            "dp",
            // 「恢复默认」：回到 fcitx5 原版给空格键定的 13dp。
            // 注意这里返回的是「初始值」而不是 prefs 的当前值 —— 用户要的是回到初值，
            // 哪怕他从来没改过（也是 13），点了不亏，但语义上必须是初值。
            onReset = { SpaceLabel.IDLE_TEXT_SIZE.toInt() }
        ) { v ->
            kbPrefs.spaceLabelTextSize.setValue(v)
            // 键盘那边注册了 OnChangeListener，松手就当场换字号，不用重开键盘
            refreshSpacePreview()
        }

        /** 刷新输入框下面那条预览：内容、字体、开关状态三样都得对上 */
        fun refreshSpacePreviewImpl() {
            val on = kbPrefs.showSpaceLabel.getValue()
            val custom = kbPrefs.spaceLabelText.getValue().trim()
            val font = kbPrefs.spaceFont.getValue()
            spacePreview.text = buildString {
                append(custom.ifEmpty { "雾凇拼音" })
                if (!on) append("　（当前已关闭，空格键不显示字）")
            }
            spacePreview.setTextColor(
                if (on) 0xFF202124.toInt() else 0xFFB0B4BB.toInt()
            )
            spacePreview.typeface =
                if (on) font.typeface(this@SetupActivity) else null
            // 预览条的字号跟着拉条走，让用户拖之前就能看到效果。
            // 预览条能显示的字宽比空格键宽得多，所以这里按真值显示、不做等比压缩，
            // 用户看到的大小就是键上那个字的大小（dp 相同）。
            spacePreview.textSize = kbPrefs.spaceLabelTextSize.getValue()
                .coerceIn(SpaceLabel.MIN_TEXT_SIZE, SpaceLabel.MAX_TEXT_SIZE)
                .toFloat()
            // 输入框和预览只在开关打开时有意义
            spaceLabelInput.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE
            spacePreview.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE
            // 导入行：只要开关开着就一直给，因为「一个字体都没有」的时候
            // 用户更需要它——不然连入口都找不到。
            importFontRow.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE
            // 字体行：开关关掉时不显示；另外「系统默认」之外一个可选字体都没有时
            // 也隐藏——点开一个只有「系统默认」一项的列表毫无意义。
            // 导入过字体后 available() 就是 true，这一行会自己出现。
            spaceFontRow.visibility =
                if (on && SpaceFontLoader.available(this@SetupActivity)) {
                    ViewGroup.VISIBLE
                } else {
                    ViewGroup.GONE
                }
            // 字号拉条：只在开关打开时给。关掉后空格键上根本没字，拖了也没意义
            spaceSizeSeek.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE
            // 字体行右边显示的当前字体名；导入行右边的「已导入 N 个」
            pickRows["空格键字体"]?.invoke(font.label)
            pickRows["导入字体"]?.invoke(
                SpaceFontLoader.imported(this@SetupActivity).size
                    .let { if (it == 0) "去导入" else "已导入 $it 个" }
            )
        }
        // 控件都造好了，把上面那个占位函数接到真实实现上
        refreshSpacePreview = ::refreshSpacePreviewImpl

        behavior.addView(
            switchRow(
                "空格键显示字",
                "打开时空格键上写字（方案名或自定义）；关掉就是纯空格键",
                kbPrefs.showSpaceLabel.getValue()
            ) { on ->
                kbPrefs.showSpaceLabel.setValue(on)
                refreshSpacePreview()
            }
        )
        spaceLabelBox.addView(spaceLabelInput, marginTop(dp(-4)))
        spaceLabelBox.addView(spaceFontRow)
        spaceLabelBox.addView(importFontRow)
        // 字号拉条放在预览条上面：拖它 → 预览条变 → 眼睛确认 → 键盘就是那个大小
        spaceLabelBox.addView(spaceSizeSeek)
        spaceLabelBox.addView(
            spacePreview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )
        behavior.addView(
            spaceLabelBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        )
        refreshSpacePreview()

        root.addView(
            behavior,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )

        // ---- 键盘尺寸 ----
        // 用户 #20：高度 / 两侧边距 / 底部边距，除了这里填数字，
        // 键盘上长按逗号 →「键盘调节」也能直接拖着拉伸，两边写的是同一份 prefs。
        val sizeTitle = sectionTitle("键盘尺寸")
        keyboardSizeAnchor = sizeTitle
        root.addView(sizeTitle, marginTop(dp(26)))
        root.addView(
            subtitle("拖完松手立刻生效；也可以长按键盘上的逗号 →「键盘调节」直接在键盘上拉伸"),
            marginTop(dp(4))
        )

        val sizeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        // 竖屏：默认 34%（和 AppPrefs 里的默认值一致）
        sizeBox.addView(
            seekRow(
                "键盘高度（竖屏）",
                "占屏幕高度的百分比（范围 20~70，默认 34）",
                min = 20, max = 70, step = 1,
                value = kbPrefs.keyboardHeightPercent.getValue(),
                unit = "%"
            ) { v ->
                kbPrefs.keyboardHeightPercent.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        // 横屏：默认 49%
        sizeBox.addView(
            seekRow(
                "键盘高度（横屏）",
                "占屏幕高度的百分比（范围 20~70，默认 49）",
                min = 20, max = 70, step = 1,
                value = kbPrefs.keyboardHeightPercentLandscape.getValue(),
                unit = "%"
            ) { v ->
                kbPrefs.keyboardHeightPercentLandscape.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        sizeBox.addView(
            seekRow(
                "两侧边距（竖屏）",
                "键盘左右各留多少空（范围 0~60 dp，默认 0）",
                min = 0, max = 60, step = 1,
                value = kbPrefs.keyboardSidePadding.getValue(),
                unit = "dp"
            ) { v ->
                kbPrefs.keyboardSidePadding.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        sizeBox.addView(
            seekRow(
                "两侧边距（横屏）",
                "键盘左右各留多少空（范围 0~60 dp，默认 0）",
                min = 0, max = 60, step = 1,
                value = kbPrefs.keyboardSidePaddingLandscape.getValue(),
                unit = "dp"
            ) { v ->
                kbPrefs.keyboardSidePaddingLandscape.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        sizeBox.addView(
            seekRow(
                "底部边距（竖屏）",
                "键盘底边离屏幕底留多少空（范围 0~60 dp，默认 0）",
                min = 0, max = 60, step = 1,
                value = kbPrefs.keyboardBottomPadding.getValue(),
                unit = "dp"
            ) { v ->
                kbPrefs.keyboardBottomPadding.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        sizeBox.addView(
            seekRow(
                "底部边距（横屏）",
                "键盘底边离屏幕底留多少空（范围 0~60 dp，默认 0）",
                min = 0, max = 60, step = 1,
                value = kbPrefs.keyboardBottomPaddingLandscape.getValue(),
                unit = "dp"
            ) { v ->
                kbPrefs.keyboardBottomPaddingLandscape.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        root.addView(
            sizeBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )

        // ---- 剪贴板 ----
        // 记录上限只填数字：填多少就留多少，不设硬上限。
        // 一边打一边写 prefs，键盘下一次复制就按新数字裁，不用重启。
        // 下限 1 由 ClipboardHistory 的 limit getter 兜（填 0 等于不留历史，没意义）。
        val clipPrefs = AppPrefs.getInstance().clipboard
        root.addView(sectionTitle("剪贴板"), marginTop(dp(26)))
        val clipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        clipRow.addView(
            TextView(this).apply {
                text = "记录上限"
                textSize = 15f
                setTextColor(0xFF202124.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val clipLimitInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            setSingleLine(true)
            textSize = 15f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(0xFF202124.toInt())
            setHintTextColor(0xFFB0B4BB.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFFF4F6F9.toInt())
            setText(clipPrefs.historyLimit.getValue().toString())
            // 空着或删到一半就先不写，等用户打完；负数/0 照写，下限交给 ClipboardHistory
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val v = s?.toString()?.trim()?.toIntOrNull() ?: return
                    if (clipPrefs.historyLimit.getValue() == v) return
                    clipPrefs.historyLimit.setValue(v)
                    // 改完立刻按新数字收一次，不必等下一次复制
                    ClipboardHistory.applyLimitNow()
                }
            })
        }
        clipRow.addView(
            clipLimitInput,
            LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            clipRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )
        // ---- 版本 ----
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) {
            "?"
        }
        root.addView(
            subtitle("版本 $version").also { it.setPadding(0, dp(24), 0, 0) }
        )

        val scroller = ScrollView(this).apply { addView(root) }
        setContentView(scroller)
        // 从键盘「更多 → 外观」进来时，直接滚到「按键外观」那块
        val scrollTo = when {
            intent.getBooleanExtra(EXTRA_SCROLL_TO_APPEARANCE, false) -> appearanceAnchor
            intent.getBooleanExtra(EXTRA_SCROLL_TO_KEYBOARD_SIZE, false) -> keyboardSizeAnchor
            else -> null
        }
        if (scrollTo != null) {
            // 等布局完了才有真实坐标，普通 setScrollY 那会儿还都是 0
            scroller.post {
                scroller.scrollTo(0, scrollTo.top)
            }
        }

        RimeEngine.bind(this)
        // 不再把 this 挂成 callback：原来是为了刷新状态卡和自检报告，
        // 这两块已经去掉了。引擎照旧在这里初始化（幂等，键盘那边也会调）。
        RimeEngine.ensureInitialized()
    }

    override fun onResume() {
        super.onResume()
        // 从「主题与定制」页回来可能换了皮肤，预览和那行字跟着变
        ThemeManager.activeTheme.let { entryPreview?.setTheme(it) }
        skinLabelView?.text = "当前：${currentSkinLabel()}"
    }

    // ---- 小工具 ----

    /**
     * 当前皮肤的显示名。给入口行「当前：xxx」用。
     *
     * 现在永远有一套具体皮肤在用（默认 Pixel 亮色），所以一定拿得到名字。
     * 皮肤被删掉了但存档里还留着 id 时，[ThemeManager.activeTheme] 自己会
     * 回落到内置，这里也就找不到名字，统一显示成「系统默认」。
     */
    private fun currentSkinLabel(): String {
        val id = ThemeManager.currentSkinId()
        return try {
            SkinManager.list(this).firstOrNull { it.id == id }?.name ?: "系统默认"
        } catch (t: Throwable) {
            "系统默认"
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    /** 白色底座里的一条分隔线：1px 淡灰，左右留出边距，看着不像硬切一刀 */
    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    /**
     * 白色底座里的一行「点一下就跳走」的条目：左边标题，右边一个 ›。
     *
     * 原来是两个大按钮（① 启用 / ② 选择），做成行以后和下面的设置项一个样子，
     * 也不再用「①②」这种编号——顺序摆在那就够了。
     */
    private fun navRow(t: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                text = t
                textSize = 15f
                setTextColor(0xFF111318.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        // 用文字画个 ›，省一个 drawable 资源；颜色和说明文字一致
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(0xFFB0B4BB.toInt())
        })
        return row
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 26f
        setTextColor(0xFF111318.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun subtitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(0xFF8A8F98.toInt())
        setPadding(0, dp(6), 0, 0)
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(0xFF111318.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /**
     * 一行开关：左边标题 + 说明，右边 [android.widget.Switch]。
     * 键盘那边读的是 ThemePrefs，这里改完直接写进去，键盘下一次重建就生效。
     */
    private fun switchRow(
        title: String,
        desc: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), dp(8), 0)
            // 说明留空就整行不显示：只有标题的开关不用多占那一行高度
            visibility = if (desc.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val sw = android.widget.Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                // 程序在刷状态时不要当成用户操作，否则会绕成回环
                if (syncingSwitches) return@setOnCheckedChangeListener
                onToggle(isChecked)
            }
        }
        row.addView(sw)
        // 记下来，外面调 refreshAppearance() 时把勾选状态跟 prefs 对齐
        switchRows[title] = { sw.isChecked = it }
        return row
    }

    /**
     * 一行滑块：左边标题 + 说明，右边数值，下面一整条 [android.widget.SeekBar]。
     *
     * @param min 最小值（含）
     * @param max 最大值（含）
     * @param step 步进，SeekBar 只能从 0 数起，这里换算成「第几格」
     * @param value 当前值
     * @param unit 单位，显示在数字后面
     * @param onReset 可选。传了就在数值右边多一个「恢复默认」按钮，点击时由调用方
     *                返回该项的默认值，本方法自己把滑条位置和数字一起对齐后再回调
     *                [onChanged]。不传（默认 null）就跟以前完全一样，
     *                所以其他滑条不用改一行代码。
     * @param labelFor 可选。把数值翻成要显示的文字。不传就显示「数值 + 单位」。
     *                 给「0 代表系统默认」这种值用，不然右边写「0 ms」看不出是特殊值。
     * @param onChanged 拖动结束时回调真实值（拖动过程中只更新数字，不写 prefs）
     */
    private fun seekRow(
        title: String,
        desc: String,
        min: Int,
        max: Int,
        step: Int,
        value: Int,
        unit: String,
        onReset: (() -> Int)? = null,
        labelFor: ((Int) -> String)? = null,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        // 统一的显示格式：调用方给了 labelFor 就用它的，否则退回「数值 + 单位」。
        // 定义一次，下面所有要刷新文字的地方都用它，避免几处写法不一致。
        val format: (Int) -> String = { v -> labelFor?.invoke(v) ?: "$v $unit" }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), dp(8), 0)
        })
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 右边那个「300 ms」，拖动时跟着变
        val valueView = TextView(this).apply {
            text = format(value)
            textSize = 15f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        head.addView(valueView)
        column.addView(head)

        // SeekBar 只有整数格，换算成「第几格」：真实值 = min + progress * step
        val steps = (max - min) / step
        val bar = android.widget.SeekBar(this).apply {
            setMax(steps)
            setProgress(((value - min) / step).coerceIn(0, steps))
            setPadding(dp(2), dp(8), dp(2), dp(8))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    valueView.text = format(min + progress * step)
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

                // 松手才写 prefs：拖的时候每动一格都写，会白写几十次
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val real = min + (sb?.progress ?: 0) * step
                    valueView.text = format(real)
                    onChanged(real)
                }
            })
        }
        column.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 可选「恢复默认」：只有调用方传了 onReset 才有。
        // 放在标题那一行的数值右边（head 已经 addView 进 column 了，往里塞就能贴着数字），
        // 而不是滑条下面独占一行 —— 否则这一项会比设置页其他滑条高出一截。
        if (onReset != null) {
            val resetBtn = TextView(this).apply {
                text = "恢复默认"
                textSize = 12f
                setTextColor(0xFF3B6FE0.toInt())
                setPadding(dp(10), dp(4), dp(10), dp(4))
                // 淡蓝底 + 圆角，跟设置页其他地方的手写样式接近，也省得新建 drawable 资源
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFFEAF0FE.toInt())
                }
                isClickable = true
                setOnClickListener {
                    val def = onReset()
                    // 顺序：先把滑条和数字对齐到默认值，最后再回调写 prefs。
                    // 反过来的话，写 prefs 会触发刷新链路，可能把滑条又摆回旧值。
                    val p = ((def - min) / step).coerceIn(0, steps)
                    bar.setProgress(p)
                    valueView.text = format(min + p * step)
                    onChanged(def)
                }
            }
            head.addView(
                resetBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.leftMargin = dp(10) }
            )
        }

        // 外面刷新界面时把滑块和数字一起对齐
        seekRows[title] = { newValue ->
            val p = ((newValue - min) / step).coerceIn(0, steps)
            bar.setProgress(p)
            valueView.text = format(min + p * step)
        }
        return column
    }

    /**
     * 一行「可点开选的条目」：左边标题 + 说明，右边当前值 + ›。
     *
     * 这个页面全是手写 View、没有布局文件，点开弹一个列表就够了。
     * 右边的当前值由 [refreshAppearance] 通过 [pickRows] 同步。
     */
    private fun pickRow(
        title: String,
        desc: String,
        current: () -> String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), dp(8), 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = TextView(this).apply {
            text = current()
            textSize = 14f
            setTextColor(0xFF8A8F98.toInt())
        }
        row.addView(valueView)
        // 记下来，refreshAppearance 时把右边的当前值刷一遍
        pickRows[title] = { valueView.text = it }
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * 选空格键字体。每一行**用它自己的字体**显示自己的名字和预览字。
     *
     * 这是这个功能最关键的一步：只列「霞鹜文楷 / 志莽行书」几个名字，
     * 用户根本分不出差别 —— 字体是用来「看」的，不是用来读名字的。
     * 所以列表项左半是字体名、右半是「你好」两个字的实时预览，
     * 名字和预览都套上那个字体本身。
     *
     * 用 ListView + 自建 adapter 而不是 AlertDialog.setSingleChoiceItems：
     * 后者只能传字符串数组，样式全是系统的，套不上 Typeface。
     */
    private fun showFontDialog(onPick: (SpaceFont) -> Unit) {
        // 「系统默认 + 内置 + 已导入」，导入的字体是运行时才出现的，不能写死
        val fonts = SpaceFontLoader.all(this)
        val current = AppPrefs.getInstance().keyboard.spaceFont.getValue()
        // 比「序列化后的身份」而不是 label：导入的字体 label 就是文件名，
        // 万一有两个同名（或者内置名字撞了），按 label 会比错项、打错勾。
        val currentKey = SpaceFontLoader.serialize(current)
        val selectedIndex = fonts.indexOfFirst {
            SpaceFontLoader.serialize(it) == currentKey
        }.coerceAtLeast(0)
        // 预览用的字：有自定义就用自定义的头两个字，没有就「你好」
        val previewText = AppPrefs.getInstance().keyboard.spaceLabelText.getValue()
            .trim().take(2).ifEmpty { "你好" }
        val bgNormal = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        val dialogRef = arrayOfNulls<android.app.AlertDialog>(1)
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = fonts.size
            override fun getItem(position: Int) = fonts[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup?): android.view.View {
                val font = fonts[position]
                val typeface = font.typeface(this@SetupActivity)
                // 整行：左边名字 + 说明，右边预览字，最右边放个小勾 / 删除叉
                val row = LinearLayout(this@SetupActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    background = if (position == selectedIndex) {
                        android.graphics.drawable.ColorDrawable(0xFFF0F4FF.toInt())
                    } else bgNormal
                }
                // 点击选中必须挂在行自己身上。
                // 之前是把 onItemClickListener 挂在 ListView 上，同时给导入行挂了
                // onLongClickListener —— 子 View 一旦能响应长按，就会把整串触摸事件
                // 吃掉，ListView 的 onItemClick 永远不触发，表现就是「导入的字体点不了、
                // 只有系统默认能选」。改成行内自己处理，不再依赖 ListView 的分发。
                row.setOnClickListener {
                    onPick(font)
                    dialogRef[0]?.dismiss()
                }
                val left = LinearLayout(this@SetupActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                left.addView(TextView(this@SetupActivity).apply {
                    text = font.label
                    textSize = 16f
                    setTextColor(0xFF111318.toInt())
                    // 名字用这个字体本身写，一眼就能看出笔形
                    if (font.isCustom) setTypeface(typeface)
                })
                left.addView(TextView(this@SetupActivity).apply {
                    text = font.desc
                    textSize = 11.5f
                    setTextColor(0xFF8A8F98.toInt())
                    setPadding(0, dp(2), 0, 0)
                })
                row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // 右边的预览：统一 22sp，能看清笔锋
                row.addView(TextView(this@SetupActivity).apply {
                    text = previewText
                    textSize = 22f
                    setTextColor(0xFF202124.toInt())
                    gravity = Gravity.CENTER
                    if (font.isCustom) setTypeface(typeface)
                })
                if (position == selectedIndex) {
                    row.addView(TextView(this@SetupActivity).apply {
                        text = " ✓"
                        textSize = 15f
                        setTextColor(0xFF2B6FFF.toInt())
                    })
                }
                // 导入的字体：右边给一个小小的 ✕ 删除按钮。
                // 用按钮而不是长按——长按会抢点击事件（上面那行注释说的坑），
                // 而且「长按删除」本身也不好发现。
                if (font is SpaceFont.Imported) {
                    row.addView(TextView(this@SetupActivity).apply {
                        text = "✕"
                        textSize = 13f
                        setTextColor(0xFFB0B4BB.toInt())
                        gravity = Gravity.CENTER
                        setPadding(dp(14), dp(6), dp(2), dp(6))
                        setOnClickListener {
                            confirmDeleteFont(font) {
                                // 删完把弹窗关掉，让用户重新打开看到最新列表
                                onPick(SpaceFont.Default)
                                dialogRef[0]?.dismiss()
                            }
                        }
                    })
                }
                return row
            }
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("空格键字体（预览「$previewText」）")
            .setAdapter(adapter) { _, which -> }
            .setNegativeButton("取消", null)
            .create()
        dialogRef[0] = dialog
        dialog.show()
    }

    /** 确认删除一个导入的字体。删完把文件删掉，正在用它的就退回系统默认 */
    private fun confirmDeleteFont(font: SpaceFont.Imported, onDeleted: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删掉「${font.label}」？")
            .setMessage("只删掉导入到手机里的这个字体文件，你原来的文件还在原处，不受影响。")
            .setPositiveButton("删除") { _, _ ->
                val f = java.io.File(SpaceFontLoader.importedDir(this), font.fileName)
                val ok = try {
                    f.delete()
                } catch (t: Throwable) {
                    false
                }
                SpaceFontLoader.forgetDisplayName(this, font.fileName)
                SpaceFontLoader.clearCache()
                // 正在用的就是它 → 退回系统默认，别把用户卡在一个没有的字体上。
                // 按「序列化身份」比，不能用 label（同名字体会误判）
                val inUse = SpaceFontLoader.serialize(
                    AppPrefs.getInstance().keyboard.spaceFont.getValue()
                )
                if (inUse == SpaceFontLoader.serialize(font)) {
                    AppPrefs.getInstance().keyboard.spaceFont.setValue(SpaceFont.Default)
                }
                onDeleted()
                // 列表里少了一项，预览和「已导入 N 个」都要重算
                spacePreviewRefresh()
                applyKeyAppearanceChange()
                android.widget.Toast.makeText(
                    this,
                    if (ok) "已删掉「${font.label}」" else "删除失败，文件可能被占用了",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 从手机里挑一个字体文件导入。
     *
     * 用 SAF（ACTION_OPEN_DOCUMENT）而不是直接读路径：Android 10+ 分区存储以后
     * 不能再直接读 /sdcard 下的任意路径，SAF 由系统给一个临时可读的 Uri，
     * 不需要申请存储权限。
     */
    private fun pickFontFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // 有些文件管理器不认 ttf 的 MIME，只给 */* 再用扩展名兜一层
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "font/ttf", "font/otf", "font/ttc",
                        "application/x-font-ttf", "application/x-font",
                        "application/octet-stream"
                    )
                )
            }
            startActivityForResult(intent, REQ_PICK_FONT)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                this, "打不开文件选择器：${t.message}", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** SAF 选完回来了：把文件拷进 files/fonts/，读一下字体名，刷新列表 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQ_PICK_FONT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // 取文件名：SAF 的 Uri 最后一段通常是编码过的文件名
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "font.ttf"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) {
            android.widget.Toast.makeText(
                this, "这不是字体文件（要 .ttf / .otf / .ttc）", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        // 重名就加个序号，别把之前导入的覆盖掉
        val dir = SpaceFontLoader.importedDir(this)
        var target = java.io.File(dir, name)
        var seq = 2
        while (target.exists()) {
            target = java.io.File(dir, "${name.substringBeforeLast('.')}(${seq++}).${ext}")
        }
        val ok = try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: false
            true
        } catch (t: Throwable) {
            false
        }
        if (!ok || target.length() <= 0L) {
            try { target.delete() } catch (t: Throwable) {}
            android.widget.Toast.makeText(
                this, "读不出来这个文件，换一个试试", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        // 太小的肯定不是有效字体（一个中文字库再怎么子集化也有几百 KB）
        if (target.length() < 16 * 1024) {
            try { target.delete() } catch (t: Throwable) {}
            android.widget.Toast.makeText(
                this, "这个文件太小了（${target.length()} 字节），不像字体", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        // 试加载一次：字体坏了 Android 会抛异常，早发现早说，别等用户选了才发现
        val tf = try {
            android.graphics.Typeface.createFromFile(target)
        } catch (t: Throwable) {
            null
        }
        if (tf == null) {
            try { target.delete() } catch (t: Throwable) {}
            android.widget.Toast.makeText(
                this, "这个字体 Android 读不了，可能文件坏了", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        // 用字体自己声明的名字当显示名，比文件名好看；读不到就用文件名
        val family = SpaceFontLoader.readFamilyName(target)
        val display = family?.takeIf { it.isNotBlank() } ?: target.nameWithoutExtension
        // 记下来：下次重建列表还能显示这个名字，不会变回文件名
        SpaceFontLoader.rememberDisplayName(this, target.name, display)
        SpaceFontLoader.clearCache()
        // 导入完直接选中它——用户选字体就是想用它，不用再点一次
        val imported = SpaceFont.Imported(target.name, display)
        AppPrefs.getInstance().keyboard.spaceFont.setValue(imported)
        // 预览条、字体行右边的当前字体名、导入行右边的「已导入 N 个」全刷一遍
        spacePreviewRefresh()
        refreshAppearance()
        // 再发个广播让正开着的键盘立刻变
        applyKeyAppearanceChange()
        android.widget.Toast.makeText(
            this, "已导入「$display」，空格键已经换上了", android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /** 把开关行 / 滑块行的状态跟 prefs 里的真实值对齐 */
    private fun refreshAppearance() {
        val prefs = ThemeManager.prefs
        syncingSwitches = true
        try {
            switchRows["启用按键边框"]?.invoke(prefs.keyBorder.getValue())
            switchRows["启用按键边框描边"]?.invoke(prefs.keyBorderStroke.getValue())
            switchRows["启用按键水波纹特效"]?.invoke(prefs.keyRippleEffect.getValue())
            seekRows["长按延迟"]?.invoke(AppPrefs.getInstance().keyboard.longPressDelay.getValue())
            switchRows["按键预览"]?.invoke(
                AppPrefs.getInstance().keyboard.popupOnKeyPress.getValue()
            )
            // ---- 输入反馈 ----
            // 界面上是纯开关，只有开 / 关两种，底层映射到三态枚举
            switchRows["按键震动"]?.invoke(
                AppPrefs.getInstance().keyboard.hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled
            )
            switchRows["按住连发震动"]?.invoke(
                AppPrefs.getInstance().keyboard.hapticOnRepeat.getValue()
            )
            switchRows["按键音效"]?.invoke(
                AppPrefs.getInstance().keyboard.soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
            )
            switchRows["显示语言切换键"]?.invoke(
                AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()
            )
            switchRows["显示语言输入按钮"]?.invoke(
                AppPrefs.getInstance().keyboard.showVoiceInputButton.getValue()
            )
            switchRows["空格键显示字"]?.invoke(
                AppPrefs.getInstance().keyboard.showSpaceLabel.getValue()
            )
            pickRows["空格键字体"]?.invoke(
                AppPrefs.getInstance().keyboard.spaceFont.getValue().label
            )
            seekRows["空格键字号"]?.invoke(
                AppPrefs.getInstance().keyboard.spaceLabelTextSize.getValue()
            )
            // 键盘尺寸六项：对着 prefs 里的真实值
            val kb = AppPrefs.getInstance().keyboard
            seekRows["键盘高度（竖屏）"]?.invoke(kb.keyboardHeightPercent.getValue())
            seekRows["键盘高度（横屏）"]?.invoke(kb.keyboardHeightPercentLandscape.getValue())
            seekRows["两侧边距（竖屏）"]?.invoke(kb.keyboardSidePadding.getValue())
            seekRows["两侧边距（横屏）"]?.invoke(kb.keyboardSidePaddingLandscape.getValue())
            seekRows["底部边距（竖屏）"]?.invoke(kb.keyboardBottomPadding.getValue())
            seekRows["底部边距（横屏）"]?.invoke(kb.keyboardBottomPaddingLandscape.getValue())
        } finally {
            syncingSwitches = false
        }
    }

    /**
     * 让正在运行的键盘立刻应用新的按键外观。
     *
     * KeyView 的这几个属性是构造时读一次的，只能重建视图。这里发广播让
     * [JimengIME] 自己重建键盘那一层——设置页拿不到输入法服务的实例，
     * 而且键盘可能正开着别的应用，直接改 View 也不现实。
     */
    private fun applyKeyAppearanceChange() {
        sendBroadcast(Intent(JimengIME.ACTION_RELOAD_KEY_APPEARANCE).setPackage(packageName))
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = v }
}