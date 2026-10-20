/*
 * 语言（引擎方案）列表。
 *
 * 小地球键短按就是在这几个之间循环。一个「语言」在本工程里就是一个 rime 方案，
 * 切语言 = nativeSelectSchema。
 *
 * 以后加语言只要三步：
 *   1. 把 xxx.schema.yaml 放进 assets/rime
 *   2. 在 assets/rime/default.yaml 的 schema_list 里登记一行
 *   3. 在这个 All 列表里加一项
 * 小地球就会自动多一个循环档位，不用改别的代码。
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

    /** 循环顺序就是这里的顺序 */
    val All = listOf(
        Lang("rime_ice", "雾凇拼音", "zh_CN"),
        Lang("melt_eng", "English", "en_US"),
    )

    val Default: Lang get() = All[0]

    fun of(schemaId: String): Lang =
        All.firstOrNull { it.schemaId == schemaId } ?: Default

    /** 下一个语言，到末尾绕回第一个 */
    fun next(schemaId: String): Lang {
        val i = All.indexOfFirst { it.schemaId == schemaId }
        return if (i < 0) Default else All[(i + 1) % All.size]
    }

    /** 转成键盘层认识的输入法条目（空格键显示、picker 面板显示都用它） */
    fun Lang.toEntry() = InputMethodEntry(
        name = schemaId,
        displayName = displayName,
        subMode = InputMethodEntry.SubMode.None,
        languageCode = languageCode,
    )
}
