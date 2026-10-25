/*
 * 「隐私政策」页。
 *
 * ── 写这一页的原则 ──────────────────────────────────────────
 * 只写代码里真实发生的事，不写「我们可能收集……」这种为了免责而堆的废话。
 * 每一条都能在源码里对上：
 *   · 网络出口只有两处 —— StoreInstaller.kt:387（下插件）、
 *     TranslationService.kt:175（翻译）
 *   · 录音走 AudioRecord，识别完就丢，不落盘（voice/ 下没有任何写音频文件的代码）
 *   · 剪贴板历史进 SharedPreferences(MODE_PRIVATE)，仅本机
 *   · 没有 Firebase / 友盟 / Bugly / Sentry 之类任何统计或崩溃上报
 *
 * ── 有一说一的地方 ──────────────────────────────────────────
 * 「翻译」是本 App 唯一会把用户输入内容发出去的路径，而且发往
 * 用户在设置页自己填的服务器。这一点必须讲明白，不能含糊成
 * 「可能会与第三方共享」。所以单独列一节，并把「不发」的前提写清。
 *
 * librime 会在 filesDir/rime/log 下写日志，但实测内容全是引擎加载
 * 状态（dictionary.cc: loading dictionary 'xxx'），没有用户输入的词。
 * 这一点也如实写进去，而不是简单说一句「我们不记录输入」。
 */

package com.jimeng.ime

import android.app.Activity
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

class PrivacyActivity : Activity() {

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
            text = "隐私政策"
            textSize = 20f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(topBar)

        // ---- 开头 ----
        val head = card()
        head.addView(TextView(this).apply {
            text = "霁梦输入法不联网也能用。你打出的字留在你的手机里，" +
                "不上传、不统计、不分享。唯一会把文字发出去的功能是「翻译」，" +
                "而且只发给你自己配置的服务器。"
            textSize = 14f
            setTextColor(0xFF111318.toInt())
            setLineSpacing(dp(4).toFloat(), 1.45f)
        })
        root.addView(head, marginTop(dp(22)))

        // ---- 各节 ----
        section(
            root, "1  输入内容",
            "输入法的核心是键盘记录，但记录只发生在内存里：按键 → librime 组字 → " +
                "候选 → 上屏到当前输入框。上屏之后，输入法不再持有这段文字。\n\n" +
                "本应用没有「云输入」「云联想」「词库同步」这类功能，不存在把" +
                "你打的字发到服务器换取候选的行为。断网状态下功能完整。"
        )

        section(
            root, "2  本地存储",
            "以下内容写在应用私有目录（/data/data/com.jimeng.ime/），其他应用读不到，" +
                "卸载即删除：\n\n" +
                "· 用户词典与词频 —— 你选过的词，用来让候选排序更合你的习惯\n" +
                "· 剪贴板历史 —— 最近复制过的文本，默认保留 20 条，条数可在设置里改\n" +
                "· 各项设置 —— 皮肤、字号、按键行为、语言档位等\n\n" +
                "剪贴板历史只在设备上，条目数上限默认 20，可在设置里改（最小 1 条）。" +
                "不想留记录，随时在剪贴板页清空即可；它不参与任何同步。"
        )

        section(
            root, "3  联网",
            "应用只在两个地方发起网络请求，都必须在你的主动操作下才会发生：\n\n" +
                "① 下载扩展（插件商店）\n" +
                "下载语音模型、日语方案等资源。请求只包含资源地址本身，" +
                "不带你输入的任何内容。模型体积较大（语音模型约 239MB），" +
                "所以默认不打包进 APK，需要时才下载。\n\n" +
                "② 翻译\n" +
                "见下一节。\n\n" +
                "除此之外没有任何后台请求。本应用不含广告 SDK、统计 SDK、" +
                "崩溃上报 SDK，也没有推送服务。"
        )

