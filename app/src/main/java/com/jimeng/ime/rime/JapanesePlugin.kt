package com.jimeng.ime.rime

import android.content.Context
import android.system.Os
import android.util.Log
import com.jimeng.ime.kb.core.LanguageData
import java.io.File

/**
 * 商店插件：日本語（ローマ字入力）。
 *
 * ── 为什么不在 APK 里 ─────────────────────────────────────
 * 词库 37MB（mozc 31MB + jmdict 6.7MB）。打进 APK 等于让所有装输入法的人
 * 先吞下这份空间，而绝大多数人不用日语。所以它和商店里的方案一样走
 * 「用户自己点下载 → 落到 `filesDir/store/japanese` → 插件页点启用」。
 * APK 里一个字节都不带。
 *
 * ── 为什么不能走 SchemeManager 那套 ────────────────────────
 * [SchemeManager] 是**互斥**设计：同一时间只有一套方案在场，因为四套商店方案
 * 有 50 多个同名文件、版本各不相同，不可能共存。
 *
 * 日语不是「换一套方案」，是「在现有方案之外多一档语言」。按互斥那套走，
 * 启用日语就等于把雾凇顶掉了。所以它落在 librime 的 **user 目录**
 * （`filesDir/rime/user`），完全不碰 shared：
 *
 * · user 目录优先级**高于** shared —— librime 的 source resolver root 是
 *   user、fallback 才是 shared（service.cc），所以不管当前用内置雾凇还是
 *   商店方案，日语都能被找到。
 * · shared 会被 [RimeEngine.deployAssets] 整体清空重铺（换方案/升级时），
 *   日语放那儿会被静默抹掉。
 * · 用户词典本来就写在 user，放一起不冲突。
 *
 * ── 为什么用硬链接 ────────────────────────────────────────
 * store 里已经有一整份 37MB。要是再复制一份到 user，装个日语插件就吃掉 74MB。
 * 用 `Os.link()` 建硬链接：两个路径指向同一份 inode，磁盘上只有一份数据，
 * 但 librime 从 user 路径能正常读到。卸载时删链接即可，store 那份留着复用。
 *
 * minSdk 26，`android.system.Os.link` 从 API 21 就有，安全。
 *
 * ── 怎么「启用」 ──────────────────────────────────────────
 * 不改 default.yaml，而是往 user 写一份 `default.custom.yaml`：
 *
 * ```
 * patch:
 *   schema_list/+:
 *     - schema: japanese
 * ```
 *
 * librime 的 core_module 给所有配置装了 AutoPatchConfigPlugin，它会自动给
 * `default` 挂 `default.custom:/patch`（auto_patch_config_plugin.cc）。
 * 于是雾凇自己的 default.yaml 一个字节都不用动，日语就追加进方案列表。
 * 停用就是把这份补丁删掉——干净、可逆、互不影响。
 */
object JapanesePlugin {
    private const val TAG = "JimengJapanese"

    /** 插件 id，和商店条目 id、store 目录名一致。 */
    const val ID = "japanese"

    /** rime 方案 id，也是 yaml 里的 schema_id。 */
    const val SCHEMA_ID = "japanese"

    /** 显示名。 */
    const val DISPLAY_NAME = "日本語"

    private const val PREF = "jimeng_builtin_plugins"
    private const val KEY_ENABLED = "japanese_enabled"

    /**
     * 要从 store 挂到 user 的文件。
     *
     * `japanese.schema.yaml` 是方案本体，`japanese.dict.yaml` 用 import_tables
     * 把后三张表并成 `japanese` 词典（schema 里 `translator/dictionary`
     * 指的就是它）。
     */
    private val FILE_NAMES = listOf(
        "japanese.schema.yaml",
        "japanese.dict.yaml",
        "japanese.kana.dict.yaml",
        "japanese.jmdict.dict.yaml",
        "japanese.mozc.dict.yaml",
    )

