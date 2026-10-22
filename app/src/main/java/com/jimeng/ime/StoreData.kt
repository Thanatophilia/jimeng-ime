package com.jimeng.ime

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 扩展商店的货架数据。
 *
 * 和最早那版的区别：**模型不再打进 APK**。
 * APK 里只留一份几 KB 的清单 [INDEX]，说明货架上有什么、去哪下、下完多大、
 * 校验值是多少；文件本体等用户自己点「下载」才去网络拉。
 *
 * 这样做的直接好处：装输入法的人不必先吞下 231MB —— 不用语音的人
 * 拿到的还是原来那个二十几兆的小包，想要语音的人自己去商店下。
 *
 * 清单格式（`assets/store/index.json`）：
 * ```json
 * {
 *   "version": 1,
 *   "items": [
 *     {
 *       "id": "sensevoice-small-int8",     ← 也是安装后的目录名
 *       "kind": "model",                   ← scheme / plugin / model
 *       "category": "语音识别",             ← 列表上的分组标题
 *       "name": "...", "version": "...", "author": "...",
 *       "license": "...", "description": "...",
 *       "files": [
 *         { "name": "model.int8.onnx", "size": 239233841,
 *           "sha256": "...", "urls": ["https://...", "https://..."] }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * `urls` 是**候选列表**，按顺序试：第一个不通就换下一个（国内直连
 * huggingface 常被卡，第二个就是镜像）。
 *
 * 解析失败不当成崩溃：记日志、返回空列表，商店显示「暂时没有内容」。
 */
object StoreData {
    private const val TAG = "JimengStore"

    /** APK 里那份货架清单的位置。 */
    private const val INDEX = "store/index.json"

    /** 清单里一个文件的描述。 */
    data class FileSpec(
        /** 落到磁盘上的文件名，安装时也按它对齐 */
        val name: String,
        /** 字节数。下载进度和「完整性检查」都靠它 */
        val size: Long,
        /** 下载完的 sha256（小写十六进制）。空串表示这个文件不校验 */
        val sha256: String,
        /** 候选下载地址，按顺序试 */
        val urls: List<String>,
    )

    /** 商店里的一样东西。 */
    data class Item(
        /** 目录名，也是 id */
        val id: String,
        /** scheme / plugin / model，对应三个导航分类 */
        val kind: String,
        /** 显示用的分类名（自由文本，"语音识别" / "词库" 之类） */
        val category: String,
        val name: String,
        val version: String,
        val author: String,
        val license: String,
        val description: String,
        /** 这套东西包含哪些文件 */
        val files: List<FileSpec>,
        /**
         * 装好后要不要解压。
         *
         * 方案类（kind=scheme）是一整个 zip，下完要解开成目录才能用；
         * 模型类是一堆散文件，直接落地。
         */
        val unzip: Boolean = false,
        /**
         * 解压后只保留顶层这一个子目录里的东西。
         *
         * GitHub 仓库压缩包会多套一层 `仓库名-分支/`，白拿一层目录会让 librime
         * 找不到 yaml。留空表示直接铺在根上。
         */
        val stripPrefix: String = "",
        /** 方案主 id（schema_id），只对 kind=scheme 有意义 */
        val schemaId: String = "",
        /** 方案英文档位 id，只对 kind=scheme 有意义；空表示这套没带英文 */
        val englishSchemaId: String = "",
        /**
         * 装完之后占多少。方案类是解压展开的体积，比 zip 大一两倍。
         *
         * 0 表示不知道，就当和 [sizeBytes] 一样大。
         */
        val installBytes: Long = 0L,
    ) {
        /** 全部文件加起来多大，列表上显示用。 */
        val sizeBytes: Long get() = files.sumOf { it.size }

        /** 落地后要占多少：解压类看解压体积，普通类就是文件本身。 */
        val occupyBytes: Long get() = if (installBytes > 0L) installBytes else sizeBytes
    }

    /** 解析一次的缓存：assets 在应用生命周期里不会变，不必每次重读。 */
    @Volatile
    private var cache: List<Item>? = null

    /**
     * 取某个分类下能装的东西。
     *
     * @param kind `scheme` / `plugin` / `model`
     */
    fun items(ctx: Context, kind: String): List<Item> = all(ctx).filter { it.kind == kind }

    /** 货架上的全部东西。 */
    fun all(ctx: Context): List<Item> {
        cache?.let { return it }
        val parsed = try {
            parse(ctx.applicationContext)
        } catch (t: Throwable) {
            // 清单写坏了不该让商店崩：当成「没货」处理，日志里能看到原因
            Log.e(TAG, "parse $INDEX failed", t)
            emptyList()
        }
        cache = parsed
        return parsed
    }

    /** 按 id 找。 */
    fun find(ctx: Context, id: String): Item? = all(ctx).firstOrNull { it.id == id }

    private fun parse(ctx: Context): List<Item> {
        val text = ctx.assets.open(INDEX).use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            // 没 id 的条目没法装（不知道往哪放），直接跳过
            if (id.isEmpty()) {
                Log.w(TAG, "item #$i has no id, skipped")
                continue
            }
            val files = ArrayList<FileSpec>()
            val fileArr = o.optJSONArray("files")
            if (fileArr != null) {
                for (j in 0 until fileArr.length()) {
                    val f = fileArr.optJSONObject(j) ?: continue
                    val name = f.optString("name").trim()
                    if (name.isEmpty()) continue
                    val urls = ArrayList<String>()
                    val urlArr = f.optJSONArray("urls")
                    if (urlArr != null) {
                        for (k in 0 until urlArr.length()) {
                            val u = urlArr.optString(k).trim()
                            if (u.isNotEmpty()) urls.add(u)
                        }
                    }
                    files.add(
                        FileSpec(
                            name = name,
                            size = f.optLong("size", 0L),
                            sha256 = f.optString("sha256").trim().lowercase(),
                            urls = urls,
                        )
                    )
                }
            }
            out.add(
                Item(
                    id = id,
                    kind = o.optString("kind", "plugin").trim().lowercase(),
                    category = o.optString("category", "其它").trim(),
                    name = o.optString("name", id).trim(),
                    version = o.optString("version", "").trim(),
                    author = o.optString("author", "").trim(),
                    license = o.optString("license", "").trim(),
                    description = o.optString("description", "").trim(),
                    files = files,
                    unzip = o.optBoolean("unzip", false),
                    stripPrefix = o.optString("stripPrefix", "").trim(),
                    schemaId = o.optString("schemaId", "").trim(),
                    englishSchemaId = o.optString("englishSchemaId", "").trim(),
                    installBytes = o.optLong("installBytes", 0L),
                )
            )
        }
        return out
    }
}