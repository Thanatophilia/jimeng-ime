/*
 * 皮肤缩略图。仿 fcitx5-android 的 ThemeThumbnailUi。
 *
 * ── fcitx5 怎么画的 ────────────────────────────────────────────
 * 每个主题不是一块纯色方块，而是**一张缩小的键盘**：
 *   · 整块底 = backgroundDrawable()（皮肤的背景图，没有就是底色）
 *   · 顶上一条 = barColor（工具栏）
 *   · 底部一个圆角条 = spaceBarColor（空格键）
 *   · 右下一个小圆点 = accentKeyBackgroundColor（回车键）
 *   · 正中间一个大图标 = 勾 / 月亮 / 太阳（选中状态）
 * 一眼就能看出「这个主题用起来是什么样」，比色块直观得多。
 *
 * ── 我们的对应关系 ────────────────────────────────────────────
 *   bkg      → backgroundImage（有图用图，没图用 keyboardColor）
 *   bar      → barColor
 *   spaceBar → spaceBarColor
 *   returnKey→ accentKeyBackgroundColor
 *   勾       → 当前正在用的皮肤
 *   月亮/太阳→ 皮肤声明了暗/亮那一套（跟随系统时才用得上）
 */
package com.jimeng.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.jimeng.ime.kb.theme.SkinCache
import com.jimeng.ime.kb.theme.Theme

