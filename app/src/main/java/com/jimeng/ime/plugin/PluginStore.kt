package com.jimeng.ime.plugin

import android.content.Context
import android.util.Log
import com.jimeng.ime.StoreData
import com.jimeng.ime.StoreInstaller
import java.io.File

/**
 * 已经装到本地的插件（商店下的 + 本地导入的）。
 *
 * ── 为什么要有这一层 ────────────────────────────────────
 * 商店的货架清单是 APK 里那份 `assets/store/index.json`，只写了「有什么可下」。
 * 本地导入的包不在那份清单里，但是装上之后**必须能被看见**：
 * 插件管理页要能开关它，商店要有地方移除它。
 *
 * 所以这里做一件事：把两边的插件合起来看——
 *   · 清单里 `kind=plugin` 的条目（如日本語），装在 `store/<id>`；
 *   · 磁盘上 `store/` 里凡是带 `manifest.json` 的都算本地导入插件。
 *
 * 判「装没装」也不一样：商店条目靠 [StoreInstaller.state]（比对文件清单），
 * 本地导入的靠目录里那份 manifest 在不在（因为它的 files 清单是空的）。
 */
object PluginStore {
    private const val TAG = "JimengPlugin"

    /** 一个已装的插件：商店条目 + 是否本地导入 */
    data class Entry(
        /** 显示/操作都靠这个。本地导入的由 manifest 生成 */
        val item: StoreData.Item,
        /** manifest；商店条目也可能有（日本語没带），可能为空 */
        val manifest: PluginManifest?,
        /** 是不是本地导入的（决定能不能「下载」、移除后怎么提示） */
        val local: Boolean,
        /** 安装目录占多少字节 */
        val bytes: Long,
    ) {
        val id: String get() = item.id
        val name: String get() = item.name
        val description: String get() = item.description
        val entry: String get() = manifest?.entry ?: item.id
        val type: String get() = manifest?.type ?: ""
    }

    /**
     * 全部已装的插件。
     *
     * 商店的排前面、本地导入的排后面，各自内部按名字排——
     * 用户在自己导入的东西里找起来会更快。
     */
    fun installed(ctx: Context): List<Entry> {
        val app = ctx.applicationContext
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()

        // 1) 商店清单里的插件
        val listed = try {
            StoreData.items(app, "plugin")
        } catch (t: Throwable) {
            Log.w(TAG, "读商店清单失败", t)
            emptyList()
        }
        for (item in listed) {
            if (StoreInstaller.state(app, item) != StoreInstaller.State.Installed) continue
            val dir = StoreInstaller.installDir(app, item.id)
            out.add(
                Entry(
                    item = item,
                    manifest = PluginManifest.readFrom(dir).getOrNull(),
                    local = false,
                    bytes = StoreInstaller.installedBytes(app, item),
                )
            )
            seen.add(item.id)
        }

        // 2) 磁盘上带 manifest 的目录 = 本地导入的
        val root = File(app.filesDir, "store")
        val dirs = root.listFiles() ?: return out.sortedBy { it.name }
        for (d in dirs) {
            if (!d.isDirectory || d.name.startsWith(".")) continue
            if (d.name in seen) continue
            if (!File(d, PluginManifest.FILE_NAME).isFile) continue
            val m = PluginManifest.readFrom(d).getOrNull() ?: continue
            out.add(
                Entry(
                    item = m.toStoreItem(),
                    manifest = m,
                    local = true,
                    bytes = try {
                        d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    } catch (t: Throwable) {
                        0L
                    },
                )
            )
            seen.add(d.name)
        }
        return out.sortedWith(compareBy({ it.local }, { it.name }))
    }

    /** 按 id 找已装的插件 */
    fun find(ctx: Context, id: String): Entry? = installed(ctx).firstOrNull { it.id == id }

    /**
     * 这个插件是不是开着。
     *
     * 商店插件（日本語）有自己一套开关（[com.jimeng.ime.rime.JapanesePlugin]），
     * 本地导入的走 [PluginRegistry] 的通用开关。这里按 id 分派。
     */
    fun isActive(ctx: Context, entry: Entry): Boolean {
        // 日本語是商店里的特殊插件：它要挂 rime 文件，不是纯开关
        if (entry.id == com.jimeng.ime.rime.JapanesePlugin.ID) {
            return com.jimeng.ime.rime.JapanesePlugin.isActive(ctx)
        }
        return PluginRegistry.isEnabled(ctx, entry.id)
    }

    /** 打开一个插件。返回 false 表示失败，界面该提示 */
    fun enable(ctx: Context, entry: Entry): Boolean {
        if (entry.id == com.jimeng.ime.rime.JapanesePlugin.ID) {
            return com.jimeng.ime.rime.JapanesePlugin.activate(ctx)
        }
        return PluginRegistry.enable(ctx, entry.id, entry.entry, entry.type)
    }

    /** 关掉一个插件 */
    fun disable(ctx: Context, entry: Entry) {
        if (entry.id == com.jimeng.ime.rime.JapanesePlugin.ID) {
            com.jimeng.ime.rime.JapanesePlugin.deactivate(ctx)
            return
        }
        PluginRegistry.disable(ctx, entry.id, entry.entry, entry.type)
    }

    /**
     * 移除之前先收拾干净：插件还开着的话必须先停用。
     *
     * 顺序不能反：先删文件再停用的话，适配器会去读已经不存在的目录。
     */
    fun beforeRemove(ctx: Context, entry: Entry) {
        if (entry.id == com.jimeng.ime.rime.JapanesePlugin.ID) {
            com.jimeng.ime.rime.JapanesePlugin.beforeRemove(ctx)
            return
        }
        if (PluginRegistry.isEnabled(ctx, entry.id)) {
            PluginRegistry.disable(ctx, entry.id, entry.entry, entry.type)
        }
        PluginRegistry.forget(ctx, entry.id)
    }

    /** 真正删掉安装目录 */
    fun remove(ctx: Context, entry: Entry): Boolean {
        val app = ctx.applicationContext
        val dir = StoreInstaller.installDir(app, entry.id)
        return try {
            dir.deleteRecursively() || !dir.exists()
        } catch (t: Throwable) {
            Log.w(TAG, "删插件目录失败：${entry.id}", t)
            false
        }
    }
}
