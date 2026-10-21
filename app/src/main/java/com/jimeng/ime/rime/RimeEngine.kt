package com.jimeng.ime.rime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jimeng.ime.kb.core.LanguageData
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

    /** 最近一次自检结果，null 表示还没跑过 */
    @Volatile
    var lastSelfTest: SelfTestReport? = null
        private set

    /** 自检是否正在跑 */
    @Volatile
    var selfTestRunning: Boolean = false
        private set

    private lateinit var appContext: Context

    /** 方案资源是否需要重新铺开（版本号或方案标记变化时才铺） */
    private const val RIME_ASSET_VERSION = "rime-ice-3"

    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    val sharedDir: File get() = File(appContext.filesDir, "rime/shared")
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

    /** 手动触发一次自检（后台线程跑，结果回调到 UI） */
    fun runSelfTest() {
        if (state != State.Ready) return
        if (selfTestRunning) return
        Thread({ selfTest() }, "rime-selftest").start()
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
            // 选定默认语言（雾凇拼音）
            RimeNative.nativeSelectSchema(id, LanguageData.Default.schemaId)
            currentSchema = LanguageData.Default.schemaId
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

                val schemaOk = try {
                    RimeNative.nativeSelectSchema(probe, "rime_ice")
                } catch (t: Throwable) {
                    false
                }
                line(schemaOk, "方案 rime_ice 选择${if (schemaOk) "成功" else "失败"}")

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
        lastSelfTest = report
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

    /** 把 assets/rime 铺到 filesDir，只在版本变化时重建 */
    private fun deployAssets() {
        sharedDir.mkdirs()
        userDir.mkdirs()
        logDir.mkdirs()

        val stamp = File(sharedDir, ".asset_version")
        if (stamp.isFile && stamp.readText().trim() == RIME_ASSET_VERSION) {
            Log.i(TAG, "方案资源已是 $RIME_ASSET_VERSION，跳过铺包")
            return
        }

        Log.i(TAG, "开始铺开方案资源")
        sharedDir.deleteRecursivelyKeepDirs()
        sharedDir.mkdirs()
        val assets = appContext.assets
        copyAssetDir(assets, "rime", sharedDir)
        stamp.writeText(RIME_ASSET_VERSION)
        Log.i(TAG, "方案资源铺开完成")
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