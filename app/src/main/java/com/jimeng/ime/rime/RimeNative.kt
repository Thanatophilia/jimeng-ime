package com.jimeng.ime.rime

/**
 * librime 的 JNI 桥。
 *
 * 这个类的方法签名必须和 libjimeng_rime.so 里导出的符号严格对应，
 * 否则 JNI 会找不到方法直接抛 UnsatisfiedLinkError。
 *
 * 对应符号：Java_com_jimeng_ime_rime_RimeNative_<方法名>
 *
 * 下面的返回结构不是猜的，是按 .so 里 RimeContext / RimeMenu 的
 * 结构体偏移反推出来的（详见各方法注释）。
 */
object RimeNative {

    init {
        System.loadLibrary("jimeng_rime")
    }

    // ---- 生命周期 ----

    /**
     * nativeInit(sharedDir, userDir, logDir, version, fullCheck) -> Boolean
     *
     * 内部顺序：setup → initialize → start_maintenance(fullCheck) → join_maintenance_thread，
     * 也就是说**部署是在这个调用里同步完成的**，首次跑要编译 50M 词库，几十秒起步。
     * 重复调用直接返回 true（幂等）。
     */
    external fun nativeInit(
        sharedDir: String,
        userDir: String,
        logDir: String,
        version: String,
        fullCheck: Boolean
    ): Boolean

    /** nativeGetVersion() -> String */
    external fun nativeGetVersion(): String

    /**
     * nativeFinalize() —— 复位 librime，让下一次 [nativeInit] 重新部署。
     *
     * 换方案以前只能杀进程，因为 nativeInit 里有个「只跑一次」的开关。
     * 现在改成调用 librime 自己的 `RimeApi::finalize`：
     *   join 维护线程 → StopService（清空全部会话）
     *                 → Registry::Clear()（组件注册表清空）
     *                 → ModuleManager::UnloadModules()（模块 finalize 后卸下）
     * 做完之后内部状态回到 Initialize 之前，再 setup + initialize 就是干净的一轮。
     *
     * 调用前必须先把会话销毁干净（[RimeEngine.releaseSession]）。
     * 没有初始化过时是空操作，可以放心重复调用。
     */
    external fun nativeFinalize()

    // ---- 会话 ----

    /** nativeCreateSession() -> Long（0 表示失败） */
    external fun nativeCreateSession(): Long

    /** nativeDestroySession(session) */
    external fun nativeDestroySession(session: Long)

    /** nativeProcessKey(session, keycode, mask) -> Boolean（true 表示引擎吃掉了这个键） */
    external fun nativeProcessKey(session: Long, keycode: Int, mask: Int): Boolean

    /** nativeCommitComposition(session) -> Boolean */
    external fun nativeCommitComposition(session: Long): Boolean

    /** nativeClearComposition(session) */
    external fun nativeClearComposition(session: Long)

    /**
     * nativeSetInput(session, input) -> Boolean
     *
     * 直接把引擎里的输入串换成 [input]，**不删字母、不重打**。
     *
     * librime 的 `Context::set_input` 只改 `input_` 和 `caret_pos_`，
     * 不碰 `composition_`——已经选成汉字的部分一个字都不会丢。
     * 换谐音走这条，就不会有「删光字母→再一个个打回去」的中间状态。
     *
     * 返回 false 表示这台机器的 librime 没导出 `set_input`（老版本），
     * 调用方应该回退到旧的按键重打路线。
     */
    external fun nativeSetInput(session: Long, input: String): Boolean

    /**
     * nativeGetInput(session) -> String?
     *
     * 取引擎里的**整串**原始输入，含已经变成汉字的那部分字母。
     * 例如 `不haowan` 时它仍然是 `buhaowan`。
     *
     * 换谐音必须拿它当底：nativeSetInput 收的是整串输入，
     * 少掉 `bu` 前缀，Segmentation::Reset 从头比对 diff_pos 会变成 0，
     * 已经选好的「不」那一整段会被全部丢掉。
     */
    external fun nativeGetInput(session: Long): String?

    /**
     * nativeGetCaretPos(session) -> Int
     *
     * 光标在**原始输入串 `input()`** 上的下标（-1 = 拿不到）。
     *
     * 注意它不是 preedit 里的下标：`preedit` 是显示串，会被 `preedit_format`
     * 改写（雾凇把 `jv` 写成 `ju`，双拼更是整串改写），
     * 而 `input()` 才是用户真实敲的按键串。换谐音、算分段都得在 `input()` 的
     * 坐标里做，否则「用显示串的字母去对齐按键串」必然错位。
     *
     * 引擎自己会按音节跳光标（navigator 的 left/right_by_syllable，
     * 雾凇绑在 Shift+Left / Shift+Right）。让它跳、我们只读停靠点，
     * 就能**把权威音节边界量出来**，不用猜、也不用碰 librime 内部 C++ 类。
     */
    external fun nativeGetCaretPos(session: Long): Int

