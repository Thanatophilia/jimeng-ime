/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package com.jimeng.ime.kb.keyboard

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * 空格键上那个字用什么字体。
 *
 * ── 两种来源 ───────────────────────────────────────────
 *   1. 内置字体：打进 APK 的 app/src/main/assets/fonts/ 下，见 [SpaceFont.Builtin]
 *   2. 导入字体：主人自己从手机存储里选一个 .ttf / .otf / .ttc，
 *      拷进 App 私有目录 files/fonts/，见 [SpaceFont.Imported]
 *
 * 为什么要做成导入了而不是只靠内置：
 *   中文字体动辄二三十兆，塞进 APK 直接翻倍；而且主人觉得哪款不好看就得
 *   重新编译、重新安装。导入就自由了——主人自己下载，选进来就能用，
 *   APK 一个字节都不涨，小雅也不用为了换字体重新打包。
 *
 * ── 加一个内置字体 ─────────────────────────────────────
 *   1. 把子集化好的 .ttf 放进 app/src/main/assets/fonts/
 *   2. 在 [SpaceFont.Builtin] 枚举里加一行：名字("文件名.ttf", "显示名", "一句话风格说明")
 *   3. 编译。设置页的字体列表、预览、键盘端全都会自动带上它。
 *
 * ── 关于子集化 ─────────────────────────────────────────
 *   内置字体必须子集化，否则 APK 会大几十兆。空格键最多显示 8 个字，
 *   留「3500 常用字 + ASCII + 全角标点」就够：
 *
 *     pyftsubset 原字体.ttf --text-file=chars3500.txt \
 *       --unicodes="U+0020-007E,U+3000-303F,U+FF00-FFEF,U+00B7,U+2018-201F,U+2014" \
 *       --output-file=输出.ttf --no-hinting --drop-tables+=DSIG --desubroutinize
 *
 *   导入的字体不用管这些：体积是主人自己那边的事，全量字库反而更保险
 *   （万一主人想在空格键上打生僻字，子集化的字库就没有那个字）。
 *
 * 授权注意：随 APK 分发的内置字体必须允许再分发，SIL OFL 1.1 最省事
 * （可商用、可嵌入、无需署名）；商业字库（方正、汉仪等）不能打包。
 * 导入到本机自用的字体不涉及再分发，主人自己下载的自己用，没这个问题。
 */
sealed interface SpaceFont {
    /** 设置页、字体弹窗里显示的名字 */
    val label: String

    /** 一句话风格说明（字体弹窗第二行的小字） */
    val desc: String

    /** 是不是真的要换字体（[Default] 表示保留系统字体） */
    val isCustom: Boolean

    /**
     * 拿这个字体对应的 Typeface。
     *
     * 返回 null 表示用系统默认字体。加载失败也返回 null——字体只是
     * 空格键上的装饰，任何一个字体读不出来都不该把整个键盘搞崩。
     */
    fun typeface(ctx: Context): Typeface?

    /** 不换字体，跟键盘其他按键一样 */
    data object Default : SpaceFont {
        override val label = "系统默认"
        override val desc = "跟键盘其他按键一样的系统字体"
        override val isCustom = false
        override fun typeface(ctx: Context): Typeface? = null
        override fun toString() = label
    }

    /**
     * 打进 APK 的字体。
     *
     * [Default] 必须留在第一位：它是「不换字体」的语义，也是兜底值。
     */
    enum class Builtin(
        /** assets/fonts/ 下的文件名 */
        val assetName: String,
        override val label: String,
        override val desc: String
    ) : SpaceFont {
        ErMoXingShu(
            "aermoxingshu.ttf",
            "Aa而墨行书",
            "行书手写感，连笔飘逸（自带，汉字全覆盖）"
        ),
        AnJingChen(
            "anjingchen.ttf",
            "安景臣新瘦金体",
            "瘦金体，笔锋锐利清瘦（自带，汉字全覆盖）"
        ),
        BeautifulEs(
            "beautiful_es.ttf",
            "Beautiful ES",
            "装饰性英文体，只含拉丁字母（空格键写中文会显示不出来）"
        ),
        Rechtman(
            "rechtman.ttf",
            "Rechtman",
            "装饰性英文体，只有 84 个字形（空格键写中文会显示不出来）"
        );

        override val isCustom = true
        override fun typeface(ctx: Context): Typeface? = SpaceFontLoader.loadAsset(ctx, assetName)
        override fun toString() = label
    }

