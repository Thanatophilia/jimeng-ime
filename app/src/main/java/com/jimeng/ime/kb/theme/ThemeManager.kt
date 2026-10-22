/*
 * 当前主题 + 主题偏好。
 *
 * ── 谁在改 activeTheme ──────────────────────────────────
 *   · init()               首次启动，按系统深色/浅色 + 用户选的皮肤算出主题
 *   · updateFromContext()  转屏、切深色模式时重算（外部环境变了）
 *   · applySkin()          用户在设置页选了一个皮肤（用户主动）
 *   · resetToSystem()      删掉正在用的皮肤，退回归系统默认
 *
 * ── 为什么皮肤选择要落盘 ────────────────────────────────
 * 输入法进程随时可能被系统回收。用户的皮肤选择必须跟着走，
 * 不能每次拉起键盘都变回默认。存的是皮肤 id。
 *
 * ── 变更通知 ────────────────────────────────────────────
 * 监听器只用来「让设置页刷新界面」，不负责通知键盘重绘——
 * 键盘每次 onStartInputView 都会读 activeTheme，天然是最新的。
 */
package com.jimeng.ime.kb.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

object ThemeManager {

    private const val PREFS = "jimeng_skin"
    private const val KEY_SKIN_ID = "skin_id"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var _prefs: ThemePrefs? = null

    val prefs: ThemePrefs
        get() = _prefs ?: throw IllegalStateException("ThemeManager 未初始化")

    private val skinPrefs: SharedPreferences?
        get() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var _activeTheme: Theme? = null

    /**
     * 当前生效的主题。
     *
     * 还没 init 过就返回 PixelLight，而不是抛异常——
     * 设置页、键盘里的 View 都可能在服务 init 之前就被构造出来，
     * 那时候拿一个安全的默认色，比让整个界面崩掉强。
     */
    var activeTheme: Theme
        get() = _activeTheme ?: Theme.PixelLight
        set(value) {
            _activeTheme = value
        }

    /** 皮肤变化监听，设置页用 */
    fun interface OnSkinChangedListener {
        fun onSkinChanged(theme: Theme)
    }

    private val listeners = mutableSetOf<OnSkinChangedListener>()

    fun addListener(l: OnSkinChangedListener) {
        listeners += l
    }

    fun removeListener(l: OnSkinChangedListener) {
        listeners -= l
    }

    private fun notifyChanged() {
        val t = _activeTheme ?: return
        listeners.toList().forEach { it.onSkinChanged(t) }
    }

    fun init(ctx: Context) {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        if (_prefs == null) {
            synchronized(this) {
                if (_prefs == null) _prefs = ThemePrefs(appCtx)
            }
        }
        if (_activeTheme == null) {
            _activeTheme = resolve(appCtx)
        }
    }

    // ---------------------------------------------------------------- 选择

    /** 当前选中的皮肤 id。空串表示「跟随系统默认」 */
    fun currentSkinId(): String =
        skinPrefs?.getString(KEY_SKIN_ID, "").orEmpty()

    /**
     * 选一个皮肤。
     *
     * @param id 皮肤 id。空串表示回到「跟随系统」
     * @return 是否真的应用成功（找不到该皮肤 / 内容坏了返回 false）
     */
    fun applySkin(ctx: Context, id: String): Boolean {
        val appCtx = ctx.applicationContext
        appContext = appCtx

        if (id.isEmpty()) {
            resetToSystem()
            return true
        }

        val entry = SkinManager.find(appCtx, id) ?: return false
        val wantDark = shouldUseDark(appCtx)
        val theme = (if (wantDark) entry.dark else entry.light)
            ?: entry.light
            ?: entry.dark
            ?: return false

        skinPrefs?.edit()?.putString(KEY_SKIN_ID, id)?.apply()
        activeTheme = theme
        notifyChanged()
        return true
    }

    /**
     * 直接应用一个已经解析好的皮肤条目。
     *
     * 设置页刚列出来的就是 [SkinEntry]，手上已经有完整对象了，
     * 再按 id 让它自己 find 一遍是白读一次磁盘 + 解析一遍 JSON。
     */
    fun applySkin(ctx: Context, entry: SkinManager.SkinEntry): Boolean {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        if (_prefs == null) {
            synchronized(this) {
                if (_prefs == null) _prefs = ThemePrefs(appCtx)
            }
        }

        val wantDark = shouldUseDark(appCtx)
        val theme = (if (wantDark) entry.dark else entry.light)
            ?: entry.light
            ?: entry.dark
            ?: return false

        skinPrefs?.edit()?.putString(KEY_SKIN_ID, entry.id)?.apply()
        activeTheme = theme
        notifyChanged()
        return true
    }

    /** 删掉正在用的皮肤时用：退回「跟随系统」 */
    fun resetToSystem() {
        skinPrefs?.edit()?.remove(KEY_SKIN_ID)?.apply()
        val ctx = appContext ?: return
        activeTheme = Theme.of(ctx)
        notifyChanged()
    }

    /** 转屏 / 切深色模式时调用，重算当前主题 */
    fun updateFromContext(ctx: Context) {
        val appCtx = ctx.applicationContext
        appContext = appCtx
        activeTheme = resolve(appCtx)
        notifyChanged()
    }

    // ---------------------------------------------------------------- 内部

    /** 现在该用暗色吗 */
    private fun shouldUseDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 按「用户选的皮肤 + 系统明暗」算出最终主题 */
    private fun resolve(ctx: Context): Theme {
        val id = currentSkinId()
        if (id.isNotEmpty()) {
            val entry = SkinManager.find(ctx, id)
            if (entry != null) {
                val wantDark = shouldUseDark(ctx)
                (if (wantDark) entry.dark else entry.light)?.let { return it }
                // 皮肤只有一套配色，就拿它顶
                (entry.light ?: entry.dark)?.let { return it }
            }
            // 皮肤被删了 / 坏了 → 清掉记录，退回系统默认
            skinPrefs?.edit()?.remove(KEY_SKIN_ID)?.apply()
        }
        return Theme.of(ctx)
    }
}