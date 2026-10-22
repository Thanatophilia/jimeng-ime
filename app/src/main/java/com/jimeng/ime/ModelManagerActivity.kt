package com.jimeng.ime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.theme.ThemeManager
import com.jimeng.ime.voice.VoiceAsrEngine
import java.io.File

/**
 * 模型管理。
 *
 * 跟扩展商店的分工：
 *   · 商店 = 货架，负责「有什么、去下」
 *   · 这里 = 库房，负责「我装了什么、占了多少、要不要删」
 *
 * 只列出**已经装在本地**的模型。没下过的模型不该出现在这儿 ——
 * 那会让人以为自己有一堆东西，其实一个字节都没落盘。
 *
 * 语音模型这一条额外显示引擎状态（就绪 / 正在加载 / 没加载），
 * 这样按下麦克风没反应时，用户能先来这里看一眼到底是没装还是没加载好。
 */
class ModelManagerActivity : Activity() {

    /** 列表容器 */
    private var listBox: LinearLayout? = null

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager.init(this)
        AppPrefs.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F8FA.toInt())
        }
        root.addView(buildTopBar())
        root.setPadding(0, statusBarHeight(), 0, 0)

        val scroller = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        listBox = list
        scroller.addView(
            list,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroller,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        // 从商店下完回来，这里要立刻能看到新模型
        render()
    }

    // ------------------------------------------------------------ 顶栏

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(12), dp(6))
        }
        bar.addView(
            TextView(this).apply {
                text = "‹"
                textSize = 26f
                setTextColor(0xFF111318.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), dp(2))
                isClickable = true
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        bar.addView(
            TextView(this).apply {
                text = "模型管理"
                textSize = 18f
                setTextColor(0xFF111318.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(
            TextView(this).apply {
                text = "去商店"
                textSize = 14f
                setTextColor(0xFF3B6FE0.toInt())
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@ModelManagerActivity, StoreActivity::class.java))
                }
            }
        )
        return bar
    }

    // ------------------------------------------------------------ 列表

    private fun render() {
        val box = listBox ?: return
        box.removeAllViews()

        // 只列模型这一类的、并且确实装在本地
        val installed = StoreData.items(this, "model").filter {
            StoreInstaller.state(this, it) == StoreInstaller.State.Installed
        }

        if (installed.isEmpty()) {
            box.addView(
                TextView(this).apply {
                    text = "还没有装任何模型"
                    textSize = 14f
                    setTextColor(0xFF8A8F98.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, dp(56), 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            box.addView(
                TextView(this).apply {
                    text = "去扩展商店下载一个，装好后会出现在这里"
                    textSize = 13f
                    setTextColor(0xFFB0B4BB.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            // 空的时候也给个能点的按钮，别让用户自己找路
            box.addView(
                TextView(this).apply {
                    text = "去扩展商店"
                    textSize = 14f
                    setTextColor(0xFF3B6FE0.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(0xFFEAF0FE.toInt())
                    }
                    isClickable = true
                    setOnClickListener {
                        startActivity(Intent(this@ModelManagerActivity, StoreActivity::class.java))
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(20)
                }
            )
            return
        }

        // 小标题：一共占了多少
        val totalBytes = installed.sumOf { StoreInstaller.installedBytes(this, it) }
        box.addView(
            TextView(this).apply {
                text = "已装 ${installed.size} 个 · 共 ${StoreInstaller.fmtSize(totalBytes)}"
                textSize = 13f
                setTextColor(0xFF8A8F98.toInt())
                setPadding(dp(4), dp(16), 0, dp(6))
            }
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        installed.forEachIndexed { i, item ->
            if (i > 0) card.addView(hairLine())
            card.addView(buildRow(item))
        }
        box.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // 语音模型的引擎状态单独一张说明卡：出问题时这是第一手线索
        val voice = installed.firstOrNull { it.id == VOICE_MODEL_ID }
        if (voice != null) {
            box.addView(buildEngineStatusCard())
        }
    }

    /** 一行：名字 + 版本/作者/大小 + 右边「移除」 */
    private fun buildRow(item: StoreData.Item): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = item.name
            textSize = 15f
            setTextColor(0xFF111318.toInt())
        })
        val meta = buildString {
            if (item.version.isNotBlank()) append("v${item.version}  ")
            if (item.author.isNotBlank()) append("${item.author}  ")
            append(StoreInstaller.fmtSize(StoreInstaller.installedBytes(this@ModelManagerActivity, item)))
        }
        texts.addView(TextView(this).apply {
            text = meta
            textSize = 12f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        // 装到哪了：排查问题时有用，也让人知道文件真在
        texts.addView(TextView(this).apply {
            text = item.files.joinToString(" · ") { it.name }
            textSize = 11f
            setTextColor(0xFFB0B4BB.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(TextView(this).apply {
            text = "移除"
            textSize = 13f
            setTextColor(0xFF8A8F98.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFF0F1F4.toInt())
            }
            isClickable = true
            setOnClickListener { confirmRemove(item) }
        })
        return row
    }

    /** 语音引擎现在什么状态。 */
    private fun buildEngineStatusCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val (stateText, stateColor) = when {
            VoiceAsrEngine.isReady -> "已就绪，按麦克风就能用" to 0xFF2E9E5B.toInt()
            VoiceAsrEngine.lastError != null -> "没加载起来：${VoiceAsrEngine.lastError}" to 0xFFD0453B.toInt()
            else -> "还没加载（回到键盘按一下麦克风就会加载）" to 0xFF8A8F98.toInt()
        }

        card.addView(TextView(this).apply {
            text = "语音引擎"
            textSize = 14f
            setTextColor(0xFF111318.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = stateText
            textSize = 12f
            setTextColor(stateColor)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "首次使用会花 1~4 秒把模型读进内存，之后一直常驻"
            textSize = 11f
            setTextColor(0xFFB0B4BB.toInt())
            setPadding(0, dp(6), 0, 0)
        })
        // 重新试一次：清掉之前的失败状态，再预热
        card.addView(TextView(this).apply {
            text = "重新加载"
            textSize = 13f
            setTextColor(0xFF3B6FE0.toInt())
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFFEAF0FE.toInt())
            }
            isClickable = true
            setOnClickListener {
                VoiceAsrEngine.release()
                VoiceAsrEngine.preload(this@ModelManagerActivity)
                android.widget.Toast.makeText(
                    this@ModelManagerActivity, "正在重新加载…",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                // 给引擎几秒时间，然后刷新状态
                main.postDelayed({ render() }, 2500)
            }
        })
        card.addView(
            TextView(this).apply {
                text = "文件位置"
                textSize = 12f
                setTextColor(0xFF8A8F98.toInt())
                setPadding(0, dp(14), 0, 0)
            }
        )
        card.addView(TextView(this).apply {
            text = File(filesDir, "store/$VOICE_MODEL_ID").absolutePath
            textSize = 11f
            setTextColor(0xFFB0B4BB.toInt())
            setPadding(0, dp(3), 0, 0)
        })
        return card.also {
            // 这张卡放在上面的卡下面，留点间距
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
    }

    private fun confirmRemove(item: StoreData.Item) {
        AlertDialog.Builder(this)
            .setTitle("移除「${item.name}」？")
            .setMessage(
                "会删掉 ${StoreInstaller.fmtSize(StoreInstaller.installedBytes(this, item))} 文件，"
                    + "下次要用得去商店重新下载。"
            )
            .setPositiveButton("移除") { _, _ ->
                val ok = StoreInstaller.remove(this, item)
                // 语音模型被删了，引擎手里的路径就失效了，先放掉
                if (item.id == VOICE_MODEL_ID) VoiceAsrEngine.release()
                if (!ok) {
                    android.widget.Toast.makeText(
                        this, "没删干净，稍后再试", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                render()
            }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun hairLine() = View(this).apply {
        setBackgroundColor(0xFFEDEFF3.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        )
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    companion object {
        /** 语音模型在商店清单里的 id。 */
        private const val VOICE_MODEL_ID = "sensevoice-small-int8"
    }
}