/*
 * 键盘预览。仿 fcitx5-android 的 KeyboardPreviewUi。
 *
 * ── fcitx5 怎么做 ────────────────────────────────────────────
 * 它没有「画」一张键盘图，而是把真正的 TextKeyboard 造出来，
 * 按真机尺寸摆好，然后整个 View 树 scaleX/scaleY = 0.5f 缩小，
 * 再加 elevation 造出浮起的阴影。好处是预览和真键盘永远一致：
 * 边距、圆角、字号、按键图、背景图全都跑同一套代码。
 *
 * ── 我们照抄 ────────────────────────────────────────────────
 * 这里也造真的 TextKeyboard，按「屏幕宽 × 键盘高度」量好，
 * 再用 fitScale 缩进给定的大小里。皮肤怎么改，预览跟着怎么变。
 *
 * ── 为什么不用 ImageView + 截图 ──────────────────────────────
 * 截图要占内存、要缓存、换皮肤要重截，而且用户滚动时会闪。
 * 真 View 直接测量绘制，换皮肤只需 setTheme 重建一次。
 */
package com.jimeng.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout
import com.jimeng.ime.kb.keyboard.TextKeyboard
import com.jimeng.ime.kb.theme.Theme

/**
 * 一块「缩小版真键盘」。
 *
 * 用法：
 * ```
 * val preview = KeyboardPreviewView(context)
 * preview.setTheme(ThemeManager.activeTheme)   // 换皮肤调这个
 * ```
 * 高度由外面给（比如 120dp），内部按屏幕比例算出键盘该多高，
 * 再整体缩放塞进去。
 */
class KeyboardPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 当前这块预览对应的是哪套皮肤，换皮肤才重建键盘 */
    private var themeSig: String? = null

    /** 真的键盘。不挂到窗口上，纯粹用来测量 + 绘制 */
    private var keyboard: TextKeyboard? = null

    /** 键盘的「设计尺寸」，也就是真机上它该占多大的地方 */
    private var keyboardW = 0
    private var keyboardH = 0

    /** 缩小比例，让设计尺寸刚好塞进本 View */
    private var fitScale = 1f

    /** 圆角裁剪，让预览看起来像一块浮起的卡片 */
    private val clipPath = Path()
    private val rect = RectF()
    private var cornerPx = 0f

    init {
        // 键盘本身不需要交互，别让它抢触摸
        isClickable = false
        isFocusable = false
        clipToPadding = false
        cornerPx = resources.displayMetrics.density * 12f
    }

    /**
     * 换皮肤。
     *
     * 同一套皮肤重复调用会直接返回——设置页里 onResume、换皮肤回调
     * 都会调它，不挡一下会反复重建键盘视图，白费。
     */
    fun setTheme(theme: Theme) {
        // 判断依据不能只用 skinId：内置的 PixelLight / PixelDark 是两套不同
        // 配色，但 skinId 都等于各自的 name... 这里用「id + 是否暗色 + 主色」
        // 拼一个签名，系统深浅色切换、换皮肤都能识别出来，同一套重复调用
        // 则直接返回——设置页 onResume 和换皮肤回调都会调它，不挡会反复重建。
        val sig = "${theme.skinId}|${theme.isDark}|${theme.keyboardColor}|" +
            "${theme.keyBackgroundColor}|${theme.accentKeyBackgroundColor}|" +
            "${theme.backgroundImage?.path}|${theme.keyImage?.path}"
        if (sig == themeSig && keyboard != null) return
        themeSig = sig

        // 旧键盘整个丢掉，新建一个。TextKeyboard 的构造里读了一堆
        // ThemeManager.prefs，换皮肤必须重建才能吃到新值。
        removeAllViews()
        val kb = TextKeyboard(context, theme)
        keyboard = kb
        addView(kb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 键盘高度照设置页里的真实比例算，这样预览里键盘的长宽比
        // 和用户真机上看到的一样，不会压扁也不会拉长
        keyboardW = resources.displayMetrics.widthPixels
        val pct = com.jimeng.ime.kb.prefs.AppPrefs.getInstance()
            .keyboard.keyboardHeightPercent.getValue()
        keyboardH = resources.displayMetrics.heightPixels * pct / 100

        requestLayout()
        invalidate()
    }

    /**
     * ── 为什么不调 super.onMeasure ──────────────────────────────
     * 身兼 FrameLayout，默认的测量会把子键盘（MATCH_PARENT）量成
     * 本 View 的宽高；而绘制时我们又要按 fitScale 整体缩放一次。
     * 缩两次的结果就是键盘只占左上角一小块。所以这里自己管：
     * 子键盘永远保持「设计尺寸」，缩放全交给 dispatchDraw。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)

        // 按「设计尺寸」量键盘：只有量过，它内部的键才知道自己多大
        keyboard?.let { kb ->
            if (keyboardW > 0 && keyboardH > 0) {
                kb.measure(
                    MeasureSpec.makeMeasureSpec(keyboardW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(keyboardH, MeasureSpec.EXACTLY),
                )
                // 缩放比取两个方向的较小值，保证整块键盘都塞得进去、不变形
                val sx = w.toFloat() / keyboardW
                val sy = h.toFloat() / keyboardH
                fitScale = minOf(sx, sy)
            }
        }

        setMeasuredDimension(w, h)
    }

    /**
     * 子键盘固定摆在设计尺寸的原点。
     *
     * 不调 super.onLayout —— 那会把键盘拉成 View 的大小（见 onMeasure 的注释）。
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        keyboard?.layout(0, 0, keyboardW, keyboardH)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val kb = keyboard ?: run { super.dispatchDraw(canvas); return }
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || keyboardW <= 0 || keyboardH <= 0) return

        // 整个键盘按 fitScale 缩小，居中摆
        val drawW = keyboardW * fitScale
        val drawH = keyboardH * fitScale
        val dx = (w - drawW) / 2f
        val dy = (h - drawH) / 2f

        canvas.save()
        // 圆角裁剪：预览是一块卡片，不该有尖角
        rect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW)
        canvas.clipPath(clipPath)

        canvas.translate(dx, dy)
        canvas.scale(fitScale, fitScale)
        // 让键盘自己画：它会走 onDraw / dispatchDraw 那一套
        kb.draw(canvas)

        canvas.restore()
    }
}
