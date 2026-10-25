/*
 * 「第三方开源许可」页：本应用包含的全部第三方组件。
 *
 * ── 为什么清单要标「已用 / 未用」 ──────────────────────────────
 * 常有人拿别的输入法的许可页来问「这个你用了没」，与其反复解释，
 * 不如在 App 里一次列清。带 ✓ 的是本版本实际打包或链接进来的；
 * 带「—」的是本项目没有引入的（Compose / Navigation Compose /
 * Material Icons 这套现代 Android 常见件，本项目一个都没用）。
 *
 * ── 深层的那些依赖 ──────────────────────────────────────────
 * OpenCC / Boost / leveldb / marisa-trie / yaml-cpp / glog 不是 Gradle
 * 依赖，是 librime 静态链接进 libjimeng_rime.so 的。查 .so 符号表可确认：
 * opencc 329 个、boost 1797 个、leveldb 817 个、marisa 452 个符号。
 * 它们的许可证同样要求随分发附带声明，所以一并列出。
 */

package com.jimeng.ime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LicensesActivity : Activity() {

    /**
     * 清单里的一条。
     *
     * @param name    组件名
     * @param license 许可证
     * @param usage   本项目怎么用它的
     * @param url     上游仓库，点条目时用浏览器打开
     * @param used    是否真的在本项目的构建里
     */
    private data class Comp(
        val name: String,
        val license: String,
        val usage: String,
        val url: String,
        val used: Boolean = true
    )

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
            text = "第三方开源许可"
            textSize = 20f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(topBar)

        val comps = listOf(
            Comp(
                "librime", "BSD-3-Clause",
                "Rime 输入法核心：按键处理、组字、候选、词库查询",
                "https://github.com/rime/librime"
            ),
            Comp(
                "OpenCC", "Apache-2.0",
                "简繁转换；数据 STCharacters.ocd2 / STPhrases.ocd2 在 assets/rime/opencc/",
                "https://github.com/BYVoid/OpenCC"
            ),
            Comp(
                "Boost", "BSL-1.0",
                "librime 的 boost::signals2 信号槽、boost::unordered 容器",
                "https://github.com/boostorg/boost"
            ),
            Comp(
                "leveldb", "BSD-3-Clause",
                "用户词典与历史记录的持久化存储",
                "https://github.com/google/leveldb"
            ),
            Comp(
                "marisa-trie", "BSD-2-Clause",
                "词库的紧凑 trie 结构，.bin 词库文件即由它生成",
                "https://github.com/s-yata/marisa-trie"
            ),
            Comp(
                "yaml-cpp", "MIT",
                "解析方案配置（*.schema.yaml / *.dict.yaml）",
                "https://github.com/jbeder/yaml-cpp"
            ),
            Comp(
                "glog", "BSD-3-Clause",
                "librime 的运行日志",
                "https://github.com/google/glog"
            ),
            Comp(
                "fcitx5-android", "LGPL-2.1-or-later",
                "键盘视图层移植：按键气泡与长按符号面板、候选栏三态状态机、预编辑码区（26 个源文件保留原始 SPDX 头）",
                "https://github.com/fcitx5-android/fcitx5-android"
            ),
            Comp(
                "AndroidX Core", "Apache-2.0",
                "core-ktx 1.13.1：Compat 封装与 Kotlin 扩展",
                "https://developer.android.com/jetpack/androidx"
            ),
            Comp(
                "AndroidX Lifecycle", "Apache-2.0",
                "随 core / fragment 传递引入，提供生命周期与 ViewModel",
                "https://developer.android.com/jetpack/androidx/releases/lifecycle"
            ),
            Comp(
                "RecyclerView / ViewPager2", "Apache-2.0",
                "候选栏与剪贴板、输入页的分页容器",
                "https://developer.android.com/jetpack/androidx/releases/recyclerview"
            ),
            Comp(
                "ConstraintLayout", "Apache-2.0",
                "皮肤预览与设置页的约束布局",
                "https://developer.android.com/jetpack/androidx/releases/constraintlayout"
            ),
            Comp(
                "FlexboxLayout", "Apache-2.0",
                "候选栏的流式换行排布（FlexboxLayoutManager）",
                "https://github.com/google/flexbox-layout"
            ),
            Comp(
                "Splitties", "Apache-2.0",
                "dimensions / resources / views-dsl 等模块，视图 DSL 与尺寸扩展",
                "https://github.com/LouisCAD/Splitties"
            ),
            Comp(
                "Jetpack Compose", "Apache-2.0",
                "未引入：本项目为纯 View 体系，源码中 0 处 androidx.compose 引用",
                "https://developer.android.com/jetpack/compose",
                used = false
            ),
            Comp(
                "Navigation Compose", "Apache-2.0",
                "未引入：页面跳转直接用 startActivity",
                "https://developer.android.com/jetpack/androidx/releases/navigation",
                used = false
            ),
            Comp(
                "Material Design Icons", "Apache-2.0",
                "未引入：图标全部用代码绘制或字体渲染，不带 material-icons 资源",
                "https://fonts.google.com/icons",
                used = false
            ),
            Comp(
                "Kotlin", "Apache-2.0",
                "Kotlin 2.0.21 标准库（kotlin-stdlib）",
                "https://github.com/JetBrains/kotlin"
            ),
            Comp(
                "kotlinx.coroutines", "Apache-2.0",
                "翻译请求、模型加载等异步任务与取消",
                "https://github.com/Kotlin/kotlinx.coroutines"
            ),
            Comp(
                "sherpa-onnx", "Apache-2.0",
                "离线语音识别引擎：libsherpa-onnx-c-api / cxx-api / jni，含 Silero VAD 切句",
                "https://github.com/k2-fsa/sherpa-onnx"
            ),
            Comp(
                "ONNX Runtime", "MIT",
                "推理运行时，libonnxruntime.so，sherpa-onnx 依赖它加载模型",
                "https://github.com/microsoft/onnxruntime"
            ),
            Comp(
                "SenseVoiceSmall", "MIT",
                "识别模型 model.int8.onnx（239MB）不随 APK 分发，用户按需下载",
                "https://github.com/QwenAudio/SenseVoice"
            ),
            Comp(
                "rime-ice / 雾凇拼音", "GPL-3.0",
                "拼音方案与词库，打包于 assets/rime/；本项目整体 GPL-3.0 即源于此",
                "https://github.com/iDvel/rime-ice"
            )
        )

        root.addView(sectionTitle("本应用包含的组件"), marginTop(dp(22)))
        val box = card()
        comps.forEachIndexed { i, c ->
            if (i > 0) box.addView(divider())
            box.addView(compRow(c))
        }
        root.addView(box, marginTop(dp(10)))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ------------------------------------------------------------ 行

    /** 一条组件：左边勾/叉，中间名字 + 用法，右边许可证。整行可点，点了去仓库。 */
    private fun compRow(c: Comp): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            // 点击反馈：按下时整行淡灰，手指抬起再变白，让人知道这是能点的
            setOnClickListener { openLicense(c.url) }
        }

        row.addView(TextView(this).apply {
            text = if (c.used) "✓" else "—"
            textSize = 15f
            setTextColor(if (c.used) 0xFF2F7D5A.toInt() else 0xFFC4C8CE.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(10), 0)
        }, LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT))

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(this).apply {
            text = c.name
            textSize = 14f
            setTextColor(if (c.used) 0xFF111318.toInt() else 0xFFB0B4BB.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        mid.addView(TextView(this).apply {
            text = c.usage
            textSize = 11f
            setTextColor(if (c.used) 0xFF8A8F98.toInt() else 0xFFC4C8CE.toInt())
            setLineSpacing(dp(2).toFloat(), 1.25f)
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = c.license
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(if (c.used) licenseFg(c.license) else 0xFFB0B4BB.toInt())
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (c.used) licenseBg(c.license) else 0xFFF2F3F5.toInt())
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.leftMargin = dp(8) })

        // 尾巴上的 ›：告诉用户这一行能点进浏览器
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFFC8CCD2.toInt())
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.leftMargin = dp(6) })

        return row
    }

    // ------------------------------------------------------------ 打开链接

    /**
     * 用浏览器打开上游仓库。
     *
     * 为什么跳浏览器而不是在应用内嵌一个 WebView：许可证页看的是仓库主页和
     * 其中的 LICENSE 文件，用户可能还想顺便看看 README、issue、其他文件，
     * 浏览器体验更好；也省掉一个 WebView 的坑。
     */
    private fun openLicense(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 没有浏览器（少见，但精简 ROM 上会有）
            Toast.makeText(this, "没有可用的浏览器，地址：$url", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------ 基础件

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
        setPadding(dp(16), dp(2), dp(16), dp(2))
    }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(0xFF111318.toInt())
        setTypeface(typeface, Typeface.BOLD)
    }

    /** 宽松的绿、Apache 的蓝、弱互惠的褐、强互惠的橙、未声明的红 */
    private fun licenseFg(license: String): Int = when {
        license.startsWith("MIT") || license.startsWith("BSD") || license.startsWith("BSL") ->
            0xFF1F6B4A.toInt()
        license.startsWith("Apache") -> 0xFF1F4E6B.toInt()
        license.startsWith("LGPL") -> 0xFF5B4A1F.toInt()
        license.startsWith("GPL") -> 0xFF8A3B12.toInt()
        else -> 0xFF8A1F1F.toInt()
    }

    private fun licenseBg(license: String): Int = when {
        license.startsWith("MIT") || license.startsWith("BSD") || license.startsWith("BSL") ->
            0xFFE6F4EC.toInt()
        license.startsWith("Apache") -> 0xFFE7F0F6.toInt()
        license.startsWith("LGPL") -> 0xFFF7F1DF.toInt()
        license.startsWith("GPL") -> 0xFFFBEBE2.toInt()
        else -> 0xFFFBE6E6.toInt()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = v }
}