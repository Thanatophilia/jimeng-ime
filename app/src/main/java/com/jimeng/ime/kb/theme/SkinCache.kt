/*
 * 皮肤图片的解码与缓存。
 *
 * ── 为什么要有这一层 ────────────────────────────────────
 * 键盘每建一次（切皮肤、转屏、换布局）都要拿一批图。
 * 每次都从磁盘解码 PNG 太慢——一张背景图 100KB 解出来 2MB 位图，
 * 转个屏就能明显卡一下。这里按 (skinId, path) 缓存解码结果。
 *
 * ── 失败怎么办 ──────────────────────────────────────────
 * 皮肤包是用户导入的，图可能坏、可能超大、可能是伪装成 PNG 的其他格式。
 * 这里每一层都 try-catch，拿不到就返回 null，
 * 调用方（KeyView / CandidateBar）拿到 null 就退回纯色绘制，键盘不会白掉。
 *
 * ── 内存上限 ────────────────────────────────────────────
 * LruCache 按字节计数，上限取可用内存的 1/8，最多 16MB。
 * 键盘皮肤图不会太多，这个量级够用，也不会把内存吃穿。
 */
package com.jimeng.ime.kb.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import java.io.File

object SkinCache {

    private const val TAG = "JMSKIN"

    /** 单张图最大边长。超过就按比例缩，防止用户塞 10000×10000 的图把内存撑爆 */
    private const val MAX_DIMENSION = 2048

