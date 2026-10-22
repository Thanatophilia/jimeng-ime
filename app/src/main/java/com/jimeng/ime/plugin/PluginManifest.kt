package com.jimeng.ime.plugin


import android.util.Log
import com.jimeng.ime.StoreData
import org.json.JSONObject
import java.io.File

/**
 * 插件包里的 `manifest.json`。
 *
 * ── 为什么插件要带一份 manifest ─────────────────────────────
 * 用户自己能往输入法里塞东西，那就必须先说明「这是什么、要什么」。
 * 没有清单的包只能靠猜，装了也说不清它需要哪些能力。
 *
 * 第一版只认一句最朴素的话：**插件包不带任何可执行代码**。
 * 它只有数据和声明，功能由 APK 里已经编译好的适配器（Adapter）实现。
 * 所以这里的 `entry` 不是"入口类"，而是"要找哪个适配器"的钥匙：
 * 引擎在 APK 内置的注册表里查 `entry`，查不到就拒绝安装。
 *
 * 这样设计的好处很直接：用户下载来的 zip 里就算塞了 .dex / .so，
 * 也没有任何路径能让它跑起来——我们只解压声明过的东西。
 *
 * 格式（对应 `assets/store/index.json` 的条目结构）：
 * ```json
 * {
 *   "id": "jiemeng.clipboard.history",
 *   "name": "剪贴板增强",
 *   "version": "1.0.0",
 *   "type": "clipboard",
 *   "platform": "jiemeng",
 *   "apiVersion": 1,
 *   "entry": "clipboard",
 *   "permissions": [],
 *   "description": "……",
 *   "author": "……"
 * }
 * ```
 */
