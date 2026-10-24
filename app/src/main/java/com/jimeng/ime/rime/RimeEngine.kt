package com.jimeng.ime.rime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jimeng.ime.kb.core.LanguageData
import com.jimeng.ime.kb.prefs.AppPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * librime 引擎外壳。
 *
 * 两个职责：
 *  1. 第一次启动时把 assets/rime 下的方案数据铺到 filesDir 里；
 *  2. 在后台线程初始化引擎并创建/销毁会话，保证 UI 线程不被部署卡住。
 *
 * 雾凇拼音首次部署要编译 50M 的词库，可能要几十秒，所以这里用状态机往外播。
 */
object RimeEngine {

    private const val TAG = "RimeEngine"

    /** 引擎状态 */
    enum class State { Idle, Deploying, Ready, Failed }

    interface Callback {
        fun onStateChanged(state: State, message: String?)

        /** 部署完成后在 UI 线程回调 */
        fun onReady()

        /** 自检开始（report=null）或结束时（report=结果）回调，UI 线程 */
        fun onSelfTest(report: SelfTestReport?)
    }

    /**
     * 引擎自检报告。
     *
     * lines 是给人看的一行行结论，SetupActivity 直接摊开显示，
     * 不用去 logcat 里翻——日志缓冲区很容易被别的进程刷掉。
     */
    data class SelfTestReport(
        val ok: Boolean,
        val lines: List<String>,
        val costMs: Long
    )

    var callback: Callback? = null

    private val main = Handler(Looper.getMainLooper())
    private val initStarted = AtomicBoolean(false)

    @Volatile
    var state: State = State.Idle
        private set

    @Volatile
    var sessionId: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 简繁选择的「记忆」。
     *
     * option 只活在 librime 的内存 Context 里，进程一死就没了。
     * 这个字段配合 [com.jimeng.ime.kb.prefs.AppPrefs.output.traditional]
     * 一起用：写的时候两边都写，读的时候优先信引擎——
     * 引擎没就绪（sessionId == 0）时才退回这个值。
     *
     * 不直接用 AppPrefs 是因为 [isTraditional] 在打字路径上会被频繁调用，
     * 每次都读 SharedPreferences 太浪费。
     */
    @Volatile
    private var traditionalFallback: Boolean = false

    /**
     * 下一词预测开关的「记忆」。
     *
     * 和 [traditionalFallback] 同一套路：option 只活在 librime 的
     * 内存 Context 里，进程一死就没了，所以两边都写，读的时候优先信引擎。
     */
    @Volatile
    private var predictionFallback: Boolean = false

    /**
     * 标点全半角的「记忆」。
     *
     * 和 [traditionalFallback] 同一套路：option 只活在 librime 的
     * 内存 Context 里，进程一死就没了，所以两边都写，读的时候优先信引擎。
     */
    @Volatile
    private var fullShapeFallback: Boolean = false

    /** 自检是否正在跑 */
    @Volatile
    var selfTestRunning: Boolean = false
        private set

    private lateinit var appContext: Context

    /** 方案资源是否需要重新铺开（版本号或方案标记变化时才铺） */
    // rime-ice-8：全角标点下 \ 由「候选」改成直接上屏 、（punctuator.full_shape）
    private const val RIME_ASSET_VERSION = "rime-ice-8"

    /**
     * 一屏至少给几个候选。
     *
     * 候选栏固定按 6 格排（[com.jimeng.ime.view.CandidateBar.MAX_ITEMS]），
     * 而官方包里雾凇、墨奇都写着 `page_size: 5`——引擎只吐 5 个，
     * 第 6 格永远空着，看着就像「这输入法字很少」。
     * 低于这个数的一律补上来；已经够的（万象 6、白霜 8）不动。
     */
    private const val MIN_PAGE_SIZE = 6

    fun bind(context: Context) {
        appContext = context.applicationContext
        // 方案开关也在这里初始化：deployAssets 要用它判断铺哪一套
        SchemeManager.init(appContext)
        AppPrefs.init(appContext)
        // 简繁偏好：这次进程里引擎还没起，先用存下来的值垫着。
        // 真正的 option 会在 doInit 里通过 restoreOptions 灌进会话。
        traditionalFallback = try {
            AppPrefs.getInstance().output.traditional.getValue()
        } catch (t: Throwable) {
            false
        }
        predictionFallback = try {
            AppPrefs.getInstance().output.prediction.getValue()
        } catch (t: Throwable) {
            false
        }
        fullShapeFallback = try {
            AppPrefs.getInstance().output.fullShape.getValue()
        } catch (t: Throwable) {
            false
        }
        // 把当前启用的方案灌给 LanguageData（小地球键循环用）
        val act = SchemeManager.active()
        if (act == null) {
            LanguageData.applyScheme(null, null, null, null)
        } else {
            LanguageData.applyScheme(act.primary, act.name, act.english, "English")
        }
        // 日语插件和商店方案不是一回事：它是「在现有档位之外多一档」，
        // 所以要在上面定好基础档位之后再追加。
        LanguageData.setExtra(JapanesePlugin.extraLang(appContext))
    }