    /**
     * nativeSetCaretPos(session, pos) -> Boolean
     *
     * 把光标设到 `input()` 上的 pos（对应 `RimeApi::set_caret_pos`）。
     * 只改 caret_pos_ 并让引擎重算 composition，不碰 input_、不丢已选的汉字。
     * 探测完边界用它把光标还原回去。
     */
    external fun nativeSetCaretPos(session: Long, pos: Int): Boolean

    /** nativeGetCommit(session) -> String?（没有待上屏内容时返回 null） */
    external fun nativeGetCommit(session: Long): String?
    /**
     * nativeGetContext(session) -> Array<String>?
     *
     * 返回数组布局（4 个固定字段 + 候选）：
     *   [0]     preedit（composition.preedit）
     *   [1]     sel_start（十进制字符串，-1 表示没有）
     *   [2]     sel_end  （十进制字符串，-1 表示没有）
     *   [3]     caret_pos（十进制字符串，-1 表示没有）
     *   [4..n]  当前页候选词文本（menu.candidates[i].text）
     *
     * sel_start/sel_end 是 rime 里「正在转换、还没上屏」那一段在 preedit
     * 里的字符下标，fcitx5-android 就是靠它做码区高亮的。
     *
     * 注意：**没有页码字段**，页码要用 nativeGetMenuInfo 拿。
     * 没有输入时返回 null。
     */
    external fun nativeGetContext(session: Long): Array<String>?

    /**
     * 解析 [nativeGetContext] 的返回。
     *
     * 把字符串数组拆成结构化数据，调用方不用自己记下标。
     *
     * @return [Context] 或 null（引擎没给上下文）
     */
    fun getContext(session: Long): Context? {
        val raw = try {
            nativeGetContext(session)
        } catch (t: Throwable) {
            null
        } ?: return null
        if (raw.size < 4) return null
        val preedit = raw[0] ?: ""
        val selStart = raw[1]?.toIntOrNull() ?: -1
        val selEnd = raw[2]?.toIntOrNull() ?: -1
        val caretPos = raw[3]?.toIntOrNull() ?: -1
        val candidates = raw.drop(4).filterNotNull().filter { it.isNotEmpty() }
        return Context(preedit, selStart, selEnd, caretPos, candidates)
    }

    /**
     * 一次取到的输入上下文。
     *
     * @param preedit    输入码整串（如 "ni hao"）
     * @param selStart   高亮段起点（-1 = 没有分段信息）
     * @param selEnd     高亮段终点（-1 = 没有分段信息）
     * @param caretPos   光标位置（-1 = 没有）
     * @param candidates 当前页候选词
     */
    data class Context(
        val preedit: String,
        val selStart: Int,
        val selEnd: Int,
        val caretPos: Int,
        val candidates: List<String>
    )

    /**
     * nativeGetMenuInfo(session) -> IntArray?（固定 3 个元素）
     *   [0] 当前页码 page_no（从 0 开始）
     *   [1] 是否最后一页（0/1）
     *   [2] 当前页候选数
     */
    external fun nativeGetMenuInfo(session: Long): IntArray?

    /** nativeSelectSchema(session, schemaId) -> Boolean */
    external fun nativeSelectSchema(session: Long, schemaId: String): Boolean

    /** nativeSelectCandidate(session, index) -> Boolean（index 是当前页内的下标，从 0 开始） */
    external fun nativeSelectCandidate(session: Long, index: Int): Boolean

    /** nativeChangePage(session, backward) -> Boolean（backward=true 上翻，false 下翻） */
    external fun nativeChangePage(session: Long, backward: Boolean): Boolean

    /** nativeSelectCandidateGlobal(session, index) -> Boolean（整句候选里的绝对下标） */
    external fun nativeSelectCandidateGlobal(session: Long, index: Int): Boolean

    /** nativeGetAllCandidates(session) -> Array<String>?（用于展开的候选列表） */
    external fun nativeGetAllCandidates(session: Long): Array<String>?

    /**
     * 设一个运行时开关（option）。作用在当前会话的 Context 上。
     *
     * 简繁切换就靠它：方案里 `switches: traditionalization: [简, 繁]`，
     * `filters: simplifier@traditionalize` 读这个开关决定要不要过 opencc。
     * 不换方案、不重载词库，改完下一个键就按新状态出候选。
     *
     * 会话必须已经建立（[nativeCreateSession] 之后）。
     */
    external fun nativeSetOption(session: Long, name: String, value: Boolean)

    /**
     * 读一个运行时开关。
     *
     * 没设过、方案没声明、会话不在，都返回 false——
     * 对「简繁」来说就是「按简体来」，和方案默认一致。
     */
    external fun nativeGetOption(session: Long, name: String): Boolean
}