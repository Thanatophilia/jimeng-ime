/*
 * `.jmskin` 皮肤包的清单解析。
 *
 * ── 包结构 ──────────────────────────────────────────────
 *   myskin.jmskin  (就是一个 zip)
 *   ├── skin.json          清单，必须有
 *   ├── preview.png        预览图，可选
 *   ├── bg_light.png       亮色背景图，可选
 *   ├── bg_dark.png        暗色背景图，可选
 *   ├── key_light.png      亮色按键图，可选
 *   ├── key_dark.png       暗色按键图，可选
 *   └── ...                其他图，随便命名
 *
 * ── skin.json 长什么样 ──────────────────────────────────
 *   {
 *     "schema": 1,
 *     "id": "mo_ye",
 *     "name": "墨夜",
 *     "author": "作者名",
 *     "version": "1.0",
 *     "variants": {
 *       "light": { "keyboardColor": "#FAFAFA", ... },
 *       "dark":  { "keyboardColor": "#2D2D2D", ... }
 *     },
 *     "geometry": { "keyRadius": 8, "keyShadowAlpha": 0.6 },
 *     "images": {
 *       "background": { "light": "bg_light.png", "dark": "bg_dark.png" },
 *       "key":        { "light": "key_light.png",
 *                       "dark":  "key_dark.png",
 *                       "patch": { "left": 12, "top": 12, "right": 12, "bottom": 12 } },
 *       "candidateBar": "bar.png"
 *     }
 *   }
 *
 * ── 设计取舍 ────────────────────────────────────────────
 *   1. 颜色全部可选。没写的字段回落到 PixelLight/PixelDark，皮肤作者只改想改的。
 *   2. 图片可以是「单个字符串」或「亮暗两套」。单字符串 = 亮暗共用一张。
 *   3. 用 org.json（Android 内置），不引 kotlinx.serialization。
 */
package com.jimeng.ime.kb.theme

import android.graphics.Color
import org.json.JSONObject

/** 一份皮肤清单。解析完就是这个，之后合成 Theme。 */
data class SkinManifest(
    val schema: Int,
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val preview: String?,
    val light: SkinVariant?,
    val dark: SkinVariant?,
    val geometry: SkinGeometry,
    val images: SkinImages,
)

/** 一套（亮或暗）配色。null 字段表示「沿用基础主题」。 */
data class SkinVariant(
    val backgroundColor: Int? = null,
    val barColor: Int? = null,
    val keyboardColor: Int? = null,
    val keyBackgroundColor: Int? = null,
    val keyTextColor: Int? = null,
    val candidateTextColor: Int? = null,
    val candidateLabelColor: Int? = null,
    val candidateCommentColor: Int? = null,
    val altKeyBackgroundColor: Int? = null,
    val altKeyTextColor: Int? = null,
    val accentKeyBackgroundColor: Int? = null,
    val accentKeyTextColor: Int? = null,
    val keyPressHighlightColor: Int? = null,
    val keyShadowColor: Int? = null,
    val popupBackgroundColor: Int? = null,
    val popupTextColor: Int? = null,
    val spaceBarColor: Int? = null,
    val dividerColor: Int? = null,
    val clipboardEntryColor: Int? = null,
    val genericActiveBackgroundColor: Int? = null,
    val genericActiveForegroundColor: Int? = null,
)

/** 几何覆盖。null = 用 ThemePrefs 的设置 */
data class SkinGeometry(
    val keyRadius: Float? = null,
    val keyShadowAlpha: Float? = null,
)

/** 图片声明。每项可以是「共用一张」或「亮暗各一张」。 */
data class SkinImages(
    val background: SidedImage? = null,
    val key: SidedImage? = null,
    val candidateBar: SidedImage? = null,
    val bar: SidedImage? = null,
    val spaceBar: SidedImage? = null,
)

/** 一张图，可能区分亮暗。patch / alpha / tile 是共用参数。 */
data class SidedImage(
    val light: String? = null,
    val dark: String? = null,
    val patch: NinePatchInsets? = null,
    val alpha: Float = 1f,
    val tile: Boolean = false,
) {
    fun fileFor(isDark: Boolean): String? = if (isDark) (dark ?: light) else (light ?: dark)
}

/** 解析结果：要么成功，要么带一组错误（不抛异常，方便界面展示）。 */
sealed interface SkinParseResult {
    data class Ok(val manifest: SkinManifest) : SkinParseResult
    data class Fail(val errors: List<String>) : SkinParseResult
}

object SkinParser {