    /**
     * 方案资源目录（librime 的 shared_data_dir）。
     *
     * 启用商店方案时**直接指向安装目录**，不再复制一份到 rime/shared：
     * 墨奇展开有 211MB，白霜 109MB，手机上多占一倍的空间太亏。
     * librime 只读 shared、只往 user 里写，所以把安装目录当 shared 是安全的。
     */
    val sharedDir: File
        get() {
            val act = SchemeManager.active()
            return if (act != null) {
                SchemeManager.schemeDir(appContext, act.id)
            } else {
                File(appContext.filesDir, "rime/shared")
            }
        }

    val userDir: File get() = File(appContext.filesDir, "rime/user")
    val logDir: File get() = File(appContext.filesDir, "rime/log")

    /** 启动初始化（幂等，重复调用无副作用） */
    fun ensureInitialized() {
        if (!::appContext.isInitialized) return
        if (!initStarted.compareAndSet(false, true)) return
        setState(State.Deploying, null)
        Thread({ doInit() }, "rime-init").start()
    }

    /** 拿到（必要时创建）当前会话 id。UI 线程调用前要先确认 state == Ready。 */
    @Synchronized
    fun ensureSession(): Long {
        if (state != State.Ready) return 0L
        if (sessionId == 0L) {
            sessionId = try {
                RimeNative.nativeCreateSession()
            } catch (t: Throwable) {
                Log.e(TAG, "createSession 失败", t)
                0L
            }
        }
        return sessionId
    }

    /** 当前语言（rime 方案 id）。小地球键切语言时改这个 */
    @Volatile
    var currentSchema: String = LanguageData.Default.schemaId
        private set

    /** 切换语言。成功返回 true，UI 据此刷新空格键上的名字 */
    @Synchronized
    fun switchLanguage(schemaId: String): Boolean {
        val id = sessionId
        if (id == 0L) return false
        val ok = try {
            RimeNative.nativeSelectSchema(id, schemaId)
        } catch (t: Throwable) {
            Log.e(TAG, "selectSchema($schemaId) 失败", t)
            false
        }
        if (ok) {
            currentSchema = schemaId
            Log.i(TAG, "已切到方案 $schemaId")
        } else {
            Log.w(TAG, "方案 $schemaId 切换失败（可能没在 schema_list 里）")
        }
        return ok
    }

    // ------------------------------------------------------------ 运行时开关

    /**
     * 简繁转换的开关名，必须和方案 `switches:` 里那条完全一致。
     *
     * 九个拼音方案和九键（t9 通过 `__include` 继承 rime_ice）都声明了它，
     * 英文方案（melt_eng）没有——所以在英文下开这个不会有任何效果，
     */
    const val OPT_TRADITIONAL = "traditionalization"

    /**
     * 现在是不是繁体输出。
     *
     * 读的是引擎当前会话里的真实状态，不是我们自己的缓存——
     * 这样「用户用 Rime 自带快捷键切过」这种情况也能反映出来。
     */
    fun isTraditional(): Boolean {
        val id = ensureSession()
        if (id == 0L) return traditionalFallback
        return try {
            RimeNative.nativeGetOption(id, OPT_TRADITIONAL)
        } catch (t: Throwable) {
            Log.e(TAG, "读简繁状态失败", t)
            traditionalFallback
        }
    }

