/*
 * 皮肤缩略图网格。仿 fcitx5-android 的 ResponsiveThemeListView + ThemeListItemDecoration。
 *
 * ── fcitx5 的做法 ────────────────────────────────────────────
 * 用 RecyclerView + GridLayoutManager，按屏宽算能放几列：
 *     spanCount = (width - minMargin) / (itemWidth + minMargin)
 * 再用 ItemDecoration 把每格撑到你想要的尺寸、四周补上间距。
 * 好处是转屏、平板、折叠屏都能自动改列数。
 *
 * ── 我们为什么不用 RecyclerView ──────────────────────────────
 * 皮肤一共就四五个，多了也就十几个。RecyclerView 的复用、ViewHolder、
 * Adapter 那一套在这规模上纯属负担。这里用 GridLayout 手动分行：
 *   · 每行是一个横向 LinearLayout，往里加固定宽高的卡片
 *   · 换行时新起一行
 *   · onSizeChanged 时按宽度重算每行能放几个，转屏会自己重排
 * 代码只有 RecyclerView 那套的三分之一，行为一样。
 */
package com.jimeng.ime.view

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jimeng.ime.kb.theme.SkinManager
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.kb.theme.ThemeManager

class SkinGridLayout(context: Context) : LinearLayout(context) {

    /** 点「+」新建（导入）皮肤 */
    var onAddClicked: () -> Unit = {}

    /** 点某套皮肤。传 null 表示「跟随系统」那一格 */
    var onSkinClicked: (SkinManager.SkinEntry?) -> Unit = {}

    /** 长按导入的皮肤：删除 */
    var onSkinDelete: (SkinManager.SkinEntry) -> Unit = {}

    /** 每格的尺寸，照 fcitx5 的 dp(128) × dp(92)，这里稍扁一点更像键盘 */
    private val itemWidth = (resources.displayMetrics.density * 104).toInt()
    // 高度从 74dp 抬到 92dp：扣掉名字行后缩略图还有七十多 dp，
    // 三行键块才画得开、看得出是键盘，而不是压成一条色带
    private val itemHeight = (resources.displayMetrics.density * 92).toInt()

    /** 格与格之间、行与行之间的空隙 */
    private val gap = (resources.displayMetrics.density * 10).toInt()

    /** 上一次排版的宽度，宽度没变就不用重排 */
    private var lastWidth = -1

    private var rendered = false

    /** 正在 onMeasure 里排版：这期间子 View 的 requestLayout 一律吞掉 */
    private var inMeasure = false

    init {
        orientation = VERTICAL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 只作为兜底：正常的首次排版在 onMeasure 里做完了，这里一般不会触发
        if (w <= 0 || w == lastWidth || rendered) return
        lastWidth = w
        render()
    }

    /**
     * 在测量阶段就把格子排好。
     *
     * ── 为什么不能在 onSizeChanged 里排 ──────────────────────────
     * onSizeChanged 是在 setFrame 里回调的，那会儿已经进了 layout pass。
     * 在里面 addView 虽然会标记 requestLayout，但本次 traversal 的测量
     * 早就走过了，新加的子 View 拿不到尺寸，下一次 traversal 又不一定来
     * ——结果就是高度算成 0，整个网格看不见。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        if (avail > 0 && avail != lastWidth) {
            lastWidth = avail
            // render 里的 removeAllViews / addView 会触发 requestLayout，
            // 测量中这么做没意义还可能绕回来，先挡掉
            inMeasure = true
            try {
                render()
            } finally {
                inMeasure = false
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun requestLayout() {
        if (inMeasure) return
        super.requestLayout()
    }

    /** 重新画一遍整个网格。换皮肤 / 导入 / 删除后由外部调用 */
    fun refresh() {
        rendered = false
        if (width <= 0) {
            // 还没上过屏，等 onMeasure 里自然会画
            lastWidth = -1
            return
        }
        // 宽度没变 → 直接重排；变了 → 交给 onMeasure 重排
        if (width == lastWidth) {
            render()
        } else {
            lastWidth = -1
            requestLayout()
        }
    }

