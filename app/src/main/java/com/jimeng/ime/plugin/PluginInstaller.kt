package com.jimeng.ime.plugin

import android.content.Context
import android.util.Log
import com.jimeng.ime.StoreData
import com.jimeng.ime.StoreInstaller
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 本地插件包导入。
 *
 * ── 一个包长什么样 ──────────────────────────────────────
 * 用户拿到的是一份 zip，解开后根目录必须有 `manifest.json`：
 *
 * ```
 * JiemengClipboard.zip
 * ├── manifest.json      ← 必须有，说明这是什么、要什么
 * ├── README.md          ← 可选，给人看
 * └── assets/
 *     └── icon.png       ← 可选，图标
 * ```
 *
 * 允许 zip 外面多套一层目录（很多人用「右键压缩」压出来就是那样），
 * 这里会自动往下探一层找 manifest.json——`JiemengClipboard/manifest.json`
 * 和 `manifest.json` 同样认。
 *
 * ── 装到哪 ──────────────────────────────────────────────
 * `filesDir/store/<id>/`，和商店下载的东西放在一起。这样它天然能用上
 * [StoreInstaller.state] 的状态判断、插件管理页的列表、移除逻辑——
 * 不用为「本地的」和「商店的」各写一套。
 *
 * ── 安全 ────────────────────────────────────────────────
 *   · 只解压文件，不执行任何东西（Kotlin 代码一个字节都不会跑）；
 *   · 路径里的 `..` 全被剔除（zip 是外部数据）；
 *   · 只解压白名单里的扩展名 + 根目录的说明文件。就算包里夹了 .dex / .so，
 *     不会被写进安装目录。
 *   · manifest 里的 entry 必须在注册表里查得到，否则整个包拒收。
 */
object PluginInstaller {
    private const val TAG = "JimengPlugin"

    /** 装完写的标记，和商店安装保持一致（[StoreInstaller] 也认这个名） */
    private const val MARKER = ".installed"

    /** 允许解压出来的文件类型。插件第一版只吃数据和文本，不吃代码。 */
    private val ALLOWED_EXT = setOf(
        "json", "yaml", "yml", "md", "txt", "png", "jpg", "jpeg", "webp", "svg",
    )

    /** 单文件上限，防 zip 炸弹。64MB 对纯数据插件已经很宽松了。 */
    private const val MAX_FILE = 64L * 1024 * 1024

    /** 整个包解开后的总上限 */
    private const val MAX_TOTAL = 256L * 1024 * 1024

    /** 进度回调，都在工作线程里调，界面自己 post 回主线程。 */
    fun interface Listener {
        fun onProgress(text: String)
    }

    /**
     * 检查一个 zip 能不能装，**不解压到正式目录**。
     *
     * 导入前先跑这个，把「这个包是什么、能不能装、会不会顶掉别的」
     * 一次性问清楚，再让用户点确认——比装到一半失败要好得多。
     */
    fun inspect(ctx: Context, zip: File): PluginResult<Inspect> {
        if (!zip.isFile) {
            return PluginResult.failure(PluginError("找不到这个文件"))
        }
        if (!zip.name.lowercase().endsWith(".zip")) {
            return PluginResult.failure(PluginError("只支持 .zip 格式的插件包"))
        }

        // 先只找 manifest.json，别的都不碰
        val manifestText = try {
            readManifestText(zip)
        } catch (t: Throwable) {
            Log.w(TAG, "读 zip 失败", t)
            return PluginResult.failure(PluginError("这个 zip 打不开，可能是坏的或没下完"))
        } ?: return PluginResult.failure(
            PluginError("包里没找到 ${PluginManifest.FILE_NAME}\n插件包需要一份清单说明它是什么")
        )

        val manifest = PluginManifest.parse(manifestText, zip.name).fold(
            onSuccess = { it },
            onFailure = { return PluginResult.failure(it) },
        )

        // 这个插件 apk 认不认识
        if (!PluginRegistry.isSupported(manifest.entry, manifest.type)) {
            return PluginResult.failure(
                PluginError(
                    "这个插件要「${manifest.entry}」的支持，当前版本没有\n" +
                        "认识的插件：" + PluginRegistry.knownEntries().joinToString("、")
                )
            )
        }

        val dir = StoreInstaller.installDir(ctx, manifest.id)
        val existing = StoreInstaller.state(ctx, manifest.toStoreItem())
        val upgrading = existing == StoreInstaller.State.Installed

        return PluginResult.success(
            Inspect(
                manifest = manifest,
                zip = zip,
                upgrading = upgrading,
                oldVersion = if (upgrading) readInstalledVersion(dir) else "",
            )
        )
    }

