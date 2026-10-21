package com.jimeng.ime

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.jimeng.ime.kb.clipboard.ClipboardHistory
import splitties.dimensions.dp

/**
 * 剪贴板条目编辑页（照 fcitx5 `ui/main/ClipboardEditActivity`）。
 *
 * 原版是一个 `Activity`，从长按菜单的「编辑」跳过来：上面一个多行输入框放着原文，
 * 底下三个按钮——取消（直接关）、确定（存回去）、复制（存回去并写到系统剪贴板）。
 *
 * 几个从原版搬过来的细节：
 * - `window.attributes.gravity = Gravity.TOP`：从顶部往下铺，不居中跳动
 * - `onStop` 里 `finish()`：切走就关掉，不留一个孤零零的页面在后台
 * - 打开就自动弹软键盘（`SHOW_IMPLICIT`）
 *
 * 和原版唯一的差别是数据源——原版拿 `entry.id` 回数据库查，我们拿原文回
 * [ClipboardHistory] 里找（我们的条目没有 id，就用文本当身份）。
 */
class ClipboardEditActivity : Activity() {

    private lateinit var editText: EditText

    /** 进来时那一条的原文（编辑完用它定位是哪条，改了就按新文字存） */
    private var originalText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 从顶部铺开，别在屏幕中间跳一下
        window.attributes = window.attributes.apply { gravity = Gravity.TOP }

        ClipboardHistory.init(this)

        originalText = intent.getStringExtra(EXTRA_TEXT) ?: ""

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(20), pad, dp(16))
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        root.addView(
            TextView(this).apply {
                text = "编辑剪贴板内容"
                textSize = 16f
                setTextColor(0xFF202124.toInt())
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        )

        editText = EditText(this).apply {
            setText(originalText)
            setSelection(text.length)
            // 多行 + 允许换行，长文本才编辑得动
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            maxLines = 12
            textSize = 15f
        }
        root.addView(
            editText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ---- 底下三个按钮：取消 / 确定 / 复制 ----
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(
            flatButton("取消") { finish() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        )
        buttons.addView(
            flatButton("确定") { finishEditing(copy = false) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        )
        buttons.addView(
            flatButton("复制") { finishEditing(copy = true) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )

        setContentView(root)

        // 打开就把软键盘弹出来（照原版 SHOW_IMPLICIT）
        editText.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * 保存并关页（照原版 `finishEditing`）。
     *
     * [copy] 为 true 时除了存回历史，还把新内容写进系统剪贴板——
     * 主人点「复制」的意思就是「改完顺便复制一下」。
     */
    private fun finishEditing(copy: Boolean = false) {
        val newText = editText.text?.toString() ?: ""
        if (newText.isNotBlank() && newText != originalText) {
            ClipboardHistory.updateText(originalText, newText)
        }
        if (copy && newText.isNotBlank()) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("", newText))
            } catch (_: Throwable) {
                // 写系统剪贴板失败无所谓，历史里已经存好了
            }
        }
        finish()
    }

    /** 一个朴素的文字按钮 */
    private fun flatButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { onClick() }
        }

    /** 切走就关掉（照原版 `onStop` 里 `finish()`），不留后台页面 */
    override fun onStop() {
        super.onStop()
        finish()
    }

    companion object {
        /** 要编辑的原文 */
        const val EXTRA_TEXT = "text"

        /** 造一个跳到本页的 Intent（照原版 AppUtil.launchClipboardEdit） */
        fun intent(context: Context, text: String): Intent =
            Intent(context, ClipboardEditActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                // 从输入法服务里启动，必须带这个 flag
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}