    /** 当前支持的 schema 版本。老包用新版本 APP 打开要能提示。 */
    const val CURRENT_SCHEMA = 1

    /**
     * 解析 skin.json。
     *
     * @param json     skin.json 的完整文本
     * @param zipEntries zip 里实际存在的条目名集合，用于校验图片是否存在
     */
    fun parse(json: String, zipEntries: Set<String>): SkinParseResult {
        val errors = mutableListOf<String>()
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return SkinParseResult.Fail(listOf("skin.json 不是合法的 JSON：${e.message}"))
        }

        val schema = root.optInt("schema", 0)
        if (schema == 0) errors += "缺少 schema 字段"
        else if (schema > CURRENT_SCHEMA) {
            errors += "这个皮肤需要更新的版本（schema=$schema，本机支持 $CURRENT_SCHEMA）"
        }

        val id = root.optString("id").trim()
        if (id.isEmpty()) errors += "缺少 id 字段"
        else if (!id.matches(Regex("[A-Za-z0-9_.-]{1,48}"))) {
            errors += "id 只能用字母、数字、下划线、点、短横线，长度 1–48"
        }

        val name = root.optString("name").trim()
        if (name.isEmpty()) errors += "缺少 name 字段"

        val variants = root.optJSONObject("variants")
        if (variants == null) {
            errors += "缺少 variants 字段"
        } else if (variants.optJSONObject("light") == null && variants.optJSONObject("dark") == null) {
            errors += "variants 里至少要有一套配色（light 或 dark）"
        }

        val light = variants?.optJSONObject("light")?.let { parseVariant(it, "light", errors) }
        val dark = variants?.optJSONObject("dark")?.let { parseVariant(it, "dark", errors) }

        val geometry = root.optJSONObject("geometry")?.let { g ->
            SkinGeometry(
                keyRadius = g.optDoubleOrNull("keyRadius")?.toFloat()?.also {
                    if (it < 0f || it > 40f) errors += "geometry.keyRadius 超出范围（0–40）"
                },
                keyShadowAlpha = g.optDoubleOrNull("keyShadowAlpha")?.toFloat()?.also {
                    if (it < 0f || it > 1f) errors += "geometry.keyShadowAlpha 超出范围（0–1）"
                },
            )
        } ?: SkinGeometry()

        val images = parseImages(root.optJSONObject("images"), zipEntries, errors)

        val preview = root.optString("preview").takeIf { it.isNotBlank() }?.also {
            if (it !in zipEntries) errors += "预览图不存在：$it"
        }

        if (errors.isNotEmpty()) return SkinParseResult.Fail(errors)

