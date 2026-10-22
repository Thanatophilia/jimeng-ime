package com.jimeng.ime.plugin

import android.content.Context
import android.util.Log
import com.jimeng.ime.kb.clipboard.ClipboardAdapter

/**
 * 插件适配器注册表。
 *
 * ── 这个文件是「插件包不带代码」这句话的落点 ─────────────────
 * 用户导入的 zip 里只有数据和声明，一行 Kotlin 都不会执行。
 * manifest 里的 `entry` 是**钥匙**，在这里查表：
 *
 * ```
 * entry = "clipboard"  →  ClipboardAdapter（编进 APK 的那个）
 * entry = "随便写的"    →  查不到，直接拒绝安装
 * ```
 *
 * 所以哪怕有人往 zip 里塞 .dex / .so，也没有任何代码路径去加载它——
 * 我们只认表里这几个已知的适配器，别的一概不执行。
 *
 * ── 加一个新插件要动哪里 ──────────────────────────────────
 *   1. 写一个 object 实现 [Adapter]，在 APK 里编译好；
 *   2. 加进下面的 [adapters] 列表；
 *   3. 打包一份带 `manifest.json` 的 zip（entry 写你的钥匙）。
 *
 * 开关状态按插件 id 存 SharedPreferences：同一份包换版本重装，
 * 开着的还是开着，不会被版本号一改就打回原形。
 */
object PluginRegistry {
    private const val TAG = "JimengPlugin"
    private const val PREF = "jimeng_plugin_switch"

    /**
     * 一个插件适配器。
     *
     * [onEnable] / [onDisable] 要**可重复调用**：用户可能连点两下，
     * 或者开关状态和实际效果因为崩溃错位了——自愈的时候会再调一次。
     */
    interface Adapter {
        /** 钥匙，和 manifest.json 的 `entry` 对应 */
        val entry: String

        /** 类型，manifest.json 的 `type`。entry 没写时用它兜底匹配 */
        val type: String

        /** 这个适配器提供哪些能力，给插件详情页显示 */
        fun capabilities(): List<String>

        /** 打开。返回 false 表示没成功（界面会回滚开关） */
        fun onEnable(ctx: Context): Boolean

        /** 关掉。不抛异常，尽力而为 */
        fun onDisable(ctx: Context)
    }

    /**
     * APK 里已经编译好的全部适配器。
     *
     * 顺序就是插件详情页里的展示顺序，不重要。
     */
    private val adapters: List<Adapter> = listOf(
        ClipboardAdapter,
    )

    /** 按 entry 找；找不到再按 type 找（`entry` 可以不写，默认等于 type） */
    fun adapterFor(entry: String, type: String = ""): Adapter? {
        adapters.firstOrNull { it.entry == entry }?.let { return it }
        if (type.isNotEmpty()) return adapters.firstOrNull { it.type == type }
        return null
    }

    /** 这个 entry / type 认不认识。导入时用它当「装得了装不了」的判据。 */
    fun isSupported(entry: String, type: String = ""): Boolean = adapterFor(entry, type) != null

    /** 所有认识的 entry，导入失败时列给用户看 */
    fun knownEntries(): List<String> = adapters.map { it.entry }

    // ---------------------------------------------------------- 开关

    private fun key(id: String) = "on:$id"

    fun isEnabled(ctx: Context, id: String): Boolean = try {
        ctx.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(key(id), false)
    } catch (t: Throwable) {
        Log.w(TAG, "读插件开关失败：$id", t)
        false
    }

    private fun writeFlag(ctx: Context, id: String, on: Boolean) {
        try {
            ctx.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(key(id), on)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "写插件开关失败：$id", t)
        }
    }

    /**
     * 打开插件。
     *
     * 顺序是「先让适配器生效，再落开关」：反过来的话，适配器失败时会留下
     * 一个「显示已启用、其实什么都没做」的假状态。
     */
    fun enable(ctx: Context, id: String, entry: String, type: String): Boolean {
        val adapter = adapterFor(entry, type)
        if (adapter == null) {
            Log.w(TAG, "没有能处理 entry=$entry type=$type 的适配器")
            return false
        }
        val ok = try {
            adapter.onEnable(ctx)
        } catch (t: Throwable) {
            Log.e(TAG, "适配器 ${adapter.entry} 启用失败", t)
            false
        }
        if (!ok) return false
        writeFlag(ctx, id, true)
        Log.i(TAG, "插件已启用：$id（${adapter.entry}）")
        return true
    }

    /** 关掉插件。适配器出错也照样落开关——不能让用户卡在一个关不掉的开关上。 */
    fun disable(ctx: Context, id: String, entry: String, type: String) {
        adapterFor(entry, type)?.let {
            try {
                it.onDisable(ctx)
            } catch (t: Throwable) {
                Log.w(TAG, "适配器 ${it.entry} 停用出错，忽略", t)
            }
        }
        writeFlag(ctx, id, false)
        Log.i(TAG, "插件已停用：$id")
    }

    /** 插件文件被删了，开关也一起清掉，别留个指向空气的开关 */
    fun forget(ctx: Context, id: String) {
        try {
            ctx.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(key(id))
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "清插件开关失败：$id", t)
        }
    }
}
