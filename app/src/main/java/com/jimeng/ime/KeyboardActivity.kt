/*
 * 「键盘管理」独立页面。
 *
 * ── 为什么单独开一页 ────────────────────────────────────────
 * 键盘尺寸（六条滑条）、按键外观（边框 / 描边 / 水波纹）和空格键上的字
 * （开关 / 输入框 / 字体 / 字号 / 预览）说的都是「键盘自己长什么样」，
 * 但它们塞在设置主页里的时候被夹在「按键行为」和「剪贴板」中间，
 * 一屏放不下、滚起来还得来回找。
 * 单独一页之后：上面是键盘缩放预览，下面按「尺寸 / 外观 / 空格键」三块排开。
 *
 * ── 页面结构 ────────────────────────────────────────────
 *   ┌──────────────────────────────┐
 *   │        真键盘缩小版（带阴影）    │  ← KeyboardPreviewView
 *   ├──────────────────────────────┤
 *   │ 键盘尺寸                       │
 *   │   键盘高度（竖屏）  ────────34% │
 *   │   键盘高度（横屏）  ────────49% │
 *   │   两侧边距 / 底部边距…          │
 *   ├──────────────────────────────┤
 *   │ 按键外观                       │
 *   │   [开关] 启用按键边框            │
 *   │   [开关] 启用按键边框描边        │
 *   │   [开关] 启用按键水波纹特效      │
 *   ├──────────────────────────────┤
 *   │ 空格键                         │
 *   │   [开关] 空格键显示字            │
 *   │   [输入框]                     │
 *   │   空格键字体           霞鹜文楷 › │
 *   │   导入字体             已导入 2 个›│
 *   │   空格键字号  ──────────  18 dp │
 *   │   [预览条]                     │
 *   └──────────────────────────────┘
 *
 * 所有改动都是即时生效：尺寸滑条松手发广播、空格键的字一边打一边写 prefs，
 * 键盘那边监听同一条广播重建一层，不用退出去重开键盘。
 */
package com.jimeng.ime

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.keyboard.SpaceFont
import com.jimeng.ime.kb.keyboard.SpaceFontLoader
import com.jimeng.ime.kb.keyboard.SpaceLabel
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.view.KeyboardPreviewView

class KeyboardActivity : Activity() {

    companion object {
        /** 从手机里挑字体文件：SAF 的 requestCode */
        const val REQ_PICK_FONT = 1101
    }

    /** 键盘缩放预览。改完尺寸就地重画，让用户当场看到高矮胖瘦 */
    private var preview: KeyboardPreviewView? = null

    /** 开关行标题 -> 同步勾选状态（跟设置页同构，便于两个页面互相复用） */
    private val switchRows = LinkedHashMap<String, (Boolean) -> Unit>()

    /** 滑块行标题 -> 同步滑块位置 */
    private val seekRows = LinkedHashMap<String, (Int) -> Unit>()

    /** 可选行标题 -> 同步右侧当前值 */
    private val pickRows = LinkedHashMap<String, (String) -> Unit>()

    /**
     * 正在把状态往界面上刷的时候为 true。
     * 没有它，程序设 Switch.isChecked 会触发用户监听，再写一次 prefs，白绕一圈。
     */
    private var syncingSwitches = false

    /**
     * 刷新空格键那一块：输入框显隐、字体行、导入行、字号条、预览文字。
     * onCreate 里造控件时赋值，之后由输入框 / 开关 / 导入字体共用。
     */
    private var refreshSpaceBlock: () -> Unit = {}

    // 空格键那一块要用到的控件，refreshSpaceBlock 里要按开关状态显隐
    private var spaceLabelInput: EditText? = null
    private var spaceFontRow: LinearLayout? = null
    private var importFontRow: LinearLayout? = null
    private var spaceSizeSeek: LinearLayout? = null
    private var spacePreview: TextView? = null