        return SkinParseResult.Ok(
            SkinManifest(
                schema = schema,
                id = id,
                name = name,
                author = root.optString("author").ifBlank { "未署名" },
                version = root.optString("version").ifBlank { "1.0" },
                preview = preview,
                light = light,
                dark = dark,
                geometry = geometry,
                images = images,
            )
        )
    }

    // ---------------------------------------------------------------- fcitx5 兼容

    /**
     * 把一个 fcitx5 的单文件主题（`xxx.json`）翻译成我们的清单。
     *
     * ── 为什么要支持这个 ────────────────────────────────────
     * fcitx5 主题满网都是，格式就是一个 JSON：22 个颜色字段 + name
     * + isDark，颜色是**带符号 int**（比如 -3091495 = 0xFFD0DCD9）。
     * 我们的 Theme 本来就从 fcitx5 抄的字段名，所以一一对上，不需要
     * 转换颜色空间，直接搬数字就行。
     *
     * 用户拿到一个 `仿iOS.zip` 里面只有 `仿iOS.json`，本来就该能导入。
     *
     * ── 单文件主题怎么落到我们的模型上 ──────────────────────
     * 我们支持「亮暗两套」，fcitx5 单文件只有一套。所以按 isDark
     * 放进对应那一格，另一格留空——buildTheme() 发现对面是空的，
     * 会拿这一套顶上，两个模式都能用。
     *
     * @param fileName 原文件名（含 .json），用来推 id 和名字
     * @param entries  zip 里实际存在的条目，用于校验背景图
     * @param takenIds 已经占用的 id，避免覆盖内置皮肤
     */
    fun parseFcitx5(
        json: String,
        fileName: String,
        entries: Set<String>,
        takenIds: Set<String> = emptySet(),
    ): SkinParseResult {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return SkinParseResult.Fail(listOf("$fileName 不是合法的 JSON：${e.message}"))
        }

        // 判定依据：得有一批我们认识的颜色键，否则这不是主题文件
        val known = listOf(
            "keyboardColor", "keyBackgroundColor", "keyTextColor",
            "barColor", "spaceBarColor", "backgroundColor",
        )
        if (known.none { root.has(it) }) {
            return SkinParseResult.Fail(
                listOf("$fileName 里找不到皮肤配色字段（keyboardColor 之类），不像是主题文件")
            )
        }

        val stem = fileName.substringBeforeLast('.').ifBlank { "imported" }
        val name = root.optString("name").trim().ifBlank { stem }
        val isDark = root.optBoolean("isDark", false)

        // id 只能用 ASCII 字母数字下划线点短横线。中文名（仿iOS）会把中文
        // 剔掉，剩 "iOS"；整个被剔空就退回 imported。
        val sanitized = stem.map { c ->
            if (c.code < 128 && (c.isLetterOrDigit() || c == '_' || c == '-' || c == '.')) c else '_'
        }.joinToString("").trim('_', '.', '-').take(40)
        var id = sanitized.ifBlank { "imported" }
        if (!id[0].isLetterOrDigit()) id = "t_$id"
        // 别撞上已有的（尤其是内置皮肤，同 id 会被列表跳过）
        if (id in takenIds) {
            var n = 2
            val base = id
            while ("${base}_$n" in takenIds) n++
            id = "${base}_$n"
        }

        val variant = SkinVariant(
            backgroundColor = root.rawColor("backgroundColor"),
            barColor = root.rawColor("barColor"),
            keyboardColor = root.rawColor("keyboardColor"),
            keyBackgroundColor = root.rawColor("keyBackgroundColor"),
            keyTextColor = root.rawColor("keyTextColor"),
            candidateTextColor = root.rawColor("candidateTextColor"),
            candidateLabelColor = root.rawColor("candidateLabelColor"),
            candidateCommentColor = root.rawColor("candidateCommentColor"),
            altKeyBackgroundColor = root.rawColor("altKeyBackgroundColor"),
            altKeyTextColor = root.rawColor("altKeyTextColor"),
            accentKeyBackgroundColor = root.rawColor("accentKeyBackgroundColor"),
            accentKeyTextColor = root.rawColor("accentKeyTextColor"),
            keyPressHighlightColor = root.rawColor("keyPressHighlightColor"),
            keyShadowColor = root.rawColor("keyShadowColor"),
            popupBackgroundColor = root.rawColor("popupBackgroundColor"),
            popupTextColor = root.rawColor("popupTextColor"),
            spaceBarColor = root.rawColor("spaceBarColor"),
            dividerColor = root.rawColor("dividerColor"),
            clipboardEntryColor = root.rawColor("clipboardEntryColor"),
            genericActiveBackgroundColor = root.rawColor("genericActiveBackgroundColor"),
            genericActiveForegroundColor = root.rawColor("genericActiveForegroundColor"),
        )

        // 背景图：fcitx5 是单个字符串，存在就用
        val bg = root.optString("backgroundImage").takeIf { it.isNotBlank() && it != "null" }
        val bgOk = bg?.takeIf { it in entries }
        val images = if (bgOk != null) {
            SkinImages(background = SidedImage(light = bgOk, dark = bgOk))
        } else {
            SkinImages()
        }

        val preview = root.optString("preview").takeIf { it.isNotBlank() && it in entries }

        return SkinParseResult.Ok(
            SkinManifest(
                schema = CURRENT_SCHEMA,
                id = id,
                name = name,
                author = root.optString("author").trim().ifBlank { "fcitx5 主题" },
                version = root.optString("version").trim().ifBlank { "1.0" },
                preview = preview,
                light = if (isDark) null else variant,
                dark = if (isDark) variant else null,
                geometry = SkinGeometry(),
                images = images,
            )
        )
    }

    /**
     * 读一个颜色字段，兼容两种写法：
     *   · 数字：-3091495（fcitx5 原生写法，就是 ARGB 的带符号 int）
     *   · 字符串："#FFD0DCD9"（我们的写法）
     */
    private fun JSONObject.rawColor(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Int -> v
            is Number -> v.toInt()
            is String -> {
                val s = v.trim()
                if (s.isEmpty()) null
                else try {
                    Color.parseColor(s)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            else -> null
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun parseVariant(o: JSONObject, tag: String, errors: MutableList<String>) = SkinVariant(
        backgroundColor = o.color("backgroundColor", tag, errors),
        barColor = o.color("barColor", tag, errors),
        keyboardColor = o.color("keyboardColor", tag, errors),
        keyBackgroundColor = o.color("keyBackgroundColor", tag, errors),
        keyTextColor = o.color("keyTextColor", tag, errors),
        candidateTextColor = o.color("candidateTextColor", tag, errors),
        candidateLabelColor = o.color("candidateLabelColor", tag, errors),
        candidateCommentColor = o.color("candidateCommentColor", tag, errors),
        altKeyBackgroundColor = o.color("altKeyBackgroundColor", tag, errors),
        altKeyTextColor = o.color("altKeyTextColor", tag, errors),
        accentKeyBackgroundColor = o.color("accentKeyBackgroundColor", tag, errors),
        accentKeyTextColor = o.color("accentKeyTextColor", tag, errors),
        keyPressHighlightColor = o.color("keyPressHighlightColor", tag, errors),
        keyShadowColor = o.color("keyShadowColor", tag, errors),
        popupBackgroundColor = o.color("popupBackgroundColor", tag, errors),
        popupTextColor = o.color("popupTextColor", tag, errors),
        spaceBarColor = o.color("spaceBarColor", tag, errors),
        dividerColor = o.color("dividerColor", tag, errors),
        clipboardEntryColor = o.color("clipboardEntryColor", tag, errors),
        genericActiveBackgroundColor = o.color("genericActiveBackgroundColor", tag, errors),
        genericActiveForegroundColor = o.color("genericActiveForegroundColor", tag, errors),
    )

    /**
     * 解析一个颜色。支持：
     *   "#RRGGBB"      → 不透明
     *   "#AARRGGBB"    → 带透明度
     *   "#RGB" / "#ARGB" → 简写
     * 数字（比如 0.12）当透明度处理不了，直接报错。
     */
    private fun JSONObject.color(key: String, tag: String, errors: MutableList<String>): Int? {
        if (!has(key) || isNull(key)) return null
        val raw = optString(key).trim()
        return try {
            Color.parseColor(raw)
        } catch (e: IllegalArgumentException) {
            errors += "$tag.$key 颜色格式不对：$raw（要 #RRGGBB 或 #AARRGGBB）"
            null
        }
    }

    private fun parseImages(
        o: JSONObject?,
        entries: Set<String>,
        errors: MutableList<String>,
    ): SkinImages {
        if (o == null) return SkinImages()
        return SkinImages(
            background = side(o, "background", entries, errors),
            key = side(o, "key", entries, errors),
            candidateBar = side(o, "candidateBar", entries, errors),
            bar = side(o, "bar", entries, errors),
            spaceBar = side(o, "spaceBar", entries, errors),
        )
    }

    /** 一个图片项可以是 "a.png"，也可以是 {"light":..., "dark":..., "patch":{...}} */
    private fun side(
        parent: JSONObject,
        key: String,
        entries: Set<String>,
        errors: MutableList<String>,
    ): SidedImage? {
        if (!parent.has(key) || parent.isNull(key)) return null

        // 形式一：纯字符串，亮暗共用
        if (parent.opt(key) is String) {
            val f = parent.optString(key).trim()
            if (f.isEmpty()) return null
            if (f !in entries) errors += "images.$key 指向的图不存在：$f"
            return SidedImage(light = f, dark = f)
        }

        // 形式二：对象
        val ob = parent.optJSONObject(key)
            ?: run { errors += "images.$key 格式不对"; return null }

        val lighter = ob.optString("light").takeIf { it.isNotBlank() }
        val darker = ob.optString("dark").takeIf { it.isNotBlank() }
        if (lighter == null && darker == null) {
            errors += "images.$key 至少要给 light 或 dark"
            return null
        }
        lighter?.let { if (it !in entries) errors += "images.$key.light 图不存在：$it" }
        darker?.let { if (it !in entries) errors += "images.$key.dark 图不存在：$it" }

        val patch = ob.optJSONObject("patch")?.let { p ->
            NinePatchInsets(
                left = p.optInt("left", 0),
                top = p.optInt("top", 0),
                right = p.optInt("right", 0),
                bottom = p.optInt("bottom", 0),
            )
        }

        return SidedImage(
            light = lighter,
            dark = darker,
            patch = patch,
            alpha = ob.optDoubleOrNull("alpha")?.toFloat()?.coerceIn(0f, 1f) ?: 1f,
            tile = ob.optBoolean("tile", false),
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key, Double.NaN).takeUnless { it.isNaN() } else null
}