    /**
     * 切简繁。返回切换之后的状态。
     *
     * 为什么不用重载：`simplifier@traditionalize` 是在每条候选生成时
     * 读 option 决定的，option 一改，下一次 `getContext` 出来的候选
     * 就已经是转换后的了。所以整个切换是**即时**的，词库不用重新编译。
     */
    fun setTraditional(on: Boolean): Boolean {
        traditionalFallback = on
        // 落盘：引擎里那个 option 进程一死就没了，用户的选择要留住
        try {
            AppPrefs.getInstance().output.traditional.setValue(on)
        } catch (t: Throwable) {
            Log.w(TAG, "存简繁偏好失败", t)
        }
        val id = ensureSession()
        if (id == 0L) {
            // 引擎还没就绪：先记下来，[restoreOptions] 会在会话建好后装回去
            return on
        }
        try {
            RimeNative.nativeSetOption(id, OPT_TRADITIONAL, on)
        } catch (t: Throwable) {
            Log.e(TAG, "设简繁状态失败", t)
            return !on
        }
        Log.i(TAG, "简繁切换：${if (on) "繁体" else "简体"}")
        return isTraditional()
    }

    // ------------------------------------------------------------ 标点全半角

    /**
     * 标点全半角开关名，必须和方案 `switches:` 里那条完全一致。
     *
     * rime_ice 声明了它（states: [半角, 全角]），英文方案（melt_eng）没有。
     * 开的时侯引擎把标点按 `punctuator.full_shape` 表上屏
     * （`,` → `，`、`<` → `《`…），关的时候按 `half_shape`。
     */
    const val OPT_FULL_SHAPE = "full_shape"

    /**
     * 现在标点是不是全角。
     *
     * 读引擎里真实状态，和 [isTraditional] 同一套路。
     */
    fun isFullShape(): Boolean {
        val id = ensureSession()
        if (id == 0L) return fullShapeFallback
        return try {
            RimeNative.nativeGetOption(id, OPT_FULL_SHAPE)
        } catch (t: Throwable) {
            Log.e(TAG, "读标点全半角状态失败", t)
            fullShapeFallback
        }
    }

    /**
     * 切标点全半角。返回切换之后的状态。
     *
     * punctuator 每次查表时读 option，所以是即时的，词库不用重新编译。
     */
    fun setFullShape(on: Boolean): Boolean {
        fullShapeFallback = on
        try {
            AppPrefs.getInstance().output.fullShape.setValue(on)
        } catch (t: Throwable) {
            Log.w(TAG, "存标点全半角偏好失败", t)
        }
        val id = ensureSession()
        if (id == 0L) return on
        try {
            RimeNative.nativeSetOption(id, OPT_FULL_SHAPE, on)
        } catch (t: Throwable) {
            Log.e(TAG, "设标点全半角状态失败", t)
            return !on
        }
        Log.i(TAG, "标点全半角切换：${if (on) "全角" else "半角"}")
        return isFullShape()
    }

    // ------------------------------------------------------------ 下一词预测

    /**
     * 下一词预测的开关名，必须和方案 `switches:` 里那条完全一致。
     *
     * 九个拼音方案和九键都声明了它（t9 通过 `__include` 继承 rime_ice），
     * 英文方案（melt_eng）没有。
     */
    const val OPT_PREDICTION = "prediction"

    /**
     * 现在开没开预测。
     *
     * 和 [isTraditional] 一样，读引擎里的真实状态而不是我们的缓存。
     */
    fun isPrediction(): Boolean {
        val id = ensureSession()
        if (id == 0L) return predictionFallback
        return try {
            RimeNative.nativeGetOption(id, OPT_PREDICTION)
        } catch (t: Throwable) {
            Log.e(TAG, "读预测开关失败", t)
            predictionFallback
        }
    }

    /**
     * 开/关预测。返回切换之后的状态。
     *
     * 为什么不用重载：`predictor` 这个 processor 在每次上下文更新时
     * 都会检查 `get_option("prediction")`，option 一关就跳过预测、
     * 顺便把已经挂上去的预测段清掉，所以切换是即时的。
     */
    fun setPrediction(on: Boolean): Boolean {
        predictionFallback = on
        try {
            AppPrefs.getInstance().output.prediction.setValue(on)
        } catch (t: Throwable) {
            Log.w(TAG, "存预测偏好失败", t)
        }
        val id = ensureSession()
        if (id == 0L) return on
        try {
            RimeNative.nativeSetOption(id, OPT_PREDICTION, on)
        } catch (t: Throwable) {
            Log.e(TAG, "设预测开关失败", t)
            return !on
        }
        Log.i(TAG, "预测开关：${if (on) "开" else "关"}")
        return isPrediction()
    }

