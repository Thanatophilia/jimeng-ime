package com.jimeng.ime.rime

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * 「现在用哪套方案」的总开关。
 *
 * ── 为什么需要这一层 ─────────────────────────────────────
 * 四套方案里有 50 多个同名文件（雾凇和白霜各带一套 cn_dicts、lua、en_dicts，
 * 版本还不一样），它们是**互斥**的，不可能同时铺在一个 shared 目录里。
 * 而 librime 的初始化是一次性的（nativeInit 在一个进程里只跑一次），
 * .so 也没导出「重新部署」的接口，所以做不到热切换。
 *
 * 于是干脆定成：**同一时间只有一套方案在场**。
 *   · [active] 为 null → 用 APK 内置那套（雾凇 + 英文）
 *   · [active] 不为 null → 用商店里下好的那一套
 *
 * 启用只写标记，不立刻动文件——真正把文件铺进 shared 是下次引擎启动时
 * 由 [RimeEngine.deployAssets] 做的事。这样切换时不会去碰一个
 * 已经被引擎 mmap 着的目录，安全。代价是第一次要重新部署词库。
 */
object SchemeManager {
    private const val TAG = "JimengScheme"

    private const val PREF = "jimeng_scheme"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_NAME = "active_name"
    private const val KEY_PRIMARY = "active_primary"
    private const val KEY_ENGLISH = "active_english"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    /** 一套被启用的商店方案。 */
    data class Active(
        /** 商店 id，也是 filesDir/store 下的目录名 */
        val id: String,
        /** 显示名，比如「白霜拼音」 */
        val name: String,
        /** 主方案 id，比如 rime_frost */
        val primary: String,
        /** 英文档位 id，小地球键切过去用的那个 */
        val english: String,
    )

    /** 当前启用的方案；null 表示用 APK 内置那套。 */
    fun active(): Active? {
        val p = prefs ?: run {
            // prefs 没 init 就直接问，多半是调用顺序错了。记一笔，别默默返回内置。
            Log.w(TAG, "active() 时 prefs 还没 init，按内置处理")
            return null
        }
        val id = p.getString(KEY_ACTIVE, null)
        if (id.isNullOrEmpty()) {
            // 这一句是「标记丢了」的现场记录：如果用户明明启用过却走到这里，
            // 说明标记在两次启动之间被清了，配合 deactivate 里那条带栈的日志能对上。
            if (id == null) Log.d(TAG, "active_id 为空（内置方案）") else Log.w(TAG, "active_id 是空串（内置方案）")
            return null
        }
        return Active(
            id = id,
            name = p.getString(KEY_NAME, id).orEmpty(),
            primary = p.getString(KEY_PRIMARY, "").orEmpty(),
            english = p.getString(KEY_ENGLISH, "").orEmpty(),
        )
    }

    fun isActive(id: String): Boolean = active()?.id == id

    /**
     * 启用某套商店方案。
     *
     * 只写标记，不动文件；下次引擎启动时才重新铺 shared。
     * [primary] 和 [english] 从商店清单里读，决定小地球键怎么循环。
     */
    fun activate(ctx: Context, id: String, name: String, primary: String, english: String) {
        init(ctx)
        prefs?.edit()
            ?.putString(KEY_ACTIVE, id)
            ?.putString(KEY_NAME, name)
            ?.putString(KEY_PRIMARY, primary)
            ?.putString(KEY_ENGLISH, english)
            ?.apply()
        // 写进去之后立刻回读一次：apply 是异步落盘的，但内存里应该已经生效。
        // 回读不到说明 prefs 本身有问题（比如被别的进程清了），早一点暴露比晚一点好。
        val back = prefs?.getString(KEY_ACTIVE, null)
        Log.i(TAG, "启用方案 $id（$name）主=$primary 英=$english，回读=$back")
        if (back != id) Log.w(TAG, "启用标记回读不一致！期望 $id，实际 $back")
    }

    /** 切回内置方案（APK 里那份雾凇）。 */
    fun deactivate(ctx: Context) {
        init(ctx)
        val before = prefs?.getString(KEY_ACTIVE, null)
        prefs?.edit()?.clear()?.apply()
        // 这里原本只写一句「已切回内置」。但线上真出现过「标记自己没了、
        // 用户以为在用商店方案、其实退回内置」的情况，光看结果分不清是谁清的。
        // 把清之前的 id 和调用栈一起打出来——下次再丢就能直接定位。
        Log.i(TAG, "已切回内置方案（清掉的 active_id=$before）", Throwable("deactivate 调用来源"))
    }

    /** 商店方案解压后放哪。 */
    fun schemeDir(ctx: Context, id: String): File = File(ctx.filesDir, "store/$id")

    /**
     * 这套方案的文件是不是能用（目录在、default.yaml 在、至少有一张 schema）。
     *
     * 只问「有没有 yaml」不够：解压到一半也能有一堆 yaml，交给 librime 就是启动失败。
     */
    fun hasFiles(ctx: Context, id: String): Boolean {
        val dir = schemeDir(ctx, id)
        if (!dir.isDirectory) return false
        if (!File(dir, "default.yaml").isFile) return false
        return dir.listFiles()?.any {
            it.isFile && it.name.endsWith(".schema.yaml")
        } == true
    }
}
