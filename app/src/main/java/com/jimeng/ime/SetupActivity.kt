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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = v }
}