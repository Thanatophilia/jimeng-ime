package com.jimeng.ime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把商店里的东西下到本地、校验、装上、卸掉。
 *
 * ── 目录约定 ─────────────────────────────────────────────
 * ```
 * filesDir/store/<id>/            已安装的东西
 * filesDir/store/.download/<id>/  正在下的临时文件（下完校验通过才搬进去）
 * ```
 * 用 filesDir 而不是外部存储：一是应用私有、卸载自动清干净，
 * 二是 sherpa-onnx 要按路径直接读，私有目录不会有权限问题。
 *
 * ── 为什么先下到 .download 再搬 ──────────────────────────
 * 239MB 的模型下一半断网是常事。如果直接往正式目录写，
 * 下次启动时「文件存在但只有一半」，引擎会拿着坏模型去加载，
 * 报出来的错跟「没下载」混在一起，很难判断。
 * 现在的规则很干脆：正式目录里只会有校验通过的全套文件。
 *
 * ── 校验 ────────────────────────────────────────────────
 * 边下边算 sha256，下完对一次。对不上就删掉重试下一个地址，
 * 全都不行就报错——不把一个可疑的文件交给推理引擎。
 */
object StoreInstaller {
    private const val TAG = "JimengStore"

    /** 装完写一份小标记，用来区分「完整安装」和「文件刚好都在」。 */
    private const val MARKER = ".installed"

    /** 连接超时。网络差的时候 20 秒还没握上手，基本就是不通了。 */
    private const val CONNECT_TIMEOUT = 20_000

    /** 读超时。239MB 是一段段读的，这个值管的是「两次读之间最多等多久」。 */
    private const val READ_TIMEOUT = 60_000

    /** 下载状态：给界面显示用。 */
    enum class State {
        /** 一个文件都没有 */
        NotInstalled,

        /** 有文件但没装完/校验没过 */
        Incomplete,

        /** 全套都在，校验通过 */
        Installed,
    }

    /** 下载进度回调。全部在主线程之外调用，界面自己 post。 */
    interface Listener {
        /** @param file 正在下哪个文件；@param done 已完成字节；@param total 总字节 */
        fun onProgress(file: String, done: Long, total: Long) {}

        /** 换了个下载地址（第一个不通时） */
        fun onRetry(file: String, url: String) {}

        /** 装好了 */
        fun onDone() {}

        /** 失败，[reason] 是给用户看的一句话 */
        fun onError(reason: String) {}
    }

    // ------------------------------------------------------------ 路径

    /** `filesDir/store` */
    private fun storeRoot(ctx: Context): File = File(ctx.filesDir, "store").apply { mkdirs() }

    /** 某样东西装好后放哪。 */
    fun installDir(ctx: Context, id: String): File = File(storeRoot(ctx), id)

    /** 下载时的临时目录。 */
    private fun tempDir(ctx: Context, id: String): File =
        File(File(storeRoot(ctx), ".download"), id).apply { mkdirs() }

    // ------------------------------------------------------------ 状态

    /** 看它现在是什么状态。只看文件在不在、大小对不对，不算 sha（太慢）。 */
    fun state(ctx: Context, item: StoreData.Item): State {
        val dir = installDir(ctx, item.id)
        if (!dir.isDirectory) return State.NotInstalled
        val marker = File(dir, MARKER)

        // 方案类是解压出来的，半成品长得跟成品很像（解了十几张 yaml 就算「有东西」），
        // 所以必须看到安装标记才算装好——标记只在整个解压跑完才写。
        // 进程被系统杀掉时不会走到 catch，只有这一层能拦住半成品。
        if (item.unzip) {
            if (!marker.isFile) return State.Incomplete
            return if (filesComplete(dir, item)) State.Installed else State.Incomplete
        }

        if (!marker.isFile) {
            // 没有标记：要么是老版本直接推文件进来的，要么装到一半被打断。
            // 文件齐了就当装好（这样手工推进来的模型也能用），缺了就算没装完。
            return if (filesComplete(dir, item)) State.Installed else State.Incomplete
        }
        return if (filesComplete(dir, item)) State.Installed else State.Incomplete
    }