    /**
     * 把偏好装回引擎。
     *
     * 会话刚建好、或者切完方案之后调——`Context::Clear()` 不动 options_，
     * 所以其实切换方案后原状态还在；这里主要是为了「进程重启」那一刻：
     * 新会话的 Context 是空的，得把用户上次的选择重新灌一遍。
     */
    private fun restoreOptions(session: Long) {
        if (session == 0L) return
        try {
            RimeNative.nativeSetOption(session, OPT_TRADITIONAL, traditionalFallback)
            Log.i(TAG, "简繁状态恢复为：${if (traditionalFallback) "繁体" else "简体"}")
        } catch (t: Throwable) {
            Log.e(TAG, "恢复简繁状态失败", t)
        }
        try {
            RimeNative.nativeSetOption(session, OPT_PREDICTION, predictionFallback)
            Log.i(TAG, "预测开关恢复为：${if (predictionFallback) "开" else "关"}")
        } catch (t: Throwable) {
            Log.e(TAG, "恢复预测开关失败", t)
        }
        try {
            RimeNative.nativeSetOption(session, OPT_FULL_SHAPE, fullShapeFallback)
            Log.i(TAG, "标点全半角状态恢复为：${if (fullShapeFallback) "全角" else "半角"}")
        } catch (t: Throwable) {
            Log.e(TAG, "恢复标点全半角状态失败", t)
        }
    }

    /** 是否正在重载（换方案时用，防止并发重复进入） */
    @Volatile
    var reloading: Boolean = false
        private set

    /**
     * 换方案后重新部署：**不杀进程**，只把引擎复位再初始化一遍。
     *
     * 以前换方案是「起 Activity + killProcess」一锅端，代价是
     * 商店页也跟着没了、键盘要重新被系统拉起。现在改成在进程内做：
     *
     *   1. 销毁会话（librime 里会话持有 schema / 词库句柄）
     *   2. nativeFinalize  → librime 回到 Initialize 之前的状态
     *   3. 清掉旧的错误和自检结果，状态回到 Deploying
     *   4. 直接走 [doInit]：铺新方案的包 → nativeInit → 建会话
     *      （initStarted 故意不复位，免得别的线程趁空档再起一条 doInit）
     *
     * 全程在后台线程做，状态照样通过 [callback] 往外播，
     * 键盘上那条「正在部署…」的提示会自己亮起来。
     *
     * 返回 false 表示上一次重载还没结束，这次就跳过了。
     */
    fun reload(): Boolean {
        if (!::appContext.isInitialized) return false
        if (reloading) {
            Log.i(TAG, "重载已在进行中，忽略这次请求")
            return false
        }
        reloading = true
        setState(State.Deploying, null)
        Thread({
            val t0 = System.currentTimeMillis()
            try {
                // 1) 会话先放掉：它还握着旧方案的 schema 和词库
                releaseSession()
                // 2) 复位 librime
                try {
                    RimeNative.nativeFinalize()
                    Log.i(TAG, "librime 已复位")
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeFinalize 失败，后续初始化可能不生效", t)
                }
                // 3) 清掉上一次的错误和自检结果，状态机回到「部署中」
                //    注意：**不能**把 initStarted 复位——它一放开，
                //    别的线程（键盘服务）就会 CAS 成功再起一条 doInit，
                //    两条线程同时 setup/initialize 会把 librime 弄坏。
                //    这里直接自己调 doInit，initStarted 保持 true 就行。
                currentSchema = LanguageData.Default.schemaId
                lastError = null
                setState(State.Deploying, null)
                // 4) 重新初始化（内部会重新铺包 + 部署）
                doInit()
                Log.i(
                    TAG,
                    "换方案重载完成，用时 ${System.currentTimeMillis() - t0}ms，" +
                        "方案=${SchemeManager.active()?.name ?: "内置雾凇拼音"}"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "重载失败", t)
                fail(t.message ?: t.javaClass.simpleName)
            } finally {
                reloading = false
            }
        }, "rime-reload").start()
        return true
    }

    /** 释放会话（输入法服务销毁时调用） */
    @Synchronized
    fun releaseSession() {
        val id = sessionId
        sessionId = 0L
        if (id != 0L) {
            try {
                RimeNative.nativeDestroySession(id)
            } catch (t: Throwable) {
                Log.w(TAG, "destroySession 失败", t)
            }
        }
    }

