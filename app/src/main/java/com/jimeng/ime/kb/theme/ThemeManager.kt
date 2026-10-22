/*
 * 当前主题 + 主题偏好。
 *
 * ── 谁在改 activeTheme ──────────────────────────────────
 *   · init()               首次启动，按系统深色/浅色 + 用户选的皮肤算出主题
 *   · updateFromContext()  转屏、切深色模式时重算（外部环境变了）
 *   · applySkin()          用户在设置页选了一个皮肤（用户主动）
 *   · resetToDefault()     删掉正在用的皮肤，退回默认皮肤（Pixel 亮色）
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

    /**
     * 没选过皮肤时用的那一套。
     *
     * 以前「不选皮肤」= 跟随系统深浅色（[Theme.of]），可它在浅色模式下
     * 和 Pixel 亮色长得一模一样——列表里等于摆了两个同样的格子。
     * 现在把那一格去掉，默认直接落到 Pixel 亮色上。
     */
    const val DEFAULT_SKIN_ID = "pixel_light"

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

    /**
     * 当前选中的皮肤 id。
     *
     * 从没选过（或选择被清掉）时返回 [DEFAULT_SKIN_ID]，
     * 而不是空串——「跟随系统」那一格已经去掉了，列表里必须始终有一个
     * 高亮着的格子，否则用户看不出现在用的是哪套。
     */
    fun currentSkinId(): String =
        skinPrefs?.getString(KEY_SKIN_ID, "")?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_SKIN_ID

    /**
     * 选一个皮肤。
     *
     * @param id 皮肤 id。传空串等于回到默认皮肤（Pixel 亮色）
     * @return 是否真的应用成功（找不到该皮肤 / 内容坏了返回 false）
     */
    fun applySkin(ctx: Context, id: String): Boolean {
        val appCtx = ctx.applicationContext
        appContext = appCtx

        if (id.isEmpty()) {
            resetToDefault()
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

    /**
     * 回到默认皮肤（Pixel 亮色）。
     *
     * 以前这里退的是「跟随系统深浅色」，现在默认皮肤就是一个具体方案，
     * 所以直接按 id 走 [applySkin] 那套解析——找不到 pixel_light（比如
     * assets 被裁掉）才退到 [Theme.of] 兜底，不至于让键盘没配色。
     */
    fun resetToDefault() {
        skinPrefs?.edit()?.remove(KEY_SKIN_ID)?.apply()
        val ctx = appContext ?: return
        val entry = SkinManager.find(ctx, DEFAULT_SKIN_ID)
        val theme = entry?.let { (if (shouldUseDark(ctx)) it.dark else it.light) ?: it.light ?: it.dark }
            ?: Theme.of(ctx)
        activeTheme = theme
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
        // currentSkinId() 现在一定至少给出 DEFAULT_SKIN_ID，所以正常情况下
        // 这里不会落到 Theme.of()；只有内置皮肤整个丢了才走兜底。
        val id = currentSkinId()
        val entry = SkinManager.find(ctx, id)
        if (entry != null) {
            val wantDark = shouldUseDark(ctx)
            (if (wantDark) entry.dark else entry.light)?.let { return it }
            // 皮肤只有一套配色，就拿它顶
            (entry.light ?: entry.dark)?.let { return it }
        }
        // 皮肤被删了 / 坏了 → 清掉记录，退到内置兜底
        skinPrefs?.edit()?.remove(KEY_SKIN_ID)?.apply()
        return Theme.of(ctx)
    }
}