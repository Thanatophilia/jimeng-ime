package com.jimeng.ime.kb.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 剪贴板历史（精简版）。
 *
 * 思路照 fcitx5-android 的 `data/clipboard/ClipboardManager`：
 * 监听系统剪贴板 → 记到本地 → 面板里点一条上屏。
 *
 * 差异：原版用 Room 建库（带 pinned / 敏感标记 / 软删除 / 撤销），
 * 我们这里只需要「最近复制过的几条文本」，就直接塞 SharedPreferences 的 JSON：
 * 少一个数据库依赖，读写都在内存里做完，输入法启动也不会多一次磁盘 IO。
 *
 * **置顶（pinned）**：照原版 `ClipboardEntry.pinned`。
 * 原版的「清空」按钮其实叫 `deleteSweep`，删的是**未置顶**的那些
 * （`ClipboardManager.haveUnpinned()` → `deleteAll(skipPinned = true)`），
 * 所以提示语「删除所有未置顶条目吗？」必须有置顶概念才名正言顺。
 */
object ClipboardHistory : ClipboardManager.OnPrimaryClipChangedListener {

    /** 一条历史：内容是文本，[pinned] 置顶后清空时不会被删 */
    data class Entry(val text: String, val pinned: Boolean = false)

    private const val TAG = "JimengClipboard"
    private const val SP_NAME = "jimeng_clipboard"
    private const val KEY_ENTRIES = "entries"

    /**
     * 上一次从系统剪贴板读到的原文（持久化，重启也不忘）。
     *
     * 为什么需要这个：进剪贴板页时会调 [syncFromSystem] 补读一次系统剪贴板，
     * 补的是「输入法在后台时没收到广播」漏掉的那条。但它没有「是不是新的」判断，
     * 每次进页都把系统剪贴板里现存的那条重新 add 一遍——
     * 主人刚删掉的第一条（正好就是系统剪贴板里那条）就被原样塞回来了，看着像删不掉。
     * 记住上次读到的是哪条，就能判断这次到底有没有新东西。
     */
    private const val KEY_LAST_SEEN = "last_seen"

    /** 最多留多少条（原版默认 clipboardHistoryLimit = 20，这里给 30） */
    const val LIMIT = 30

    private var prefs: SharedPreferences? = null
    private var manager: ClipboardManager? = null

    /** 上次从系统剪贴板读到的原文（见 [KEY_LAST_SEEN]） */
    private var lastSeen: String? = null

    /** 置顶的在最前，其余按时间倒序（照原版 `ORDER BY pinned DESC, timestamp DESC`） */
    private val items = ArrayList<Entry>()

    fun init(context: Context) {
        if (manager != null) return
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        prefs = sp
        items.clear()
        items.addAll(read(sp))
        lastSeen = sp.getString(KEY_LAST_SEEN, null)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager = cm
        try {
            cm?.addPrimaryClipChangedListener(this)
        } catch (t: Throwable) {
            Log.e(TAG, "注册剪贴板监听失败", t)
        }
    }

    fun release() {
        try {
            manager?.removePrimaryClipChangedListener(this)
        } catch (t: Throwable) {
            Log.e(TAG, "注销剪贴板监听失败", t)
        }
        manager = null
        prefs = null
    }

    /** 当前历史快照（调用方改不到内部表） */
    fun entries(): List<Entry> = ArrayList(items)

    /** 只取文本的快照 */
    fun texts(): List<String> = items.map { it.text }

    /** 有没有「未置顶」的条目——决定清空提示问的是哪一种 */
    fun haveUnpinned(): Boolean = items.any { !it.pinned }

    /**
     * 打开面板前同步一次系统剪贴板。
     *
     * 为什么需要：监听器只在**输入法服务活着**的时候收得到广播。
     * 主人在别处复制完、再呼出键盘，那一下的变化我们没听见，
     * 打开面板时补读一次才不会漏掉最新那条。
     *
     * **只在内容跟上次读到的不一样时才记**：否则每次进页都会把系统剪贴板里
     * 现存的那条重新插回第一条——主人刚删掉它就又回来了，看着像删不掉。
     */
    fun syncFromSystem() {
        val cm = manager ?: return
        val clip = try {
            cm.primaryClip
        } catch (t: Throwable) {
            Log.e(TAG, "同步剪贴板失败", t)
            return
        } ?: return
        if (clip.itemCount <= 0) return
        val text = clip.getItemAt(0).text?.toString() ?: return
        // 跟上次一样：没新东西，别把已经删掉的那条塞回来
        if (text == lastSeen) return
        lastSeen = text
        persistLastSeen()
        add(text)
    }

    override fun onPrimaryClipChanged() {
        val cm = manager ?: return
        val clip = try {
            cm.primaryClip
        } catch (t: Throwable) {
            // Android 10+ 在后台读剪贴板会被拒，吞掉就行，不影响键盘
            Log.e(TAG, "读剪贴板失败", t)
            return
        } ?: return
        if (clip.itemCount <= 0) return
        val text = clip.getItemAt(0).text?.toString() ?: return
        // 真的复制了一次（哪怕内容和上次一样，也是主人主动重新复制的），
        // 所以这里不判重、直接记，并更新 lastSeen
        lastSeen = text
        persistLastSeen()
        add(text)
    }

