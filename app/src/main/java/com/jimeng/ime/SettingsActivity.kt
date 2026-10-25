/*
 * 「键盘设置」独立页面：按键行为 + 剪贴板。
 *
 * ── 为什么单独开一页 ────────────────────────────────────────
 * 设置主页原来是「输入法 / 主题与定制 / 按键外观 / 插件 / 按键行为 / 剪贴板」
 * 一路排下来，后两块加起来十几行开关和滑条，把主页拉得很长，
 * 而这两块改的都是「键盘用起来什么样」，和主页上那些「跳去别的页」的门
 * 本来就不是一类东西。
 * 收到这一页之后，主页只留入口行，这一页专心摆细节。
 *
 * ── 页面结构 ────────────────────────────────────────────
 *   ┌──────────────────────────────┐
 *   │ ‹  键盘设置                    │
 *   ├──────────────────────────────┤
 *   │ 按键行为                       │
 *   │   长按延迟        ─────── 300ms │
 *   │   [开关] 按键预览               │
 *   │   [开关] 显示语言切换键          │
 *   │   [开关] 显示语言输入按钮        │
 *   ├──────────────────────────────┤
 *   │ 输入反馈                       │
 *   │   [开关] 按键震动               │
 *   │   [开关] 按住连发震动            │
 *   │   震动时长 / 震动幅度            │
 *   │   [开关] 按键音效               │
 *   │   音效音量                      │
 *   ├──────────────────────────────┤
 *   │ 剪贴板                         │
 *   │   记录上限          [ 20 ]      │
 *   └──────────────────────────────┘
 *
 * 所有改动都是即时生效：键盘那边每次按键 / 每次复制都现读 prefs，
 * 这边写完就见效，不用退出重开键盘。
 */
package com.jimeng.ime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.InputFeedbacks
import com.jimeng.ime.kb.clipboard.ClipboardHistory
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.prefs.InputFeedbackMode
import com.jimeng.ime.kb.theme.ThemeManager

/**
 * 键盘设置页：按键行为 + 输入反馈 + 剪贴板。
 *
 * 从设置主页的「键盘设置」入口进来。主页那边只留门，不再摊开这些开关。
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这个页面可能比输入法服务先打开，得自己把两个单例初始化起来
        ThemeManager.init(this)
        AppPrefs.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
        }
        root.addView(buildTopBar())
        root.setPadding(0, statusBarHeight(), 0, 0)

        val scroller = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        scroller.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        val kbPrefs = AppPrefs.getInstance().keyboard

        // ============================ 按键行为 ============================
        content.addView(sectionTitle("按键行为"))

        val behavior = whiteBox()
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
        content.addView(behavior, marginTop(dp(8)))

        // ============================ 输入反馈 ============================
        // 两个开关底层存的仍是三态枚举（InputFeedbacks 要区分「听系统的」和「强行开」），
        // 但界面上只给开 / 关：开 = Enabled（不管系统设置都给），关 = Disabled。
        content.addView(sectionTitle("输入反馈"), marginTop(dp(26)))

        val feedback = whiteBox()
        feedback.addView(
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
        feedback.addView(
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
        feedback.addView(
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
        feedback.addView(
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
        feedback.addView(
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
        feedback.addView(
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
        content.addView(feedback, marginTop(dp(8)))

        // ============================ 剪贴板 ============================
        // 记录上限只填数字：填多少就留多少，不设硬上限。
        // 一边打一边写 prefs，键盘下一次复制就按新数字裁，不用重启。
        // 下限 1 由 ClipboardHistory 的 limit getter 兜（填 0 等于不留历史，没意义）。
        content.addView(sectionTitle("剪贴板"), marginTop(dp(26)))
        content.addView(subtitle("键盘剪贴板面板里保留多少条历史"))

        val clipPrefs = AppPrefs.getInstance().clipboard
        // whiteBox() 给的是 VERTICAL（那一串分区都是竖排的），这里必须显式改成横排：
        // 不改的话「记录上限」和输入框会排成上下两行，输入框掉到下面去，
        // 中间空出一大块，看着像是标题和输入框不相干。
        val clipRow = whiteBox()
        clipRow.orientation = LinearLayout.HORIZONTAL
        clipRow.gravity = Gravity.CENTER_VERTICAL
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
        content.addView(clipRow, marginTop(dp(8)))
    }

    // ============================================================
    //  基础件
    // ============================================================

    /** 顶部返回栏。跟「按键外观设置」那一页保持同一个样子。 */
    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bar.addView(
            TextView(this).apply {
                text = "‹"
                textSize = 26f
                setTextColor(0xFF111318.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), dp(2))
                isClickable = true
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        bar.addView(
            TextView(this).apply {
                text = "键盘设置"
                textSize = 18f
                setTextColor(0xFF111318.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return bar
    }

    /** 白色圆角底座：一组设置项装在一块，跟主页那些卡片的样式一致。 */
    private fun whiteBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(4))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(0xFF111318.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun subtitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(0xFF8A8F98.toInt())
        setPadding(0, dp(6), 0, 0)
    }

    /** 一行开关：左边标题 + 说明，右边 Switch。 */
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
            // 说明留空就整行不显示：只有标题的开关不用多占那一行高度
            visibility = if (desc.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            android.widget.Switch(this).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
            }
        )
        return row
    }

    /**
     * 一行滑块：左边标题 + 说明，右边数值，下面一整条 SeekBar。
     *
     * @param labelFor 可选。把数值翻成要显示的文字。不传就显示「数值 + 单位」。
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
        labelFor: ((Int) -> String)? = null,
        onChanged: (Int) -> Unit
    ): LinearLayout {
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
        head.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

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
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
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
        return column
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = v }
}