    /** 键盘尺寸滑条（改完要重画预览 + 通知键盘） */
    private var sizeRows = mutableListOf<Pair<String, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这个页面可能比输入法服务先打开，得自己把两个单例初始化起来
        ThemeManager.init(this)
        AppPrefs.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
        }

        root.addView(buildTopBar())
        root.setPadding(0, statusBarHeight(), 0, 0)

        val scroller = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        scroller.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
                previewHeight()
            )
        )
        content.addView(card)

        content.addView(
            TextView(this).apply {
                text = "上面的就是键盘现在的样子，改下面的数值会立刻跟着变"
                textSize = 12f
                setTextColor(0xFF8A8F98.toInt())
                setPadding(dp(2), dp(10), dp(2), dp(2))
            }
        )

        // ---- 键盘尺寸 ----
        content.addView(sectionTitle("键盘尺寸"))
        content.addView(
            subtitle("拖完松手立刻生效；也可以长按键盘上的逗号 →「键盘调节」直接在键盘上拉伸")
        )
        val sizeBox = whiteBox()
        val kb = AppPrefs.getInstance().keyboard

        // 竖屏：默认 34%（和 AppPrefs 里的默认值一致）
        sizeBox.addView(
            seekRow(
                "键盘高度（竖屏）",
                "占屏幕高度的百分比（范围 20~70，默认 34）",
                min = 20, max = 70, step = 1,
                value = kb.keyboardHeightPercent.getValue(),
                unit = "%",
                onReset = { 34 }
            ) { v ->
                kb.keyboardHeightPercent.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        // 横屏：默认 49%
        sizeBox.addView(
            seekRow(
                "键盘高度（横屏）",
                "占屏幕高度的百分比（范围 20~70，默认 49）",
                min = 20, max = 70, step = 1,
                value = kb.keyboardHeightPercentLandscape.getValue(),
                unit = "%",
                onReset = { 49 }
            ) { v ->
                kb.keyboardHeightPercentLandscape.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        sizeBox.addView(
            seekRow(
                "两侧边距（竖屏）",
                "键盘左右各留多少空（范围 0~60 dp，默认 0）",
                min = 0, max = 60, step = 1,
                value = kb.keyboardSidePadding.getValue(),
                unit = "dp",
                onReset = { 0 }
            ) { v ->
                kb.keyboardSidePadding.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        sizeBox.addView(
            seekRow(
                "两侧边距（横屏）",
                "键盘左右各留多少空（范围 0~60 dp，默认 0）",
                min = 0, max = 60, step = 1,
                value = kb.keyboardSidePaddingLandscape.getValue(),
                unit = "dp",
                onReset = { 0 }
            ) { v ->
                kb.keyboardSidePaddingLandscape.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        sizeBox.addView(
            seekRow(
                "底部边距（竖屏）",
                "键盘底边离屏幕底留多少空（范围 0~60 dp，默认 0）",
                min = 0, max = 60, step = 1,
                value = kb.keyboardBottomPadding.getValue(),
                unit = "dp",
                onReset = { 0 }
            ) { v ->
                kb.keyboardBottomPadding.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        sizeBox.addView(
            seekRow(
                "底部边距（横屏）",
                "键盘底边离屏幕底留多少空（范围 0~60 dp，默认 0）",
                min = 0, max = 60, step = 1,
                value = kb.keyboardBottomPaddingLandscape.getValue(),
                unit = "dp",
                onReset = { 0 }
            ) { v ->
                kb.keyboardBottomPaddingLandscape.setValue(v)
                applyKeyAppearanceChange()
            }
        )
        content.addView(sizeBox)

        // ---- 按键外观 ----
        // 键的底块、描边、水波纹。原来在设置主页的「按键外观」分区，
        // 搬来这里是因为它们改的也是「键自己长什么样」，跟尺寸属于同一类事；
        // 主页那边只留一个入口行，从这里进来改。
        //
        // 三个开关都会立刻重建键盘（applyKeyAppearanceChange 发广播），
        // 所以这个页面里改完，底下的真键盘当场就变。
        content.addView(sectionTitle("按键外观"), marginTop(dp(26)))
        content.addView(subtitle("改完立刻生效，不用重开键盘"))
        val appearanceBox = whiteBox()
        val themePrefs = ThemeManager.prefs

        appearanceBox.addView(
            switchRow(
                "启用按键边框",
                "每个按键显示独立的底块，键与键之间自然分出边",
                themePrefs.keyBorder.getValue()
            ) { on ->
                themePrefs.keyBorder.setValue(on)
                applyKeyAppearanceChange()
                refreshAppearance()
            }
        )
        appearanceBox.addView(
            switchRow(
                "启用按键边框描边",
                "边框画成线条，而不是带阴影的底块（需先启用按键边框）",
                themePrefs.keyBorderStroke.getValue()
            ) { on ->
                themePrefs.keyBorderStroke.setValue(on)
                // 开描边时顺手把边框也打开，否则看不出效果
                if (on && !themePrefs.keyBorder.getValue()) {
                    themePrefs.keyBorder.setValue(true)
                }
                applyKeyAppearanceChange()
                refreshAppearance()
            }
        )
        appearanceBox.addView(
            switchRow(
                "启用按键水波纹特效",
                "按下时从手指位置扩散一圈波纹，更接近 Material 手感",
                themePrefs.keyRippleEffect.getValue()
            ) { on ->
                themePrefs.keyRippleEffect.setValue(on)
                applyKeyAppearanceChange()
            }
        )
        content.addView(appearanceBox, marginTop(dp(8)))

        // ---- 空格键 ----
        content.addView(sectionTitle("空格键"), marginTop(dp(26)))
        content.addView(subtitle("空格键上写什么字、用什么字体、多大"))
        val spaceBox = whiteBox()

        // 开关：关掉时下面那一串全都 GONE
        spaceBox.addView(
            switchRow(
                "空格键显示字",
                "打开时空格键上写字（方案名或自定义）；关掉就是纯空格键",
                kb.showSpaceLabel.getValue()
            ) { on ->
                kb.showSpaceLabel.setValue(on)
                refreshSpaceBlock()
            }
        )

        // 输入框：一边打一边写 prefs，键盘上的字跟着手指实时变
        val input = EditText(this).apply {
            hint = "想写在空格键上的字（最多 ${SpaceLabel.MAX_LENGTH} 个）"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFF202124.toInt())
            setHintTextColor(0xFFB0B4BB.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFF4F6F9.toInt())
            setText(kb.spaceLabelText.getValue())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val raw = s?.toString() ?: ""
                    // 超长就截断并回写，否则光标会跑到前面去
                    if (raw.length > SpaceLabel.MAX_LENGTH) {
                        s?.replace(0, raw.length, raw.take(SpaceLabel.MAX_LENGTH))
                        return
                    }
                    kb.spaceLabelText.setValue(raw)
                    refreshSpaceBlock()
                }
            })
        }
        spaceLabelInput = input
        spaceBox.addView(input, marginTop(dp(-4)))

        // 字体行
        val fontRow = pickRow(
            "空格键字体",
            "选个写法：楷书、行书、圆体…点开每一项都用它自己的字体显示",
            current = { kb.spaceFont.getValue().label }
        ) {
            showFontDialog { font ->
                kb.spaceFont.setValue(font)
                refreshSpaceBlock()
                applyKeyAppearanceChange()
            }
        }
        spaceFontRow = fontRow
        spaceBox.addView(fontRow)

        // 导入字体行
        val importRow = pickRow(
            "导入字体",
            "从手机里选一个 .ttf / .otf 文件，选完立刻出现在上面的字体列表里",
            current = {
                val n = SpaceFontLoader.imported(this).size
                if (n == 0) "去导入" else "已导入 $n 个"
            }
        ) {
            pickFontFile()
        }
        importFontRow = importRow
        spaceBox.addView(importRow)

        // 字号拉条
        val sizeSeek = seekRow(
            "空格键字号",
            "拖动调整空格键上文字的大小，上面的预览条会跟着变",
            SpaceLabel.MIN_TEXT_SIZE,
            SpaceLabel.MAX_TEXT_SIZE,
            SpaceLabel.TEXT_SIZE_STEP,
            kb.spaceLabelTextSize.getValue()
                .coerceIn(SpaceLabel.MIN_TEXT_SIZE, SpaceLabel.MAX_TEXT_SIZE),
            "dp",
            // 「恢复默认」回到 fcitx5 原版给空格键定的 13dp
            onReset = { SpaceLabel.IDLE_TEXT_SIZE.toInt() }
        ) { v ->
            kb.spaceLabelTextSize.setValue(v)
            refreshSpaceBlock()
        }
        spaceSizeSeek = sizeSeek
        spaceBox.addView(sizeSeek)

        // 预览条
        val prev = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF202124.toInt())
            setPadding(0, dp(14), 0, dp(14))
            setBackgroundColor(0xFFF4F6F9.toInt())
        }
        spacePreview = prev
        spaceBox.addView(
            prev,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) }
        )
        content.addView(spaceBox, marginTop(dp(8)))

        // 控件都造好了，接上刷新实现
        refreshSpaceBlock = { refreshSpaceBlockImpl() }
        refreshSpaceBlock()

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        // 从别处回来可能换了皮肤 / 改了尺寸，预览跟着变
        ThemeManager.activeTheme.let { preview?.setTheme(it) }
        preview?.let {
            val lp = it.layoutParams
            lp.height = previewHeight()
            it.layoutParams = lp
        }
    }

    // ------------------------------------------------------------ 空格键刷新

    /** 刷新空格键那一块：预览内容 / 字体 / 字号 / 开关状态一起对齐 */
    private fun refreshSpaceBlockImpl() {
        val kb = AppPrefs.getInstance().keyboard
        val on = kb.showSpaceLabel.getValue()
        val custom = kb.spaceLabelText.getValue().trim()
        val font = kb.spaceFont.getValue()

        spacePreview?.let { p ->
            p.text = buildString {
                append(custom.ifEmpty { "雾凇拼音" })
                if (!on) append("　（当前已关闭，空格键不显示字）")
            }
            p.setTextColor(if (on) 0xFF202124.toInt() else 0xFFB0B4BB.toInt())
            p.typeface = if (on) font.typeface(this) else null
            p.textSize = kb.spaceLabelTextSize.getValue()
                .coerceIn(SpaceLabel.MIN_TEXT_SIZE, SpaceLabel.MAX_TEXT_SIZE)
                .toFloat()
            p.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE
        }
        spaceLabelInput?.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE
        // 导入行：开关开着就一直给——「一个字体都没有」的时候用户更需要它
        importFontRow?.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE
        // 字体行：开关关掉不显示；一个可选字体都没有时也隐藏
        fun typfaceOrNull() = if (on && SpaceFontLoader.available(this)) {
            ViewGroup.VISIBLE
        } else {
            ViewGroup.GONE
        }
        spaceFontRow?.visibility = typfaceOrNull()
        // 字号拉条：只在开关打开时给
        spaceSizeSeek?.visibility = if (on) ViewGroup.VISIBLE else ViewGroup.GONE

        pickRows["空格键字体"]?.invoke(font.label)
        pickRows["导入字体"]?.invoke(
            SpaceFontLoader.imported(this).size.let { if (it == 0) "去导入" else "已导入 $it 个" }
        )
    }

    // ------------------------------------------------------------ 字体选择

    /**
     * 选空格键字体。每一行**用它自己的字体**显示自己的名字和预览字。
     *
     * 字体是用来「看」的，不是用来读名字的——只列名字用户分不出差别，
     * 所以列表项左半是字体名、右半是「你好」两个字的实时预览。
     */
    private fun showFontDialog(onPick: (SpaceFont) -> Unit) {
        val fonts = SpaceFontLoader.all(this)
        val current = AppPrefs.getInstance().keyboard.spaceFont.getValue()
        // 比「序列化后的身份」而不是 label：同名字体会比错项、打错勾
        val currentKey = SpaceFontLoader.serialize(current)
        val selectedIndex = fonts.indexOfFirst {
            SpaceFontLoader.serialize(it) == currentKey
        }.coerceAtLeast(0)
        // 预览用的字：有自定义就用自定义的头两个字，没有就「你好」
        val previewText = AppPrefs.getInstance().keyboard.spaceLabelText.getValue()
            .trim().take(2).ifEmpty { "你好" }
        val bgNormal = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        val dialogRef = arrayOfNulls<android.app.AlertDialog>(1)
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = fonts.size
            override fun getItem(position: Int) = fonts[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(
                position: Int,
                convertView: android.view.View?,
                parent: ViewGroup?
            ): android.view.View {
                val font = fonts[position]
                val typeface = font.typeface(this@KeyboardActivity)
                val row = LinearLayout(this@KeyboardActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    background = if (position == selectedIndex) {
                        android.graphics.drawable.ColorDrawable(0xFFF0F4FF.toInt())
                    } else {
                        bgNormal
                    }
                }
                // 点击选中挂在行自己身上：子 View 一旦响应长按就会吃掉整串触摸事件，
                // ListView 的 onItemClick 永远不触发（这个坑踩过一次）
                row.setOnClickListener {
                    onPick(font)
                    dialogRef[0]?.dismiss()
                }
                val left = LinearLayout(this@KeyboardActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                left.addView(TextView(this@KeyboardActivity).apply {
                    text = font.label
                    textSize = 16f
                    setTextColor(0xFF111318.toInt())
                    // 名字用这个字体本身写，一眼看出笔形
                    if (font.isCustom) setTypeface(typeface)
                })
                left.addView(TextView(this@KeyboardActivity).apply {
                    text = font.desc
                    textSize = 11.5f
                    setTextColor(0xFF8A8F98.toInt())
                    setPadding(0, dp(2), 0, 0)
                })
                row.addView(
                    left,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                // 右边的预览：统一 22sp，能看清笔锋
                row.addView(TextView(this@KeyboardActivity).apply {
                    text = previewText
                    textSize = 22f
                    setTextColor(0xFF202124.toInt())
                    gravity = Gravity.CENTER
                    if (font.isCustom) setTypeface(typeface)
                })
                if (position == selectedIndex) {
                    row.addView(TextView(this@KeyboardActivity).apply {
                        text = " ✓"
                        textSize = 15f
                        setTextColor(0xFF2B6FFF.toInt())
                    })
                }
                // 导入的字体：右边给一个 ✕ 删除
                if (font is SpaceFont.Imported) {
                    row.addView(TextView(this@KeyboardActivity).apply {
                        text = "✕"
                        textSize = 13f
                        setTextColor(0xFFB0B4BB.toInt())
                        gravity = Gravity.CENTER
                        setPadding(dp(14), dp(6), dp(2), dp(6))
                        setOnClickListener {
                            confirmDeleteFont(font) {
                                onPick(SpaceFont.Default)
                                dialogRef[0]?.dismiss()
                            }
                        }
                    })
                }
                return row
            }
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("空格键字体（预览「$previewText」）")
            .setAdapter(adapter) { _, _ -> }
            .setNegativeButton("取消", null)
            .create()
        dialogRef[0] = dialog
        dialog.show()
    }

    /** 确认删除一个导入的字体。删完把文件删掉，正在用它的就退回系统默认 */
    private fun confirmDeleteFont(font: SpaceFont.Imported, onDeleted: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删掉「${font.label}」？")
            .setMessage("只删掉导入到手机里的这个字体文件，你原来的文件还在原处，不受影响。")
            .setPositiveButton("删除") { _, _ ->
                val f = java.io.File(SpaceFontLoader.importedDir(this), font.fileName)
                val ok = try {
                    f.delete()
                } catch (t: Throwable) {
                    false
                }
                SpaceFontLoader.forgetDisplayName(this, font.fileName)
                SpaceFontLoader.clearCache()
                // 正在用的就是它 → 退回系统默认，别把用户卡在一个没有的字体上
                val kb = AppPrefs.getInstance().keyboard
                val inUse = SpaceFontLoader.serialize(kb.spaceFont.getValue())
                if (inUse == SpaceFontLoader.serialize(font)) {
                    kb.spaceFont.setValue(SpaceFont.Default)
                }
                onDeleted()
                refreshSpaceBlock()
                applyKeyAppearanceChange()
                toast(if (ok) "已删掉「${font.label}」" else "删除失败，文件可能被占用了")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 从手机里挑一个字体文件导入。
     *
     * 用 SAF（ACTION_OPEN_DOCUMENT）而不是直接读路径：Android 10+ 分区存储后
     * 不能再直接读 /sdcard 下的任意路径，SAF 由系统给一个临时可读的 Uri，
     * 不需要申请存储权限。
     */
    private fun pickFontFile() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // 有些文件管理器不认 ttf 的 MIME，用 */* 再用扩展名兜一层
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "font/ttf", "font/otf", "font/ttc",
                        "application/x-font-ttf", "application/x-font",
                        "application/octet-stream"
                    )
                )
            }
            startActivityForResult(intent, REQ_PICK_FONT)
        } catch (t: Throwable) {
            toast("打不开文件选择器：${t.message}")
        }
    }

    /** SAF 选完回来了：把文件拷进 files/fonts/，读一下字体名，刷新列表 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FONT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "font.ttf"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) {
            toast("这不是字体文件（要 .ttf / .otf / .ttc）")
            return
        }
        // 重名就加序号，别把之前导入的覆盖掉
        val dir = SpaceFontLoader.importedDir(this)
        var target = java.io.File(dir, name)
        var seq = 2
        while (target.exists()) {
            target = java.io.File(dir, "${name.substringBeforeLast('.')}(${seq++}).${ext}")
        }
        val ok = try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: false
            true
        } catch (t: Throwable) {
            false
        }
        if (!ok || target.length() <= 0L) {
            try { target.delete() } catch (t: Throwable) {}
            toast("读不出来这个文件，换一个试试")
            return
        }
        // 太小的肯定不是有效字体（中文字库再怎么子集化也有几百 KB）
        if (target.length() < 16 * 1024) {
            try { target.delete() } catch (t: Throwable) {}
            toast("这个文件太小了（${target.length()} 字节），不像字体")
            return
        }
        // 试加载一次：字体坏了 Android 会抛异常，早发现早说
        val tf = try {
            android.graphics.Typeface.createFromFile(target)
        } catch (t: Throwable) {
            null
        }
        if (tf == null) {
            try { target.delete() } catch (t: Throwable) {}
            toast("这个字体 Android 读不了，可能文件坏了")
            return
        }
        // 用字体自己声明的名字当显示名；读不到就用文件名
        val family = SpaceFontLoader.readFamilyName(target)
        val display = family?.takeIf { it.isNotBlank() } ?: target.nameWithoutExtension
        SpaceFontLoader.rememberDisplayName(this, target.name, display)
        SpaceFontLoader.clearCache()
        // 导入完直接选中它——用户选字体就是想用它
        val imported = SpaceFont.Imported(target.name, display)
        AppPrefs.getInstance().keyboard.spaceFont.setValue(imported)
        refreshSpaceBlock()
        applyKeyAppearanceChange()
        toast("已导入「$display」，空格键已经换上了")
    }

    // ------------------------------------------------------------ 顶栏 / 工具

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
                text = "键盘管理"
                textSize = 18f
                setTextColor(0xFF111318.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return bar
    }

    /**
     * 预览区的高度。
     *
     * 真机上键盘占屏高的 keyboardHeightPercent%。这里按同一个比例算出一块
     * 高度，让预览里键盘的长宽比和用户实际看到的一模一样——不然预览里
     * 键盘被压扁，用户会以为改完尺寸键盘就变形了。
     */
    private fun previewHeight(): Int {
        val pct = try {
            AppPrefs.getInstance().keyboard.keyboardHeightPercent.getValue()
        } catch (t: Throwable) {
            34
        }
        val metrics = resources.displayMetrics
        val kbH = metrics.heightPixels * pct / 100
        // 卡片内宽 = 屏宽 - 页边距(16*2) - 卡片内边距(8*2)
        val cardW = metrics.widthPixels - dp(32) - dp(16)
        val scaled = kbH.toLong() * cardW / metrics.widthPixels
        return scaled.toInt().coerceIn(dp(90), dp(260))
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun whiteBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(2), dp(16), dp(2))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
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
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    /** 一行开关：左边标题 + 说明，右边 Switch */
    private fun switchRow(
        title: String,
        desc: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), dp(8), 0)
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sw = android.widget.Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                // 程序在刷状态时不要当成用户操作，否则会绕成回环
                if (syncingSwitches) return@setOnCheckedChangeListener
                onToggle(isChecked)
            }
        }
        row.addView(sw)
        switchRows[title] = { sw.isChecked = it }
        return row
    }

    /**
     * 一行滑块：左边标题 + 说明，右边数值，下面一整条 SeekBar。
     *
     * @param onReset 可选。传了就在数值右边多一个「恢复默认」，点击时由调用方
     *                返回该项的默认值，本方法把滑条和数字对齐后再回调 [onChanged]。
     * @param onChanged 拖动结束时回调真实值（拖动过程中只更新数字，不写 prefs）
     */
    private fun seekRow(
        title: String,
        desc: String,
        min: Int,
        max: Int,
        step: Int,
        value: Int,
        unit: String,
        onReset: (() -> Int)? = null,
        labelFor: ((Int) -> String)? = null,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        val format: (Int) -> String = { v -> labelFor?.invoke(v) ?: "$v $unit" }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), dp(8), 0)
        })
        head.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val valueView = TextView(this).apply {
            text = format(value)
            textSize = 15f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        head.addView(valueView)
        column.addView(head)

        // SeekBar 只有整数格，换算成「第几格」：真实值 = min + progress * step
        val steps = (max - min) / step
        val bar = android.widget.SeekBar(this).apply {
            setMax(steps)
            setProgress(((value - min) / step).coerceIn(0, steps))
            setPadding(dp(2), dp(8), dp(2), dp(8))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (!fromUser) return
                    valueView.text = format(min + progress * step)
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}

                // 松手才写 prefs：拖的时候每动一格都写，会白写几十次
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    val real = min + (sb?.progress ?: 0) * step
                    valueView.text = format(real)
                    onChanged(real)
                }
            })
        }
        column.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 可选「恢复默认」：放在标题那一行的数值右边，不独占一行
        if (onReset != null) {
            val resetBtn = TextView(this).apply {
                text = "恢复默认"
                textSize = 12f
                setTextColor(0xFF3B6FE0.toInt())
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(0xFFEAF0FE.toInt())
                }
                isClickable = true
                setOnClickListener {
                    val def = onReset()
                    // 先对齐滑条和数字，最后再回调写 prefs：反过来的话写 prefs
                    // 会触发刷新链路，可能把滑条又摆回旧值
                    val p = ((def - min) / step).coerceIn(0, steps)
                    bar.setProgress(p)
                    valueView.text = format(min + p * step)
                    onChanged(def)
                }
            }
            head.addView(
                resetBtn,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.leftMargin = dp(10) }
            )
        }

        seekRows[title] = { newValue ->
            val p = ((newValue - min) / step).coerceIn(0, steps)
            bar.setProgress(p)
            valueView.text = format(min + p * step)
        }
        return column
    }

    /** 一行「可点开选的条目」：左边标题 + 说明，右边当前值 */
    private fun pickRow(
        title: String,
        desc: String,
        current: () -> String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), dp(8), 0)
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val valueView = TextView(this).apply {
            text = current()
            textSize = 14f
            setTextColor(0xFF8A8F98.toInt())
        }
        row.addView(valueView)
        pickRows[title] = { valueView.text = it }
        row.setOnClickListener { onClick() }
        return row
    }
    private fun applyKeyAppearanceChange() {
        sendBroadcast(Intent(JimengIME.ACTION_RELOAD_KEY_APPEARANCE).setPackage(packageName))
        // 尺寸改完预览要跟着变高矮
        preview?.let {
            val lp = it.layoutParams
            lp.height = previewHeight()
            it.layoutParams = lp
        }
    }

    /**
     * 把开关行的勾选状态跟 prefs 里的真实值对齐。
     *
     * 只用于「一个开关改了另一个开关」这种联动：比如点了「启用按键边框描边」，
     * 代码顺手把「启用按键边框」也打开，那第一行的开关得跟着动一下，
     * 不然界面上看着是关的、键盘上却已经开了。
     */
    private fun refreshAppearance() {
        syncingSwitches = true
        try {
            val p = ThemeManager.prefs
            switchRows["启用按键边框"]?.invoke(p.keyBorder.getValue())
            switchRows["启用按键边框描边"]?.invoke(p.keyBorderStroke.getValue())
            switchRows["启用按键水波纹特效"]?.invoke(p.keyRippleEffect.getValue())
        } finally {
            syncingSwitches = false
        }
    }


    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = v }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