    /** 检查结果 */
    data class Inspect(
        val manifest: PluginManifest,
        val zip: File,
        /** 已经装过同一个 id（这次是升级） */
        val upgrading: Boolean,
        /** 装过的版本号，没装过就是空 */
        val oldVersion: String,
    )

    /**
     * 真正装。**阻塞**，要放后台线程。
     *
     * 顺序刻意做成「先解到临时目录 → 校验 → 再整体搬进去」，
     * 和商店安装同一套思路：正式目录里永远不会出现半成品。
     */
    fun install(ctx: Context, inspect: Inspect, listener: Listener = Listener {}): PluginResult<StoreData.Item> {
        val app = ctx.applicationContext
        val m = inspect.manifest
        val dest = StoreInstaller.installDir(app, m.id)
        val tmp = File(app.filesDir, "store/.import/${m.id}")

        try {
            tmp.deleteRecursively()
            if (!tmp.mkdirs() && !tmp.isDirectory) {
                return PluginResult.failure(PluginError("建不出临时目录，检查一下存储空间"))
            }
            listener.onProgress("正在解开插件包…")
            val count = unzipSafe(inspect.zip, tmp, listener)
            if (count <= 0) {
                tmp.deleteRecursively()
                return PluginResult.failure(PluginError("包里没有能用的文件"))
            }

            // 解完之后再找一次 manifest：可能刚才只是「探到」了，这里要确认真实存在
            PluginManifest.readFrom(tmp).fold(
                onSuccess = { },
                onFailure = {
                    tmp.deleteRecursively()
                    return PluginResult.failure(it)
                },
            )

            listener.onProgress("正在安装…")
            // 换掉正式目录。先删旧的再搬新的：升级时旧版本的残留文件
            // 不该留在新版本里（那个文件可能已经不该存在了）。
            if (dest.exists() && !dest.deleteRecursively()) {
                tmp.deleteRecursively()
                return PluginResult.failure(PluginError("旧版本删不掉，先回商店把它移除再试"))
            }
            if (!dest.parentFile?.mkdirs()!! && !dest.parentFile.isDirectory) {
                tmp.deleteRecursively()
                return PluginResult.failure(PluginError("建不出安装目录"))
            }
            if (!tmp.renameTo(dest)) {
                // 跨文件系统会 rename 失败，退回复制
                if (!copyTree(tmp, dest)) {
                    dest.deleteRecursively()
                    tmp.deleteRecursively()
                    return PluginResult.failure(PluginError("文件搬不过去，检查一下存储空间"))
                }
                tmp.deleteRecursively()
            }

            File(dest, MARKER).writeText(
                buildString {
                    appendLine("id=${m.id}")
                    appendLine("version=${m.version}")
                    appendLine("source=local")
                    appendLine("time=${System.currentTimeMillis()}")
                }
            )
            Log.i(TAG, "本地导入完成：${m.id} ${m.version}")
            return PluginResult.success(m.toStoreItem())
        } catch (t: Throwable) {
            Log.e(TAG, "导入 ${m.id} 失败", t)
            try {
                tmp.deleteRecursively()
            } catch (ignored: Throwable) {
            }
            return PluginResult.failure(
                PluginError(t.message?.takeIf { it.isNotBlank() } ?: "导入失败")
            )
        }
    }