class SkinThumbnailView(
    context: Context,
) : View(context) {

    enum class State { Normal, Selected, LightMode, DarkMode }

    /**
     * 缩略图按「刚才显示的是哪种状态」决定画什么。
     *
     * 尺寸用 dp 换算，外面给多少它就画多大，但内部各块按固定比例缩，
     * 这样网格里放得下、放大也不糊。
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** 背景图（皮肤有 backgroundImage 时才有） */
    private var backgroundSkin: android.graphics.drawable.Drawable? = null

    private var theme: Theme? = null
    private var state: State = State.Normal

    /** 背景图加载过就不重复加载，换皮肤时才清 */
    private var loadedSkinId: String? = null

    fun setTheme(t: Theme) {
        theme = t
        // 换一套皮肤才重新取图，同一套只是状态变化就没必要再读一次磁盘
        if (loadedSkinId != t.skinId) {
            loadedSkinId = t.skinId
            backgroundSkin = t.backgroundImage?.let {
                SkinCache.drawable(context, t.skinId, it, fallbackColor = t.keyboardColor)
            }
        }
        invalidate()
    }

    fun setState(s: State) {
        if (state == s) return
        state = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = theme ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val r = density * 8f

        // ---- 整块底：有背景图铺图，没有就填键盘底色 ----
        canvas.save()
        rect.set(0f, 0f, w, h)
        val clip = android.graphics.Path().apply {
            addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clip)

        val bg = backgroundSkin
        if (bg != null) {
            bg.setBounds(0, 0, width, height)
            bg.draw(canvas)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = t.keyboardColor
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        // ---- 顶部一条：工具栏 ----
        paint.color = t.barColor
        val barH = h * 0.16f
        canvas.drawRect(0f, 0f, w, barH, paint)

        // ---- 键盘区：三行按键 ----
        drawKeyGrid(canvas, w, h, t, barH)

        // ---- 中间的状态图标 ----
        drawStateIcon(canvas, w, h, t)

        canvas.restore()
    }

    /**
     * 键盘形态示意：三行按键。
     *
     * ── 为什么不在缩略图里也放真键盘 ────────────────────────────
     * 格子只有 104×74dp，扣掉名字行后缩略图剩不到 58dp 高。真键盘
     * 三行六十来个键缩进去，每个键只有一两个 dp，等于一片糊。所以
     * 这里画「形态示意」：三行键块阵列把皮肤的底色、键色、圆角、
     * 空格色、回车色全交代清楚，看得出「键盘会变成什么样」，又不糊。
     * 想看真键盘细节，上面的大预览就是。
     *
     * 布局照真键盘：10 键 / 9 键（缩进半键）/ 功能键 + 空格 + 回车。
     */
    private fun drawKeyGrid(canvas: Canvas, w: Float, h: Float, t: Theme, barH: Float) {
        val top = barH + h * 0.05f
        val bottom = h - h * 0.05f
        val areaH = bottom - top
        if (areaH <= 0f) return

        val rows = 3
        val rowGap = areaH * 0.09f
        val keyH = (areaH - rowGap * (rows - 1)) / rows
        if (keyH <= 0f) return

        val side = w * 0.045f
        val colGap = w * 0.014f

        // 圆角跟着皮肤走：keyRadiusOverride 大的皮肤，键块更圆
        val radiusBase = keyH * 0.24f
        val radius = (t.keyRadiusOverride?.let { it / 8f } ?: 1f)
            .coerceIn(0f, 2.2f)
            .let { radiusBase * it }

        fun slot(rowTop: Float, l: Float, r: Float, color: Int) {
            if (r - l <= 0f) return
            paint.style = Paint.Style.FILL
            paint.color = color
            rect.set(l, rowTop, r, rowTop + keyH)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        val row0 = top
        val row1 = top + keyH + rowGap
        val row2 = top + (keyH + rowGap) * 2

        // ---- 第 1 行：10 个字母键 ----
        val n0 = 10
        val usable0 = w - side * 2
        val keyW0 = (usable0 - colGap * (n0 - 1)) / n0
        for (i in 0 until n0) {
            val l = side + i * (keyW0 + colGap)
            slot(row0, l, l + keyW0, t.keyBackgroundColor)
        }

        // ---- 第 2 行：9 个，左右各缩进半个键宽 ----
        val n1 = 9
        val indent = keyW0 * 0.55f
        val usable1 = w - (side + indent) * 2
        val keyW1 = (usable1 - colGap * (n1 - 1)) / n1
        for (i in 0 until n1) {
            val l = side + indent + i * (keyW1 + colGap)
            slot(row1, l, l + keyW1, t.keyBackgroundColor)
        }

        // ---- 第 3 行：功能键 + 空格 + 回车 ----
        // 权重照真键盘：功能键 1，空格 4.6，回车 1.4
        val row2L = side
        val row2R = w - side
        val totalGap = colGap * 3
        val unit = (row2R - row2L - totalGap) / 7f
        var x = row2L
        // 左功能键 × 2（altKey：符号/中英切换那种键）
        repeat(2) {
            slot(row2, x, x + unit, t.altKeyBackgroundColor)
            x += unit + colGap
        }
        // 空格
        val spaceW = unit * 4.6f - colGap
        slot(row2, x, x + spaceW, t.spaceBarColor)
        x += spaceW + colGap
        // 回车：强调色
        slot(row2, x, row2R, t.accentKeyBackgroundColor)
    }

    /**
     * 中间那个大图标。
     *
     * fcitx5 用的是 Material 矢量图，我们没引入那套资源，
     * 就手画——勾、月亮、太阳三个形状都简单，画出来还更轻。
     */
    private fun drawStateIcon(canvas: Canvas, w: Float, h: Float, t: Theme) {
        if (state == State.Normal) return

        val cx = w / 2f
        val cy = h / 2f
        val density = resources.displayMetrics.density

        // 图标下面垫个半透明圆，深浅底色上都看得清
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 0, 0, 0)
        if (t.isDark) paint.color = Color.argb(140, 255, 255, 255)
        val base = Color.argb(
            0x8C,
            Color.red(t.keyboardColor),
            Color.green(t.keyboardColor),
            Color.blue(t.keyboardColor),
        )
        paint.color = base
        canvas.drawCircle(cx, cy, minOf(w, h) * 0.26f, paint)

        val fg = if (t.isDark) Color.WHITE else Color.BLACK
        paint.color = fg
        val s = minOf(w, h) * 0.16f

        when (state) {
            State.Selected -> {
                // 勾：两笔画出来
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 2.2f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                val path = android.graphics.Path().apply {
                    moveTo(cx - s * 0.7f, cy + s * 0.05f)
                    lineTo(cx - s * 0.15f, cy + s * 0.6f)
                    lineTo(cx + s * 0.75f, cy - s * 0.6f)
                }
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            }
            State.LightMode -> {
                // 太阳：一个圆 + 八条射线
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 1.6f
                canvas.drawCircle(cx, cy, s * 0.45f, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val cos = kotlin.math.cos(a).toFloat()
                    val sin = kotlin.math.sin(a).toFloat()
                    canvas.drawLine(
                        cx + cos * s * 0.68f, cy + sin * s * 0.68f,
                        cx + cos * s * 0.95f, cy + sin * s * 0.95f,
                        paint
                    )
                }
                paint.style = Paint.Style.FILL
            }
            State.DarkMode -> {
                // 月牙：一个大圆挖掉偏移的小圆
                val path = android.graphics.Path().apply {
                    addCircle(cx, cy, s * 0.72f, android.graphics.Path.Direction.CW)
                    addCircle(cx + s * 0.42f, cy - s * 0.32f, s * 0.62f, android.graphics.Path.Direction.CCW)
                }
                canvas.drawPath(path, paint)
            }
            else -> Unit
        }
    }
}