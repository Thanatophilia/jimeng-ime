/*
 * 「关于」页：应用自身的信息 + 一个通往「第三方开源许可」的门。
 *
 * 组件清单和许可内容都收在 LicensesActivity，这里只留应用级别的东西
 * （版本 / 许可证 / 源码），外加一个入口。许可证要求随分发附带声明，
 * 普通用户装在手机上看不到仓库，所以清单必须能在 App 内查到。
 */

package com.jimeng.ime

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(28), pad, dp(32))
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        // ---- 顶栏 ----
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
            text = "关于"
            textSize = 20f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(topBar)

        // ---- 应用信息卡 ----
        val info = card()
        info.addView(TextView(this).apply {
            text = "霁梦输入法"
            textSize = 19f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        info.addView(divider(), marginTop(dp(12)))
        info.addView(infoRow("版本", versionName()))
        info.addView(divider())
        info.addView(infoRow("许可证", "GPL-3.0"))
        info.addView(divider())
        info.addView(infoRow("源码", "github.com/Thanatophilia/jimeng-ime"))
        root.addView(info, marginTop(dp(22)))

        // ---- 隐私政策 + 第三方开源许可 ----
        val licBox = card()
        licBox.addView(navRow("隐私政策") {
            startActivity(Intent(this@AboutActivity, PrivacyActivity::class.java))
        })
        licBox.addView(divider())
        licBox.addView(navRow("第三方开源许可") {
            startActivity(Intent(this@AboutActivity, LicensesActivity::class.java))
        })
        root.addView(licBox, marginTop(dp(16)))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ------------------------------------------------------------ 行

    /** 应用信息卡里的一行：左边标签，右边值。 */
    private fun infoRow(k: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(11), 0, dp(11))
        addView(TextView(this@AboutActivity).apply {
            text = k
            textSize = 13f
            setTextColor(0xFF8A8F98.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@AboutActivity).apply {
            text = v
            textSize = 13f
            setTextColor(0xFF4A4F57.toInt())
        })
    }

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
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(0xFFB0B4BB.toInt())
        })
        return row
    }

    // ------------------------------------------------------------ 基础件

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
        setPadding(dp(16), dp(4), dp(16), dp(4))
    }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = v }
}