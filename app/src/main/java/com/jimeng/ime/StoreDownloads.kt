package com.jimeng.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 正在进行的下载（进程级）。
 *
 * ── 为什么要单独拎出来 ───────────────────────────────────
 * 231MB 的模型下起来要几分钟，用户一定会切走（回消息、锁屏、去别处）。
 * 如果下载状态挂在 Activity 上，页面一销毁状态就丢了，回来再点「下载」
 * 就会**再开一条线程下同一份文件**，两个线程往同一个临时文件写，
 * 结果一定是坏的。
 *
 * 所以：任务归这个单例管，Activity 只是「观察者」，来去自由。
 *   · Activity 走 → 取消注册，下载继续
 *   · Activity 回来 → 读 [task] 拿当前进度，接着画
 *
 * 进度写在 [Task] 的 volatile 字段里，所以新挂上来的界面
 * 立刻就能显示正确位置，不用等下一次回调。
 */
object StoreDownloads {
    private const val TAG = "JimengStore"

    /** 一个下载任务。字段都是 volatile：下载线程写、界面线程读。 */
    class Task internal constructor(val itemId: String) {
        internal val cancel = AtomicBoolean(false)

        /** 当前文件已完成字节 */
        @Volatile var done: Long = 0L

        /** 当前文件总字节 */
        @Volatile var total: Long = 0L

        /** 当前在下的文件名 */
        @Volatile var fileName: String = ""

        /** 换源提示：第一个地址不通时会短暂显示 */
        @Volatile var note: String? = null
    }

    /** 界面要听的两种事。都在主线程回调。 */
    interface Listener {
        fun onProgress(itemId: String) {}
        fun onFinished(itemId: String, success: Boolean, reason: String?) {}
    }

    private val tasks = ConcurrentHashMap<String, Task>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    /** 现在有没有在下的（按商店 id）。 */
    fun isRunning(itemId: String): Boolean = tasks.containsKey(itemId)

    /** 拿任务快照，没在跑就返回 null。 */
    fun task(itemId: String): Task? = tasks[itemId]

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * 开始下载。已经在跑就直接返回，不重复开线程。
     *
     * @return true 表示这次真的启动了
     */
    fun start(context: Context, item: StoreData.Item): Boolean {
        if (tasks.containsKey(item.id)) return false
        val task = Task(item.id)
        // putIfAbsent 保证并发点两次也只会有一个任务
        if (tasks.putIfAbsent(item.id, task) != null) return false

        val app = context.applicationContext
        Thread({
            StoreInstaller.install(app, item, object : StoreInstaller.Listener {
                override fun onProgress(file: String, done: Long, total: Long) {
                    task.fileName = file
                    task.done = done
                    task.total = total
                    task.note = null
                    dispatchProgress(item.id)
                }

                override fun onRetry(file: String, url: String) {
                    task.fileName = file
                    task.done = 0L
                    task.total = 0L
                    task.note = "换源重试…"
                    dispatchProgress(item.id)
                }

                override fun onDone() {
                    tasks.remove(item.id)
                    Log.i(TAG, "download ok: ${item.id}")
                    dispatchFinished(item.id, true, null)
                }

                override fun onError(reason: String) {
                    tasks.remove(item.id)
                    Log.w(TAG, "download failed: ${item.id} / $reason")
                    dispatchFinished(item.id, false, reason)
                }
            }, task.cancel)
        }, "jimeng-store-${item.id}").start()
        return true
    }

    /** 取消。真正的收尾（删临时文件）由下载线程做。 */
    fun cancel(itemId: String) {
        val t = tasks.remove(itemId) ?: return
        t.cancel.set(true)
    }

    private fun dispatchProgress(itemId: String) {
        if (listeners.isEmpty()) return
        main.post { listeners.toList().forEach { it.onProgress(itemId) } }
    }

    private fun dispatchFinished(itemId: String, ok: Boolean, reason: String?) {
        main.post {
            listeners.toList().forEach { it.onFinished(itemId, ok, reason) }
        }
    }
}