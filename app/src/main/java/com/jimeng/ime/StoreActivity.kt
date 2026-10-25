package com.jimeng.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.plugin.PluginImporter
import com.jimeng.ime.rime.JapanesePlugin
import com.jimeng.ime.rime.RimeEngine
import com.jimeng.ime.rime.SchemeManager
import com.jimeng.ime.voice.VoiceAsrEngine
import java.io.File

/**
 * 扩展商店。
 *
 * 三件事：
 *   1. 顶上分「方案 / 插件 / 模型」三类，切一下就换下面的列表；
 *   2. 每一项左上角写清楚状态——**没下载 / 下载中 / 已安装**，
 *      没下载的点「下载」才去网络拉，不会偷偷占用户的空间；
 *   3. 右上角「导入」让用户自己带的包进来。
 *
 * 数据源是 APK 里的 `assets/store/index.json`（见 [StoreData]），
 * 文件本体不随 APK 走，全都靠 [StoreInstaller] 现下。
 */
class StoreActivity : Activity() {

    /** 商店的三个分类，顺序就是导航上的顺序 */
    /**
     * 商店的三个分类。
     *
     * [kind] 是清单里 `kind` 字段的值（scheme / plugin / model），
     * 用来从 index.json 里筛这一页该显示哪些条目——显示名 [label] 只给导航用。
     */
    private enum class Tab(val label: String, val kind: String) {
        Scheme("方案", "scheme"),
        Plugin("插件", "plugin"),
        Model("模型", "model"),
    }

    private var current = Tab.Scheme

    /** 列表容器，切分类时整块换掉 */
    private var listBox: LinearLayout? = null
    /** 三个导航标签，切分类时要改颜色 */
    private val tabViews = ArrayList<TextView>()
    /** 三个标签下面那根 2dp 指示条，和 [tabViews] 同序 */
    private val indicators = ArrayList<View>()

    /**
     * 每一行上那几个要随时改的 View，按 item id 存。
     *
     * 下载进度回调来的时候按 id 找，找不到（行已经被重画掉）就当没事发生 ——
     * 重画后 [bindRow] 会从 [StoreDownloads.task] 把当前进度读回来。
     */
    private class RowViews(
        val status: TextView,
        val progress: ProgressBar,
        val percent: TextView,
        val button: TextView,
        /** 方案行专用：主按钮是「启用/使用中」，移除另挂一个小的，平时不占位 */
        val more: TextView,
    )

    private val rows = HashMap<String, RowViews>()

    private val main = Handler(Looper.getMainLooper())

    /** 本地导入：选文件 → 预检 → 确认 → 安装，都交给他 */
    private var importer: PluginImporter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 和皮肤页一样：这个页面可能比输入法服务先打开，自己把 prefs 初始化起来。
        // SchemeManager 也要 init：下面读「现在用的是哪套」全靠它，
        // 没初始化会一直读到 null，界面上就永远显示「内置使用中」。
        ThemeManager.init(this)
        AppPrefs.init(this)
        SchemeManager.init(this)

        // 这个页面可能是冷启动的第一屏（比如从桌面点进商店），
        // 此时 RimeEngine 还没 bind 过，reload() 会因为没 appContext 直接返回 false。
        // bind 是幂等的，重复调用只是把 appContext 再赋一次，没有副作用。
        com.jimeng.ime.rime.RimeEngine.bind(this)

        // 下架的方案（比如已经和内置合并的雾凇）不再出现在货架上，
        // 顺手把设备上残留的目录收掉，别让几十 MB 一直躺着。
        sweepOrphanSchemes()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        // ---- 顶栏：返回 + 标题 + 右上角导入 ----
        root.addView(buildTopBar())
        root.setPadding(0, statusBarHeight(), 0, 0)

        // ---- 分类导航 ----
        root.addView(buildTabs())