    private fun filesComplete(dir: File, item: StoreData.Item): Boolean {
        if (item.files.isEmpty()) return false
        // 解压类看目录里的 yaml，但不能只问「有没有 yaml」——解压到一半也有一堆。
        //
        // 判据按种类分开，别一刀切：
        //   · 方案包（kind=scheme）必须有 default.yaml + <schemaId>.schema.yaml。
        //     librime 靠 default.yaml 才认这套配置，少一个启动就失败；
        //   · 插件包（kind=plugin）**故意不带 default.yaml**——插件不该覆盖主配置，
        //     只提供自己的 schema 和词库，所以只认 <schemaId>.schema.yaml。
        //     早先这里对谁都硬要 default.yaml，结果日本語下完永远判成「没下完」，
        //     卡片一直显示「继续下载」，用户根本点不到「启用」。
        if (item.unzip) {
            if (item.kind == "scheme" && !File(dir, "default.yaml").isFile) return false
            val main = item.schemaId
            if (main.isNotEmpty()) {
                if (!File(dir, "$main.schema.yaml").isFile) return false
            } else if (!File(dir, "default.yaml").isFile) {
                // 没写 schemaId 的老条目没法认主文件，退回来看 default.yaml
                return false
            }
            return dir.listFiles()?.any { it.isFile && it.name.endsWith(".yaml") } == true
        }
        return item.files.all { f ->
            val file = File(dir, f.name)
            file.isFile && (f.size <= 0L || file.length() == f.size)
        }
    }

