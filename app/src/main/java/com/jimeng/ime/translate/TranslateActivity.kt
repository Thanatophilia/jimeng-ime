/*
 * 「翻译服务」设置页：内置 + 外接两条通道。
 *
 * ── 页面结构 ──────────────────────────────────
 *   ‹  翻译服务
 *
 *   配置
 *     自动
 *     仅内置
 *     仅外接
 *
 *   内置服务（作者提供）
 *     状态：已配置 / 未配置
 *     开关：启用
 *     [不可编辑，只读展示]
 *
 *   外接服务（你自己填）
 *     接口地址  [          ]
 *     API Key  [          ]
 *     模型名    [          ]
 *     [测试连接] [保存] [清除]
 *
 *   默认目标语言  [自动 / 中文 / 英文 / 日文 …]
 *
 *   试用
 *     [输入要翻译的文字      ] [翻译]
 *     （结果出现在下面）
 *
 * ── 为什么内置那条不让编辑 ────────────────────
 * 它是编译进 APK 的，改了也不落盘，下次启动又还原。与其让人以为改了有用，
 * 不如明说「这条是随版本走的，想用自己的请填外接」。
 */

package com.jimeng.ime.translate

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslateActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var channelRow: LinearLayout
    private lateinit var builtInBox: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var langRow: LinearLayout
    private lateinit var statusView: TextView

    /** 试翻区的输入框 */
    private lateinit var tryInput: EditText

    /** 试翻区的结果，还没翻过时是空串 */
    private lateinit var tryResult: TextView

    /** 试翻按钮。翻译期间禁用，避免连点打出一堆并发请求 */
    private lateinit var tryButton: TextView

    /** 当前这次试翻。重新点一次就把上一次取消掉 */
    private var tryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(28), pad, dp(24))
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        // ---- 顶部返回栏 ----
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(0, 0, 0, dp(6))
            setOnClickListener { finish() }
        }
        topBar.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(0xFF111318.toInt())
            setPadding(0, 0, dp(12), 0)
        })
        topBar.addView(TextView(this).apply {
            text = "翻译服务"
            textSize = 20f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(topBar)

        // ---- 配置 ----
        root.addView(sectionTitle("配置"), marginTop(dp(26)))
        channelRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        root.addView(channelRow, marginTop(dp(8)))
        renderChannels()

        // ---- 内置服务 ----
        root.addView(sectionTitle("内置服务"), marginTop(dp(26)))
        root.addView(
            subtitle("内置模型为掺了王者灵宝的豆包套了deepseek外壳,又蠢又笨延迟巨大,谨慎使用"),
            marginTop(dp(4))
        )
        builtInBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(builtInBox, marginTop(dp(8)))
        renderBuiltIn()

        // ---- 外接服务 ----
        root.addView(sectionTitle("外接服务"), marginTop(dp(26)))
        val customBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(customBox, marginTop(dp(8)))

        val existing = TranslateConfigStore.custom(this)
        urlInput = inputField(
            customBox, "接口地址", existing.baseUrl,
            "https://api.deepseek.com",
            InputType.TYPE_TEXT_VARIATION_URI
        )
        // 补全规则不写出来，用户填了自建地址就会被拼坏还查不出原因
        customBox.addView(TextView(this).apply {
            text = "提示：如果您输入的是基础 URL，系统将自动补全路径。如需禁用，请在 URL 末尾添加 # 号。"
            textSize = 11.5f
            setTextColor(0xFF8A8F98.toInt())
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(6), 0, 0)
        })
        keyInput = inputField(
            customBox, "API Key", existing.apiKey,
            "sk-…",
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        modelInput = inputField(
            customBox, "模型名", existing.model,
            "deepseek-chat",
            InputType.TYPE_CLASS_TEXT
        )

        // 按钮排一行
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        btnRow.addView(pillButton("测试连接", primary = false) { testConnection() })
        btnRow.addView(pillButton("保存", primary = true) { save() }, marginLeft(dp(8)))
        btnRow.addView(pillButton("清除", primary = false) { clearCustom() }, marginLeft(dp(8)))
        customBox.addView(btnRow)

        // ---- 互译语言对 ----
        root.addView(sectionTitle("互译语言对"), marginTop(dp(26)))
        langRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        root.addView(langRow, marginTop(dp(8)))
        renderLanguages()

        // ---- 试用 ----
        // 这一块是给「配好了但不放心」准备的：不用切回聊天框试，直接在这儿翻一句。
        // 用的就是上面配好的那条通道（自动 / 内置 / 外接按当前设置走），
        // 所以在这儿翻得通，回输入框也一定翻得通。
        root.addView(sectionTitle("试用"), marginTop(dp(26)))
        root.addView(
            subtitle("用当前生效的那条通道翻一句，看看效果"),
            marginTop(dp(4))
        )

        val tryBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(tryBox)

        // 输入行：左边输入框，右边按钮，两者底边对齐
        val tryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        tryBox.addView(tryRow)

        tryInput = EditText(this).apply {
            hint = "输入要翻译的文字"
            textSize = 14f
            setTextColor(0xFF111318.toInt())
            setHintTextColor(0xFFB0B4BB.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 3
            setPadding(dp(14), dp(11), dp(14), dp(11))
            // 两边圆角、上下也带一点，凑成一颗胶囊；外层不加白底，
            // 直接靠这个形状跟灰背景区分开
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), 0xFFE4E7EC.toInt())
            }
        }
        tryRow.addView(
            tryInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        tryButton = TextView(this).apply {
            text = "翻译"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF2F7D5A.toInt())
            }
            isClickable = true
            setOnClickListener { runTryTranslate() }
        }
        tryRow.addView(tryButton, marginLeft(dp(10)))

        // 结果区：一开始不占位置，翻完才出现
        tryResult = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFF111318.toInt())
            setLineSpacing(dp(3).toFloat(), 1.25f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFF2F4F7.toInt())
            }
            visibility = android.view.View.GONE
        }
        tryBox.addView(
            tryResult,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
        )

        // ---- 状态提示 ----
        statusView = subtitle("").also {
            it.setPadding(0, dp(24), 0, 0)
        }
        root.addView(statusView)

        val scroller = ScrollView(this).apply { addView(root) }
        setContentView(scroller)
    }

    // ------------------------------------------------------------ 三个区块

    /**
     * 通道选择：三个可点行，右边打勾。
     *
     * 每行只有名字，不带说明小字——「自动 / 仅内置 / 仅外接」三个词
     * 本身已经够直白，底下再挂一句解释反而把列表撑得又高又啰嗦。
     */
    private fun renderChannels() {
        channelRow.removeAllViews()
        val current = TranslateConfigStore.settings(this).channel
        val labels = listOf(
            TranslateChannel.Auto to "自动",
            TranslateChannel.BuiltIn to "仅内置",
            TranslateChannel.Custom to "仅外接"
        )
        labels.forEachIndexed { i, (ch, label) ->
            if (i > 0) channelRow.addView(divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener {
                    TranslateConfigStore.saveSettings(
                        this@TranslateActivity,
                        TranslateConfigStore.settings(this@TranslateActivity).copy(channel = ch)
                    )
                    renderChannels()
                    renderBuiltIn()
                    toast("已切换为「$label」")
                }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(0xFF111318.toInt())
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (ch == current) {
                row.addView(TextView(this).apply {
                    text = "✓"
                    textSize = 18f
                    setTextColor(0xFF2F7D5A.toInt())
                })
            }
            channelRow.addView(row)
        }
    }

    /**
     * 内置服务：只读展示 + 一个开关。
     *
     * 只留「已配置 / 未配置」和那个开关，底下那几行灰色小字
     * （模型、地址、Key 后四位、走哪条通道）都去掉了：
     * 用户要的就是「有没有、开没开」，多出来的信息只是噪音。
     */
    private fun renderBuiltIn() {
        builtInBox.removeAllViews()
        val info = TranslateConfigStore.builtIn()
        val s = TranslateConfigStore.settings(this)

        // 唯一一行：状态 + 开关
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(TextView(this).apply {
            text = if (info.isConfigured) "已配置" else "未配置"
            textSize = 15f
            setTextColor(
                if (info.isConfigured) 0xFF2F7D5A.toInt() else 0xFF8A8F98.toInt()
            )
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 开关
        val sw = android.widget.Switch(this).apply {
            isChecked = s.builtInEnabled
            isEnabled = info.isConfigured
            setOnCheckedChangeListener { _, checked ->
                TranslateConfigStore.saveSettings(
                    this@TranslateActivity,
                    TranslateConfigStore.settings(this@TranslateActivity)
                        .copy(builtInEnabled = checked)
                )
            }
        }
        statusRow.addView(sw)
        builtInBox.addView(statusRow)
    }

    private fun renderLanguages() {
        langRow.removeAllViews()
        val current = TranslateConfigStore.settings(this).pair
        // 只给这三对。它们是「互译」——两个方向都能翻，
        // 所以标签写成「中英互译」而不是「译成英文」，
        // 用户不必在翻之前先想清楚该选哪个方向。
        val options = TranslatePair.entries
        options.forEachIndexed { i, pair ->
            if (i > 0) langRow.addView(divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(13), 0, dp(13))
                isClickable = true
                setOnClickListener {
                    TranslateConfigStore.savePair(this@TranslateActivity, pair)
                    renderLanguages()
                }
            }
            row.addView(TextView(this).apply {
                text = pair.label
                textSize = 15f
                setTextColor(0xFF111318.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (pair == current) {
                row.addView(TextView(this).apply {
                    text = "✓"
                    textSize = 18f
                    setTextColor(0xFF2F7D5A.toInt())
                })
            }
            langRow.addView(row)
        }
    }

    // ------------------------------------------------------------ 动作

    private fun save() {
        val ep = collectCustom()
        if (!ep.isConfigured) {
            toast("三项都要填：地址、Key、模型名")
            return
        }
        TranslateConfigStore.saveCustom(this, ep)
        renderBuiltIn()
        toast("已保存")
    }

    private fun clearCustom() {
        TranslateConfigStore.clearCustom(this)
        urlInput.setText("")
        keyInput.setText("")
        modelInput.setText("")
        renderBuiltIn()
        toast("已清除外接配置")
    }

    private fun collectCustom() = CustomEndpoint(
        baseUrl = urlInput.text.toString().trim(),
        apiKey = keyInput.text.toString().trim(),
        model = modelInput.text.toString().trim()
    )

    /**
     * 测试连接。
     *
     * 用「当前填在框里的值」测，而不是用已保存的值——用户刚填完想先验证再保存，
     * 这是最常见的顺序。测完不自动保存，避免填错了被默默存下去。
     */
    private fun testConnection() {
        val ep = collectCustom()
        if (!ep.isConfigured) {
            toast("三项都要填才能测")
            return
        }
        statusView.text = "正在测试…"
        scope.launch {
            val resolved = ResolvedEndpoint(ep.baseUrl, ep.apiKey, ep.model, TranslateChannel.Custom)
            val cfg = TranslateConfigStore.settings(this@TranslateActivity)
            val result = TranslationService.test(
                endpoint = resolved,
                pair = cfg.pair,
                timeoutSeconds = cfg.timeoutSeconds
            )
            statusView.text = result.fold(
                onSuccess = { data ->
                    val parts = data.split("|", limit = 2)
                    val ms = parts.getOrNull(0) ?: "?"
                    val text = parts.getOrNull(1) ?: ""
                    "连接成功，${ms}ms，返回：${text.take(40)}"
                },
                onFailure = { e ->
                    "连接失败：${e.message?.take(160)}"
                }
            )
            statusView.setTextColor(
                if (result.isSuccess) 0xFF2F7D5A.toInt() else 0xFFC0392B.toInt()
            )
        }
    }

    // ------------------------------------------------------------ 试翻

    /**
     * 试翻一句话。
     *
     * 用哪条通道：
     * 1. 外接框里填了东西 → 就用框里的值（可能还没点保存，但用户既然填了就是想试它）
     * 2. 否则按已保存的设置 resolve（自动 / 内置 / 仅外接照旧）
     *
     * 这样「填完先试一下再决定保不保存」这个最自然的顺序能走通。
     */
    private fun runTryTranslate() {
        val text = tryInput.text.toString().trim()
        if (text.isEmpty()) {
            toast("先输入要翻译的文字")
            return
        }

        val typed = collectCustom()
        val endpoint: ResolvedEndpoint
        if (typed.isConfigured) {
            endpoint = ResolvedEndpoint(
                typed.baseUrl, typed.apiKey, typed.model, TranslateChannel.Custom
            )
        } else {
            val saved = TranslateConfigStore.resolve(this)
            if (saved == null) {
                toast("还没有可用的服务，先在上面配一条")
                return
            }
            endpoint = saved
        }

        val settings = TranslateConfigStore.settings(this)

        // 连点两下：前一次直接作废，不然两个结果会先后落到同一个结果区
        tryJob?.cancel()
        setTryBusy(true)
        tryResult.visibility = android.view.View.GONE

        tryJob = scope.launch {
            try {
                val started = System.currentTimeMillis()
                val translated = TranslationService.translate(
                    text = text,
                    pair = settings.pair,
                    endpoint = endpoint,
                    timeoutSeconds = settings.timeoutSeconds
                )
                val ms = System.currentTimeMillis() - started
                showTryResult(translated, ok = true, ms = ms, channel = endpoint.channel)
            } catch (t: Throwable) {
                showTryResult(friendlyError(t), ok = false, ms = 0, channel = endpoint.channel)
            } finally {
                setTryBusy(false)
            }
        }
    }

    private fun setTryBusy(busy: Boolean) {
        tryButton.isEnabled = !busy
        tryButton.text = if (busy) "翻译中…" else "翻译"
        tryButton.alpha = if (busy) 0.55f else 1f
    }

    private fun showTryResult(
        text: String,
        ok: Boolean,
        ms: Long,
        channel: TranslateChannel
    ) {
        tryResult.visibility = android.view.View.VISIBLE
        tryResult.text = text
        tryResult.setTextColor(
            if (ok) 0xFF111318.toInt() else 0xFFC0392B.toInt()
        )
        // 结果后面缀一行来路：哪条通道、花了多久。
        // 出问题时这一行比译文本身有用——是内置还是外接、慢不慢，一眼就知道。
        val from = if (channel == TranslateChannel.BuiltIn) "内置" else "外接"
        statusView.text = if (ok) "$from · ${ms}ms" else "$from · 失败"
        statusView.setTextColor(
            if (ok) 0xFF8A8F98.toInt() else 0xFFC0392B.toInt()
        )
    }

    /** 把异常转成一句人能看懂的话 */
    private fun friendlyError(t: Throwable): String = when (t) {
        is TranslateError.Http ->
            if (t.code == 401 || t.code == 403) "Key 无效或无权限（HTTP ${t.code}）"
            else if (t.code == 404) "地址不对（HTTP 404），检查是否要以 # 结尾"
            else "服务端报错（HTTP ${t.code}）"
        is TranslateError.Network -> "网络不通或超时"
        is TranslateError.Parse -> "返回内容看不懂，可能不是 OpenAI 兼容接口"
        is TranslateError.NotConfigured -> "还没有可用的服务"
        else -> t.message?.take(120) ?: "翻译失败"
    }

    // ------------------------------------------------------------ 基础件

    private fun inputField(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String,
        inputType: Int
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(8), 0, dp(4))
        })
        val edit = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 14f
            setTextColor(0xFF111318.toInt())
            setHintTextColor(0xFFB0B4BB.toInt())
            this.inputType = inputType
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFFF2F4F7.toInt())
            }
        }
        parent.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(2) }
        )
        return edit
    }

    private fun pillButton(text: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else 0xFF111318.toInt())
        setPadding(dp(18), dp(9), dp(18), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (primary) 0xFF2F7D5A.toInt() else 0xFFF2F4F7.toInt())
        }
        isClickable = true
        setOnClickListener { onClick() }
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

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        )
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = v }

    private fun marginLeft(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.leftMargin = v }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tryJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }
}

/** 便于从别的页面拿到 Context 时直接开 */
fun openTranslateSettings(ctx: Context) {
    ctx.startActivity(
        android.content.Intent(ctx, TranslateActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}