        // 翻译单独一节，用醒目样式 —— 这是唯一会把内容发出去的路径
        root.addView(warnCard(
            "4  翻译（唯一会把文字发出去的功能）",
            "你在翻译栏里输入的、或选中要翻译的文字，会通过 HTTPS 发送到" +
                "「翻译服务」设置里填的那个地址。\n\n" +
                "· 默认不配置 = 不发任何东西。内置通道需要构建时写入地址与密钥，" +
                "开源版本里是空的；外接通道要你自己填地址、密钥、模型名。\n" +
                "· 发给谁由你决定。填自己的服务器，就只有你的服务器能看到这些文字；" +
                "填第三方服务，就按那家的隐私政策处理。\n" +
                "· 本应用不代理、不中转、不留存翻译请求的内容。请求发出后即释放，" +
                "日志里失败信息会先抹掉密钥片段再记录。\n\n" +
                "不想让文字离开设备，就不要用翻译功能，或把它指向自己的服务器。"
        ))

        section(
            root, "5  麦克风与语音输入",
            "语音输入需要 RECORD_AUDIO 权限。首次使用时会向你申请，" +
                "拒绝也不影响键盘打字。\n\n" +
                "录音只用于本地识别：音频经 sherpa-onnx 与 SenseVoice 模型在设备上" +
                "转成文字，识别结束即释放，不写入文件、不上传。整个语音链路离线运行，" +
                "断网也能用。"
        )

        section(
            root, "6  日志",
            "输入引擎（librime）会在应用私有目录写运行日志，" +
                "内容是引擎加载状态，例如「loading dictionary 'melt_eng'」、" +
                "「found switch option: ascii_mode」。\n" +
                "实测日志里不含你输入的字词。日志仅存本机，不随任何请求外发，" +
                "卸载应用即一并删除。"
        )

        section(
            root, "7  权限",
            "本应用只申请四项权限，没有多余的：\n\n" +
                "· VIBRATE —— 按键震动反馈\n" +
                "· RECORD_AUDIO —— 语音输入（可拒绝）\n" +
                "· INTERNET —— 下载扩展、翻译\n" +
                "· ACCESS_NETWORK_STATE —— 判断是否在网，避免无网时白等\n\n" +
                "没有读取通讯录、短信、位置、相册、设备标识符的权限。"
        )

        section(
            root, "8  儿童",
            "本应用不面向儿童设计，也不收集任何年龄、身份相关信息——" +
                "它根本没有账号体系，不需要注册登录。"
        )

        section(
            root, "9  你的控制权",
            "· 清空剪贴板历史：剪贴板页内直接清\n" +
                "· 移除已下载的模型：「插件 → 模型管理」里逐个移除\n" +
                "· 彻底清除全部数据（含用户词典、全部设置）：系统设置里清除应用数据，" +
                "或卸载应用\n" +
                "· 关闭联网能力：不配置翻译服务、不下载扩展，应用即为纯离线工具"
        )

        section(
            root, "10  政策变更",
            "这一页随应用版本一起发布。若数据处理方式有变化（例如新增联网功能），" +
                "会在新版本里更新这一页，并在更新说明中注明。\n\n" +
                "本政策对应当前版本 " + versionName() + "。"
        )

        // ---- 结尾 ----
        val tail = card()
        tail.addView(TextView(this).apply {
            text = "本应用以 GPL-3.0 开源，上面每一条都可以在源码里核对：" +
                "github.com/selenaflora927-dot/jimeng-ime"
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setLineSpacing(dp(3).toFloat(), 1.4f)
        })
        root.addView(tail, marginTop(dp(26)))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ------------------------------------------------------------ 段落

    /** 一节：小标题 + 正文，中间隔开。 */
    private fun section(parent: LinearLayout, title: String, body: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }, marginTop(dp(26)))

        parent.addView(TextView(this).apply {
            text = body
            textSize = 13f
            setTextColor(0xFF4A4F57.toInt())
            setLineSpacing(dp(5).toFloat(), 1.5f)
            setPadding(0, dp(8), 0, 0)
        })
    }

    /** 需要特别看清的一节：浅黄底 + 左边一条竖线。 */
    private fun warnCard(title: String, body: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFFFDF9EC.toInt())
                setStroke(dp(1), 0xFFEFE0B8.toInt())
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(0xFF6B4E12.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = body
            textSize = 12f
            setTextColor(0xFF6B5A38.toInt())
            setLineSpacing(dp(4).toFloat(), 1.45f)
            setPadding(0, dp(8), 0, 0)
        })
        return box
    }

    // ------------------------------------------------------------ 基础件

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
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