    /**
     * 主人自己导入的字体，存在 App 私有目录里。
     *
     * [fileName] 只是 files/fonts/ 下的文件名，不含路径——prefs 里存的就是这个名字，
     * 万一文件被删了，[SpaceFontLoader.loadFile] 会返回 null，自动退回系统字体。
     */
    data class Imported(
        val fileName: String,
        /** 主人导入时文件本来的名字（去掉扩展名），给主人认的 */
        val displayName: String
    ) : SpaceFont {
        override val label get() = displayName
        override val desc get() = "导入的字体（手机里）"
        override val isCustom get() = true
        override fun typeface(ctx: Context): Typeface? = SpaceFontLoader.loadFile(ctx, fileName)
        override fun toString() = label
    }
}

/**
 * 空格键自定义文字的限制。
 *
 * 空格键宽度是那一行剩下的部分，8 个中文字已经是
 * 「AutoScaleTextView 自动缩放」的极限；再多就只能缩到看不清了。
 * 设置页的输入框和键盘端读 prefs 时都用这个上限，两边一致。
 */
object SpaceLabel {
    const val MAX_LENGTH = 8

    /**
     * 开关关闭时（或键位定义里）用的原始字号。
     *
     * 这个值是 fcitx5 原版给空格键「一个看不见的符号」定的，
     * 保留原值是为了关掉功能后空格键的样子和改造前完全一致。
     * 同时也是字号拉条的**默认值**——主人说「字体先回到默认大小」，指的就是它。
     */
    const val IDLE_TEXT_SIZE = 13f

    /** 字号拉条的下限（dp）。再小就跟原来的 13 没什么区别，拖到头也看不出变化 */
    const val MIN_TEXT_SIZE = 10

    /** 字号拉条的上限（dp）。空格键高度就那么大，再大就会顶到上下边缘 */
    const val MAX_TEXT_SIZE = 30

    /** 拉条步进（dp）。1dp 一格，30-10 一共 20 格，够细了 */
    const val TEXT_SIZE_STEP = 1
}

/**
 * 字体加载 + 缓存。
 *
 * Typeface.createFromAsset / createFromFile 每次都从存储读一遍、解析字体表，
 * 又慢又吃内存。键盘会反复重建（切换应用、旋转屏幕、改设置），所以按字体缓存起来。
 */
object SpaceFontLoader {
    /** 导入字体放在 App 私有目录的这里：files/fonts/ */
    private const val DIR = "fonts"

    private val cache = HashMap<String, Typeface>()