        // ---- 列表 ----
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
        renderList()
    }

    override fun onStart() {
        super.onStart()
        // 下载在进程里跑，页面只是观察者：回来就挂上，走就摘掉。
        // 这样切走再回来，进度不会丢、也不会重复下载。
        StoreDownloads.addListener(downloadListener)
        StoreDownloads.addListener(installListener)
        renderList()
    }

    override fun onStop() {
        StoreDownloads.removeListener(downloadListener)
        StoreDownloads.removeListener(installListener)
        super.onStop()
    }

    override fun onDestroy() {
        // 行视图全没了，引用一起断掉，别让回调往已销毁的 View 上写
        rows.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 下载回调

    /** 进度：按 id 找到那一行就地改，不整块重画（重画会把滚动位置抖掉）。 */
    private val downloadListener = object : StoreDownloads.Listener {
        override fun onProgress(itemId: String) {
            val task = StoreDownloads.task(itemId) ?: return
            val row = rows[itemId] ?: return
            paintRunning(itemId, row, task)
        }

        override fun onFinished(itemId: String, success: Boolean, reason: String?) {
            if (!success && reason != null && reason != "已取消") {
                android.widget.Toast.makeText(
                    this@StoreActivity, reason, android.widget.Toast.LENGTH_LONG
                ).show()
            }
            renderList()
        }
    }

    /** 装完了：让键盘那边重新试着加载模型。 */
    private val installListener = object : StoreDownloads.Listener {
        override fun onFinished(itemId: String, success: Boolean, reason: String?) {
            if (success) {
                VoiceAsrEngine.release()
                if (VoiceAsrEngine.hasModel(this@StoreActivity)) {
                    VoiceAsrEngine.preload(this@StoreActivity)
                }
            }
        }
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
                text = "扩展商店"
                textSize = 18f
                setTextColor(0xFF111318.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        // 右上角：本地导入。用户自己带的包从这里进，和商店里的东西走同一条安装链路。
        bar.addView(
            TextView(this).apply {
                text = "导入"
                textSize = 14f
                setTextColor(0xFF3B6FE0.toInt())
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                setOnClickListener { importLocal() }
            }
        )
        return bar
    }

    // ------------------------------------------------------------ 分类导航

    private fun buildTabs(): LinearLayout {
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
        }
        for (tab in Tab.entries) {
            // 每个标签竖着排：上面文字，下面一条 2dp 指示条。
            // 用真 View 当指示条，比自定义 Drawable 简单，也不用管绘制回调。
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setOnClickListener {
                    if (current == tab) return@setOnClickListener
                    current = tab
                    paintTabs()
                    renderList()
                }
            }
            val label = TextView(this).apply {
                text = tab.label
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(10))
            }
            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(2)
                )
            }
            column.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            column.addView(indicator)

            tabViews.add(label)
            indicators.add(indicator)
            tabs.addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        paintTabs()
        return tabs
    }

    /** 选中的那个：字深一点、加粗、下面的条是蓝的；没选中的字浅灰、条透明 */
    private fun paintTabs() {
        Tab.entries.forEachIndexed { i, tab ->
            val label = tabViews.getOrNull(i) ?: return@forEachIndexed
            val bar = indicators.getOrNull(i) ?: return@forEachIndexed
            val active = tab == current
            label.setTextColor(if (active) 0xFF111318.toInt() else 0xFF8A8F98.toInt())
            label.setTypeface(
                label.typeface,
                if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            bar.setBackgroundColor(if (active) 0xFF3B6FE0.toInt() else Color.TRANSPARENT)
        }
    }

    // ------------------------------------------------------------ 列表

    /** 按当前分类把列表重画一遍 */
    private fun renderList() {
        val box = listBox ?: return
        box.removeAllViews()

        val items = StoreData.items(this, current.name.lowercase())
        // 「没内容」这个提示只给**真的没东西**的页用。
        // 方案页有内置雾凇卡片、插件页有内置日本語卡片——这两页的固定条目
        // 不来自 index.json，index.json 里没条目 ≠ 页面上没东西。
        // 早先这里只放过了方案页，于是插件页一进来就「暂时没有内容」直接返回，
        // 下面那张日语卡片根本没机会被 addView（用户看到的就是这个）。
        if (items.isEmpty() && current == Tab.Model) {
            box.addView(
                TextView(this).apply {
                    text = "暂时没有内容"
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
            return
        }

        // 方案页顶上单独列一项「内置」：商店里下了别套之后，
        // 用户总得有条路能切回来。它不是一个可下载项目，所以单独画。
        if (current == Tab.Scheme) {
            box.addView(
                TextView(this).apply {
                    text = "默认启用"
                    textSize = 13f
                    setTextColor(0xFF8A8F98.toInt())
                    setPadding(dp(4), dp(16), 0, dp(6))
                }
            )
            box.addView(buildBuiltinCard())
        }

        // 按分类聚合：分类名做小标题，下面接这一类的条目。
        // 插件页的日本語也从这里出——它在 index.json 里有 kind=plugin 的条目，
        // 和别的商品一样「没下载 → 下载 → 启用」，不再跟着 APK 走。
        items.groupBy { it.category }.forEach { (category, group) ->
            box.addView(
                TextView(this).apply {
                    text = category
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
            group.forEachIndexed { i, item ->
                if (i > 0) card.addView(hairLine())
                card.addView(buildItemRow(item))
            }
            box.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // 本地导入的插件不在 index.json 里，得单列一块，
        // 否则用户导进来之后在商店既看不见、也删不掉。
        if (current == Tab.Plugin) renderLocalPlugins(box)
    }

    /** 商店插件页里「本地导入」那一块 */
    private fun renderLocalPlugins(box: LinearLayout) {
        val imported = try {
            com.jimeng.ime.plugin.PluginStore.installed(this).filter { it.local }
        } catch (t: Throwable) {
            Log.w(TAG, "列本地插件失败", t)
            emptyList()
        }
        if (imported.isEmpty()) return

        box.addView(
            TextView(this).apply {
                text = "本地导入"
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
        imported.forEachIndexed { i, entry ->
            if (i > 0) card.addView(hairLine())
            card.addView(buildLocalRow(entry))
        }
        box.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        box.addView(
            TextView(this).apply {
                text = "开关在插件管理页。这里是「收进来」和「不要了就删」。"
                textSize = 12f
                setTextColor(0xFFB0B4BB.toInt())
                setPadding(dp(4), dp(10), dp(4), 0)
            }
        )
    }

    /** 本地导入的一行：和商店条目长得一样，但按钮只有「移除」 */
    private fun buildLocalRow(
        entry: com.jimeng.ime.plugin.PluginStore.Entry
    ): LinearLayout {
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
            text = entry.description.ifEmpty { "本地导入的插件" }
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        val active = try {
            com.jimeng.ime.plugin.PluginStore.isActive(this, entry)
        } catch (t: Throwable) {
            false
        }
        val version = entry.item.version.takeIf { it.isNotEmpty() }
        texts.addView(TextView(this).apply {
            text = buildString {
                append(StoreInstaller.fmtSize(entry.bytes))
                if (version != null) append(" · v$version")
                append(
                    if (active) " · 启用中，去插件管理停用"
                    else " · 已导入，去插件管理启用"
                )
            }
            textSize = 12f
            setTextColor(if (active) 0xFF3B6FE0.toInt() else 0xFF8A8F98.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(TextView(this).apply {
            text = "移除"
            textSize = 13f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFF0F1F4.toInt())
            }
            isClickable = true
            setOnClickListener { confirmRemoveLocal(entry) }
        })
        return row
    }

    /** 删一个本地导入的插件。开着的话先停用，再把目录删掉。 */
    private fun confirmRemoveLocal(entry: com.jimeng.ime.plugin.PluginStore.Entry) {
        AlertDialog.Builder(this)
            .setTitle("移除「${entry.name}」？")
            .setMessage(
                "会删掉这个插件占的 ${StoreInstaller.fmtSize(entry.bytes)} 文件，" +
                    "插件管理里也不会再出现。\n\n" +
                    "想再用得把包重新导入一次。"
            )
            .setPositiveButton("移除") { _, _ ->
                // 顺序不能反：先删文件再停用，适配器会去读已经不存在的目录
                try {
                    com.jimeng.ime.plugin.PluginStore.beforeRemove(this, entry)
                } catch (t: Throwable) {
                    Log.w(TAG, "停用导入插件失败：${entry.id}", t)
                }
                val ok = try {
                    com.jimeng.ime.plugin.PluginStore.remove(this, entry)
                } catch (t: Throwable) {
                    Log.w(TAG, "删导入插件失败：${entry.id}", t)
                    false
                }
                android.widget.Toast.makeText(
                    this,
                    if (ok) "已移除「${entry.name}」" else "没删干净，稍后再试",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                renderList()
            }
            .setNegativeButton("算了", null)
            .show()
    }

    /**
     * 「默认启用」那张卡。
     *
     * 就是内置的雾凇，只是它没有对应的下载项目（文件在 assets 里）。
     * 只做一件事：从商店方案切回内置。没启用商店方案时按钮是灰的「使用中」。
     */
    private fun buildBuiltinCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = "雾凇拼音（内置）"
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = "开箱即用的简体中文 Rime 配置，词库长期维护，全拼 + 双拼 + 九键，完全离线"
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        val inUse = SchemeManager.active() == null
        texts.addView(TextView(this).apply {
            text = if (inUse) "使用中" else "可用"
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(TextView(this).apply {
            textSize = 13f
            if (inUse) {
                text = "使用中"
                setTextColor(0xFF8A8F98.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xFFF0F1F4.toInt())
                }
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = false
            } else {
                text = "切回"
                setTextColor(0xFF3B6FE0.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xFFEAF0FE.toInt())
                }
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                setOnClickListener { confirmUseBuiltin() }
            }
        })

        card.addView(row)
        return card
    }

    /** 切回内置方案。和启用商店方案一样，只重载引擎，不重启 app。 */
    private fun confirmUseBuiltin() {
        AlertDialog.Builder(this)
            .setTitle("切回内置雾凇拼音？")
            .setMessage(
                "商店里那套方案的文件还会留着。\n\n" +
                    "想再切回去，点它那行的「启用」就行。\n\n" +
                    "切回后键盘会重新部署一次内置词库。"
            )
            .setPositiveButton("切回") { _, _ ->
                SchemeManager.deactivate(this)
                com.jimeng.ime.kb.core.LanguageData.applyScheme(null, null, null, null)
                renderList()
                val started = com.jimeng.ime.rime.RimeEngine.reload()
                AlertDialog.Builder(this)
                    .setTitle("已切回内置方案")
                    .setMessage(
                        if (started) "键盘正在重新部署内置那套词库，稍等一下就好。"
                        else "上一次部署还没结束，等它跑完再切会更稳。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            }
            .setNegativeButton("算了", null)
            .show()
    }

    /** 一行商品：左边名字 + 说明 + 状态，右边「下载 / 安装 / 使用」按钮 */
    private fun buildItemRow(item: StoreData.Item): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = item.name
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = item.description
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        // 第三行：体积 + 状态。状态文字单独留个引用，下载时实时改
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(4), 0, 0)
            text = statusText(item)
        }
        texts.addView(status)

        // 下载中的进度条：平时 GONE，开始下载才出现
        val progress = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            progress = 0
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF3B6FE0.toInt())
        }
        texts.addView(
            progress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { topMargin = dp(8) }
        )
        // 百分比文字放在进度条下面一行
        val percentView = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF3B6FE0.toInt())
            visibility = View.GONE
        }
        texts.addView(percentView)

        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val button = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF3B6FE0.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFEAF0FE.toInt())
            }
            isClickable = true
        }
        row.addView(button)

        // 方案行的第二个按钮（移除）。非方案行恒隐藏，不占位、不影响原来的一列布局。
        val more = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(dp(12), dp(6), dp(10), dp(6))
            isClickable = true
            visibility = View.GONE
        }
        row.addView(more)

        // 进度条 / 百分比现在才拿得到引用（上面是先建后 add 的）
        bindRow(item, status, progress, percentView, button, more)
        return row
    }

    /**
     * 把一行的按钮和状态接到实际动作上。
     *
     * 三种情况：
     *   · 已安装 → 按钮「移除」（模型这类还能顺手跳去模型管理看详情）
     *   · 下载中 → 按钮「取消」，进度条在动
     *   · 没装   → 按钮「下载」
     */
    private fun bindRow(
        item: StoreData.Item,
        status: TextView,
        progress: ProgressBar,
        percent: TextView?,
        button: TextView,
        more: TextView,
    ) {
        // 这一行的控件先登记进去：进度回调按 id 找它们就地改
        rows[item.id] = RowViews(status, progress, percent ?: TextView(this), button, more)
        // 默认收起副按钮，下面只有方案行会把它翻出来
        more.visibility = View.GONE

        val task = StoreDownloads.task(item.id)
        if (task != null) {
            // 下载在进程里还在跑（可能刚从别的页面切回来）：把进度接回来
            status.text = "${StoreInstaller.fmtSize(item.sizeBytes)} · 下载中…"
            progress.visibility = View.VISIBLE
            percent?.visibility = View.VISIBLE
            applyProgress(item, task)
            button.text = "取消"
            button.setTextColor(0xFF8A8F98.toInt())
            button.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFF0F1F4.toInt())
            }
            button.setOnClickListener { cancelDownload(item) }
            return
        }
        progress.visibility = View.GONE
        percent?.visibility = View.GONE

        val st = StoreInstaller.state(this, item)
        // 方案装好了才有「启用 / 使用中」这套说法，没装时就当普通项目办
        if (item.kind == "scheme" && st == StoreInstaller.State.Installed) {
            bindSchemeRow(item, status, button, more)
            return
        }
        when (st) {
            StoreInstaller.State.Installed -> {
                button.text = "移除"
                button.setTextColor(0xFF8A8F98.toInt())
                button.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xFFF0F1F4.toInt())
                }
                button.setOnClickListener { confirmRemove(item) }
            }

            StoreInstaller.State.Incomplete -> {
                button.text = "继续下载"
                button.setTextColor(0xFF3B6FE0.toInt())
                button.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xFFEAF0FE.toInt())
                }
                button.setOnClickListener { startDownload(item) }
            }

            StoreInstaller.State.NotInstalled -> {
                button.text = "下载"
                button.setTextColor(0xFF3B6FE0.toInt())
                button.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xFFEAF0FE.toInt())
                }
                button.setOnClickListener { startDownload(item) }
            }
        }
    }

    /**
     * 方案行的按钮。
     *
     * 三态：
     *   · 当前正在用 → 按钮写「使用中」，灰的，点了弹一句说明（不做事）
     *   · 已装但没启用 → 按钮写「启用」
     *   · 其它已启用着另一套 → 也是「启用」，点了直接换来这套
     *
     * 不管从哪种点过去，都会提醒「重启键盘后生效」：librime 的部署在
     * 进程里只跑一次，不重启连词库都不会重新编。
     */
    private fun bindSchemeRow(
        item: StoreData.Item,
        status: TextView,
        button: TextView,
        more: TextView,
    ) {
        val activeId = SchemeManager.active()?.id
        val inUse = activeId == item.id

        status.text = "${StoreInstaller.fmtSize(item.occupyBytes)} · " +
            if (inUse) "使用中" else "已下载"

        if (inUse) {
            button.text = "使用中"
            button.setTextColor(0xFF8A8F98.toInt())
            button.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFF0F1F4.toInt())
            }
            button.setOnClickListener {
                android.widget.Toast.makeText(
                    this, "现在用的就是${item.name}", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            button.text = "启用"
            button.setTextColor(0xFF3B6FE0.toInt())
            button.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFEAF0FE.toInt())
            }
            button.setOnClickListener { confirmActivate(item) }
        }

        // 使用中的那套不给删（删了引擎下次启动就没方案可铺），先切走才能移除
        if (inUse) {
            more.visibility = View.GONE
        } else {
            more.visibility = View.VISIBLE
            more.text = "移除"
            more.setOnClickListener { confirmRemove(item) }
        }
    }

    /** 状态那一行文字：体积 · 已安装 / 未下载 */
    private fun statusText(item: StoreData.Item): String {
        val size = StoreInstaller.fmtSize(item.sizeBytes)
        val st = StoreInstaller.state(this, item)
        return when (st) {
            // 插件的开关不在这页：下完提示一句「去插件管理启用」，
            // 免得用户在这页找半天启用按钮
            StoreInstaller.State.Installed ->
                if (item.kind == "plugin") "$size · 已下载，去插件管理启用"
                else "$size · 已安装"
            StoreInstaller.State.Incomplete ->
                "$size · 没下完，点继续"
            StoreInstaller.State.NotInstalled ->
                "$size · 未下载"
        }
    }

    /**
     * 把任务进度画到那一行上。
     *
     * 从 [StoreDownloads.Task] 的 volatile 字段现读，不缓存副本——
     * 页面刚回来时读到的就是当前位置，不会先画个 0% 再跳。
     */
    private fun applyProgress(item: StoreData.Item, task: StoreDownloads.Task) {
        val row = rows[item.id] ?: return
        val done = task.done
        val total = task.total
        if (total <= 0) {
            // 两种没进度的时刻：刚换源还没拿到文件长度，或者正在解压 zip。
            // 后者 task.fileName 里是「正在解压」，直接把那句话显示出来。
            row.progress.progress = 0
            row.percent.text = task.note
                ?: task.fileName.takeIf { it.isNotEmpty() }
                ?: "准备中…"
            return
        }
        row.progress.progress = ((done * 1000) / total).toInt().coerceIn(0, 1000)
        row.percent.text = buildString {
            append(done * 100 / total).append("%  ")
            append(StoreInstaller.fmtSize(done)).append(" / ")
            append(StoreInstaller.fmtSize(total))
        }
    }

    /**
     * 下载线程报进度：只改这一行的几个控件，不整块重画。
     *
     * 重画（renderList）在下载中每次回调都会重建所有行，滚动位置会被抖掉，
     * 用户想边下边看别的条目都划不动。
     */
    private fun paintRunning(itemId: String, row: RowViews, task: StoreDownloads.Task) {
        val item = currentItems().firstOrNull { it.id == itemId } ?: return
        applyProgress(item, task)
    }

    /** 当前这一页的条目（下载回调里按 id 找回 Item 用）。 */
    private fun currentItems(): List<StoreData.Item> = try {
        StoreData.items(this, current.kind)
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * 清理「货架上已经没有、但设备上还占着地方」的方案目录。
     *
     * 雾凇从商店下架之后就是这种情况：文件还在 `files/store/rime-ice` 躺着，
     * 清单里没了就再也点不到「移除」，几十 MB 白占着。
     *
     * 只动方案类（`files/store` 下的目录），语音模型一律不碰——
     * 模型是几百 MB 的东西，误删了要重新下，风险不值当。
     * 正在使用中的那套也不动，否则等于把用户脚下的地抽了。
     */
    private fun sweepOrphanSchemes() {
        val listed = try {
            // 插件的目录也在 files/store 下（日本語就是 store/japanese），
            // 必须一起算进白名单，否则哪天插件包里带了 default.yaml 就会被当
            // 下架方案清掉——用户刚下完 12MB 就没了。
            val schemes = StoreData.items(this, "scheme").map { it.id }
            val plugins = StoreData.items(this, "plugin").map { it.id }
            (schemes + plugins).toSet()
        } catch (t: Throwable) {
            return
        }
        val activeId = SchemeManager.active()?.id
        val root = File(filesDir, "store")
        val dirs = root.listFiles() ?: return
        for (d in dirs) {
            if (!d.isDirectory || d.name.startsWith(".")) continue
            if (d.name in listed || d.name == activeId) continue
            // 只认「像方案目录」的：有 default.yaml 才算，避免误删别的东西。
            // 插件（日本語）不带 default.yaml，天然躲过这条；即使哪天带了，
            // 上面已经把插件 id 算进白名单了，也不会被清。
            if (!File(d, "default.yaml").isFile) continue
            Thread {
                val size = try {
                    d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } catch (t: Throwable) {
                    0L
                }
                if (d.deleteRecursively()) {
                    Log.i(TAG, "清理下架方案 ${d.name}，回收 ${size / 1024 / 1024}MB")
                } else {
                    Log.w(TAG, "清理下架方案 ${d.name} 失败")
                }
            }.start()
        }
    }

    private fun hairLine() = View(this).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    // ------------------------------------------------------------ 下载

    /**
     * 开始下载。真正的下载归 [StoreDownloads] 管，这里只管发指令 + 重画。
     *
     * 为什么不在这里开线程：231MB 下起来要几分钟，用户一定会切走。
     * 线程挂在 Activity 上的话，页面一销毁就断了，回来再点就是两条线程
     * 往同一个临时文件里写——下出来的一定是坏的。
     */
    private fun startDownload(item: StoreData.Item) {
        // 已经在跑（比如连点两下）时 start 返回 false，重画一下同步界面就行
        StoreDownloads.start(this, item)
        renderList()
    }

    private fun cancelDownload(item: StoreData.Item) {
        StoreDownloads.cancel(item.id)
        // 界面立刻恢复成「下载」，临时文件的清理由下载线程收尾
        renderList()
        android.widget.Toast.makeText(
            this, "已取消下载", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // ------------------------------------------------------------ 启用方案

    /**
     * 启用一套商店方案。
     *
     * 只写标记，不搬文件：现在在跑的引擎还 mmap 着旧那套 shared，
     * 这时候删它/换它都不安全。真正的铺包交给 [RimeEngine.reload] 在后台做，
     * 它会先 finalize 旧引擎再重新铺包部署，商店页和 app 都不用退出。
     */
    private fun confirmActivate(item: StoreData.Item) {
        val current = SchemeManager.active()?.name ?: "内置雾凇拼音"
        val note = buildString {
            append("将从「")
            append(current)
            append("」换成「")
            append(item.name)
            append("」。\n\n")
            append("两套方案不能同时存在，换过去会重新部署一次词库，")
            append("第一次大约要几十秒。\n\n")
            append("启用后键盘会自己重新加载，不用重启 app。")
        }
        AlertDialog.Builder(this)
            .setTitle("启用「${item.name}」？")
            .setMessage(note)
            .setPositiveButton("启用") { _, _ -> activate(item) }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun activate(item: StoreData.Item) {
        if (item.schemaId.isEmpty()) {
            android.widget.Toast.makeText(
                this, "这套方案缺少方案信息，不能用", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        SchemeManager.activate(
            this, item.id, item.name, item.schemaId, item.englishSchemaId
        )
        // 档位表跟着换，小地球键切语言时不会指到已经不存在的旧方案
        com.jimeng.ime.kb.core.LanguageData.applyScheme(
            item.schemaId, item.name, item.englishSchemaId, "English"
        )
        renderList()
        // 引擎是进程级单例，但 librime 支持 finalize 后重新初始化：
        // RimeEngine.reload() 会先复位再重铺，不用把整个 app 杀掉。
        // 商店页留在原地，只有键盘会闪一下「正在部署…」。
        val started = com.jimeng.ime.rime.RimeEngine.reload()
        AlertDialog.Builder(this)
            .setTitle("已启用「${item.name}」")
            .setMessage(
                if (started) {
                    "键盘正在重新部署这套词库，第一次要几十秒，之后立刻就好。\n\n" +
                        "这段时间请不要关闭商店页，部署完成后会提示。"
                } else {
                    "上一次部署还没结束，等它跑完再启用会更稳。"
                }
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun confirmRemove(item: StoreData.Item) {
        val extra = if (item.unzip) "\n\n删掉之后就不能再启用它了。" else ""
        AlertDialog.Builder(this)
            .setTitle("移除「${item.name}」？")
            .setMessage(
                "会删掉已经占用的 ${StoreInstaller.fmtSize(item.occupyBytes)} 文件，" +
                    "下次要用得重新下。" + extra
            )
            .setPositiveButton("移除") { _, _ ->
                // 正在用的东西不给直接删：插件要先去插件管理停用，方案要先切走。
                // 否则 user 里会留下孤儿硬链接 / 引擎抱着已删的目录。
                val busy = (item.kind == "plugin" && JapanesePlugin.isActive(this)) ||
                    (item.kind == "scheme" && SchemeManager.active()?.id == item.id)
                if (busy) {
                    val where = if (item.kind == "plugin") "插件管理" else "方案"
                    android.widget.Toast.makeText(
                        this, "先去$where 停用它，再回来移除",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                val ok = StoreInstaller.remove(this, item)
                if (!ok) {
                    android.widget.Toast.makeText(
                        this, "没删干净，稍后再试", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                // 移除语音模型后，正在跑的引擎要放掉，否则它还抱着旧路径
                VoiceAsrEngine.release()
                renderList()
                if (item.kind == "plugin") {
                    android.widget.Toast.makeText(
                        this, "已移除，插件管理里也不会再出现",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("算了", null)
            .show()
    }

    // ------------------------------------------------------------ 导入

    /**
     * 本地导入。
     *
     * 用户自己带的插件包（zip + manifest.json）从这里进。
     * 走的是和商店下载**同一条安装链路**：解到 store/<id> 下、
     * 由 [com.jimeng.ime.plugin.PluginStore] 列进插件管理页——
     * 装完之后用户去插件管理开关它，商店这边只负责「收进来」。
     */
    private fun importLocal() {
        val helper = importer ?: PluginImporter(this) {
            // 装完/失败后统一回来重画：插件类列表里要能看见新导入的
            if (current == Tab.Plugin) renderList()
        }.also { importer = it }
        helper.pick()
    }

    /** 文件选择器的结果要转给导入器，别在这里漏掉 */
    @Deprecated("Activity 的老回调，商店不用 ActivityResultLauncher")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        importer?.onResult(requestCode, resultCode, data)
    }

    // ------------------------------------------------------------ 小工具

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    companion object {
        private const val TAG = "JimengStore"
    }
}