    /** 已装的话占多大。 */
    fun installedBytes(ctx: Context, item: StoreData.Item): Long {
        val dir = installDir(ctx, item.id)
        if (item.unzip) {
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return item.files.sumOf { f -> File(dir, f.name).let { if (it.isFile) it.length() else 0L } }
    }

    // ------------------------------------------------------------ 卸载

    /**
     * 卸掉。
     *
     * 返回 true 表示现在确实是「没装」的状态。
     * 删不干净也没关系——[state] 会看到残留，用户还能再删一次。
     */
    fun remove(ctx: Context, item: StoreData.Item): Boolean {
        val dir = installDir(ctx, item.id)
        val ok = try {
            dir.deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "remove ${item.id} failed", t)
            false
        }
        // 临时文件一并没有意义了，顺手清掉
        try {
            tempDir(ctx, item.id).deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "clear temp ${item.id} failed", t)
        }
        return ok || !dir.exists()
    }

    // ------------------------------------------------------------ 安装

    /**
     * 下载并安装。**阻塞**，必须放后台线程（[Thread] 或协程的 IO 调度器）。
     *
     * @param cancel 用户点「取消」时置 true，这里会在下一个数据块处退出并清掉临时文件
     */
    fun install(
        ctx: Context,
        item: StoreData.Item,
        listener: Listener,
        cancel: AtomicBoolean = AtomicBoolean(false),
    ) {
        val app = ctx.applicationContext
        if (item.files.isEmpty()) {
            listener.onError("这个项目没有可下载的文件")
            return
        }

        // 空间检查：宁可提前说「地方不够」，也别下到 99% 才失败。
        // 解压类是 zip 落地 + 展开同时占地方，两份要一起算。
        val need = item.occupyBytes + if (item.unzip) item.sizeBytes else 0L
        val free = try {
            app.filesDir.usableSpace
        } catch (t: Throwable) {
            Long.MAX_VALUE
        }
        if (need > 0 && free < need * 11 / 10) {
            listener.onError("存储空间不够，还需要约 ${fmtSize(need - free)}")
            return
        }

        val tmp = tempDir(app, item.id)
        try {
            for (f in item.files) {
                if (cancel.get()) {
                    cleanTemp(tmp)
                    listener.onError("已取消")
                    return
                }
                val target = File(tmp, f.name)
                if (target.isFile && f.size > 0 && target.length() == f.size &&
                    (f.sha256.isEmpty() || sha256Of(target) == f.sha256)
                ) {
                    // 上次就下好了这一份，跳过（断点续传的粗粒度版本，够用）
                    continue
                }
                downloadOne(app, item, f, target, listener, cancel)
                if (cancel.get()) {
                    cleanTemp(tmp)
                    listener.onError("已取消")
                    return
                }
            }

            if (cancel.get()) {
                cleanTemp(tmp)
                listener.onError("已取消")
                return
            }

            // 到最后再搬：临时目录里全是校验通过的，正式目录不会出现半成品
            val dest = installDir(app, item.id)
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            if (item.unzip) {
                // 方案类：临时目录里就一个 zip，解开铺到正式目录。
                // 解压中途被取消的话 dest 是半成品，整块删掉，别让 state() 认成已装。
                try {
                    for (f in item.files) {
                        val zip = File(tmp, f.name)
                        if (zip.isFile) {
                            unzipInto(zip, dest, item.stripPrefix, listener, cancel)
                        }
                    }
                } catch (t: Throwable) {
                    try {
                        dest.deleteRecursively()
                    } catch (ignored: Throwable) {
                    }
                    throw t
                }
            } else {
                for (f in item.files) {
                    val src = File(tmp, f.name)
                    val out = File(dest, f.name)
                    if (!src.renameTo(out)) {
                        src.copyTo(out, overwrite = true)
                        src.delete()
                    }
                }
            }
            File(dest, MARKER).writeText(
                buildString {
                    appendLine("id=${item.id}")
                    appendLine("version=${item.version}")
                    appendLine("time=${System.currentTimeMillis()}")
                }
            )
            cleanTemp(tmp)
            Log.i(TAG, "installed ${item.id} (${item.files.size} files)")
            listener.onDone()
        } catch (t: Throwable) {
            Log.e(TAG, "install ${item.id} failed", t)
            listener.onError(friendly(t))
        }
    }

    /**
     * 把 zip 解开铺到 dest。
     *
     * 两个注意点：
     *   · [stripPrefix] 不是空的，就只解压以它开头的那部分，并把这段前缀剪掉——
     *     GitHub 仓库包会多套一层 `仓库名-分支/`，不剪掉 librime 就找不到 yaml。
     *   · 路径要防穿越（`../`），zip 是外部数据，不能完全信。
     *
     * 解压过程按条目数报进度，界面上看得到「正在安装」而不是死等。
     */
    private fun unzipInto(
        zip: File,
        dest: File,
        stripPrefix: String,
        listener: Listener,
        cancel: AtomicBoolean,
    ) {
        val prefix = stripPrefix.trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }
        val zipIn = java.util.zip.ZipInputStream(zip.inputStream().buffered(64 * 1024))
        zipIn.use { zis ->
            var count = 0
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancel.get()) throw InterruptedException("已取消")
                val entry = zis.nextEntry ?: break
                var name = entry.name.replace('\\', '/')
                if (prefix.isNotEmpty()) {
                    if (!name.startsWith(prefix)) {
                        zis.closeEntry()
                        continue
                    }
                    name = name.substring(prefix.length)
                }
                if (name.isEmpty() || entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                // 防穿越：去掉所有 .. 段
                val safe = name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                if (safe.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val out = File(dest, safe.joinToString("/"))
                out.parentFile?.mkdirs()
                out.outputStream().use { os ->
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
                count++
                if (count % 20 == 0) listener.onProgress("正在解压", count.toLong(), 0L)
            }
            Log.i(TAG, "解压 ${zip.name} → ${dest.name}，${count} 个文件")
        }
    }

    private fun cleanTemp(dir: File) {
        try {
            dir.deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "clean temp failed", t)
        }
    }

    /**
     * 下一个文件。多个地址按顺序试，第一个失败换下一个。
     *
     * 边读边写边算 sha，下完对长度和哈希，两样都过才算成功。
     */
    private fun downloadOne(
        ctx: Context,
        item: StoreData.Item,
        f: StoreData.FileSpec,
        target: File,
        listener: Listener,
        cancel: AtomicBoolean,
    ) {
        if (f.urls.isEmpty()) throw IllegalStateException("${f.name} 没有下载地址")

        var lastError: Throwable? = null
        for (url in f.urls) {
            if (cancel.get()) return
            try {
                if (target.exists()) target.delete()
                listener.onRetry(f.name, url)
                fetch(url, f, target, listener, cancel)
                return
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "download ${f.name} from $url failed: ${t.message}")
                if (cancel.get()) return
                // 换下一个地址
            }
        }
        throw lastError ?: IllegalStateException("${f.name} 下载失败")
    }

    private fun fetch(
        url: String,
        f: StoreData.FileSpec,
        target: File,
        listener: Listener,
        cancel: AtomicBoolean,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            requestMethod = "GET"
            // huggingface / github 的 CDN 对没有 UA 的请求偶尔会挡，给一个
            setRequestProperty("User-Agent", "JiMengIME-Store/1.0 (Android)")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            // 服务器报的大小和清单对不上就别下了：要么清单过期，要么下错了东西
            val declared = conn.contentLengthLong
            if (declared > 0 && f.size > 0 && declared != f.size) {
                throw IllegalStateException("大小不符（服务器 $declared ≠ 清单 ${f.size}）")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            val total = if (f.size > 0) f.size else declared.coerceAtLeast(0L)
            var lastReport = 0L

            conn.inputStream.use { input: InputStream ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel.get()) throw InterruptedException("已取消")
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        // 每 256KB 报一次，免得界面被回调刷爆
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            listener.onProgress(f.name, done, total)
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            listener.onProgress(f.name, done, total)

            if (f.size > 0 && done != f.size) {
                throw IllegalStateException("下载不完整（$done / ${f.size}）")
            }
            if (f.sha256.isNotEmpty()) {
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (!got.equals(f.sha256, ignoreCase = true)) {
                    throw IllegalStateException("校验失败（sha256 不符）")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun sha256Of(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        ""
    }

    /** 把异常翻译成一句人话。 */
    fun friendly(t: Throwable): String = when {
        t is InterruptedException -> "已取消"
        t is java.net.UnknownHostException -> "连不上网，检查一下网络"
        t is java.net.SocketTimeoutException -> "网络太慢，超时了"
        t is SecurityException -> "没有网络权限"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "下载失败"
    }

    /** 字节数变人话：231 MB / 1.2 GB。 */
    fun fmtSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}