    /** 记录已经挂过哪些文件的标记（内容是文件清单的拼接）。 */
    private const val VERSION_STAMP = ".japanese_linked"

    /** 追加 schema_list 的那份补丁文件名。 */
    private const val CUSTOM_NAME = "default.custom.yaml"

    /**
     * 补丁内容。
     *
     * `schema_list/+` 的 `/+` 是 librime 的「追加到列表末尾」操作
     * （config_compiler.cc 的 ADD_SUFFIX_OPERATOR）。用它是为了**不覆盖**
     * 雾凇原本那 11 条——直接写 schema_list 会把整个列表换掉。
     */
    private const val CUSTOM_BODY = """# 霁梦插件：日本語（由插件管理写入，停用即删）
patch:
  schema_list/+:
    - schema: japanese
"""

    // ---------------------------------------------------------- 目录

    /** 商店装好的那份（[StoreInstaller.installDir] 的落点）。 */
    fun storeDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "store/$ID")

    /** librime 的 user_data_dir。 */
    private fun userDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "rime/user")

    // ---------------------------------------------------------- 下载状态

    /**
     * 商店那份下载齐了吗。
     *
     * 不查 sha256（那是 [StoreInstaller] 下载时的活），只看文件在不在、
     * 大小对不对——启动路径上不该去算 37MB 的哈希。
     */
    fun isDownloaded(ctx: Context): Boolean {
        val dir = storeDir(ctx)
        if (!dir.isDirectory) return false
        return FILE_NAMES.all { f ->
            val file = File(dir, f)
            file.isFile && file.length() > 0L
        }
    }

    // ---------------------------------------------------------- 开关

    fun isEnabled(ctx: Context): Boolean = try {
        ctx.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    } catch (t: Throwable) {
        Log.w(TAG, "读开关失败，按未启用处理", t)
        false
    }

    private fun setEnabledFlag(ctx: Context, on: Boolean) {
        try {
            ctx.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, on)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "写开关失败", t)
        }
    }

    /** 已经挂到 user 了吗（不算开关，只问磁盘现状）。 */
    fun isLinked(ctx: Context): Boolean {
        val dir = userDir(ctx)
        if (!FILE_NAMES.all { File(dir, it).isFile }) return false
        return File(dir, CUSTOM_NAME).isFile
    }

    /** 真正「在用」＝ 开关开着 且 文件已挂。 */
    fun isActive(ctx: Context): Boolean = isEnabled(ctx) && isLinked(ctx)

    // ---------------------------------------------------------- 挂 / 摘

    /**
     * 把 store 里的文件挂到 user 目录，并写 `default.custom.yaml`。
     *
     * 幂等：标记对得上、文件齐，就一个字节都不写——否则每次启动都会刷新
     * mtime，librime 的 detect_modifications 看见了就重编 38MB 词库。
     *
     * @return 有没有实际改动（改动过要清部署缓存）
     */
    fun link(ctx: Context): Boolean {
        val store = storeDir(ctx)
        if (!isDownloaded(ctx)) {
            Log.w(TAG, "插件还没下载，不能启用：$store")
            return false
        }
        val dir = userDir(ctx)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.e(TAG, "建不出 user 目录：$dir")
            return false
        }
        var changed = false

        // 标记写的是「已经挂好哪些文件」，换了内容就要重挂
        val stamp = File(dir, VERSION_STAMP)
        val want = FILE_NAMES.joinToString("\n")
        val upToDate = stamp.isFile && stamp.readText() == want &&
            FILE_NAMES.all { File(dir, it).isFile }
        if (upToDate) {
            Log.i(TAG, "插件文件已挂好，跳过")
        } else {
            val t0 = System.currentTimeMillis()
            for (name in FILE_NAMES) {
                val src = File(store, name)
                val target = File(dir, name)
                if (!src.isFile) {
                    Log.e(TAG, "store 里缺文件：$name")
                    return false
                }
                // 先删旧的（硬链接或普通副本都可能在这儿）
                if (target.exists() && !target.delete()) {
                    Log.w(TAG, "旧文件删不掉：${target.name}")
                }
                // 硬链接：同一份 inode，不额外占空间。
                // 某些文件系统不支持 link 会抛异常，这时退回复制——
                // 功能不能因为链接失败就不可用。
                var linked = false
                try {
                    Os.link(src.absolutePath, target.absolutePath)
                    linked = true
                } catch (t: Throwable) {
                    Log.w(TAG, "硬链接失败，退回复制：$name", t)
                }
                if (!linked) {
                    try {
                        src.inputStream().use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output, 256 * 1024)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "复制 $name 失败", t)
                        target.delete()
                        return false
                    }
                }
            }
            stamp.writeText(want)
            changed = true
            Log.i(
                TAG,
                "挂上 ${FILE_NAMES.size} 个文件，用时 ${System.currentTimeMillis() - t0}ms"
            )
        }

        // 补丁：内容一样就不写（写一次就变 mtime，会招来一次重编）
        val custom = File(dir, CUSTOM_NAME)
        if (!custom.isFile || custom.readText() != CUSTOM_BODY) {
            try {
                custom.writeText(CUSTOM_BODY)
                changed = true
                Log.i(TAG, "写入 $CUSTOM_NAME（schema_list 追加 japanese）")
            } catch (t: Throwable) {
                Log.e(TAG, "写 $CUSTOM_NAME 失败", t)
                return false
            }
        }
        return changed
    }

    /**
     * 摘掉 user 里的链接和补丁，顺带清掉编译缓存。
     *
     * **不删 store 那份**：下载是一回事、启用是另一回事。停用后想再用，
     * 点一下启用即可，不用重新下 12.7MB。真要腾空间在插件页点「移除」，
     * 那会走 [StoreInstaller.remove] 连 store 一起清。
     *
     * 只删 japanese 自己那几个 .bin，不动整个 build 目录——否则雾凇 50MB
     * 词库要跟着白编一次。
     */
    fun unlink(ctx: Context): Boolean {
        val dir = userDir(ctx)
        var ok = true
        for (name in FILE_NAMES) {
            val f = File(dir, name)
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "删不掉 ${f.name}")
                ok = false
            }
        }
        val custom = File(dir, CUSTOM_NAME)
        if (custom.exists() && !custom.delete()) {
            Log.w(TAG, "删不掉 $CUSTOM_NAME")
            ok = false
        }
        File(dir, VERSION_STAMP).delete()
        purgeBuildCache(ctx)
        Log.i(TAG, "已摘掉日语（${if (ok) "干净" else "有残留"}）")
        return ok
    }

    // ---------------------------------------------------------- 编译缓存

    /**
     * 清掉日语方案的编译产物。
     *
     * build 里的命名是 `<name>.table.bin` / `.prism.bin` / `.reverse.bin`
     * 和 `<schema_id>.schema.yaml`，都带 japanese 前缀，按前缀删就行。
     */
    private fun purgeBuildCache(ctx: Context) {
        val build = File(ctx.applicationContext.filesDir, "rime/user/build")
        val files = build.listFiles() ?: return
        for (f in files) {
            if (!f.name.startsWith(SCHEMA_ID)) continue
            if (f.delete()) Log.i(TAG, "清掉编译缓存 ${f.name}")
        }
    }

    /**
     * 给 [RimeEngine.deployAssets] 用的版本。
     *
     * 单独开一个 public 方法是为了让引擎那边读得懂意图：这不是「删插件」，
     * 是「插件文件变了，它的编译产物作废」。上层要不要清整个 build
     * 由它自己判断（换方案时必须全清，只有插件变时只清这一份）。
     */
    fun purgeBuildCacheForEngine(ctx: Context) = purgeBuildCache(ctx)

    // ---------------------------------------------------------- 自愈

    /**
     * 让磁盘现状和开关对齐。
     *
     * 三种要修的情况：
     *   · 开关开着、store 有货、但 user 里没挂上 → 补挂
     *   · 开关关着、user 里却有残留 → 摘掉，别白占地方
     *   · 开关开着、但 store 被「移除」清空了（下载没了）→ 关开关，
     *     不然小地球键里会留一档切过去就失败的「日本語」
     *
     * 在 [RimeEngine.deployAssets] 里调，保证每次部署后状态都对。
     *
     * @return 有没有改动（改动过要清部署缓存）
     */
    fun reconcile(ctx: Context): Boolean {
        val on = isEnabled(ctx)
        val linked = isLinked(ctx)
        val downloaded = isDownloaded(ctx)
        return when {
            on && !downloaded -> {
                Log.w(TAG, "开关开着但下载没了，关掉开关")
                unlink(ctx)
                setEnabledFlag(ctx, false)
                false // unlink 自己清了缓存
            }
            on && !linked -> {
                Log.w(TAG, "开关开着但没挂上，补挂")
                link(ctx)
            }
            !on && linked -> {
                Log.w(TAG, "开关关着但文件还在，摘掉")
                unlink(ctx)
                false
            }
            else -> false
        }
    }

    /** 打开开关（不挂文件；挂由 [link] 或 [reconcile] 做）。 */
    fun enableFlag(ctx: Context) = setEnabledFlag(ctx, true)

    /** 关掉开关。 */
    fun disableFlag(ctx: Context) = setEnabledFlag(ctx, false)

    // ---------------------------------------------------------- 语言档位

    /**
     * 给小地球键循环追加的那一档；没启用就是 null。
     *
     * 单独拿一个方法出来，是为了让 [LanguageData] 不必认识
     * [JapanesePlugin]（它属于 kb/core 层，不该反向依赖 rime 层）。
     */
    fun extraLang(ctx: Context): LanguageData.Lang? =
        if (isActive(ctx)) {
            LanguageData.Lang(SCHEMA_ID, DISPLAY_NAME, "ja_JP")
        } else {
            null
        }

    // ---------------------------------------------------------- 启用 / 停用

    /**
     * 启用：写开关 + 挂文件。
     *
     * 调用方负责随后触发 [RimeEngine.reload]，让 librime 重新部署
     * （新方案的词库要编译，这一次会久一点）。
     *
     * @return 成功与否
     */
    fun activate(ctx: Context): Boolean {
        if (!isDownloaded(ctx)) {
            // 没下载就没得挂。这个分支正常不会走到——插件页在未下载时
            // 只给「下载」按钮，不给「启用」。留一手防御。
            Log.w(TAG, "还没下载，启用被拒")
            return false
        }
        enableFlag(ctx)
        if (!link(ctx)) {
            // 挂不上就把开关退回去，别留一个「显示已启用其实没文件」的假状态
            Log.e(TAG, "启用失败：文件挂不上")
            disableFlag(ctx)
            return false
        }
        Log.i(TAG, "日本語已启用")
        return true
    }

    /** 停用：摘链接 + 清缓存 + 关开关（store 那份留着）。 */
    fun deactivate(ctx: Context): Boolean {
        val ok = unlink(ctx)
        disableFlag(ctx)
        Log.i(TAG, "日本語已停用")
        return ok
    }

    /**
     * 彻底移除时调（商店里点「移除」之前）。
     *
     * 先停用再让 [StoreInstaller.remove] 清 store 目录；顺序反了的话，
     * user 里的硬链接会变成孤儿（inode 还被引用着，空间不释放）。
     */
    fun beforeRemove(ctx: Context) {
        if (isEnabled(ctx)) deactivate(ctx)
        else if (isLinked(ctx)) unlink(ctx)
    }
}