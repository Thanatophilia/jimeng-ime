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

    /** nativeGetCommit(session) -> String?（没有待上屏内容时返回 null） */
    external fun nativeGetCommit(session: Long): String?

    /**
     * nativeGetContext(session) -> Array<String>?
     *
     * 返回数组长度 = 候选数 + 1：
     *   [0]     preedit（composition.preedit）
     *   [1..n]  当前页候选词文本（menu.candidates[i].text）
     *
     * 注意：**没有页码字段**，页码要用 nativeGetMenuInfo 拿。
     * 没有输入时返回 null。
     */
    external fun nativeGetContext(session: Long): Array<String>?

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
}