    /** 导入字体所在目录，不存在就建一个 */
    fun importedDir(ctx: Context): File =
        File(ctx.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    /** 从 assets 加载内置字体 */
    @Synchronized
    fun loadAsset(ctx: Context, assetName: String): Typeface? {
        val key = "asset:$assetName"
        cache[key]?.let { return it }
        return try {
            Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/$assetName")
                .also { cache[key] = it }
        } catch (t: Throwable) {
            null
        }
    }

    /** 从 files/fonts/ 加载导入的字体 */
    @Synchronized
    fun loadFile(ctx: Context, fileName: String): Typeface? {
        val key = "file:$fileName"
        cache[key]?.let { return it }
        val f = File(importedDir(ctx), fileName)
        if (!f.isFile) return null
        return try {
            Typeface.createFromFile(f).also { cache[key] = it }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 导入字体「文件名 -> 显示名」的映射表。
     *
     * 为什么需要它：字体里声明的名字（比如「霞鹜文楷」「Long Cang」）比文件名好看，
     * 但这个名字只在导入那一刻读得到一次——之后每次重建列表都只有文件名。
     * 不存下来的话，主人选了字体、退出设置页再进来，名字就变回
     * 「LXGWWenKai-Regular」了。
     *
     * 用独立的 SharedPreferences 而不是塞进 AppPrefs：它是字体自己的附属数据，
     * 跟键盘行为配置没关系，混在一起以后不好清。
     */
    private fun namePrefs(ctx: Context): android.content.SharedPreferences =
        ctx.applicationContext.getSharedPreferences("jimeng_space_fonts", Context.MODE_PRIVATE)

    /** 记下某个导入字体的显示名 */
    fun rememberDisplayName(ctx: Context, fileName: String, displayName: String) {
        namePrefs(ctx).edit().putString(fileName, displayName).apply()
    }

    /** 忘掉某个导入字体的显示名（删字体时） */
    fun forgetDisplayName(ctx: Context, fileName: String) {
        namePrefs(ctx).edit().remove(fileName).apply()
    }

    /** 取某个导入字体的显示名；没记过就退回文件名 */
    private fun displayNameOf(ctx: Context, fileName: String): String =
        namePrefs(ctx).getString(fileName, null)
            ?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')

    /** 主人导入过的所有字体，按文件名排序 */
    fun imported(ctx: Context): List<SpaceFont.Imported> {
        val files = importedDir(ctx).listFiles() ?: return emptyList()
        // 已经变成「自带」的字体，如果主人设备上还留着当年导入的那一份
        // （files/fonts/ 里的文件不会随版本更新消失），列表里就会同时出现
        // 「Aa而墨行书（自带）」和「Aa而墨行书（导入的）」，看着像重复。
        // 这里按「显示名」认一下：跟内置字体同名的就不再在「导入」区重复展示。
        //
        // 为什么用显示名而不是文件哈希：算 10MB 的哈希要几百毫秒，
        // 而 imported() 每次打开设置页、每次键盘重建都会调，太亏。
        // 显示名是解析 name 表拿到的家族名，同一款字体必然一致，够用了。
        val builtinLabels = SpaceFont.Builtin.entries.map { it.label }.toSet()
        return files
            .filter { it.isFile && it.length() > 0 && isFontName(it.name) }
            .map { SpaceFont.Imported(it.name, displayNameOf(ctx, it.name)) }
            .filter { it.displayName !in builtinLabels }
            .sortedBy { it.fileName }
    }

    /** 除了「系统默认」还有没有别的字体可选 */
    fun available(ctx: Context): Boolean =
        SpaceFont.Builtin.entries.isNotEmpty() || imported(ctx).isNotEmpty()

    /** 设置页字体弹窗里的完整列表：系统默认 → 内置 → 导入 */
    fun all(ctx: Context): List<SpaceFont> = buildList {
        add(SpaceFont.Default)
        addAll(SpaceFont.Builtin.entries)
        addAll(imported(ctx))
    }

    /** 按 prefs 里存的字符串找回字体对象；找不到就退回 Default */
    fun resolve(ctx: Context, raw: String): SpaceFont {
        if (raw.isEmpty()) return SpaceFont.Default
        SpaceFont.Builtin.entries.firstOrNull { it.name == raw }?.let { return it }
        if (raw.startsWith(FILE_PREFIX)) {
            val name = raw.removePrefix(FILE_PREFIX)
            imported(ctx).firstOrNull { it.fileName == name }?.let { return it }
            // 文件被删了也把这一项还回去，让主人看见「这个字体没了」而不是悄悄变样；
            // 真正取 Typeface 时 loadFile 会失败并退回系统字体
            return SpaceFont.Imported(name, displayNameOf(ctx, name))
        }
        return SpaceFont.Default
    }

    /** 把字体对象转成存进 prefs 的字符串 */
    fun serialize(font: SpaceFont): String = when (font) {
        SpaceFont.Default -> ""
        is SpaceFont.Builtin -> font.name
        is SpaceFont.Imported -> FILE_PREFIX + font.fileName
    }

    /** 导入字体的 prefs 前缀，用来跟内置字体的枚举名区分开 */
    const val FILE_PREFIX = "file:"

    /** 认扩展名。ttc 是字体集合，Android 13+ 才认，低版本会加载失败退回系统字体 */
    fun isFontName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")
    }

    /** 清缓存（删字体、换字体后用） */
    @Synchronized
    fun clearCache() = cache.clear()

    /**
     * 读字体里的「名字表」，拿到字体自己声明的名字，用来给导入的字体起显示名。
     *
     * 优先挑**中文**的家族名（platformID=3 encodingID=1，langID=0x0804 简体中文）——
     * 中文字体里英文名常常是「LXGW WenKai」这种，主人认不出来；中文名才是
     * 「霞鹜文楷」。中文名没有才退回英文名，再没有就用文件名。
     *
     * 拿不到（字体损坏、格式怪）就返回 null，让调用方用文件名兜底。
     */
    fun readFamilyName(file: File): String? = try {
        parseFamilyName(file)
    } catch (t: Throwable) {
        null
    }

    /**
     * 极简 sfnt / name 表解析，只为拿字体家族名。
     *
     * 打分规则：中文(简/繁) +8、nameId=16(排版家族名) +4、Windows 平台 +2。
     * 这样「霞鹜文楷」会赢过「LXGW WenKai」。
     */
    private fun parseFamilyName(file: File): String? = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val tag = ByteArray(4).also { raf.readFully(it) }
            val isTtc = tag.contentEquals(byteArrayOf(0x74, 0x74, 0x63, 0x66)) // "ttcf"
            val tableDirOffset: Long = if (isTtc) {
                raf.readInt()           // version
                raf.readInt()           // numFonts
                raf.readInt().toLong()  // 第一个字体在文件里的偏移
            } else 0L
            raf.seek(tableDirOffset + 4)
            val numTables = raf.readUnsignedShort()
            raf.seek(tableDirOffset + 12)
            var nameOffset = -1L
            repeat(numTables) {
                val t = ByteArray(4).also { raf.readFully(it) }
                raf.readInt() // checksum
                val off = raf.readInt().toLong() and 0xFFFFFFFFL
                raf.readInt() // length
                if (t.contentEquals(byteArrayOf(0x6E, 0x61, 0x6D, 0x65))) { // "name"
                    nameOffset = off
                }
            }
            if (nameOffset < 0) return@use null
            raf.seek(nameOffset)
            raf.readUnsignedShort() // format
            val count = raf.readUnsignedShort()
            val stringOffset = raf.readUnsignedShort()
            val base = nameOffset + stringOffset
            var best: String? = null
            var bestScore = -1
            repeat(count) {
                val platform = raf.readUnsignedShort()
                raf.readUnsignedShort() // encoding
                val language = raf.readUnsignedShort()
                val nameId = raf.readUnsignedShort()
                val len = raf.readUnsignedShort()
                val off = raf.readUnsignedShort()
                // nameId 1 = 家族名，16 = 排版家族名（更规整，略微优先）
                if (nameId != 1 && nameId != 16) return@repeat
                if (len <= 0) return@repeat
                // 中文语言：0x0804 简体、0x0404 繁体、0x0C04 香港、0x1004 新加坡
                val isZh = platform == 3 && language in
                    intArrayOf(0x0804, 0x0404, 0x0C04, 0x1004)
                val score = (if (isZh) 8 else 0) +
                    (if (nameId == 16) 4 else 0) +
                    (if (platform == 3) 2 else 0)
                if (score <= bestScore) return@repeat
                val cur = raf.filePointer
                raf.seek(base + off)
                val bytes = ByteArray(len).also { raf.readFully(it) }
                val s = if (platform == 3 || platform == 0) {
                    String(bytes, Charsets.UTF_16BE)
                } else {
                    String(bytes, Charsets.ISO_8859_1)
                }.replace("\u0000", "").trim()
                // 只接受像人名的：太过诡异（一堆控制字符）的跳过
                if (s.isNotEmpty() && s.none { it.isISOControl() }) {
                    best = s
                    bestScore = score
                }
                raf.seek(cur)
            }
            best
        }
    } catch (t: Throwable) {
        null
    }
}
