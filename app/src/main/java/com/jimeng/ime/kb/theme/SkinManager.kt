/*
 * 皮肤的总管。
 *
 * ── 它管什么 ────────────────────────────────────────────
 *   1. 发现：内置的（assets/skins/）+ 导入的（files/skins/）
 *   2. 安装：把 .jmskin 解压到 files/skins/<id>/，校验不过就回滚
 *   3. 合成：把 SkinManifest 的配色套到基础主题上，得到可直接用的 Theme
 *   4. 记住：选中的皮肤 id 存进 ThemePrefs，重启还在
 *   5. 清理：删皮肤时连文件带缓存一起清
 *
 * ── 皮肤放哪 ────────────────────────────────────────────
 *   内置：assets/skins/<id>/skin.json + 图
 *   导入：files/skins/<id>/skin.json + 图
 * 两处结构完全一样，所以加载逻辑只有一份。
 *
 * ── 安装为什么要先解压到临时目录 ────────────────────────
 * 用户给的 zip 可能有毒：路径穿越（../../）、超大解压炸弹、缺文件。
 * 所以先解到 <id>.tmp/，全部校验通过再改名成正式目录；
 * 中途任何一步失败，删掉临时目录，用户的皮肤列表不受影响。
 */
package com.jimeng.ime.kb.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object SkinManager {

    private const val TAG = "JMSKIN"

    /** 皮肤包装载后的完整信息 */
    data class SkinEntry(
        /** 唯一标识，也是目录名 */
        val id: String,
        /** 显示名 */
        val name: String,
        val author: String,
        val version: String,
        /** 是不是内置皮肤（内置的不能删） */
        val builtin: Boolean,
        /** 亮色主题。皮肤只提供暗色时为 null */
        val light: Theme?,
        /** 暗色主题。皮肤只提供亮色时为 null */
        val dark: Theme?,
        /** 预览图的绝对路径，可能为空 */
        val previewFile: File?,
        /** 解析过程中的警告（不致命，比如某张图确实） */
        val warnings: List<String>,
    )

    /** 安装结果 */
    sealed interface InstallResult {
        data class Ok(val entry: SkinEntry) : InstallResult
        data class Fail(val errors: List<String>) : InstallResult
    }

    // ---------------------------------------------------------------- 目录

    private fun importedRoot(ctx: Context) = File(ctx.filesDir, "skins")

    private fun importedDir(ctx: Context, id: String) = File(importedRoot(ctx), id)

    /** 内置皮肤的 assets 前缀 */
    private fun assetPrefix(id: String) = "skins/$id"

    // ---------------------------------------------------------------- 发现

    /**
     * 列出全部可用皮肤。内置在前，导入在后，各自按名字排序。
     *
     * 单个皮肤坏掉不影响其他皮肤：解析失败的会被跳过并打日志。
     */
    fun list(ctx: Context): List<SkinEntry> {
        val out = mutableListOf<SkinEntry>()

        // 1) 内置
        val builtinIds = try {
            ctx.assets.list("skins")?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (id in builtinIds.sorted()) {
            loadBuiltin(ctx, id)?.let { out += it }
        }

        // 2) 导入
        val dir = importedRoot(ctx)
        val importedIds = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        for (id in importedIds.sorted()) {
            // 内置已经有的 id，跳过导入的同名皮肤，避免列表里出现两个
            if (id in builtinIds) {
                Log.w(TAG, "导入皮肤与内置皮肤同 id，跳过：$id")
                continue
            }
            loadImported(ctx, id)?.let { out += it }
        }

        return out
    }

    /** 按 id 找一个皮肤 */
    fun find(ctx: Context, id: String): SkinEntry? =
        list(ctx).firstOrNull { it.id == id }

    private fun loadBuiltin(ctx: Context, id: String): SkinEntry? = try {
        val json = ctx.assets.open("${assetPrefix(id)}/skin.json")
            .bufferedReader().use { it.readText() }
        val entries = listAssetEntries(ctx, assetPrefix(id))
        buildEntry(ctx, id, json, entries, builtin = true, baseDir = null)
    } catch (e: Exception) {
        Log.w(TAG, "内置皮肤解析失败：$id — ${e.message}")
        null
    }

    private fun loadImported(ctx: Context, id: String): SkinEntry? = try {
        val dir = importedDir(ctx, id)
        val json = File(dir, "skin.json").readText()
        val entries = dir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toSet()
        buildEntry(ctx, id, json, entries, builtin = false, baseDir = dir)
    } catch (e: Exception) {
        Log.w(TAG, "导入皮肤解析失败：$id — ${e.message}")
        null
    }

    /** assets 下某个皮肤目录里的全部文件（递归），返回相对路径集合 */
    private fun listAssetEntries(ctx: Context, prefix: String): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(path: String) {
            val names = try {
                ctx.assets.list(path).orEmpty()
            } catch (e: Exception) {
                return
            }
            for (n in names) {
                val child = "$path/$n"
                // assets.list 对目录返回子项名，对文件返回空数组
                if (ctx.assets.list(child).isNullOrEmpty()) {
                    out += child.removePrefix("$prefix/")
                } else {
                    walk(child)
                }
            }
        }
        walk(prefix)
        return out
    }

    /** 解析 + 合成，两个来源共用 */
    private fun buildEntry(
        ctx: Context,
        id: String,
        json: String,
        entries: Set<String>,
        builtin: Boolean,
        baseDir: File?,
    ): SkinEntry? {
        val parsed = SkinParser.parse(json, entries)
        val manifest = when (parsed) {
            is SkinParseResult.Ok -> parsed.manifest
            is SkinParseResult.Fail -> {
                Log.w(TAG, "皮肤 $id 校验不过：${parsed.errors.joinToString("; ")}")
                return null
            }
        }

        // id 必须和目录名一致，防止包里的 id 瞎写导致选不中
        if (manifest.id != id) {
            Log.w(TAG, "皮肤 $id 的清单里 id=${manifest.id}，与目录名不一致，按目录名")
        }

        return SkinEntry(
            id = id,
            name = manifest.name,
            author = manifest.author,
            version = manifest.version,
            builtin = builtin,
            light = buildTheme(ctx, id, manifest, isDark = false),
            dark = buildTheme(ctx, id, manifest, isDark = true),
            // 内置皮肤的图在 assets 里，没有 File 可给，预览只能留空；
            // 设置页拿不到 previewFile 就画个配色色块代替。
            previewFile = manifest.preview?.let { p ->
                val dir = baseDir ?: return@let null
                File(dir, p).takeIf { it.isFile }
            },
            warnings = emptyList(),
        )
    }

    /**
     * 把皮肤配色套到基础主题上，合成一个能直接用的 Theme。
     *
     * 规则：
     *   · 皮肤没写的颜色 → 用 PixelLight / PixelDark 的对应值
     *   · 皮肤只给了亮色但系统是暗色 → 拿亮色当暗色用（总比没有强）
     */
    private fun buildTheme(
        ctx: Context,
        id: String,
        m: SkinManifest,
        isDark: Boolean,
    ): Theme? {
        // 该皮肤有没有这一套
        val variant = if (isDark) m.dark else m.light
        val fallbackVariant = if (isDark) m.light else m.dark
        val v = variant ?: fallbackVariant ?: return null

        // 配色底座：优先同明暗的 Pixel，没有就拿另一个
        val base = if (isDark) Theme.PixelDark else Theme.PixelLight

        val imageFor = { side: SidedImage? ->
            side?.let { s ->
                s.fileFor(isDark)?.let { file ->
                    SkinImage(
                        path = file,
                        patch = s.patch,
                        alpha = s.alpha,
                        tile = s.tile,
                    )
                }
            }
        }

        return Theme(
            name = m.name,
            isDark = isDark,
            skinId = id,

            backgroundColor = v.backgroundColor ?: base.backgroundColor,
            barColor = v.barColor ?: base.barColor,
            keyboardColor = v.keyboardColor ?: base.keyboardColor,
            keyBackgroundColor = v.keyBackgroundColor ?: base.keyBackgroundColor,
            keyTextColor = v.keyTextColor ?: base.keyTextColor,
            candidateTextColor = v.candidateTextColor ?: base.candidateTextColor,
            candidateLabelColor = v.candidateLabelColor ?: base.candidateLabelColor,
            candidateCommentColor = v.candidateCommentColor ?: base.candidateCommentColor,
            altKeyBackgroundColor = v.altKeyBackgroundColor ?: base.altKeyBackgroundColor,
            altKeyTextColor = v.altKeyTextColor ?: base.altKeyTextColor,
            accentKeyBackgroundColor = v.accentKeyBackgroundColor ?: base.accentKeyBackgroundColor,
            accentKeyTextColor = v.accentKeyTextColor ?: base.accentKeyTextColor,
            keyPressHighlightColor = v.keyPressHighlightColor ?: base.keyPressHighlightColor,
            keyShadowColor = v.keyShadowColor ?: base.keyShadowColor,
            popupBackgroundColor = v.popupBackgroundColor ?: base.popupBackgroundColor,
            popupTextColor = v.popupTextColor ?: base.popupTextColor,
            spaceBarColor = v.spaceBarColor ?: base.spaceBarColor,
            dividerColor = v.dividerColor ?: base.dividerColor,
            clipboardEntryColor = v.clipboardEntryColor ?: base.clipboardEntryColor,
            genericActiveBackgroundColor = v.genericActiveBackgroundColor ?: base.genericActiveBackgroundColor,
            genericActiveForegroundColor = v.genericActiveForegroundColor ?: base.genericActiveForegroundColor,

            backgroundImage = imageFor(m.images.background),
            keyImage = imageFor(m.images.key),
            candidateBarImage = imageFor(m.images.candidateBar),
            barImage = imageFor(m.images.bar),
            spaceBarImage = imageFor(m.images.spaceBar),
            keyRadiusOverride = m.geometry.keyRadius,
            keyShadowAlpha = m.geometry.keyShadowAlpha,
        )
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 从 .jmskin 安装。
     *
     * @param input   zip 的输入流（SAF 的 content:// 打开就有）
     * @param maxSize 解压后总字节上限，防止解压炸弹。默认 32MB
     */
    fun install(ctx: Context, input: InputStream, maxSize: Long = 32L * 1024 * 1024): InstallResult {
        val tmpRoot = File(ctx.filesDir, "skins_tmp")
        tmpRoot.deleteRecursively()
        if (!tmpRoot.mkdirs()) {
            return InstallResult.Fail(listOf("建不了临时目录"))
        }

        val errors = mutableListOf<String>()
        var totalBytes = 0L
        var skinJson: String? = null
        val entries = mutableSetOf<String>()

        // ---- 第一遍：安全解压到临时目录，同时记下条目清单 ----
        try {
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name

                    // 拒绝路径穿越和绝对路径
                    if (name.contains("..") || name.startsWith("/") || name.contains('\\')) {
                        errors += "包里有不安全的路径：$name"
                        break
                    }
                    if (entry.isDirectory) continue

                    val out = File(tmpRoot, name)
                    // 防御性检查：解出来的路径必须还在 tmpRoot 里
                    if (!out.canonicalPath.startsWith(tmpRoot.canonicalPath)) {
                        errors += "包里有越界路径：$name"
                        break
                    }
                    out.parentFile?.mkdirs()

                    val buf = ByteArray(8192)
                    out.outputStream().use { os ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            totalBytes += n
                            if (totalBytes > maxSize) {
                                errors += "解压后超过 ${maxSize / 1024 / 1024}MB 上限"
                                break
                            }
                            os.write(buf, 0, n)
                        }
                    }
                    if (errors.isNotEmpty()) break

                    entries += name
                    if (name == "skin.json") {
                        skinJson = out.readText()
                    }
                }
            }
        } catch (e: Exception) {
            errors += "解压失败：${e.message}"
        }

        if (errors.isEmpty() && skinJson == null) {
            errors += "包里没有 skin.json"
        }

        // ---- 校验 ----
        var manifest: SkinManifest? = null
        if (errors.isEmpty() && skinJson != null) {
            when (val parsed = SkinParser.parse(skinJson, entries)) {
                is SkinParseResult.Ok -> {
                    manifest = parsed.manifest
                    // 图片能不能解出来，也顺手验一遍（只验不清缓存）
                    val bad = checkImages(tmpRoot, parsed.manifest, entries)
                    if (bad.isNotEmpty()) errors += bad
                }
                is SkinParseResult.Fail -> errors += parsed.errors
            }
        }

        if (errors.isNotEmpty() || manifest == null) {
            tmpRoot.deleteRecursively()
            return InstallResult.Fail(errors)
        }

        // ---- 落位：先删同名旧皮肤，再把临时目录改名过去 ----
        val id = manifest.id
        val dest = importedDir(ctx, id)
        if (dest.exists()) dest.deleteRecursively()

        val ok = tmpRoot.renameTo(dest)
        if (!ok) {
            // renameTo 跨文件系统会失败，退回复制
            tmpRoot.copyRecursively(dest, overwrite = true)
            tmpRoot.deleteRecursively()
        }

        SkinCache.evict(id)

        val entry = loadImported(ctx, id)
            ?: return InstallResult.Fail(listOf("装好了但读不出来，皮肤包可能有问题"))

        return InstallResult.Ok(entry)
    }

    /** 校验图片都能解出来。返回错误列表（空 = 全好） */
    private fun checkImages(
        root: File,
        m: SkinManifest,
        entries: Set<String>,
    ): List<String> {
        val errors = mutableListOf<String>()
        val sides = listOf(
            "background" to m.images.background,
            "key" to m.images.key,
            "candidateBar" to m.images.candidateBar,
            "bar" to m.images.bar,
            "spaceBar" to m.images.spaceBar,
        )
        for ((label, side) in sides) {
            if (side == null) continue
            val files = listOfNotNull(side.light, side.dark).toSet()
            for (f in files) {
                if (f !in entries) {
                    errors += "images.$label 的图不在包里：$f"
                    continue
                }
                val file = File(root, f)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try {
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                } catch (e: Exception) {
                    errors += "images.$label 的图解不出来：$f"
                    continue
                }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                    errors += "images.$label 不是有效图片：$f"
                    continue
                }
                // 九宫格切边不能超过图片尺寸
                val p = side.patch
                if (p != null && !p.isSane(opts.outWidth, opts.outHeight)) {
                    errors += "images.$label 的九宫格切边超过了图片尺寸（${opts.outWidth}×${opts.outHeight}）"
                }
            }
        }
        return errors
    }

    // ---------------------------------------------------------------- 删除

    /** 删一个导入的皮肤。内置的删不掉。 */
    fun delete(ctx: Context, id: String): Boolean {
        val entry = find(ctx, id) ?: return false
        if (entry.builtin) return false

        SkinCache.evict(id)
        val dir = importedDir(ctx, id)
        val ok = dir.deleteRecursively()

        // 删的正好是当前在用的，切回内置默认
        if (ok && ThemeManager.currentSkinId() == id) {
            ThemeManager.resetToSystem()
        }
        return ok
    }
}