    /** 缓存上限：可用内存 1/8，封顶 16MB。单位统一用 KB（sizeOf 也返回 KB） */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).coerceAtMost(16L * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** 皮肤根目录。内置和导入各一个，靠 id 决定用哪个。 */
    private fun rootDirs(ctx: Context): List<File> = listOf(
        File(ctx.filesDir, "skins"),          // 导入的皮肤，优先
    )

    private fun assetRoot(skinId: String) = "skins/$skinId"

    // ---------------------------------------------------------------- 对外

    /**
     * 拿一张皮肤图。
     *
     * @param skinId  皮肤标识，Theme.skinId
     * @param path    皮肤包内的相对路径
     * @return 解码好的 Bitmap；图不存在 / 坏了 / 太大 → null
     */
    fun bitmap(ctx: Context, skinId: String, path: String): Bitmap? {
        val key = "$skinId\u0000$path"
        cache.get(key)?.let { return it }

        val bmp = decode(ctx, skinId, path) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /**
     * 拿一张皮肤图并包成 Drawable，按 SkinImage 的 patch / alpha / tile 处理。
     *
     * @param fallbackColor 图拿不到时用的颜色。传 null 表示「宁可不画」
     */
    fun drawable(
        ctx: Context,
        skinId: String,
        image: SkinImage?,
        fallbackColor: Int? = null,
    ): Drawable? {
        if (image == null) {
            return fallbackColor?.let { ColorDrawable(it) }
        }

        val bmp = bitmap(ctx, skinId, image.path)
        if (bmp == null) {
            // 图坏了 → 退回纯色，键盘不能开天窗
            return fallbackColor?.let { ColorDrawable(it) }
        }

        val a = (image.alpha.coerceIn(0f, 1f) * 255).toInt()

        return when {
            // 九宫格：四角不缩放、四边单向拉伸、中间双向拉伸
            image.patch != null -> NineGridDrawable(bmp, image.patch, a)
            // 平铺：按键底纹那种可以重复的图
            image.tile -> TiledDrawable(bmp, a)
            // 普通贴图：整张铺满 bounds
            else -> BitmapDrawable(ctx.resources, bmp).apply { alpha = a }
        }
    }

    /** 清空缓存。切皮肤 / 删皮肤时调用 */
    fun clear() {
        cache.evictAll()
    }

    /** 只清掉某个皮肤的缓存（删皮肤时用，不影响别的皮肤） */
    fun evict(skinId: String) {
        val keys = mutableListOf<String>()
        // LruCache 没有遍历 API，用 snapshot 拿
        val snapshot = cache.snapshot()
        for ((k, _) in snapshot) {
            if (k.startsWith("$skinId\u0000")) keys += k
        }
        keys.forEach { cache.remove(it) }
    }

    // ---------------------------------------------------------------- 内部

    private fun decode(ctx: Context, skinId: String, path: String): Bitmap? {
        return try {
            val bytes = readBytes(ctx, skinId, path)
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "图读不到：$skinId/$path")
                null
            } else {
                readScaled(bytes)
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "解码 OOM，跳过：$skinId/$path")
            cache.evictAll()
            null
        } catch (e: Exception) {
            Log.w(TAG, "解码失败：$skinId/$path — ${e.message}")
            null
        }
    }

    /** 先从导入目录找，找不到再翻 assets（内置皮肤） */
    private fun readBytes(ctx: Context, skinId: String, path: String): ByteArray? {
        val safePath = sanitize(path) ?: run {
            Log.w(TAG, "路径不合法，拒绝读取：$skinId/$path")
            return null
        }
        // 1) files/skins/<id>/<path>
        for (root in rootDirs(ctx)) {
            val skinRoot = File(root, skinId)
            val f = File(skinRoot, safePath)
            // 防御性确认：解析后必须仍在皮肤目录内
            if (!f.canonicalPath.startsWith(skinRoot.canonicalPath + File.separator)) continue
            if (f.isFile && f.length() > 0) {
                // 单张图限制 8MB，防止有人塞巨图进来
                if (f.length() > 8L * 1024 * 1024) {
                    Log.w(TAG, "图太大（${f.length()} 字节）：$skinId/$path")
                    return null
                }
                return f.readBytes()
            }
        }
        // 2) assets/skins/<id>/<path>
        return try {
            ctx.assets.open("${assetRoot(skinId)}/$safePath").use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 把皮肤包里的相对路径规整成安全的相对路径。
     *
     * 皮肤包是用户导入的，path 里可能带 `..`、绝对路径、反斜杠，
     * 放任下去就能读到 filesDir 之外的任意文件。返回值保证：
     * 不含 `..`、不以 / 开头、不含反斜杠、非空。
     */
    private fun sanitize(path: String): String? {
        if (path.isBlank()) return null
        val p = path.replace('\\', '/').trim()
        if (p.isEmpty() || p.startsWith("/")) return null
        val parts = p.split('/')
        if (parts.any { it == ".." || it.isEmpty() }) return null
        return parts.joinToString("/")
    }

    /** 先读尺寸再决定采样率，避免巨图直接解码爆内存 */
    private fun readScaled(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (maxOf(w, h) / sample > MAX_DIMENSION) {
            sample *= 2
        }

        val real = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
    }
}

/**
 * 九宫格贴图。
 *
 * ── 为什么不用 NinePatch ────────────────────────────────────────
 * Android 的 NinePatch 要求图里带 .9 的 chunk（就是那圈黑边标记），
 * 用户随便拿张 PNG 塞进来没有 chunk，NinePatch 会直接抛异常崩掉。
 * 所以这里自己画：把图切成 3×3 共九块，四个角不缩放，
 * 四条边沿一个方向拉伸，中间块双向拉伸。
 *
 * 这是标准的九宫格算法，和系统 NinePatch 的绘制效果一致，
 * 区别只是切边参数来自 skin.json 而不是图里的像素标记。
 */
private class NineGridDrawable(
    private val src: Bitmap,
    private val patch: NinePatchInsets,
    alpha: Int,
) : Drawable() {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        this.alpha = alpha
    }

    /** 源图上 9 块的矩形 */
    private val srcRects = Array(9) { Rect() }

    /** 目标区域上 9 块的矩形 */
    private val dstRects = Array(9) { Rect() }

    init {
        val w = src.width
        val h = src.height
        // 切边不能超过图片，超了就按比例压回来
        val l = patch.left.coerceIn(0, w / 2)
        val r = patch.right.coerceIn(0, w - l)
        val t = patch.top.coerceIn(0, h / 2)
        val b = patch.bottom.coerceIn(0, h - t)

        val xs = intArrayOf(0, l, w - r, w)
        val ys = intArrayOf(0, t, h - b, h)
        for (row in 0..2) {
            for (col in 0..2) {
                srcRects[row * 3 + col].set(
                    xs[col], ys[row], xs[col + 1], ys[row + 1]
                )
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return

        val w = bounds.width()
        val h = bounds.height()
        // 目标切边不能超过目标尺寸的一半，否则键盘被压扁时会画反
        val l = patch.left.coerceAtMost(w / 2)
        val r = patch.right.coerceAtMost(w - l)
        val t = patch.top.coerceAtMost(h / 2)
        val b = patch.bottom.coerceAtMost(h - t)

        val left = bounds.left
        val top = bounds.top
        val xs = intArrayOf(left, left + l, left + w - r, left + w)
        val ys = intArrayOf(top, top + t, top + h - b, top + h)
        for (row in 0..2) {
            for (col in 0..2) {
                dstRects[row * 3 + col].set(
                    xs[col], ys[row], xs[col + 1], ys[row + 1]
                )
            }
        }
        for (i in 0..8) {
            if (dstRects[i].isEmpty || srcRects[i].isEmpty) continue
            canvas.drawBitmap(src, srcRects[i], dstRects[i], paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 平铺贴图。按键底纹那种「一张小图重复铺满」的效果。
 *
 * 用 BitmapShader 的 REPEAT 模式，尺寸交给 Shader，
 * 比手动循环 drawBitmap 快得多，也不会因为按键尺寸不整除而留缝。
 */
private class TiledDrawable(
    src: Bitmap,
    alpha: Int,
) : Drawable() {

    private val shader = android.graphics.BitmapShader(
        src,
        android.graphics.Shader.TileMode.REPEAT,
        android.graphics.Shader.TileMode.REPEAT,
    )

    private val paint = Paint().apply {
        this.shader = this@TiledDrawable.shader
        this.alpha = alpha
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}