package com.jimeng.ime.plugin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * 商店右上角「导入」按钮的完整交互。
 *
 * ── 为什么单独拿一个类 ──────────────────────────────────
 * [com.jimeng.ime.StoreActivity] 已经一千多行了，再往里塞
 * 文件选择 / 预检 / 确认框 / 后台装 / 结果提示 这一串，会彻底没法读。
 *
 * 而且导入这件事跟商店列表没有耦合——它只需要：选文件 → 问清楚 → 装 →
 * 通知刷新。所以做成一个可以复用的对象，将来插件管理页也能挂同一条流程
 * （从那儿导入更顺手：导完就能直接开关）。
 *
 * ── 流程 ────────────────────────────────────────────────
 * ```
 * 点「导入」
 *   → 系统文件选择器（只给 .zip）
 *   → 复制到应用私有缓存（外部 Uri 不能稳定读第二次）
 *   → inspect：解析 manifest、校验 entry、看是不是升级
 *   → 确认框：名字/版本/作者/能力/会不会顶掉
 *   → 后台 install：解压到临时目录 → 校验 → 搬到 store/<id>
 *   → 结果提示 + 通知外层刷新
 * ```
 *
 * 每一步失败都有一句具体的话（[PluginError] 里那句话），
 * 不用「导入失败」四个字打发用户。
 */
class PluginImporter(
    private val activity: Activity,
    /** 装完/失败后回调，让外层重画列表 */
    private val onChanged: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    companion object {
        private const val TAG = "JimengPlugin"

        /** 选文件的请求码 */
        const val REQ_PICK = 0x5157

        /** 只接受 zip：有些文件管理器不认 type，还会用 octet-stream 兜底 */
        private val MIME = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
        )
    }

    /** 点「导入」：拉起系统文件选择器 */
    fun pick() {
        if (busy) {
            toast("上一个还在装，稍等一下")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, MIME)
        }
        try {
            activity.startActivityForResult(intent, REQ_PICK)
        } catch (t: Throwable) {
            Log.w(TAG, "拉不起文件选择器", t)
            toast("这台设备上没有文件选择器")
        }
    }

    /** 在 onActivityResult 里转过来。返回 true 表示这次结果归它处理。 */
    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_PICK) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true

        busy = true
        Toast.makeText(activity, "正在检查插件包…", Toast.LENGTH_SHORT).show()
        Thread {
            val zip = try {
                copyToCache(uri)
            } catch (t: Throwable) {
                Log.w(TAG, "复制导入包失败", t)
                main.post {
                    busy = false
                    toast("读不到这个文件：${t.message ?: "可能没有权限"}")
                }
                return@Thread
            }
            if (zip == null) {
                main.post {
                    busy = false
                    toast("这个文件读不出来")
                }
                return@Thread
            }

            val inspected = PluginInstaller.inspect(activity.applicationContext, zip)
            main.post {
                busy = false
                inspected.fold(
                    onSuccess = { confirm(it) },
                    onFailure = { err ->
                        zip.delete()
                        showError(err.message ?: "这个包不能用")
                    },
                )
            }
        }.start()
        return true
    }

    // ---------------------------------------------------------- 确认

    /** 装之前把「要装什么」摊开给人看 */
    private fun confirm(info: PluginInstaller.Inspect) {
        val m = info.manifest
        val adapter = PluginRegistry.adapterFor(m.entry, m.type)
        val caps = adapter?.capabilities()?.takeIf { it.isNotEmpty() }

        val text = buildString {
            append("版本：").append(m.version).append('\n')
            if (m.author.isNotEmpty()) append("作者：").append(m.author).append('\n')
            append("包大小：").append(com.jimeng.ime.StoreInstaller.fmtSize(info.zip.length())).append('\n')
            if (m.description.isNotEmpty()) {
                append('\n').append(m.description).append('\n')
            }
            if (caps != null) {
                append("\n装上之后：\n")
                caps.forEach { append("· ").append(it).append('\n') }
            }
            append("\n这个包不带可执行代码，只有数据和说明；")
            append("功能由输入法里已经带好的对应模块提供。")
            if (info.upgrading) {
                append("\n\n已经装过")
                if (info.oldVersion.isNotEmpty()) append("（").append(info.oldVersion).append("）")
                append("，这次会覆盖成 ").append(m.version).append('。')
                append("开关状态不会变。")
            }
            if (m.permissions.isNotEmpty()) {
                append("\n\n它声明要用：").append(m.permissions.joinToString("、"))
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("导入「${m.name}」？")
            .setMessage(text)
            .setPositiveButton("导入") { _, _ -> startInstall(info) }
            .setNegativeButton("算了") { _, _ -> info.zip.delete() }
            .setOnCancelListener { info.zip.delete() }
            .show()
    }

    private fun startInstall(info: PluginInstaller.Inspect) {
        busy = true
        Toast.makeText(activity, "正在导入…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = PluginInstaller.install(
                activity.applicationContext,
                info,
            ) { text ->
                main.post { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show() }
            }
            info.zip.delete()
            main.post {
                busy = false
                result.fold(
                    onSuccess = { item ->
                        onChanged()
                        val m = info.manifest
                        AlertDialog.Builder(activity)
                            .setTitle("「${m.name}」导入成功")
                            .setMessage(
                                "已经装好了，现在去插件管理里点「启用」就会生效。"
                            )
                            .setPositiveButton("去插件管理") { _, _ ->
                                try {
                                    activity.startActivity(
                                        Intent().setClassName(
                                            activity.packageName,
                                            "com.jimeng.ime.PluginManagerActivity"
                                        )
                                    )
                                } catch (t: Throwable) {
                                    Log.w(TAG, "打开插件管理失败", t)
                                }
                            }
                            .setNegativeButton("知道了", null)
                            .show()
                        Log.i(TAG, "导入成功：${item.id}")
                    },
                    onFailure = { err -> showError(err.message ?: "导入失败") },
                )
            }
        }.start()
    }

    // ---------------------------------------------------------- 小工具

    /** 把选中的 Uri 拷到缓存目录：外部 Uri 读一次就可能失效，先落到实处 */
    private fun copyToCache(uri: Uri): File? {
        val name = displayName(uri) ?: "import.zip"
        val dir = File(activity.cacheDir, "plugin-import").apply { mkdirs() }
        // 清掉上一次的残留：导入包通常几 MB，攒多了白占空间
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (ignored: Throwable) {
        }
        val out = File(dir, if (name.lowercase().endsWith(".zip")) name else "$name.zip")
        activity.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: return null
        return out.takeIf { it.isFile && it.length() > 0 }
    }

    /** 从 Uri 查显示名（决定存成什么文件名，后缀判断要用） */
    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = activity.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                uri.lastPathSegment
            }
        } catch (t: Throwable) {
            null
        } finally {
            try {
                cursor?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(activity)
            .setTitle("导入不了这个包")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