    private fun doInit() {
        val t0 = System.currentTimeMillis()
        try {
            deployAssets()
            val version = appVersionName()
            val ok = RimeNative.nativeInit(
                sharedDir.absolutePath,
                userDir.absolutePath,
                logDir.absolutePath,
                version,
                true
            )
            if (!ok) {
                fail("nativeInit 返回 false")
                return
            }
            val id = RimeNative.nativeCreateSession()
            if (id == 0L) {
                fail("createSession 返回 0")
                return
            }
            sessionId = id
            // 选定默认方案。
            // 换了商店方案之后 Default 已经不是雾凇了，先按档位表把旧 id 兜正：
            // 引擎里的 user/prefs 可能还记着上次的 rime_ice，那套文件现在根本不在。
            val want = LanguageData.Default.schemaId
            RimeNative.nativeSelectSchema(id, want)
            currentSchema = want
            // 把用户上次选的简繁状态装回去：option 只活在内存里，
            // 进程一死就没了，但用户的偏好不该跟着没了。
            restoreOptions(id)
            val cost = System.currentTimeMillis() - t0
            Log.i(TAG, "引擎就绪，用时 ${cost}ms，version=${RimeNative.nativeGetVersion()}")
            setState(State.Ready, null)
            main.post { callback?.onReady() }

            // 部署完顺手跑一次自检，验证「敲键 → 出候选 → 上屏」整条链路
            selfTest()
        } catch (t: Throwable) {
            Log.e(TAG, "初始化失败", t)
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 引擎自检。
     *
     * 不碰 UI，也不依赖真机点键盘：开一个临时会话，把 "zimeng" 逐键喂进去，
     * 再检查 preedit / 候选 / 选词上屏 / 空格上屏 / 翻页 / 标点 这几步。
     * 结果整理成几行中文结论，既进日志也回调给设置页显示。
     */
    private fun selfTest() {
        if (selfTestRunning) return
        selfTestRunning = true
        main.post { callback?.onSelfTest(null) }

        val t0 = System.currentTimeMillis()
        val lines = ArrayList<String>()
        var allOk = true
        var probe = 0L

        fun line(ok: Boolean, text: String) {
            if (!ok) allOk = false
            lines.add((if (ok) "✅ " else "❌ ") + text)
        }

        try {
            probe = RimeNative.nativeCreateSession()
            if (probe == 0L) {
                line(false, "创建会话失败（nativeCreateSession 返回 0）")
            } else {
                line(true, "创建会话成功")

                val probeSchema = LanguageData.Default
                val schemaOk = try {
                    RimeNative.nativeSelectSchema(probe, probeSchema.schemaId)
                } catch (t: Throwable) {
                    false
                }
                line(schemaOk, "方案 ${probeSchema.schemaId} 选择${if (schemaOk) "成功" else "失败"}")

                // ---- 逐键输入 "zimeng" ----
                val word = "zimeng"
                var eaten = 0
                for (ch in word) {
                    if (RimeNative.nativeProcessKey(probe, ch.code, 0)) eaten++
                }
                line(eaten == word.length, "按键 \"$word\"：引擎接受 $eaten/${word.length} 个键")

                val ctx = RimeNative.getContext(probe)
                val preedit = ctx?.preedit.orEmpty()
                val candidates = ctx?.candidates ?: emptyList()
                line(preedit.isNotEmpty(), "preedit = ${if (preedit.isEmpty()) "(空)" else preedit}")
                // sel 区间是新加的字段，顺手验一下：有输入码时应该指向串尾那一段
                if (ctx != null && preedit.isNotEmpty()) {
                    lines.add(
                        "ℹ sel 区间：${ctx.selStart}..${ctx.selEnd}" +
                            if (ctx.selStart >= 0 && ctx.selEnd > ctx.selStart) {
                                "（高亮 \"${preedit.substring(ctx.selStart, ctx.selEnd)}\"）"
                            } else {
                                "（无分段）"
                            }
                    )
                }
                line(candidates.isNotEmpty(), "候选词 ${candidates.size} 个：${candidates.take(6).joinToString(" / ")}")
                val menu = RimeNative.nativeGetMenuInfo(probe)?.toList()
                if (menu != null && menu.size >= 3) {
                    lines.add("ℹ 菜单信息：页码 ${menu[0]}，末页 ${menu[1] == 1}，候选数 ${menu[2]}")
                }

                // ---- 选第一个候选上屏 ----
                if (candidates.isNotEmpty()) {
                    val picked = RimeNative.nativeSelectCandidate(probe, 0)
                    val commit = RimeNative.nativeGetCommit(probe)
                    line(picked && !commit.isNullOrBlank(), "选首候选上屏 → ${commit ?: "(空)"}")
                } else {
                    line(false, "没有候选，跳过选词测试")
                }

                // ---- 再打一遍，用空格上屏 ----
                RimeNative.nativeClearComposition(probe)
                for (ch in "nihao") RimeNative.nativeProcessKey(probe, ch.code, 0)
                val spaceEaten = RimeNative.nativeProcessKey(probe, 0x20, 0)
                val spaceCommit = RimeNative.nativeGetCommit(probe)
                line(
                    spaceEaten && !spaceCommit.isNullOrBlank(),
                    "空格上屏 \"nihao\" → ${spaceCommit ?: "(空)"}"
                )

                // ---- 翻页 ----
                RimeNative.nativeClearComposition(probe)
                for (ch in "shi") RimeNative.nativeProcessKey(probe, ch.code, 0)
                val page0 = RimeNative.nativeGetMenuInfo(probe)?.getOrNull(0)
                val turned = RimeNative.nativeChangePage(probe, false)
                val page1 = RimeNative.nativeGetMenuInfo(probe)?.getOrNull(0)
                // 新格式：前 4 个是 preedit/sel_start/sel_end/caret_pos，候选从下标 4 开始
                val pageCtx = RimeNative.nativeGetContext(probe)?.drop(4)?.filter { it.isNotBlank() }?.take(3)
                line(
                    turned && page1 != null && page0 != null && page1 > page0,
                    "翻页：第 ${page0 ?: "?"} 页 → 第 ${page1 ?: "?"} 页，候选 ${
                        pageCtx?.joinToString("/") ?: "-"
                    }"
                )

                // ---- 标点 ----
                RimeNative.nativeClearComposition(probe)
                for (ch in "hao") RimeNative.nativeProcessKey(probe, ch.code, 0)
                RimeNative.nativeProcessKey(probe, 0x20, 0) // 先上屏，让标点从干净状态走
                RimeNative.nativeGetCommit(probe)
                val commaEaten = RimeNative.nativeProcessKey(probe, ','.code, 0)
                val commaCommit = RimeNative.nativeGetCommit(probe)
                line(
                    commaEaten && !commaCommit.isNullOrBlank(),
                    "标点 ',': 引擎处理=$commaEaten，上屏 ${commaCommit ?: "(空)"}"
                )
            }
        } catch (t: Throwable) {
            allOk = false
            lines.add("❌ 自检异常：${t.javaClass.simpleName} ${t.message}")
            Log.e(TAG, "自检失败", t)
        } finally {
            if (probe != 0L) {
                try {
                    RimeNative.nativeDestroySession(probe)
                } catch (t: Throwable) {
                    Log.w(TAG, "自检 session 释放失败", t)
                }
            }
        }

        val cost = System.currentTimeMillis() - t0
        val report = SelfTestReport(allOk, lines, cost)
        selfTestRunning = false

        Log.i(TAG, "自检完成（${cost}ms）结果=${if (allOk) "通过" else "有问题"}")
        lines.forEach { Log.i(TAG, "  自检 $it") }
        main.post { callback?.onSelfTest(report) }
    }

    private fun fail(msg: String) {
        lastError = msg
        Log.e(TAG, "初始化失败：$msg")
        setState(State.Failed, msg)
    }

    private fun setState(s: State, msg: String?) {
        state = s
        main.post { callback?.onStateChanged(s, msg) }
    }

    /**
     * 准备方案资源目录。
     *
     * ── 两套来源 ────────────────────────────────────────────
     * 1. **APK 内置**（默认）：把 assets/rime 铺到 `rime/shared`，就是雾凇那套。
     * 2. **商店方案**：直接把 `filesDir/store/<id>` 当 shared，**不复制**。
     *    墨奇展开 211MB、白霜 109MB，复制一份等于白占一倍空间。
     *
     * 两套互斥：四套方案有 50 多个同名文件、版本还各不相同，混着用必然坏。
     *
     * 要不要重铺，靠两枚标记判断：`RIME_ASSET_VERSION`（内置那套的版本）
     * 和 `.active_scheme`（当时用的是哪套）。两者都存在 `rime/user` 下，
     * 因为商店方案生效时 shared 是别人的目录，不能往里面写东西。
     */
    private fun deployAssets() {
        userDir.mkdirs()
        logDir.mkdirs()

        val active = SchemeManager.active()
        val activeId = active?.id.orEmpty()

        // 官方包有几个默认值不适合我们这块键盘：一屏 5 个候选太少、雾凇的大字表
        // （4 万多扩展汉字）还注释着。装好之后就地补一次，幂等——本来就对的不会写文件。
        val patched = if (active != null) {
            try {
                normalizeScheme(SchemeManager.schemeDir(appContext, active.id))
            } catch (t: Throwable) {
                Log.w(TAG, "方案配置补丁失败（不影响启动）", t)
                false
            }
        } else {
            false
        }

        val stamp = File(userDir, ".asset_version")
        val activeStamp = File(userDir, ".active_scheme")
        val assetSame = stamp.isFile && stamp.readText().trim() == RIME_ASSET_VERSION
        val schemeSame = activeStamp.isFile && activeStamp.readText().trim() == activeId

        // 商店方案的目录被删/被清时，标记还指着它——不能就这样把空目录交给引擎
        val storeGone = active != null && !SchemeManager.hasFiles(appContext, active.id)

        // 内置插件（日本語）的磁盘状态要和开关对齐。它落在 user 目录、
        // 不参与上面那套 shared 的铺包，所以单独对一次账。
        // 放在「跳过铺包」判断之前：插件自己变了也要让这次部署继续往下走。
        val pluginChanged = try {
            JapanesePlugin.reconcile(appContext)
        } catch (t: Throwable) {
            Log.w(TAG, "日语插件对账失败（不影响启动）", t)
            false
        }

        if (assetSame && schemeSame && !storeGone && !patched && !pluginChanged) {
            Log.i(
                TAG,
                "方案资源没变（$RIME_ASSET_VERSION / ${activeId.ifEmpty { "内置" }}" +
                    "${if (JapanesePlugin.isActive(appContext)) " + 日本語" else ""}），跳过铺包"
            )
            return
        }

        if (storeGone) {
            // 标记指向的方案目录不见了（被移除/清数据）：退回内置，别让引擎起不来
            Log.w(
                TAG,
                "方案 ${active!!.id} 的文件不在了（目录=${SchemeManager.schemeDir(appContext, active.id)}），退回内置方案"
            )
            SchemeManager.deactivate(appContext)
        }

        val useBuiltin = SchemeManager.active() == null
        // 这次是真的换了方案（或内置包版本变了）吗？
        // 只有这种情况才值得把整个 build 全删——雾凇 50MB 重编一次要几十秒。
        val schemeSwitched = !assetSame || !schemeSame || storeGone
        if (useBuiltin) {
            Log.i(TAG, "准备铺开内置方案（$RIME_ASSET_VERSION）")
            val dir = File(appContext.filesDir, "rime/shared")
            dir.deleteRecursivelyKeepDirs()
            dir.mkdirs()
            copyAssetDir(appContext.assets, "rime", dir)
        } else {
            Log.i(TAG, "使用商店方案 ${active?.name}（目录直接用，不复制）")
        }

        if (schemeSwitched) {
            // 换过方案之后，编译缓存一律作废：build 里的 .bin 是按旧那套 yaml 编的，
            // 两套有 50 多个同名文件、版本还不同，留着会被当成同一份误用。
            // 代价只是下次部署多花几十秒，换来的是不会冒出奇怪的字。
            File(userDir, "build").deleteRecursively()
        } else {
            // 方案没换、只有插件变了：只清日语自己那几个 .bin。
            // 不这么分开的话，改一下日语 yaml 就要让雾凇陪着重编一次。
            JapanesePlugin.purgeBuildCacheForEngine(appContext)
        }

        stamp.writeText(RIME_ASSET_VERSION)
        activeStamp.writeText(activeId)
        Log.i(TAG, "方案资源就绪")
    }

    /**
     * 把官方包默认配置里「不适合这块键盘」的几处就地补齐。
     *
     * 全是幂等的：本来就是对的不会写文件，返回 false。
     *
     * ── 补哪几处 ────────────────────────────────────────────
     * 1. **page_size**：雾凇 5、墨奇 5，比候选栏能放的数量（[com.jimeng.ime.view.CandidateBar.MAX_ITEMS]）
     *    还少一格——一屏少一个字，第二页又翻不出来，看着就像「词库很小」。
     *    小于 6 的一律补到 6；已经 6（万象）或更大（白霜 8）的保持原样，
     *    那是人家方案自己的取舍，不去改。
     * 2. **41448 大字表**：雾凇把它注释着（写着「按需启用」），墨奇也关着。
     *    不开的话，超出 8105 常用规范字的字一个候选都没有——这是「打生僻字
     *    没字」的真正原因。白霜本来就没注释，所以只对注释着的动手。
     *
     * @return 有没有真的改过文件（改过就要清部署缓存，让 librime 重编）
     */
    private fun normalizeScheme(dir: File): Boolean {
        if (!dir.isDirectory) return false
        var changed = false

        // ---- 1) page_size 补到 6 ----
        val def = File(dir, "default.yaml")
        if (def.isFile) {
            try {
                val text = def.readText()
                val re = Regex("""(?m)^([ \t]*page_size[ \t]*:[ \t]*)(\d+)(.*)$""")
                val m = re.find(text)
                if (m != null) {
                    val cur = m.groupValues[2].toIntOrNull() ?: 0
                    if (cur in 1 until MIN_PAGE_SIZE) {
                        def.writeText(
                            text.replaceRange(
                                m.range,
                                m.groupValues[1] + MIN_PAGE_SIZE + m.groupValues[3]
                            )
                        )
                        changed = true
                        Log.i(TAG, "${dir.name} page_size $cur → $MIN_PAGE_SIZE")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "${dir.name} 改 page_size 失败", t)
            }
        }

        // ---- 2) 打开被注释掉的大字表 ----
        // 各家的写法不一样：雾凇和白霜在 `<主方案>.dict.yaml` 里写
        // `# - cn_dicts/41448`，墨奇写在 `moqi.extended.dict.yaml` 里、
        // 路径是 `cn_dicts_moqi/41448`。所以不猜文件名，把顶层所有
        // `*.dict.yaml` 扫一遍，只认「被注释掉的 41448 导入行」——
        // 其它被注释的库（tencent、others、chengyu…）一律不碰，
        // 那些是方案作者权衡过体积才关掉的，不该我们替他决定。
        var bigTableOn = false
        dir.listFiles()?.forEach { f ->
            // 只读小文件：带 import_tables 的那个文件本来就只有几十行，
            // 十几 MB 的 base/tencent 那种是纯词条，不用碰——省掉启动时白读几十 MB。
            if (!bigTableOn && f.isFile && f.name.endsWith(".dict.yaml") &&
                f.length() <= 512 * 1024
            ) {
                try {
                    val text = f.readText()
                    // 匹配形如：
                    //   # - cn_dicts/41448     # 4万字大字表 可以关闭
                    //   #- cn_dicts_moqi/41448
                    // 允许前缀无缩进、`#` 后无空格、路径前有任意层级目录。
                    val re = Regex("""(?m)^[ \t]*#[ \t]*-[ \t]*((?:[\w.\-]+/)*)41448\b[^\n]*$""")
                    val m = re.find(text)
                    if (m != null) {
                        val path = m.groupValues[1] + "41448"
                        // 引用的文件必须真的在，否则开了会让部署直接失败——
                        // 宁可维持原样少几个生僻字，也不能把引擎弄起不来。
                        if (!File(dir, "$path.dict.yaml").isFile) {
                            Log.w(TAG, "${dir.name} 想让 $path 生效，但文件不在，跳过")
                        } else {
                            f.writeText(
                                text.replaceRange(
                                    m.range,
                                    "  - $path    # 大字表（4 万多扩展汉字，已启用）"
                                )
                            )
                            changed = true
                            bigTableOn = true
                            Log.i(TAG, "${dir.name} 启用大字表 $path（${f.name}）")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "${dir.name} 处理 ${f.name} 失败", t)
                }
            }
        }
        if (!bigTableOn) Log.i(TAG, "${dir.name} 没有需要打开的 41448 大字表（或已启用）")

        if (changed) Log.i(TAG, "方案 ${dir.name} 配置已补齐，稍后重编词库")
        return changed
    }

    private fun File.deleteRecursivelyKeepDirs() {
        // 只清 shared 下的内容，user 目录留着（存用户词典和配置）
        listFiles()?.forEach { f ->
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
    }

    private fun copyAssetDir(
        assets: android.content.res.AssetManager,
        assetPath: String,
        target: File
    ) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // 是文件
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            return
        }
        for (name in children) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val childTarget = File(target, name)
            val grand = assets.list(childAsset) ?: emptyArray()
            if (grand.isEmpty()) {
                childTarget.parentFile?.mkdirs()
                assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            } else {
                childTarget.mkdirs()
                copyAssetDir(assets, childAsset, childTarget)
            }
        }
    }

    private fun appVersionName(): String = try {
        val pkg = appContext.packageManager
        pkg.getPackageInfo(appContext.packageName, 0).versionName ?: "0.0"
    } catch (t: Throwable) {
        "0.0"
    }
}