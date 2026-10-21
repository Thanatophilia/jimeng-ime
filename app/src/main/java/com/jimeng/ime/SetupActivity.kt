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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.rime.RimeEngine
import com.jimeng.ime.rime.RimeNative

/**
 * 设置/引导页。
 *
 * 干四件事：检查输入法有没有启用、一键跳系统设置、给个输入框试打、显示引擎自检结果。
 * 首次部署要几十秒，这些状态都得看得见。
 */
class SetupActivity : Activity(), RimeEngine.Callback {

    private var statusView: TextView? = null
    private var engineView: TextView? = null
    private var selfTestView: TextView? = null
    private var selfTestButton: Button? = null

    /** 开关行标题 -> 同步勾选状态的函数（switchRow 里登记，refreshAppearance 里用） */
    private val switchRows = LinkedHashMap<String, (Boolean) -> Unit>()

    /** 滑块行标题 -> 同步滑块位置的函数（seekRow 里登记，refreshAppearance 里用） */
    private val seekRows = LinkedHashMap<String, (Int) -> Unit>()

    /**
     * 正在把勾选状态往界面上「刷」的时候为 true。
     *
     * 没有它的话：程序改 [android.widget.Switch.isChecked] → 触发用户监听 → 再写一次
     * prefs → 再发一次广播 → 再刷一次界面，白绕一圈。刷的时候先压住，刷完放开。
     */
    private var syncingSwitches = false

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

        // ---- 状态卡 ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF202124.toInt())
        }
        engineView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF5F6368.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        selfTestView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(0xFF3C4043.toInt())
            setPadding(0, dp(10), 0, 0)
            setTextIsSelectable(true)
        }
        card.addView(statusView)
        card.addView(engineView)
        card.addView(selfTestView)
        root.addView(card, marginTop(dp(18)))

        // ---- 按钮 ----
        root.addView(
            button("① 启用霁梦输入法") {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }, marginTop(dp(18))
        )
        root.addView(
            button("② 选择霁梦输入法") {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }, marginTop(dp(10))
        )
        selfTestButton = button("③ 重跑引擎自检") { RimeEngine.runSelfTest() }
        root.addView(selfTestButton, marginTop(dp(10)))

        // ---- 按键外观 ----
        root.addView(sectionTitle("按键外观"), marginTop(dp(26)))
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
        root.addView(
            behavior,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )

        // ---- 试打区 ----
        root.addView(sectionTitle("试打"), marginTop(dp(26)))
        val edit = EditText(this).apply {
            hint = "在这里打几个字试试"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.WHITE)
            setTextColor(0xFF202124.toInt())
            textSize = 16f
        }
        root.addView(
            edit,
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

        setContentView(ScrollView(this).apply { addView(root) })

        RimeEngine.bind(this)
        RimeEngine.callback = this
        RimeEngine.ensureInitialized()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        if (RimeEngine.callback === this) RimeEngine.callback = null
        super.onDestroy()
    }

    private fun refresh() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        // 用 InputMethodManager 查，比读 Settings.Secure 可靠——
        // ColorOS 这类 ROM 会把 ENABLED_INPUT_METHODS 读成空，导致误报「未启用」。
        val myId = "$packageName/.JimengIME"
        val enabledIds = try {
            imm.enabledInputMethodList.map { it.id }
        } catch (t: Throwable) {
            emptyList()
        }
        val enabled = enabledIds.any { it.equals(myId, true) || it.startsWith(packageName, true) }

        val current = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (t: Throwable) {
            null
        }
        val isDefault = current?.startsWith(packageName) == true

        statusView?.text = buildString {
            append(if (enabled) "✅ 已启用" else "⛔ 未启用")
            append("　　")
            append(if (isDefault) "✅ 当前输入法" else "⏳ 未设为当前输入法")
            when {
                !enabled -> {
                    append("\n\n请先点下面的按钮把霁梦输入法启用。")
                    if (enabledIds.isNotEmpty()) {
                        append("\n（系统列出 ${enabledIds.size} 个已启用输入法，里面没有霁梦）")
                    }
                }
                !isDefault -> append("\n\n已启用。点「选择输入法」切过来就能用。")
                else -> append("\n\n可以直接在下面试打了。")
            }
        }

        engineView?.text = buildString {
            when (RimeEngine.state) {
                RimeEngine.State.Idle -> append("引擎：未启动")
                RimeEngine.State.Deploying -> append("引擎：正在部署词库…（首次几十秒）")
                RimeEngine.State.Ready -> append("引擎：就绪")
                RimeEngine.State.Failed -> append("引擎：失败 — ${RimeEngine.lastError}")
            }
            if (RimeEngine.state == RimeEngine.State.Ready) {
                val v = try {
                    RimeNative.nativeGetVersion()
                } catch (t: Throwable) {
                    "?"
                }
                append("\nlibrime $v · 方案 雾凇拼音")
            }
            append("\n服务：$myId")
        }

        renderSelfTest()
    }

    /** 把自检结果画出来 */
    private fun renderSelfTest() {
        val box = selfTestView ?: return
        val btn = selfTestButton

        if (RimeEngine.selfTestRunning) {
            box.text = "—— 引擎自检 ——\n⏳ 正在跑：敲键 → 候选 → 上屏 全链路"
            btn?.isEnabled = false
            btn?.alpha = 0.5f
            return
        }

        btn?.isEnabled = RimeEngine.state == RimeEngine.State.Ready
        btn?.alpha = if (btn?.isEnabled == true) 1f else 0.5f

        val report = RimeEngine.lastSelfTest
        if (report == null) {
            box.text = "—— 引擎自检 ——\n尚未运行（引擎就绪后会自动跑一次）"
            return
        }

        box.text = buildString {
            append("—— 引擎自检 ——\n")
            append(if (report.ok) "结论：全部通过" else "结论：有失败项")
            append("（${report.costMs} ms）\n")
            report.lines.forEach { append(it).append('\n') }
        }.trimEnd()
    }

    override fun onStateChanged(state: RimeEngine.State, message: String?) {
        refresh()
    }

    override fun onReady() {
        refresh()
    }

    override fun onSelfTest(report: RimeEngine.SelfTestReport?) {
        renderSelfTest()
    }

    // ---- 小工具 ----

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

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

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 15f
        isAllCaps = false
        setOnClickListener { onClick() }
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
        onChanged: (Int) -> Unit
    ): LinearLayout {
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
            text = "$value $unit"
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
                    valueView.text = "${min + progress * step} $unit"
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

                // 松手才写 prefs：拖的时候每动一格都写，会白写几十次
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val real = min + (sb?.progress ?: 0) * step
                    valueView.text = "$real $unit"
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

        // 外面刷新界面时把滑块和数字一起对齐
        seekRows[title] = { newValue ->
            val p = ((newValue - min) / step).coerceIn(0, steps)
            bar.setProgress(p)
            valueView.text = "${min + p * step} $unit"
        }
        return column
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