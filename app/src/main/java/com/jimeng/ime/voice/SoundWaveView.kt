/*
 * 录音波形条。
 *
 * 移植自 fcitx5-android 的 input/voice/speech/SoundWaveView.kt
 * （再上游是 ultimateHandsomeBoy666/SoundView，MIT）。原版依赖 fcitx5 的
 * AppPrefs 判断「关掉动画」，这里改成霁梦自己的开关，其余算法原样保留：
 *   · 中间的柱子分成「跳舞」和「待机」两种状态
 *   · 音量低于 minVolume 就转待机，柱子在中间做一组来回滚动的波浪
 *   · 跳舞态按音量染高度，并加一点随机延迟让相邻柱子不同步，看起来才活
 *
 * 为什么不直接用 ProgressBar/自绘简单竖条：这套 idle 动画是「它在听你说话」
 * 的唯一视觉线索，静音时也有东西在动，用户才不会以为卡死了。
 */
package com.jimeng.ime.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.Interpolator
import kotlin.math.pow
import kotlin.random.Random

/** 让高度变化带一点「弹一下」的手感，线性插值会很呆。 */
class VolumeDanceInterpolator : Interpolator {
    override fun getInterpolation(f: Float): Float {
        val a = -2.8f
        val b = 3.8f
        return a * f.pow(2) + b * f
    }
}

private object WaveState {
    const val INIT = -1
    const val IDLE = 0
    const val DANCE = 1
    const val STOP = 2
}

@Suppress("MemberVisibilityCanBePrivate")
open class SoundWaveView(context: Context) : View(context) {

