package com.jimeng.ime.kb.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray

/**
 * 剪贴板历史（精简版）。
 *
 * 思路照 fcitx5-android 的 `data/clipboard/ClipboardManager`：
 * 监听系统剪贴板 → 记到本地 → 面板里点一条上屏。
 *
 * 差异：原版用 Room 建库（带 pinned / 敏感标记 / 软删除 / 撤销），
 * 我们这里只需要「最近复制过的几条文本」，就直接塞 SharedPreferences 的 JSON 数组：
 * 少一个数据库依赖，读写都在内存里做完，输入法启动也不会多一次磁盘 IO。
 */
object ClipboardHistory : ClipboardManager.OnPrimaryClipChangedListener {

    private const val TAG = "JimengClipboard"
    private const val SP_NAME = "jimeng_clipboard"
    private const val KEY_ENTRIES = "entries"

    /** 最多留多少条（原版默认 clipboardHistoryLimit = 20，这里给 30） */
    const val LIMIT = 30

    private var prefs: SharedPreferences? = null
    private var manager: ClipboardManager? = null

    /** 最新的在最前 */
    private val items = ArrayList<String>()

    fun init(context: Context) {
        if (manager != null) return
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        prefs = sp
        items.clear()
        items.addAll(read(sp))
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
    fun entries(): List<String> = ArrayList(items)

    /**
     * 打开面板前同步一次系统剪贴板。
     *
     * 为什么需要：监听器只在**输入法服务活着**的时候收得到广播。
     * 主人在别处复制完、再呼出键盘，那一下的变化我们没听见，
     * 打开面板时补读一次才不会漏掉最新那条。
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
        add(text)
    }

    /** 记一条：重复的提到最前，不产生重复项 */
    fun add(text: String) {
        if (text.isBlank()) return
        val old = items.indexOf(text)
        if (old == 0) return
        if (old > 0) items.removeAt(old)
        items.add(0, text)
        while (items.size > LIMIT) items.removeAt(items.size - 1)
        persist()
    }

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        persist()
    }

    private fun persist() {
        val sp = prefs ?: return
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        sp.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun read(sp: SharedPreferences): List<String> {
        val raw = sp.getString(KEY_ENTRIES, null) ?: return emptyList()
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) out.add(s)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "剪贴板历史损坏，忽略", t)
            return emptyList()
        }
        return out
    }
}