    // ---------------------------------------------------------- zip 读取

    /**
     * 从 zip 里翻出 manifest.json 的内容。
     *
     * 认两种情况：根目录的 `manifest.json`、或者外面多套一层目录的
     * `xxx/manifest.json`（只往下看一层，够用了）。
     */
    private fun readManifestText(zip: File): String? {
        var nested: String? = null
        ZipInputStream(zip.inputStream().buffered(64 * 1024)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (e.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val base = name.substringAfterLast('/')
                if (base != PluginManifest.FILE_NAME) {
                    zis.closeEntry()
                    continue
                }
                val depth = name.count { it == '/' }
                // 根目录直接就是它；深一层先记着，万一根目录没有就用它
                if (depth == 0) {
                    val text = zis.readBytes().toString(Charsets.UTF_8)
                    zis.closeEntry()
                    return text
                }
                if (depth == 1 && nested == null) {
                    nested = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
        }
        return nested
    }

    /**
     * 安全解压。返回解出多少个文件。
     *
     * 三道闸：路径去掉 `..`、扩展名白名单、单文件和总量上限。
     */
    private fun unzipSafe(zip: File, dest: File, listener: Listener): Int {
        // 先探一遍：如果 manifest 是套在一层目录里的，就把那层剪掉
        val prefix = detectPrefix(zip)
        var total = 0L
        var count = 0
        ZipInputStream(zip.inputStream().buffered(64 * 1024)).use { zis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zis.nextEntry ?: break
                var name = e.name.replace('\\', '/')
                if (prefix.isNotEmpty()) {
                    if (!name.startsWith(prefix)) {
                        zis.closeEntry()
                        continue
                    }
                    name = name.substring(prefix.length)
                }
                if (name.isEmpty() || e.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                // 防穿越
                val safe = name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                if (safe.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val fileName = safe.last()
                val ext = fileName.substringAfterLast('.', "").lowercase()
                // 白名单：manifest / README 这类无扩展名的说明文件放行
                val allowed = ext.isEmpty() || ext in ALLOWED_EXT
                if (!allowed) {
                    Log.w(TAG, "跳过不允许的文件：$name")
                    zis.closeEntry()
                    continue
                }
                val out = File(dest, safe.joinToString("/"))
                out.parentFile?.mkdirs()
                out.outputStream().use { os ->
                    var written = 0L
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        written += n
                        total += n
                        if (written > MAX_FILE || total > MAX_TOTAL) {
                            throw IllegalStateException("插件包太大了，超过限制")
                        }
                        os.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
                count++
                if (count % 10 == 0) listener.onProgress("正在解开插件包…（$count）")
            }
        }
        return count
    }

    /** 看 manifest.json 是不是套在一层目录里，返回要剪掉的前缀 */
    private fun detectPrefix(zip: File): String {
        ZipInputStream(zip.inputStream().buffered(64 * 1024)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                zis.closeEntry()
                if (name.substringAfterLast('/') != PluginManifest.FILE_NAME) continue
                val depth = name.count { it == '/' }
                if (depth == 0) return ""
                if (depth == 1) return name.substringBeforeLast('/') + "/"
            }
        }
        return ""
    }

    private fun copyTree(src: File, dest: File): Boolean = try {
        src.walkTopDown().forEach { f ->
            val target = File(dest, f.relativeTo(src).path)
            if (f.isDirectory) target.mkdirs()
            else {
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
            }
        }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "复制目录失败", t)
        false
    }

    /** 读已装版本的 manifest 里的版本号 */
    private fun readInstalledVersion(dir: File): String = try {
        PluginManifest.readFrom(dir).fold({ it.version }, { "" })
    } catch (t: Throwable) {
        ""
    }
}