    /** 单根柱子。所有绘制都在 canvas 已经平移到中心之后进行。 */
    inner class VolumeBar {
        private var height: Int = 0
        private var danceStartTime: Long = 0
        private var danceStartDelay: Int = 0
        private var idleStartTime: Long = 0

        // 动态读取，允许构造后改宽度还能正确画
        private val halfWidth: Int get() = volumeBarHalfWidth

        fun draw(
            canvas: Canvas?,
            paint: Paint,
            moveCanvas: Boolean,
            state: Int,
            index: Int
        ) {
            canvas ?: return
            // 离开待机态就清零，这样快速来回切换时缓动会重新开始
            if (state != WaveState.IDLE) {
                idleStartTime = 0
            }
            when (state) {
                WaveState.IDLE -> drawIdle(canvas, paint, moveCanvas, index)
                WaveState.DANCE -> drawDance(canvas, paint, moveCanvas, index)
                WaveState.INIT, WaveState.STOP -> drawStop(canvas, paint, moveCanvas)
            }
        }

        private fun drawStop(canvas: Canvas, paint: Paint, moveCanvas: Boolean) {
            if (moveCanvas) {
                canvas.translate((volumeBarMargin + 2 * volumeBarHalfWidth).toFloat(), 0f)
            }
            val rectF = RectF(
                -halfWidth.toFloat(), -minVolumeBarHeight.toFloat() / 2,
                halfWidth.toFloat(), minVolumeBarHeight.toFloat() / 2
            )
            canvas.drawRoundRect(rectF, halfWidth.toFloat(), halfWidth.toFloat(), paint)
        }

        private fun drawIdle(canvas: Canvas, paint: Paint, moveCanvas: Boolean, index: Int) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - idleStartTime > idleDuration) {
                idleStartTime = currentTime
            }
            val idleStart =
                (1f * (currentTime - idleStartTime) / idleDuration * (volumeCount / 2 + volumeIdleCount + 4)).toInt()
            val mid = volumeCount / 2
            val x = if (index >= mid) {
                mid + idleStart - index
            } else {
                index - (mid - idleStart)
            }
            val drawHeight = if (x in 0..volumeIdleCount / 2) {
                dp2px(getIdleHeight(x)).toFloat()
            } else if (x in volumeIdleCount / 2 + 1..volumeIdleCount) {
                dp2px(getIdleHeight(volumeIdleCount - x)).toFloat()
            } else {
                minVolumeBarHeight.toFloat()
            }
            if (moveCanvas) {
                canvas.translate((volumeBarMargin + 2 * volumeBarHalfWidth).toFloat(), 0f)
            }
            val rectF = RectF(
                -halfWidth.toFloat(), -drawHeight / 2,
                halfWidth.toFloat(), drawHeight / 2
            )
            canvas.drawRoundRect(rectF, halfWidth.toFloat(), halfWidth.toFloat(), paint)
        }

        private fun drawDance(canvas: Canvas, paint: Paint, moveCanvas: Boolean, index: Int) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - danceStartTime > danceDuration) {
                // 每一轮重新摇一次高度和延迟，相邻柱子不同步才像「在动」
                danceStartDelay = random.nextInt(maxDanceDelay)
                danceStartTime = currentTime + danceStartDelay
                val volume = currentVolume.coerceAtLeast(minVolume).coerceAtMost(maxVolume)
                val rawHeight =
                    1f * (volume - minVolume) / (maxVolume - minVolume) *
                        (maxVolumeBarHeight - minVolumeBarHeight) + minVolumeBarHeight
                val heightPortion =
                    if (index in 0 until volumeHeightDistribution.size) volumeHeightDistribution[index] else 1f
                height = (rawHeight * heightPortion).toInt()
            }
            val fraction = 1f * (currentTime - danceStartTime) / danceDuration
            val drawHeight = if (fraction in 0f..1f) {
                (interpolator.getInterpolation(fraction) * height)
                    .coerceAtLeast(minVolumeBarHeight.toFloat())
                    .coerceAtMost(maxVolumeBarHeight.toFloat())
            } else {
                minVolumeBarHeight.toFloat()
            }
            if (moveCanvas) {
                canvas.translate((volumeBarMargin + 2 * volumeBarHalfWidth).toFloat(), 0f)
            }
            val rectF = RectF(
                -halfWidth.toFloat(), -drawHeight / 2,
                halfWidth.toFloat(), drawHeight / 2
            )
            canvas.drawRoundRect(rectF, halfWidth.toFloat(), halfWidth.toFloat(), paint)
        }
    }

    var volumeCount = 37
        set(value) {
            field = value
            rebuildVolumeBars()
        }

    // 两侧进入缓动区的柱子数
    var volumeIdleCount = 16
    var maxVolume = 35
    var minVolume = 4
    var danceDuration = 250
    var maxDanceDelay = 80
    var idleDuration = 3500
    var maxIdleHeight = dp2px(16f)
    var minVolumeBarHeight = dp2px(8f)
    var maxVolumeBarHeight = dp2px(36f)
    var volumeBarMargin = dp2px(3f)
    var volumeBarHalfWidth = dp2px(1.5f)

    var volumeBarColor = Color.parseColor("#A0000000")
        set(value) {
            field = value
            volumePaint.color = value
        }

    var volumeHeightDistribution = floatArrayOf(
        0.6f, 0.6f, 0.6f, 0.8f, 1f, 1.1f, 0.95f, 0.9f, 0.8f,
        0.75f, 0.8f, 0.9f, 0.95f, 1f, 1.1f, 1.2f, 1.5f, 1.4f, 1.3f,
        1.4f, 1.5f, 1.2f, 1.1f, 1f, 0.95f, 0.9f, 0.8f, 0.75f, 0.8f,
        0.9f, 0.95f, 1.1f, 1f, 0.8f, 0.6f, 0.6f, 0.6f
    )

    private var state = WaveState.INIT
    private var volumeBarList = mutableListOf<VolumeBar>()
    private var currentVolume = -1
    private var interpolator: Interpolator = VolumeDanceInterpolator()
    private val random = Random(1)
    private val volumePaint = Paint()
    private var idleHeightGetter: (Int) -> Float = { x -> (x + 4).toFloat() }
    private var volumeChangeSmoother: (Int, Int) -> Int = { cur, new -> (cur + new) / 2 }
    private var enableIdle = true

    /**
     * 关掉动画（跟系统「移除动画」无障碍开关对齐）。
     * 关掉后不 post 自己，只画当前帧 —— 省电，也避免动画开关失效。
     */
    var animationsEnabled = true

    private val animationRunnable: Runnable = object : Runnable {
        override fun run() {
            invalidate()
            if (animationsEnabled) post(this)
        }
    }

    init {
        rebuildVolumeBars()
        volumePaint.isAntiAlias = true
        volumePaint.color = volumeBarColor
    }

    private fun rebuildVolumeBars() {
        volumeBarList = MutableList(volumeCount) { VolumeBar() }
        requestLayout()
        invalidate()
    }

    private fun dp2px(dp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        context.resources.displayMetrics
    ).toInt()

    /** 待机态每根柱子的高度由外部注入，默认「越靠中间越高」的浅弧。 */
    fun getIdleHeight(x: Int): Float = idleHeightGetter.invoke(x)

    /** 喂一个音量值；两次调用之间的动画由 View 自己续着。 */
    fun handleVolume(newVolume: Int) {
        if (currentVolume < 0) {
            currentVolume = newVolume
        } else {
            currentVolume = volumeChangeSmoother.invoke(currentVolume, newVolume)
        }
        state = if ((currentVolume < minVolume) && enableIdle) {
            WaveState.IDLE
        } else {
            WaveState.DANCE
        }
    }

    /** 待机波纹的高度来源。VoiceBarUi 会注入一个更平的弧。 */
    fun setIdleHeightGetter(idleHeightGetter: (x: Int) -> Float) {
        this.idleHeightGetter = idleHeightGetter
    }

    fun stopDance() {
        state = WaveState.STOP
        invalidate()
    }

    fun setColor(color: Int) {
        volumePaint.color = color
        volumeBarColor = color
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specWidth = MeasureSpec.getSize(widthMeasureSpec)
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> specWidth
            MeasureSpec.AT_MOST -> {
                val expectWidth = paddingStart + paddingEnd +
                    volumeCount * volumeBarHalfWidth * 2 +
                    volumeBarMargin * (volumeCount - 1)
                if (expectWidth < specWidth) expectWidth else specWidth
            }
            else -> paddingStart + paddingEnd +
                volumeCount * volumeBarHalfWidth * 2 +
                volumeBarMargin * (volumeCount - 1)
        }
        val specHeight = MeasureSpec.getSize(heightMeasureSpec)
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> specHeight
            MeasureSpec.AT_MOST -> {
                val expectHeight = maxVolumeBarHeight + paddingTop + paddingBottom
                if (expectHeight < specHeight) expectHeight else specHeight
            }
            else -> maxVolumeBarHeight + paddingTop + paddingBottom
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (volumeBarList.isEmpty()) return
        canvas.save()
        // 画布移到中心，下面所有坐标都相对中心算
        canvas.translate((measuredWidth / 2).toFloat(), (measuredHeight / 2).toFloat())

        val midPosition: Int = (volumeBarList.size - 1) / 2
        volumeBarList[midPosition].draw(canvas, volumePaint, false, state, midPosition)

        canvas.save()
        for (i in midPosition + 1 until volumeBarList.size) {
            volumeBarList[i].draw(canvas, volumePaint, true, state, i)
        }
        canvas.restore()

        // 左半边把画布转 180°，复用同一套「向右推进」的逻辑，省一半代码
        canvas.rotate(180f)
        for (i in midPosition - 1 downTo 0) {
            volumeBarList[i].draw(canvas, volumePaint, true, state, i)
        }
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 一开始就 VISIBLE 的 View 不会走 onVisibilityChanged，
        // 不在这里补一刀动画就永远不动
        if (visibility == VISIBLE && isShown) {
            state = WaveState.INIT
            post(animationRunnable)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(animationRunnable)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        when (visibility) {
            VISIBLE -> {
                state = WaveState.INIT
                post(animationRunnable)
            }
            INVISIBLE, GONE -> removeCallbacks(animationRunnable)
        }
    }
}