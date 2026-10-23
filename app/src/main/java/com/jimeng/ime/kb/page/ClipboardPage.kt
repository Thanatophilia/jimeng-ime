package com.jimeng.ime.kb.page

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.PopupMenu
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.jimeng.ime.R
import com.jimeng.ime.kb.clipboard.ClipboardHistory
import com.jimeng.ime.kb.theme.Theme
import com.jimeng.ime.view.ToolbarButtonView
import splitties.dimensions.dp

/**
 * 剪贴板页：整块铺满键盘区的历史列表。
 *
 * **照 fcitx5-android 的 `input/clipboard/` 原样做**：
 *
 * | 原版（ClipboardUi / ClipboardWindow / ClipboardEntryUi） | 我们 |
 * |---|---|
 * | `recyclerView` + `StaggeredGridLayoutManager(2, VERTICAL)` | 同款，同一个布局管理器 |
 * | `SpacesItemDecoration(dp(4))` 条目间距 4dp | 同款，自己写的最小实现 |
 * | `ClipboardEntryUi` 圆角卡片 + `clipboardEntryColor` 底色 | 同款：圆角 8dp、`clipboardEntryColor` |
 * | `textView` minLines=1 / maxLines=4 / ellipsize=END | 同款 |
 * | 卡片右下角 `pin` 图钉（pinned 才显示） | 同款：`ic_push_pin_24`、12dp、alpha 0.3 |
 * | `ClipboardInstructionUi.Empty` 大图标 + 提示 | 同款：90dp 淡色图标 + 提示文字 |
 * | `deleteAllButton` = `ToolButton(delete_sweep)`，挂在 `extension` 里 | 同款：`ToolbarButtonView` + `ic_delete_sweep_24` |
 * | 点它 `promptDeleteAll(haveUnpinned())` 弹 PopupMenu | 同款语义：页内浮层确认框，标题蓝字加粗 |
 * | **`ItemTouchHelper` + `makeMovementFlags(0, LEFT or RIGHT)`** | **同款：左右滑动，`onSwiped` 直接删，不设阈值、不回弹** |
 *
 * 为什么改用 RecyclerView：原版用 `ItemTouchHelper` 做滑动删除——它是
 * RecyclerView 的附属，手势、位移、回收全由它接管。之前手写 FrameLayout
 * 手势那一套，滑动阈值和回弹都成了没人要的行为，现在照原版走。
 *
 * 为什么确认框不用 `PopupWindow` / `PopupMenu`：输入法窗口是独立窗口类型，
 * 再弹系统弹窗容易抢焦点、还会被候选栏上方的窗口边界裁掉。改成页内浮层，
 * 定位就是「贴页面右上角」——正好在候选栏那个删除按钮的正下方。
 */