    private fun render() {
        removeAllViews()
        val avail = if (lastWidth > 0) lastWidth else resources.displayMetrics.widthPixels
        if (avail <= 0) return

        // ---- 算每行放几格 + 每格多宽 ----
        // fcitx5 用 ItemDecoration 靠 margin 撑开，格子宽度是死的、间距是活的。
        // 这里反过来：格子宽度是活的、间距固定 gap，解不等式
        //     n * cellW + (n-1) * gap <= avail
        //     cellW >= itemWidth * minScale
        // 从 1 格开始往上试，取能放下的最大值。
        val minScale = 0.85f
        val minCell = itemWidth * minScale
        var perRow = 1
        var cellW = avail
        while (true) {
            val n = perRow + 1
            val w = (avail - (n - 1) * gap) / n
            if (w < minCell) break
            perRow = n
            cellW = w
        }
        if (perRow == 1) {
            cellW = avail
        }
        cellW = cellW.coerceAtMost((itemWidth * 1.6f).toInt())

        val items = buildList<View> {
            // 第一格永远是「新建/导入」，照 fcitx5 的 NewThemeEntryUi
            add(addTile())
            // 第二格是「跟随系统」，相当于 fcitx5 的「跟随系统深浅色」那一项
            add(systemTile())
            // 然后是真实的皮肤
            val entries = try {
                SkinManager.list(context)
            } catch (t: Throwable) {
                emptyList()
            }
            for (e in entries) add(skinTile(e))
        }

        var row: LinearLayout? = null
        items.forEachIndexed { i, v ->
            if (i % perRow == 0) {
                row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    // 居中而不是左对齐：一行放不满时（比如宽屏只放 3 格还剩一截），
                    // 两边留白一样宽，比全挤在左边好看
                    gravity = Gravity.CENTER
                    if (i > 0) {
                        setPadding(0, gap, 0, 0)
                    }
                }
                addView(
                    row,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                )
            }
            val col = i % perRow
            // 第一格左边留 gap，其余格子靠 leftMargin 隔开，右边由父容器兜住
            val lp = LinearLayout.LayoutParams(cellW, itemHeight)
            if (col > 0) lp.leftMargin = gap
            row?.addView(v, lp)
        }

        rendered = true
    }

    // ------------------------------------------------------------ 三种格子

    /** 「+ 新建」：虚线感的方块，点开导入对话框 */
    private fun addTile(): View {
        val box = FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF1F3F5.toInt())
                cornerRadius = context.dp(10f)
                setStroke(context.dp(1).toInt(), 0xFFD6DAE0.toInt(), context.dp(4f), context.dp(4f))
            }
            isClickable = true
            setOnClickListener { onAddClicked() }
        }
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        texts.addView(TextView(context).apply {
            text = "+"
            textSize = 22f
            setTextColor(0xFF8A8F98.toInt())
            gravity = Gravity.CENTER
        })
        texts.addView(TextView(context).apply {
            text = "新建主题"
            textSize = 11f
            setTextColor(0xFF8A8F98.toInt())
            gravity = Gravity.CENTER
        })
        box.addView(
            texts,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        return box
    }

    /** 「跟随系统」：左右两半，示意会跟着深色模式走 */
    private fun systemTile(): View {
        val selected = ThemeManager.currentSkinId() == null
        // 底下压一行小字容易糊，改成在图上叠个「跟随系统」标签
        // 点击挂在整块 FrameLayout 上，标签本身不拦事件
        val box = FrameLayout(context).apply {
            isClickable = true
            setOnClickListener { onSkinClicked(null) }
        }
        val tile = SkinThumbnailView(context).apply {
            isClickable = false
            setTheme(Theme.PixelLight)
            setState(if (selected) SkinThumbnailView.State.Selected else SkinThumbnailView.State.Normal)
        }
        box.addView(
            tile,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        box.addView(
            TextView(context).apply {
                text = "跟随系统"
                textSize = 10f
                setTextColor(0xFFFFFFFF.toInt())
                isClickable = false
                setPadding(context.dp(5).toInt(), context.dp(2).toInt(), context.dp(5).toInt(), context.dp(2).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x99000000.toInt())
                    cornerRadius = context.dp(3f)
                }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).also {
                it.leftMargin = context.dp(5).toInt()
                it.bottomMargin = context.dp(5).toInt()
            }
        )
        return box
    }

    /** 一套皮肤：缩略图 + 名字。长按导入的可以删 */
    private fun skinTile(entry: SkinManager.SkinEntry): View {
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val preview: Theme? = (if (dark) entry.dark else entry.light) ?: entry.light ?: entry.dark

        // 点击 / 长按挂在整格上（图表 + 名字），挂在缩略图上只有按到图那一半才算，
        // 按到下面名字那行就没反应——用户会以为长按不灵
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            isClickable = true
            setOnClickListener { onSkinClicked(entry) }
            setOnLongClickListener {
                if (!entry.builtin) {
                    onSkinDelete(entry)
                    true
                } else {
                    false
                }
            }
        }
        val thumb = SkinThumbnailView(context).apply {
            // 让事件穿透到外层 box，否则点缩略图时 box 的监听收不到
            isClickable = false
            if (preview != null) {
                setTheme(preview)
                setState(
                    if (ThemeManager.currentSkinId() == entry.id) {
                        SkinThumbnailView.State.Selected
                    } else {
                        SkinThumbnailView.State.Normal
                    }
                )
            }
        }
        box.addView(
            thumb,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        box.addView(TextView(context).apply {
            text = entry.name
            textSize = 10.5f
            setTextColor(0xFF5F6368.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, context.dp(3).toInt(), 0, 0)
        })
        return box
    }

    /** dp 两种重载：Float 给画布/圆角，Int 给 padding/margin */
    private fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
    private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}