    /** 记一条：重复的提到最前（置顶的保持原位），不产生重复项 */
    fun add(text: String) {
        if (text.isBlank()) return
        val old = items.indexOfFirst { it.text == text }
        if (old == 0) return
        if (old > 0) {
            // 已经在库里：置顶的不能因为重新复制就被挤下去
            if (items[old].pinned) return
            items.removeAt(old)
        }
        // 新条目插在「置顶区」之后的最前位置
        val insertAt = items.indexOfFirst { !it.pinned }.let { if (it < 0) items.size else it }
        items.add(insertAt, Entry(text, pinned = false))
        while (items.size > LIMIT) {
            // 超限时从尾部丢，但绝不丢置顶的
            val lastUnpinned = items.indexOfLast { !it.pinned }
            if (lastUnpinned < 0) break
            items.removeAt(lastUnpinned)
        }
        persist()
    }

    /** 置顶 / 取消置顶（照原版 `ClipboardManager.pin` / `unpin`） */
    fun setPinned(text: String, pinned: Boolean) {
        val index = items.indexOfFirst { it.text == text }
        if (index < 0) return
        if (items[index].pinned == pinned) return
        items[index] = items[index].copy(pinned = pinned)
        sortPinnedFirst()
        persist()
    }

    /**
     * 改一条的内容（长按菜单里的「编辑」用，照原版 `ClipboardManager.updateText`）。
     *
     * 改完要重新判重：编辑后的文字可能和库里另一条撞车，撞了就丢掉改的这一条，
     * 免得出两个一模一样的卡片。
     */
    fun updateText(oldText: String, newText: String) {
        if (newText.isBlank()) return
        val index = items.indexOfFirst { it.text == oldText }
        if (index < 0) return
        if (oldText == newText) return
        val pinned = items[index].pinned
        items.removeAt(index)
        // 撞车就把库里那条也摘掉，等会儿只留新的这条
        items.removeAll { it.text == newText }
        val insertAt = items.indexOfFirst { !it.pinned }.let { if (it < 0) items.size else it }
        val entry = Entry(newText, pinned)
        // 编辑过来的是置顶的，就放回置顶区最前
        items.add(if (pinned) 0 else insertAt, entry)
        persist()
    }

    /** 置顶的排前面，其余保持相对顺序（`sortedByDescending` 是稳定排序） */
    private fun sortPinnedFirst() {
        val sorted = items.sortedByDescending { it.pinned }
        items.clear()
        items.addAll(sorted)
    }

    /**
     * 删一条（侧滑划走 / 长按菜单里的删除）。
     *
     * 删完还要防一手：如果删的这条正好就是**系统剪贴板里现存的那条**，
     * 下次进剪贴板页补读系统剪贴板时它又会被读回来、重新插到第一条——
     * 看起来就是「第一条删不掉」。所以把它的内容记成 lastSeen，让补读跳过它。
     */
    fun remove(text: String) {
        val index = items.indexOfFirst { it.text == text }
        if (index < 0) return
        items.removeAt(index)
        persist()
        // 系统剪贴板里还存着这条的话，记下来，别再被补读塞回来
        if (readSystemClipText() == text) {
            lastSeen = text
            persistLastSeen()
        }
    }

    /** 读系统剪贴板当前文本（读不到就返回 null，不抛异常） */
    private fun readSystemClipText(): String? {
        val cm = manager ?: return null
        return try {
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            clip.getItemAt(0).text?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    /** 清空**未置顶**的条目（照原版 `deleteAll(skipPinned = true)`） */
    fun clearUnpinned() {
        if (items.none { !it.pinned }) return
        items.removeAll { !it.pinned }
        persist()
    }

    /** 全部清空（含置顶）——一条未置顶的都没有时，删的就是置顶那批 */
    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        persist()
    }

    /** 把 lastSeen 存下来，输入法重启后也不会把删掉的条目重新塞回来 */
    private fun persistLastSeen() {
        prefs?.edit()?.putString(KEY_LAST_SEEN, lastSeen)?.apply()
    }

    private fun persist() {
        val sp = prefs ?: return
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("t", e.text)
                    put("p", e.pinned)
                }
            )
        }
        sp.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun read(sp: SharedPreferences): List<Entry> {
        val raw = sp.getString(KEY_ENTRIES, null) ?: return emptyList()
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                // 新格式是对象 {t, p}
                val obj = arr.optJSONObject(i)
                val text: String
                var pinned = false
                if (obj != null) {
                    text = obj.optString("t", "")
                    pinned = obj.optBoolean("p", false)
                } else {
                    // 旧格式是纯字符串，兼容读
                    text = arr.optString(i, "")
                }
                if (text.isNotBlank()) out.add(Entry(text, pinned))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "剪贴板历史损坏，忽略", t)
            return emptyList()
        }
        return out.sortedByDescending { it.pinned }
    }
}