package com.jimeng.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.plugin.PluginStore
import com.jimeng.ime.rime.JapanesePlugin
import com.jimeng.ime.rime.RimeEngine

/**
 * 插件管理。
 *
 * 和扩展商店的分工：
 *   · 商店 = 货架，只负责「有什么、去下、不要了就删」
 *   · 这里 = 开关台，负责「下的东西开不开、开了哪个」
 *
 * 所以商店里点日语只有「下载 / 移除」，没有启用；
 * 启用 / 停用一律在这页做。让「已经装了什么」和「现在开着什么」
 * 分成两件事，用户不会在货架上误开一堆东西。
 *
 * 列的是 [PluginStore.installed]：商店下到本地的 + 用户自己导入的，
 * 两种都在这儿，用户不用记「我那个是自己导的还是下的」。
 */
class PluginManagerActivity : Activity() {

    private companion object {
        const val TAG = "JimengPlugin"
    }

    /** 列表容器 */
    private var listBox: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager.init(this)
        AppPrefs.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
        }
        root.addView(buildTopBar())
        root.setPadding(0, statusBarHeight(), 0, 0)

        val scroller = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        listBox = list
        scroller.addView(
            list,
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
        render()
    }

    override fun onResume() {
        super.onResume()
        // 从商店下完 / 导完回来，这里要立刻能看到新插件
        render()
    }

    // ------------------------------------------------------------ 顶栏

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(12), dp(6))
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
                text = "插件管理"
                textSize = 18f
                setTextColor(0xFF111318.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(
            TextView(this).apply {
                text = "去商店"
                textSize = 14f
                setTextColor(0xFF3B6FE0.toInt())
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@PluginManagerActivity, StoreActivity::class.java))
                }
            }
        )
        return bar
    }

    // ------------------------------------------------------------ 列表

    private fun render() {
        val box = listBox ?: return
        box.removeAllViews()

        // 商店下到本地的 + 用户自己导入的，都在这一份里
        val installed = try {
            PluginStore.installed(this)
        } catch (t: Throwable) {
            Log.w(TAG, "列已装插件失败", t)
            emptyList()
        }

        if (installed.isEmpty()) {
            box.addView(
                TextView(this).apply {
                    text = "还没有装任何插件"
                    textSize = 14f
                    setTextColor(0xFF8A8F98.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, dp(56), 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            box.addView(
                TextView(this).apply {
                    text = "去扩展商店下载一个，或者点右上角「导入」带上自己的包"
                    textSize = 13f
                    setTextColor(0xFFB0B4BB.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            // 空的时候也给个能点的按钮，别让用户自己找路
            box.addView(
                TextView(this).apply {
                    text = "去扩展商店"
                    textSize = 14f
                    setTextColor(0xFF3B6FE0.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(0xFFEAF0FE.toInt())
                    }
                    isClickable = true
                    setOnClickListener {
                        startActivity(
                            Intent(this@PluginManagerActivity, StoreActivity::class.java)
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(20)
                }
            )
            return
        }

        // 小标题：一共占了多少、开了几个
        val totalBytes = installed.sumOf { it.bytes }
        val activeCount = installed.count { isActive(it) }
        box.addView(
            TextView(this).apply {
                text = "已装 ${installed.size} 个" +
                    (if (activeCount > 0) " · 启用中 $activeCount 个" else "") +
                    " · 共 ${StoreInstaller.fmtSize(totalBytes)}"
                textSize = 13f
                setTextColor(0xFF8A8F98.toInt())
                setPadding(dp(4), dp(16), 0, dp(6))
            }
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        installed.forEachIndexed { i, entry ->
            if (i > 0) card.addView(hairLine())
            card.addView(buildRow(entry))
        }
        box.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** 这行的插件现在是不是开着。分派交给 PluginStore。 */
    private fun isActive(entry: PluginStore.Entry): Boolean = try {
        PluginStore.isActive(this, entry)
    } catch (t: Throwable) {
        Log.w(TAG, "读开关状态失败：${entry.id}", t)
        false
    }

    /** 一行：名字 + 说明 + 状态，右边「启用 / 停用」 */
    private fun buildRow(entry: PluginStore.Entry): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = entry.name
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = entry.description.ifEmpty { "插件" }
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), 0, 0)
        })

        val active = isActive(entry)
        val version = entry.item.version.takeIf { it.isNotEmpty() }
        texts.addView(TextView(this).apply {
            text = buildString {
                append(StoreInstaller.fmtSize(entry.bytes))
                if (version != null) append(" · v$version")
                if (entry.local) append(" · 本地导入")
                append(" · ")
                append(if (active) "已启用" else "未启用")
            }
            textSize = 12f
            setTextColor(if (active) 0xFF3B6FE0.toInt() else 0xFF8A8F98.toInt())
            setPadding(0, dp(4), 0, 0)
        })

        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val button = TextView(this).apply {
            textSize = 13f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
        }
        row.addView(button)

        if (active) {
            button.text = "停用"
            button.setTextColor(0xFF8A8F98.toInt())
            button.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFF0F1F4.toInt())
            }
            button.setOnClickListener { confirmDisable(entry) }
        } else {
            button.text = "启用"
            button.setTextColor(0xFF3B6FE0.toInt())
            button.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFEAF0FE.toInt())
            }
            button.setOnClickListener { confirmEnable(entry) }
        }
        return row
    }

    // ------------------------------------------------------------ 开关

    private fun confirmEnable(entry: PluginStore.Entry) {
        // 日语这套要说清楚「会多一档语言」「第一次要编译词库」，
        // 其他插件就说它自己干什么。
        val message = if (entry.id == JapanesePlugin.ID) {
            "会把已经下载好的日语方案挂进键盘的数据目录。用的是硬链接，" +
                "不会再多占一份空间。\n\n" +
                "它和现在的方案并存：小地球键会从「雾凇 → English」" +
                "变成「雾凇 → English → 日本語」，雾凇照旧能用。\n\n" +
                "第一次要编译 140 万条词库，会等十几秒到几十秒。"
        } else {
            val caps = entry.manifest?.permissions.orEmpty()
            buildString {
                append("打开「${entry.name}」之后它会开始工作。")
                if (caps.isNotEmpty()) {
                    append("\n\n它会做的事：\n")
                    caps.forEach { append("· ").append(it).append('\n') }
                }
                append("\n随时可以回来停用，效果立刻消失。")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("启用「${entry.name}」？")
            .setMessage(message)
            .setPositiveButton("启用") { _, _ -> enable(entry) }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun enable(entry: PluginStore.Entry) {
        val ok = try {
            PluginStore.enable(this, entry)
        } catch (t: Throwable) {
            Log.w(TAG, "启用失败：${entry.id}", t)
            false
        }
        if (!ok) {
            AlertDialog.Builder(this)
                .setTitle("启用失败")
                .setMessage(
                    if (entry.id == JapanesePlugin.ID)
                        "文件挂不上，可能是存储空间不够或下载的文件不完整。\n\n" +
                            "可以回扩展商店点「移除」，再重新下载一次。"
                    else
                        "这个插件没能启动。包可能是给更新版本的霁梦做的，" +
                            "或者文件不完整。\n\n可以移除后重新导入一次。"
                )
                .setPositiveButton("知道了", null)
                .show()
            render()
            return
        }

        // 日语要额外挂语言档位、重载引擎；其他插件适配器自己生效，不用动 rime
        if (entry.id == JapanesePlugin.ID) {
            com.jimeng.ime.kb.core.LanguageData.setExtra(JapanesePlugin.extraLang(this))
            render()
            val started = RimeEngine.reload()
            AlertDialog.Builder(this)
                .setTitle("「${entry.name}」已启用")
                .setMessage(
                    if (started) {
                        "键盘正在编译日语词库，第一次要等一会儿。\n\n" +
                            "编译完按小地球键就能切到「日本語」，打 romaji 出假名。"
                    } else {
                        "上一次部署还没结束，等它跑完再启用会更稳。"
                    }
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        render()
        AlertDialog.Builder(this)
            .setTitle("「${entry.name}」已启用")
            .setMessage("已经生效，不用重启键盘。想关掉回来点「停用」就行。")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun confirmDisable(entry: PluginStore.Entry) {
        val message = if (entry.id == JapanesePlugin.ID) {
            "会从键盘目录里摘掉日语方案，小地球键回到原来那几档。\n\n" +
                "下载好的文件还留着，以后想用点「启用」马上就好，不用重新下。"
        } else {
            "会关掉「${entry.name}」。\n\n" +
                "装好的文件还留着，以后想用点「启用」马上就好，不用重装。"
        }
        AlertDialog.Builder(this)
            .setTitle("停用「${entry.name}」？")
            .setMessage(message)
            .setPositiveButton("停用") { _, _ -> disable(entry) }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun disable(entry: PluginStore.Entry) {
        try {
            PluginStore.disable(this, entry)
        } catch (t: Throwable) {
            Log.w(TAG, "停用失败：${entry.id}", t)
        }

        if (entry.id == JapanesePlugin.ID) {
            // 先摘档位：万一键盘此刻正停在 japanese 上，重载后 LanguageData.of()
            // 找不到这个 id 会自动兜回第一档，不会切到一个已经摘掉的方案。
            com.jimeng.ime.kb.core.LanguageData.setExtra(null)
            render()
            val started = RimeEngine.reload()
            AlertDialog.Builder(this)
                .setTitle("「${entry.name}」已停用")
                .setMessage(
                    if (started) "键盘正在重新部署，稍等一下就好。"
                    else "上一次部署还没结束，等它跑完再切会更稳。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        render()
        AlertDialog.Builder(this)
            .setTitle("「${entry.name}」已停用")
            .setMessage("效果已经收回去了，装好的文件还留着。")
            .setPositiveButton("知道了", null)
            .show()
    }

    // ------------------------------------------------------------ 小工具

    private fun hairLine() = View(this).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}