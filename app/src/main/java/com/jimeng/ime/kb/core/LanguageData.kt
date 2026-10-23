/*
 * 语言（引擎方案）列表。
 *
 * 小地球键短按就是在这几个之间循环。一个「语言」在本工程里就是一个 rime 方案，
 * 切语言 = nativeSelectSchema。
 *
 * 有两套档位：
 *   · 内置（默认）：APK 自带雾凇 + 英文两档
 *   · 商店方案：启用某个下载来的方案后，换成「那套的主方案 + 那套的英文档位」
 * 商店方案是互斥的——四套方案里 50 多个同名文件版本各不相同，不可能共存。
 */
package com.jimeng.ime.kb.core

object LanguageData {

    data class Lang(
        /** rime 方案 id，比如 rime_ice */
        val schemaId: String,
        /** 空格键上显示的名字 */
        val displayName: String,
        /** 语言代码，比如 zh_CN */
        val languageCode: String,
    )

    /** 内置那套：APK 自带雾凇 + 英文。 */
    private val builtin = listOf(
        Lang("rime_ice", "雾凇拼音", "zh_CN"),
        Lang("melt_eng", "English", "en_US"),
    )

    /**
     * 商店方案的档位。
     *
     * 这个列表在键盘每次重建时都会被读，SharedPreferences 是磁盘读，
     * 走一遍 IO 太浪费。所以启动时由 [RimeEngine.bind] 灌一次，之后只读内存。
     */
    @Volatile
    private var schemeOverride: List<Lang>? = null

    /**
     * 内置插件额外追加的档位（目前只有日本語）。
     *
     * 和 [schemeOverride] 分开存，因为两者语义不同：
     * 前者是「整套方案换掉了」，后者是「在现有档位后面多一档」。
     * 分开之后，「雾凇 + 英文 + 日语」和「白霜 + 英文 + 日语」都能自然成立。
     */
    @Volatile
    private var extraLangs: List<Lang> = emptyList()

    /**
     * 当前小地球键循环的档位表。
     *
     * = 基础档位（内置两项或商店那套）+ 插件追加的档位。
     */
    val All: List<Lang>
        get() {
            val base = schemeOverride ?: builtin
            val extra = extraLangs
            if (extra.isEmpty()) return base
            // 插件档位如果和基础档位撞了 id（理论上不会），以基础为准，避免重复
            val ids = base.mapTo(HashSet()) { it.schemaId }
            return base + extra.filter { it.schemaId !in ids }
        }

    /**
     * 设置插件追加的档位（null / 空表示没有）。
     *
     * 由 [RimeEngine.bind] 在定好基础档位之后调用。
     */
    fun setExtra(lang: Lang?) {
        extraLangs = if (lang == null) emptyList() else listOf(lang)
    }

    /**
     * 换成商店方案的档位表。
     *
     * [primary] 为空表示退回内置那套。
     * [english] 为空表示这套方案没带英文——那就只有中文一档，
     * 小地球键按下去原地绕一圈（不会切到不存在的方案）。
     */
    fun applyScheme(
        primary: String?,
        primaryName: String?,
        english: String?,
        englishName: String?,
    ) {
        schemeOverride = if (primary.isNullOrEmpty()) {
            null
        } else {
            val list = ArrayList<Lang>(2)
            list.add(Lang(primary, primaryName?.takeIf { it.isNotEmpty() } ?: primary, "zh_CN"))
            if (!english.isNullOrEmpty()) {
                list.add(Lang(english, englishName?.takeIf { it.isNotEmpty() } ?: "English", "en_US"))
            }
            list
        }
    }

    val Default: Lang get() = All[0]

    fun of(schemaId: String): Lang =
        All.firstOrNull { it.schemaId == schemaId } ?: Default

    /** 下一个语言，到末尾绕回第一个 */
    fun next(schemaId: String): Lang {
        val list = All
        if (list.isEmpty()) return Default
        val i = list.indexOfFirst { it.schemaId == schemaId }
        return if (i < 0) list[0] else list[(i + 1) % list.size]
    }

    /**
     * 把任意方案 id 修正到当前档位表里。
     *
     * 换了方案之后引擎里可能还记着旧方案（比如 rime_ice），
     * 但新铺的那套里根本没有这个 id，selectSchema 会失败。
     * 启动时拿它兜一下：不在表里就换成表头那个。
     */
    fun coerce(schemaId: String): Lang =
        All.firstOrNull { it.schemaId == schemaId } ?: Default

    /** 转成键盘层认识的输入法条目（空格键显示、picker 面板显示都用它） */
    fun Lang.toEntry() = InputMethodEntry(
        name = schemaId,
        displayName = displayName,
        subMode = InputMethodEntry.SubMode.None,
        languageCode = languageCode,
    )
}