class ClipboardPage(
    context: Context,
    theme: Theme,
    private val items: List<ClipboardHistory.Entry>,
    private val onPick: (String) -> Unit,
    private val onClear: (skipPinned: Boolean) -> Unit,
    private val onDelete: (text: String) -> Unit,
    private val onPin: (text: String, pinned: Boolean) -> Unit,
    private val onEdit: (text: String) -> Unit,
    private val onShare: (text: String) -> Unit
) : InputPage(context, theme) {

    companion object {
        /** 两列（照原版 StaggeredGridLayoutManager 的 spanCount = 2） */
        private const val COLUMNS = 2
        /** 条目间距，照原版 SpacesItemDecoration(dp(4)) */
        private const val SPACING_DP = 4
        /** 卡片圆角（原版走 ThemePrefs.clipboardEntryRadius，默认 8） */
        private const val ENTRY_RADIUS_DP = 8
        /** 卡片内边距，照原版 setPaddingDp(8, 4, 8, 4) */
        private const val ENTRY_PAD_H = 8
        private const val ENTRY_PAD_V = 4
        /** 卡片最小高度，照原版 minimumHeight = dp(30) */
        private const val ENTRY_MIN_HEIGHT_DP = 30
        /** 最多显示几行，照原版 maxLines = 4 */
        private const val ENTRY_MAX_LINES = 4
        /** 空态图标大小，照原版 dp(90) */
        private const val EMPTY_ICON_DP = 90
        /** 卡片右下角图钉尺寸，照原版 dp(12) */
        private const val PIN_SIZE_DP = 12
        /** 确认框宽度 */
        private const val DIALOG_WIDTH_DP = 240
        /** 确认框圆角 */
        private const val DIALOG_RADIUS_DP = 12
        /** 确认框里每一行的高度 */
        private const val ACTION_HEIGHT_DP = 44
    }

    override val title: String = "剪贴板"

    /** 当前列表快照（滑动删一条后自己摘掉，页面的其它逻辑还要用它判断） */
    private val entries = ArrayList(items)

    // ------------------------------------------------------------ 候选栏右端的删除按钮

    /**
     * 候选栏右上角的「清空」按钮（照原版 `deleteAllButton` + `delete_sweep` 图标）。
     *
     * 用户要的「点击做个高亮」由 [ToolbarButtonView] 自带：
     * 底色按压层 + 图标按压变色，跟工具栏其他按钮一致。
     */
    private val deleteButton = ToolbarButtonView(
        context, theme, R.drawable.ic_delete_sweep_24
    ) {
        showConfirm()
    }

    /** 挂在候选栏右端扩展位（候选栏那边会 gravity = END，贴最右） */
    override val barExtension: View = FrameLayout(context).apply {
        addView(
            deleteButton,
            FrameLayout.LayoutParams(
                context.dp(ToolbarButtonView.WIDTH_DP.toInt()),
                context.dp(ToolbarButtonView.WIDTH_DP.toInt()),
                // 贴最右：用户的要求是「剪贴板右上角」，就在最右侧那一格
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )
    }

    // ------------------------------------------------------------ 确认框（页内浮层）

    /** 确认框标题（蓝字高亮那行） */
    private lateinit var confirmTitle: TextView

    /** 确认框卡片本体（淡入用） */
    private lateinit var confirmCard: LinearLayout

    /** 整层：半透明遮罩 + 卡片，初始隐藏 */
    private val confirmLayer: View = buildConfirmLayer()

    /** 列表（照原版 ClipboardUi.recyclerView） */
    private val recycler = RecyclerView(context).apply {
        layoutManager = StaggeredGridLayoutManager(COLUMNS, StaggeredGridLayoutManager.VERTICAL)
        adapter = EntryAdapter()
        addItemDecoration(SpacesItemDecoration(context.dp(SPACING_DP)))
        clipToPadding = false
        // 整块吃掉触摸，别让事件穿到下面被盖住的键盘
        isClickable = true
    }

    private val emptyView: View = buildEmpty()

    override val root: View = FrameLayout(context).also { frame ->
        // 照原版：底色 barColor
        frame.setBackgroundColor(theme.barColor)
        // 整块吃掉触摸，别让事件穿到下面被盖住的键盘
        frame.isClickable = true
        // 有内容就铺列表，空了就换空态（原版是靠 ViewAnimator 切，这里直接切可见性）
        frame.addView(
            recycler,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        frame.addView(
            emptyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // 确认框盖在最上层
        frame.addView(
            confirmLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        refreshEmpty()
    }

    init {
        // 照原版 ClipboardWindow：
        //   ItemTouchHelper(object : ItemTouchHelper.Callback() {
        //       getMovementFlags = makeMovementFlags(0, LEFT or RIGHT)
        //       onMove = false
        //       onSwiped = 删这条
        //   }).attachToRecyclerView(recyclerView)
        //
        // 只开左右两个方向、不开拖动，滑了就删——原版没有任何阈值判定，
        // 也没做「滑一半弹回」，ItemTouchHelper 自己管滑动过程。
        ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val entry = entries.getOrNull(pos) ?: return
                // 先从本地表里摘掉，再回调删数据（照原版 delete + 刷新）
                removeEntryFromList(entry)
            }
        }).attachToRecyclerView(recycler)
    }

    /** 页面收起时把确认框关掉，下次进来是干净的 */
    override fun onDetached() {
        hideConfirm()
        dismissEntryMenu()
    }

    /**
     * 从本页列表里摘掉一条（侧滑划走、长按菜单删除都走这里）。
     *
     * 只局部刷新、不重建整页——重建会丢列表滚动位置，划到深处删一条
     * 就被弹回顶部，很难用。
     */
    private fun removeEntryFromList(entry: ClipboardHistory.Entry) {
        val pos = entries.indexOfFirst { it.text == entry.text }
        if (pos < 0) return
        entries.removeAt(pos)
        (recycler.adapter as? EntryAdapter)?.notifyItemRemoved(pos)
        refreshEmpty()
        onDelete(entry.text)
    }

    /** 有内容显示列表、空了显示空态（照原版 ViewAnimator 的 Normal / AddMore 切换） */
    private fun refreshEmpty() {
        val empty = entries.isEmpty()
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ 适配器（照原版 ClipboardAdapter）

    private inner class EntryAdapter : RecyclerView.Adapter<EntryHolder>() {

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
            val ui = EntryView(context, theme, context.dp(ENTRY_RADIUS_DP).toFloat())
            return EntryHolder(ui)
        }

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            val entry = entries[position]
            with(holder.entryUi) {
                setEntry(entry)
                root.setOnClickListener { onPick(entry.text) }
                // 长按弹菜单（照原版 ClipboardAdapter.onBindViewHolder 里的 setOnLongClickListener）
                root.setOnLongClickListener {
                    showEntryMenu(root, entry)
                    true
                }
            }
        }
    }

    private class EntryHolder(val entryUi: EntryView) : RecyclerView.ViewHolder(entryUi.root)

    /**
     * 一张剪贴板卡片，照原版 `ClipboardEntryUi`：
     * 圆角底 + 最多 4 行文字 + 右下角图钉（pinned 才显示）。
     */
    private class EntryView(
        ctx: Context,
        private val theme: Theme,
        radius: Float
    ) {
        val textView = TextView(ctx).apply {
            minLines = 1
            maxLines = ENTRY_MAX_LINES
            textSize = 14f
            setPadding(
                ctx.dp(ENTRY_PAD_H), ctx.dp(ENTRY_PAD_V),
                ctx.dp(ENTRY_PAD_H), ctx.dp(ENTRY_PAD_V)
            )
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(theme.keyTextColor)
        }

        val pin = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_push_pin_24)
            setColorFilter(theme.altKeyTextColor)
            alpha = 0.3f
        }

        private val layout = FrameLayout(ctx).apply {
            addView(
                textView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL
                )
            )
            addView(
                pin,
                FrameLayout.LayoutParams(
                    ctx.dp(PIN_SIZE_DP),
                    ctx.dp(PIN_SIZE_DP),
                    Gravity.BOTTOM or Gravity.END
                ).apply {
                    rightMargin = ctx.dp(2)
                    bottomMargin = ctx.dp(2)
                }
            )
        }

        val root = FrameLayout(ctx).apply {
            isClickable = true
            minimumHeight = ctx.dp(ENTRY_MIN_HEIGHT_DP)
            foreground = RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor),
                null,
                GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(Color.WHITE)
                }
            )
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(theme.clipboardEntryColor)
            }
            addView(
                layout,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        fun setEntry(entry: ClipboardHistory.Entry) {
            textView.text = entry.text
            pin.visibility = if (entry.pinned) View.VISIBLE else View.GONE
        }
    }

    /**
     * 条目间距，照原版 `SpacesItemDecoration(dp(4))`。
     *
     * 原版给它所有条目四边都加 4dp 内缩，两列瀑布流看着就像一张张分开的卡片。
     */
    private class SpacesItemDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.left = space
            outRect.top = space
            outRect.right = space
            outRect.bottom = space
        }
    }

    // ------------------------------------------------------------ 确认框构建

    /**
     * 造确认框：最上面蓝色高亮的问题，下面「取消」「确定」两行，
     * 两行按下都有底色块（用户明确要的）。
     */
    private fun buildConfirmLayer(): View {
        val layer = FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
        }
        // 半透明遮罩：点空白处等于取消
        layer.addView(
            View(context).apply {
                setBackgroundColor(0x66000000)
                isClickable = true
                setOnClickListener { hideConfirm() }
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = context.dp(DIALOG_RADIUS_DP).toFloat()
                setColor(theme.popupBackgroundColor)
            }
            setPadding(
                context.dp(8), context.dp(6),
                context.dp(8), context.dp(8)
            )
            // 吃掉卡片内的点击，别传到遮罩去把框关了
            isClickable = true
        }
        confirmCard = card

        // ---- 第一行：蓝色高亮的问题（照原版 bold + accent 色，且不可点）----
        confirmTitle = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.genericActiveBackgroundColor)
            setPadding(
                context.dp(12), context.dp(12),
                context.dp(12), context.dp(10)
            )
        }
        card.addView(
            confirmTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ---- 一条淡分隔线 ----
        card.addView(
            View(context).apply { setBackgroundColor(theme.dividerColor) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(1).coerceAtLeast(1)
            ).apply {
                leftMargin = context.dp(12)
                rightMargin = context.dp(12)
            }
        )

        // ---- 第二、三行：取消 / 确定（按下有底色块，两行都是普通文字色）----
        card.addView(
            buildActionRow("取消") { hideConfirm() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(ACTION_HEIGHT_DP)
            )
        )
        card.addView(
            buildActionRow("确定") { confirmClear() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(ACTION_HEIGHT_DP)
            )
        )

        layer.addView(
            card,
            FrameLayout.LayoutParams(
                context.dp(DIALOG_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // 具体位置在 showConfirm() 里按删除按钮的实际坐标算
                gravity = Gravity.TOP or Gravity.START
            }
        )
        return layer
    }

    /**
     * 把确认框贴到删除按钮的正下方（用户要的「从那个按钮弹出来」）。
     *
     * 删除按钮挂在候选栏右端，候选栏和页面是两块不同的 View，右边距不一定
     * 完全对齐；横屏 / 不同键盘宽度下按钮位置也会变。用 getLocationInWindow
     * 拿两者的窗口坐标相减，才能保证卡片右边缘跟按钮右边缘齐、
     * 上边缘正好在按钮下沿，视觉上就是从按钮里掉出来的。
     */
    private fun anchorCardUnderButton() {
        val rootView = root
        if (rootView.width == 0 || deleteButton.width == 0) return
        val btnLoc = IntArray(2)
        deleteButton.getLocationInWindow(btnLoc)
        val rootLoc = IntArray(2)
        rootView.getLocationInWindow(rootLoc)

        val cardWidth = context.dp(DIALOG_WIDTH_DP)
        val gap = context.dp(4)
        val edge = context.dp(6)

        // 卡片右边缘跟按钮右边缘对齐，再夹在本页范围内
        var left = btnLoc[0] + deleteButton.width - rootLoc[0] - cardWidth
        left = left.coerceIn(edge, maxOf(edge, rootView.width - cardWidth - edge))
        // 卡片上边缘落在按钮下沿下面一点点；按钮在候选栏里，
        // 换成 root 坐标后通常是负的，夹到顶部留白
        var top = btnLoc[1] + deleteButton.height - rootLoc[1] + gap
        top = top.coerceAtLeast(edge)

        (confirmCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.leftMargin = left
            lp.topMargin = top
            confirmCard.layoutParams = lp
        }
    }

    /**
     * 一行可选动作：按下时圆角色块高亮（用户要的「点击时有底色块」）。
     *
     * 两行文字色一致（用户说「确定」不该带蓝色高亮）——蓝色只留给
     * 最上面那句问题，动作行就是普通的弹窗文字色。
     */
    private fun buildActionRow(
        label: String,
        onClick: () -> Unit
    ): View = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.popupTextColor)
        setPadding(context.dp(14), 0, context.dp(14), 0)
        isClickable = true
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    cornerRadius = context.dp(8).toFloat()
                    setColor(theme.keyPressHighlightColor)
                }
            )
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        setOnClickListener { onClick() }
    }

    /** 弹出确认框：问的是「未置顶」还是「已置顶」，看库里还有什么 */
    private fun showConfirm() {
        confirmTitle.text = if (entries.any { !it.pinned }) {
            "删除所有未置顶条目吗？"
        } else {
            "删除所有已置顶条目吗？"
        }
        confirmLayer.visibility = View.VISIBLE
        // 等这一层真的布好（第一次显示时宽度还是 0），再按按钮位置摆卡片
        confirmLayer.post { anchorCardUnderButton() }
        confirmCard.alpha = 0f
        confirmCard.animate().alpha(1f).setDuration(120L).start()
    }

    private fun hideConfirm() {
        confirmLayer.visibility = View.GONE
    }

    /** 点了确定：按当前状态决定删哪一批，删除由 JimengIME 做（它负责重建页面） */
    private fun confirmClear() {
        val skipPinned = entries.any { !it.pinned }
        hideConfirm()
        onClear(skipPinned)
    }

    // ------------------------------------------------------------ 长按菜单

    /** 当前弹着的菜单（照原版 private var popupMenu: PopupMenu?，重复弹之前先关） */
    private var entryMenu: PopupMenu? = null

    /**
     * 长按卡片弹菜单，照原版 `ClipboardAdapter.onBindViewHolder` 里那一整段：
     *
     * 原版四项：置顶/取消置顶、编辑、分享、删除，每项都带图标。
     * - 已置顶的那条显示「取消置顶」+ 空心图钉（ic_outline_push_pin_24）
     * - 没置顶的显示「置顶」+ 实心图钉（ic_baseline_push_pin_24）
     * - 编辑跳 ClipboardEditActivity
     * - 分享走 ACTION_SEND
     * - 删除直接删（原版删完还会弹撤销 Snackbar，我们这里没做撤销）
     *
     * 原版还会 `popup.setForceShowIcon(true)` 让图标显示出来——高版本 Android 的
     * PopupMenu 默认不画图标，不带这句图标就没了。
     */
    private fun showEntryMenu(anchor: View, entry: ClipboardHistory.Entry) {
        val popup = PopupMenu(context, anchor)
        val menu = popup.menu
        // 图标颜色跟主题文字走（同原版 styledColor(android.R.attr.colorControlNormal)）
        val tint = theme.keyTextColor

        if (entry.pinned) {
            menu.item("取消置顶", R.drawable.ic_outline_push_pin_24, tint) {
                onPin(entry.text, false)
            }
        } else {
            menu.item("置顶", R.drawable.ic_baseline_push_pin_24, tint) {
                onPin(entry.text, true)
            }
        }
        menu.item("编辑", R.drawable.ic_baseline_edit_24, tint) { onEdit(entry.text) }
        menu.item("分享", R.drawable.ic_baseline_share_24, tint) { onShare(entry.text) }
        menu.item("删除", R.drawable.ic_baseline_delete_24, tint) {
            // 和侧滑删除走同一条路：先从本页摘掉卡片，再回调删数据。
            // 不重建整页（重建会丢掉列表滚动位置）
            removeEntryFromList(entry)
        }

        // 高版本默认不画图标，得强开（照原版）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            popup.setForceShowIcon(true)
        }
        popup.setOnDismissListener { if (entryMenu === popup) entryMenu = null }
        entryMenu?.dismiss()
        entryMenu = popup
        popup.show()
    }

    /**
     * 往菜单里加一项，带图标和点击回调（照原版 `utils/Menu.kt` 的 `Menu.item`）。
     *
     * 原版用 `iconTintList` 上色（API 26+），这里同样用 `setIcon` + `iconTintList`。
     */
    private fun Menu.item(
        title: String,
        iconRes: Int,
        tint: Int,
        onClick: () -> Unit
    ): MenuItem {
        val item = add(title)
        item.setIcon(iconRes)
        item.iconTintList = ColorStateList.valueOf(tint)
        item.setOnMenuItemClickListener {
            onClick()
            true
        }
        return item
    }

    /** 页面收起时把菜单关掉（照原版 `ClipboardAdapter.onDetached`） */
    private fun dismissEntryMenu() {
        entryMenu?.dismiss()
        entryMenu = null
    }

    // ------------------------------------------------------------ 空态

    /**
     * 空态：大图标 + 一句提示。
     *
     * 照原版 `ClipboardInstructionUi.Empty`：图标 90dp、距顶 24dp、
     * 提示在图标下方 16dp、水平居中。
     */
    private fun buildEmpty(): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(24), 0, 0)
        }
        box.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_clipboard_24)
                setColorFilter(theme.altKeyTextColor)
                alpha = 0.3f
            },
            LinearLayout.LayoutParams(context.dp(EMPTY_ICON_DP), context.dp(EMPTY_ICON_DP))
        )
        box.addView(
            TextView(context).apply {
                text = "还没有复制过东西\n复制一段文字，这里就会记住它"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(theme.keyTextColor)
                setPadding(context.dp(12), 0, context.dp(12), 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(16) }
        )
        return box
    }
}