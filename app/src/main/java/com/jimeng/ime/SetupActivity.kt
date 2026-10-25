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
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.SkinManager
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.rime.RimeEngine
import com.jimeng.ime.translate.TranslateActivity
import com.jimeng.ime.view.KeyboardPreviewView

/**
 * 设置/引导页。
 *
 * 顶上一个「输入法」底座：启用、选择、试打三样挨在一起，
 * 下面依次是主题与定制（皮肤 / 按键外观设置）、插件，
 * 再往下是「键盘设置」（按键行为、输入反馈、剪贴板）和「翻译服务」两个门，
 * 点进去才各自展开细节。
 *
 * 引擎的部署进度和自检结果不在这一页显示了——部署进度键盘上有，
 * 自检失败写 logcat，看完排查就够。
 */
class SetupActivity : Activity() {

    companion object {
        /** 「导入皮肤」用 SAF 挑 .jmskin 时的 requestCode */
        const val REQ_PICK_SKIN = 1002
    }

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
            hint = "点击此处开始测试输入法"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(14), dp(14), dp(14))
            // 底座本身是白的，输入框得用淡灰底才看得出边界。
            // 用 GradientDrawable 而不是 setBackgroundColor：后者是直角，
            // 跟底座 12dp 的圆角放一起显得生硬；这里圆角取 10dp，
            // 比底座小一点，视觉上才是「嵌在里面」而不是跟底座平行。
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFF4F6F9.toInt())
            }
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

        // 入口：键盘尺寸、按键外观、空格键上写什么字都收在那一页。
        // 本页原来还有一个同名的「按键外观」分区指向同一个 Activity，纯属重复，
        // 已经删掉——一页里两个门通向同一间屋子，只会让人以为里面不一样。
        // 保留下来的这一行改叫「按键外观设置」，因为它管的就是「键长什么样」。
        val kbEntryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@SetupActivity, KeyboardActivity::class.java))
            }
        }
        kbEntryRow.addView(
            TextView(this).apply {
                text = "按键外观设置"
                textSize = 15f
                setTextColor(0xFF111318.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        kbEntryRow.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(0xFFC4C8CE.toInt())
        })
        root.addView(kbEntryRow, marginTop(dp(10)))

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

        // ---- 键盘设置：入口行 ----
        // 按键行为（长按延迟 / 预览 / 语言键 / 震动 / 音效）和剪贴板记录上限
        // 原来都摊在这一页，加起来十几行，把主页拉得很长。
        // 现在收进独立的 SettingsActivity，这里只留一个门，放在最下面——
        // 它是「调细节」的入口，不该顶在「启用 / 选择输入法」这类第一步操作前面。
        root.addView(sectionTitle("键盘设置"), marginTop(dp(26)))
        val settingsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        settingsBox.addView(navRow("键盘设置") {
            startActivity(Intent(this@SetupActivity, SettingsActivity::class.java))
        })
        root.addView(
            settingsBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )

        // ---- 翻译：入口行 ----
        // 翻译配置（内置通道 / 外接通道 / 目标语言）都收在独立的 TranslateActivity。
        // 这里只留一个门，和「键盘设置」并列摆在同一块「翻译」标题下。
        root.addView(sectionTitle("翻译"), marginTop(dp(26)))
        val translateBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        translateBox.addView(navRow("翻译服务") {
            startActivity(Intent(this@SetupActivity, TranslateActivity::class.java))
        })
        root.addView(
            translateBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )

        // ---- 关于：入口行 ----
        // 「关于」收进独立页（AboutActivity）：版本、许可证、源码、以及完整的
        // 「本应用包含哪些组件」清单都在那；许可正文再往下走一层 LicensesActivity。
        // 许可证要求随分发附带声明，普通用户装在手机上看不到仓库，所以放进 App。
        root.addView(sectionTitle("关于"), marginTop(dp(26)))
        val aboutBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }
        aboutBox.addView(navRow("关于霁梦") {
            startActivity(Intent(this@SetupActivity, AboutActivity::class.java))
        })
        root.addView(
            aboutBox,
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

    // 原来这里有个 refreshAppearance()，同步的是长按延迟 / 按键震动 / 语言键这些行。
    // 那些行已经搬进 SettingsActivity（键盘设置），本页不再有对应控件，
    // 留着只会同步一堆空 map，哪天有人真去调用还会以为是这里没生效——直接删掉。

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = v }
}