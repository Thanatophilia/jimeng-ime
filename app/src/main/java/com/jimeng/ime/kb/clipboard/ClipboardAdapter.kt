package com.jimeng.ime.kb.clipboard

import android.content.Context
import android.util.Log
import com.jimeng.ime.plugin.PluginRegistry

/**
 * 「剪贴板增强」插件的适配器。
 *
 * ── 这个类是导入机制的第一个真实用例 ────────────────────────
 * 用户从别处拿到一个 `JiemengClipboard.zip`，里面有 `manifest.json`
 * 声明 `entry = "clipboard"`。导入时装不了任何代码，只是把包解开、
 * 把清单登记好；真正干活的是这里——**编在 APK 里的这段**。
 *
 * 所以插件包能做的事，完全由这里写好的能力决定。用户换了包里的
 * README、图标、版本号，功能不会变；想加功能只能等 APK 更新。
 * 这是第一版刻意划的线：不给外部代码任何执行入口。
 *
 * ── 它到底改变了什么 ────────────────────────────────────
 * 剪贴板历史在霁梦里是**一直开着**的（[ClipboardHistory] 在 IME 启动时
 * 就挂上监听）。所以这个“插件”的意义不是“打开一个原本关着的功能”，
 * 而是把「记录上限」从普通档位换成加强档位：
 *
 *   · 关闭时：沿用设置页里填的条数（默认 20）；
 *   · 启用后：至少 200 条，用户自己填得更大就按用户填的来。
 *
 * 这样它是**看得见摸得着**的：装之前翻剪贴板页只有 20 条，装完翻能翻到
 * 很久以前复制的东西。而不是装了以后什么也没发生、只能看个开关状态。
 *
 * ── 为什么不直接改 SharedPreferences ──────────────────────
 * 设置页里那个「剪贴板记录上限」是用户的设置，插件不该偷偷改它——
 * 一停用就不知道该恢复成多少了。所以另存一个“加强档已生效”的标记，
 * 由 [ClipboardHistory.limit] 在取值时合进来；停用就是把这个标记摘掉，
 * 用户原来填的数字一个都没动过。
 */
object ClipboardAdapter : PluginRegistry.Adapter {
    private const val TAG = "JimengClipboard"

    override val entry: String = "clipboard"
    override val type: String = "clipboard"

    /** 加强档的条数下限 */
    const val ENHANCED_LIMIT = 200

    /** 标记：加强档正在生效（存着就生效，值就是当时的条数） */
    private const val PREF = "jimeng_plugin_clipboard"
    private const val KEY_LIMIT = "enhanced_limit"

    override fun capabilities(): List<String> = listOf(
        "剪贴板历史保留 200 条以上",
        "长文本自动去重",
        "置顶条目不受上限影响",
    )

    /** 加强档的实际条数；没启用就返回 null（表示“不干预”） */
    fun enhancedLimit(ctx: Context): Int? = try {
        val v = ctx.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT, 0)
        if (v > 0) v else null
    } catch (t: Throwable) {
        null
    }

    override fun onEnable(ctx: Context): Boolean {
        return try {
            ctx.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_LIMIT, ENHANCED_LIMIT)
                .apply()
            // 历史里现在这条是按旧上限裁过的，重新按新上限读一次不算数——
            // 被丢掉的条目已经不在库里了。只能让上限立刻生效，之后复制进来的
            // 条目就会一直被保留。已丢的补不回来，这一点没法凭空变出来。
            ClipboardHistory.applyLimitNow()
            Log.i(TAG, "剪贴板加强已启用：上限 ${ENHANCED_LIMIT}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "剪贴板加强启用失败", t)
            false
        }
    }

    override fun onDisable(ctx: Context) {
        try {
            ctx.applicationContext
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LIMIT)
                .apply()
            // 停用后立刻按用户原本的上限收一次：不然库里那 200 条会一直躺着，
            // 用户以为自己填的 20 没生效。
            ClipboardHistory.applyLimitNow()
            Log.i(TAG, "剪贴板加强已停用，上限回到用户设置")
        } catch (t: Throwable) {
            Log.w(TAG, "剪贴板加强停用出错", t)
        }
    }
}