data class PluginManifest(
    /** 插件 id。也是安装后的目录名，所以必须是安全的文件名 */
    val id: String,
    val name: String,
    val version: String,
    /** 插件类型，决定用哪个适配器（clipboard / theme / ...） */
    val type: String,
    /** 宿主平台，只认 `jiemeng` */
    val platform: String,
    /** 插件 API 版本，对不上就拒绝 */
    val apiVersion: Int,
    /** 适配器钥匙（APK 里预先编译好的那个） */
    val entry: String,
    /** 声明的能力（不是 Android 权限，只是"我要用哪块功能"） */
    val permissions: List<String>,
    val description: String,
    val author: String,
    /** 包内相对路径的图标，可能没有 */
    val icon: String,
    /** 原始 JSON，原样写进安装目录，将来升级/详情页都用得上 */
    val raw: String,
) {
    /** 写进安装目录的文件名，和商店清单里的字段名保持一致 */
    fun toJson(): String = raw

    /**
     * 转成商店条目，让导入的插件能直接复用商店那套状态/列表/移除逻辑。
     *
     * 这里刻意把 `files` 留成空表、`unzip = false`：
     *   · 商店条目的 `files` 是「要去哪几个地址下什么」，本地导入没有下载地址，
     *     写了反而会让 [StoreInstaller.state] 去逐个比大小，永远判成「没下完」；
     *   · 导入的包已经在磁盘上了，判断「装没装」用目录里的 manifest 就够了——
     *     见 [isLocallyInstalled]。
     *
     * 所以本地插件在商店列表里要单独走一条渲染分支，不能靠 `filesComplete()`。
     */
    fun toStoreItem(): StoreData.Item = StoreData.Item(
        id = id,
        kind = "plugin",
        category = "本地导入",
        name = name,
        version = version,
        author = author,
        license = "",
        description = description.ifEmpty { "从本地导入的插件" },
        files = emptyList(),
        unzip = false,
        schemaId = "",
    )

    companion object {
        private const val TAG = "JimengPlugin"

        /** 当前支持的最高 API 版本。插件声明的版本高于它就不装。 */
        const val API_VERSION = 1

        /** 唯一认的宿主平台 */
        const val PLATFORM = "jiemeng"

        /** `manifest.json` 这个名字，包根目录下 */
        const val FILE_NAME = "manifest.json"

        /**
         * 解析一份 manifest。
         *
         * 任何一条不满足都返回 [PluginResult.failure]，附带一句能直接给用户看的话——
         * 导入失败时最怕的是「导入失败」四个字，用户完全不知道改哪。
         */
        fun parse(text: String, from: String = ""): PluginResult<PluginManifest> {
            val obj = try {
                JSONObject(text)
            } catch (t: Throwable) {
                Log.w(TAG, "manifest 不是合法 JSON：$from", t)
                return PluginResult.failure(PluginError("manifest.json 不是合法的 JSON 文件"))
            }

            val id = obj.optString("id").trim()
            if (id.isEmpty()) return PluginResult.failure(PluginError("manifest.json 缺少 id"))
            if (!isSafeId(id)) {
                return PluginResult.failure(
                    PluginError("id「$id」里有不能用的字符\n只能用字母、数字、点、下划线和连字符")
                )
            }

            val name = obj.optString("name").trim().ifEmpty { id }
            val version = obj.optString("version").trim().ifEmpty { "1.0.0" }
            val type = obj.optString("type").trim().lowercase()
            if (type.isEmpty()) return PluginResult.failure(PluginError("manifest.json 缺少 type"))

            val platform = obj.optString("platform").trim().lowercase()
            if (platform.isNotEmpty() && platform != PLATFORM) {
                return PluginResult.failure(
                    PluginError("这是给「$platform」用的插件，霁梦装不了")
                )
            }

            // apiVersion 缺省按 1 算：第一版就只有一个版本，不写也当 1
            val api = obj.optInt("apiVersion", API_VERSION)
            if (api <= 0) {
                return PluginResult.failure(PluginError("manifest.json 里的 apiVersion 不对"))
            }
            if (api > API_VERSION) {
                return PluginResult.failure(
                    PluginError("这个插件要 API $api，本机输入法只到 $API_VERSION\n先去应用商店更新一下")
                )
            }

            val entry = obj.optString("entry").trim().ifEmpty { type }

            val perms = ArrayList<String>()
            obj.optJSONArray("permissions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i).trim()
                    if (p.isNotEmpty()) perms.add(p)
                }
            }

            return PluginResult.success(
                PluginManifest(
                    id = id,
                    name = name,
                    version = version,
                    type = type,
                    platform = if (platform.isEmpty()) PLATFORM else platform,
                    apiVersion = api,
                    entry = entry,
                    permissions = perms,
                    description = obj.optString("description").trim(),
                    author = obj.optString("author").trim(),
                    icon = obj.optString("icon").trim(),
                    raw = obj.toString(2),
                )
            )
        }

        /**
         * id 要能直接当目录名。
         *
         * 允许 `点` 是因为插件 id 常写成反域名（`jiemeng.clipboard.history`），
         * 不然一堆包名式的 id 全得改。但 `..` 这种要挡掉——
         * 它拼进路径就是向上跳一级，这是外部数据，不能信。
         */
        fun isSafeId(id: String): Boolean {
            if (id.isEmpty() || id.length > 96) return false
            if (id == "." || id == ".." || id.contains("..")) return false
            return id.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        }

        /** 从 zip 包根目录的 manifest.json 读出来 */
        fun readFrom(dir: File): PluginResult<PluginManifest> {
            val f = File(dir, FILE_NAME)
            if (!f.isFile) {
                return PluginResult.failure(
                    PluginError("这个包里没有 $FILE_NAME\n插件包需要一份清单说明它是什么")
                )
            }
            val text = try {
                f.readText()
            } catch (t: Throwable) {
                Log.w(TAG, "读 manifest 失败", t)
                return PluginResult.failure(PluginError("读不出 $FILE_NAME，文件可能是坏的"))
            }
            return parse(text, dir.name)
        }
    }
}

/** 插件相关的错误：带一句能直接显示的话。 */
class PluginError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 一个小结果类型，免得到处 try/catch 又丢错误原因。 */
sealed class PluginResult<out T> {
    data class Success<T>(val value: T) : PluginResult<T>()
    data class Failure(val error: PluginError) : PluginResult<Nothing>()

    fun getOrNull(): T? = (this as? Success)?.value

    inline fun <R> fold(onSuccess: (T) -> R, onFailure: (PluginError) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }

    companion object {
        fun <T> success(v: T): PluginResult<T> = Success(v)
        fun failure(e: PluginError): PluginResult<Nothing> = Failure(e)
    }
}
