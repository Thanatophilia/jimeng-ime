/*
 * 「主题与定制」独立页面。仿 fcitx5-android 的 ThemeFragment。
 *
 * ── 为什么单独开一页 ────────────────────────────────────────
 * 皮肤区块塞在设置主页里，一屏放不下、滚动很累；而且皮肤预览要占
 * 一大块地方（要看得清键盘长什么样），挤在设置页的中间根本展不开。
 * 单独一页之后：上面一整块键盘预览，下面是缩略图网格，互不干扰。
 *
 * ── 页面结构（照 fcitx5）────────────────────────────────────
 *   ┌──────────────────────────┐
 *   │   真键盘缩小版（带阴影）    │  ← KeyboardPreviewView
 *   ├──────────────────────────┤
 *   │  [＋新建][跟随系统][皮肤1]  │  ← SkinGridLayout
 *   │  [皮肤2][皮肤3]...         │
 *   └──────────────────────────┘
 */
package com.jimeng.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jimeng.ime.kb.theme.SkinManager
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.view.KeyboardPreviewView
import com.jimeng.ime.view.SkinGridLayout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SkinActivity : Activity() {

    companion object {
        const val REQ_PICK_SKIN = 2001
    }

    /** 顶部大预览：真键盘缩小版 */
    private var preview: KeyboardPreviewView? = null

    /** 缩略图网格 */
    private var grid: SkinGridLayout? = null

    /** 当前预览的是哪套皮肤，避免重复重建 */
    private var previewSkinId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这个页面是独立入口，可能比输入法服务先打开，得自己把
        // ThemeManager / AppPrefs 初始化起来（跟 SetupActivity 一个道理）
        ThemeManager.init(this)
        com.jimeng.ime.kb.prefs.AppPrefs.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        // ---- 顶栏：返回 + 标题 ----
        root.addView(buildTopBar())
        // 顶栏在状态栏下面（跟设置页一样不侵入状态栏）
        root.setPadding(0, statusBarHeight(), 0, 0)

        val scroller = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        scroller.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            scroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // ---- 键盘预览：一块浮起的卡片 ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(4).toFloat()
        }
        preview = KeyboardPreviewView(this).apply {
            ThemeManager.activeTheme.let { setTheme(it) }
        }
        card.addView(
            preview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                // 预览高度取「键盘真高 × 卡片宽 / 屏宽」，保证和真机比例一致
                previewHeight()
            )
        )
        content.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // ---- 说明 ----
        content.addView(
            TextView(this).apply {
                text = "上面的就是键盘变成的样子，点下面的缩略图立刻换"
                textSize = 12f
                setTextColor(0xFF8A8F98.toInt())
                setPadding(dp(2), dp(10), dp(2), dp(2))
            }
        )

        // ---- 皮肤网格 ----
        grid = SkinGridLayout(this).apply {
            onAddClicked = { showNewSkinDialog() }
            onSkinClicked = { entry ->
                if (entry == null) {
                    ThemeManager.resetToSystem()
                    afterSkinChange("已切回跟随系统")
                } else if (ThemeManager.applySkin(this@SkinActivity, entry)) {
                    afterSkinChange("已换成「${entry.name}」")
                } else {
                    toast("这套皮肤没有可用配色")
                }
            }
            onSkinDelete = { entry -> confirmDeleteSkin(entry) }
        }
        content.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
        )

        setContentView(root)
    }

    // ------------------------------------------------------------ 顶栏

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
                text = "主题与定制"
                textSize = 18f
                setTextColor(0xFF111318.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return bar
    }

    // ------------------------------------------------------------ 换皮肤

    /** 换完皮肤：重建预览 + 重画网格 + 通知正开着的键盘 + 提示 */
    private fun afterSkinChange(msg: String) {
        ThemeManager.activeTheme.let { preview?.setTheme(it) }
        previewSkinId = ThemeManager.currentSkinId()
        grid?.refresh()
        // 键盘那边读的是 ThemeManager.activeTheme，重建一层就换过来了
        sendBroadcast(Intent(JimengIME.ACTION_RELOAD_KEY_APPEARANCE).setPackage(packageName))
        toast(msg)
    }

    // ------------------------------------------------------------ 新建

    /** 「+」那一格点下去：问用户想怎么新建。照 fcitx5 的 addTheme() */
    private fun showNewSkinDialog() {
        val actions = arrayOf("导入皮肤包（.jmskin）", "复制一套内置皮肤")
        AlertDialog.Builder(this)
            .setTitle("新建主题")
            .setNegativeButton("取消", null)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> pickSkinFile()
                    1 -> showDuplicateSkinDialog()
                }
            }
            .show()
    }

    private fun showDuplicateSkinDialog() {
        val builtins = try {
            SkinManager.list(this).filter { it.builtin }
        } catch (t: Throwable) {
            emptyList()
        }
        if (builtins.isEmpty()) {
            toast("找不到内置皮肤")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("复制哪一套")
            .setNegativeButton("取消", null)
            .setItems(builtins.map { it.name }.toTypedArray()) { _, i ->
                duplicateBuiltinSkin(builtins[i])
            }
            .show()
    }

    /**
     * 把一套内置皮肤复制成一份独立的导入皮肤。
     *
     * 内置皮肤在 assets 里是只读的，复制出来才变成 files/skins 下的普通包。
     * 打完仍然交给 [SkinManager.install]，白赚一遍路径穿越、解压大小、
     * 图片可解码的校验——复制出来的包和外面导入的包走同一条路。
     */
    private fun duplicateBuiltinSkin(src: SkinManager.SkinEntry) {
        val newId = nextCopyId(src.id)
        val newName = "${src.name} 副本"
        val result = try {
            ByteArrayInputStream(buildCopiedSkinZip(src.id, newId, newName)).use {
                SkinManager.install(this, it)
            }
        } catch (t: Throwable) {
            toast("复制失败：${t.message}")
            return
        }
        when (result) {
            is SkinManager.InstallResult.Ok -> {
                ThemeManager.applySkin(this, result.entry)
                afterSkinChange("已新建「${result.entry.name}」")
            }
            is SkinManager.InstallResult.Fail -> {
                AlertDialog.Builder(this)
                    .setTitle("复制失败")
                    .setMessage(result.errors.take(8).joinToString("\n") { "· $it" })
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    /** 挑一个没被占用的副本 id：xxx_copy、xxx_copy2… */
    private fun nextCopyId(baseId: String): String {
        val taken = try {
            SkinManager.list(this).map { it.id }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }
        val stem = "${baseId}_copy"
        if (stem !in taken) return stem
        var n = 2
        while ("$stem$n" in taken) n++
        return "$stem$n"
    }

    /** 把一个内置皮肤目录打成内存里的 .jmskin，顺手改掉 id 和 name */
    private fun buildCopiedSkinZip(srcId: String, newId: String, newName: String): ByteArray {
        val prefix = "skins/$srcId"
        val files = mutableListOf<String>()
        fun walk(path: String) {
            val children = try {
                assets.list(path).orEmpty()
            } catch (e: Exception) {
                return
            }
            if (children.isEmpty()) return
            for (n in children) {
                val child = "$path/$n"
                if (assets.list(child).isNullOrEmpty()) {
                    files += child.removePrefix("$prefix/")
                } else {
                    walk(child)
                }
            }
        }
        walk(prefix)
        if (files.isEmpty()) throw IllegalStateException("内置皮肤 $srcId 是空的")

        val buf = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buf).use { zip ->
            for (rel in files) {
                val raw = assets.open("$prefix/$rel").use { it.readBytes() }
                val payload = if (rel == "skin.json") rewriteManifest(raw, newId, newName) else raw
                zip.putNextEntry(java.util.zip.ZipEntry(rel))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return buf.toByteArray()
    }

    private fun rewriteManifest(raw: ByteArray, newId: String, newName: String): ByteArray {
        val obj = org.json.JSONObject(String(raw, Charsets.UTF_8))
        obj.put("id", newId)
        obj.put("name", newName)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------ 导入 / 删除

    private fun pickSkinFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // .jmskin 没有注册的 MIME，只能 */* 再靠扩展名兜
                type = "*/*"
            }
            startActivityForResult(intent, REQ_PICK_SKIN)
        } catch (t: Throwable) {
            toast("打不开文件选择器：${t.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_SKIN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importSkin(uri)
    }

    /** 导入一个 .jmskin。失败把 SkinManager 收上来的错误原样摊开给用户看 */
    private fun importSkin(uri: android.net.Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "skin.jmskin"
        if (!name.lowercase().endsWith(".jmskin")) {
            toast("这不是皮肤包（要 .jmskin）")
            return
        }
        val result = try {
            contentResolver.openInputStream(uri)?.use { input ->
                SkinManager.install(this, input)
            } ?: run {
                toast("读不出这个文件")
                return
            }
        } catch (t: Throwable) {
            toast("导入失败：${t.message}")
            return
        }

        when (result) {
            is SkinManager.InstallResult.Ok -> {
                // 导入就是想换，直接启用，不用再点一次
                val e = result.entry
                ThemeManager.applySkin(this, e)
                afterSkinChange("已导入并启用「${e.name}」")
            }
            is SkinManager.InstallResult.Fail -> {
                AlertDialog.Builder(this)
                    .setTitle("这个皮肤包装不上")
                    .setMessage(result.errors.take(8).joinToString("\n") { "· $it" })
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun confirmDeleteSkin(e: SkinManager.SkinEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除「${e.name}」")
            .setMessage("删掉之后要重新导入才能再用。内置皮肤不能删。")
            .setNegativeButton("算了", null)
            .setPositiveButton("删除") { _, _ ->
                if (SkinManager.delete(this, e.id)) {
                    afterSkinChange("已删除「${e.name}」")
                } else {
                    toast("删不掉，可能这个皮肤是内置的")
                }
            }
            .show()
    }

    // ------------------------------------------------------------ 小工具

    /**
     * 预览区的高度。
     *
     * 真机上键盘占屏高的 keyboardHeightPercent%。这里按同一个比例算出一块
     * 高度，让预览里的键盘长宽比和用户实际看到的一模一样——不然预览里
     * 键盘被压扁，用户会以为换了皮肤键盘就变形了。
     */
    private fun previewHeight(): Int {
        val pct = try {
            com.jimeng.ime.kb.prefs.AppPrefs.getInstance()
                .keyboard.keyboardHeightPercent.getValue()
        } catch (t: Throwable) {
            30
        }
        val metrics = resources.displayMetrics
        val kbH = metrics.heightPixels * pct / 100
        // 卡片内宽 = 屏宽 - 页边距(16*2) - 卡片内边距(8*2)
        val cardW = metrics.widthPixels - dp(32) - dp(16)
        // 按宽度等比缩放
        val scaled = kbH.toLong() * cardW / metrics.widthPixels
        return scaled.toInt().coerceIn(dp(90), dp(260))
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}