/*
 * 把 rime 的 preedit 拆成「已经转成汉字的前缀」+「还没转的拼音音节」。
 *
 * 这个切法是照 fcitx5 的拼音输入法来的（fcitx5-chinese-addons 里
 * im/pinyin/pinyincandidate.cpp 的 buildTabActions + libime 的
 * PinyinEncoder::stringToSyllables）。它**不查音节总表**，而是把每个音节
 * 拆成「声母 + 韵母」两段，各自在自己的表里找：
 *
 *     hui  -> h + ui   ✔  一格
 *     fui  -> f + ui   ✔  一格（fui 根本不是标准拼音，查总表会被切成 fu + i）
 *     fuiz -> fui + z  ✔  主人的那串字母原样留着，不会凭空多出格子
 *
 * 为什么必须这样：查音节总表时，表里没有的拼法只能退化成「一个字母一段」，
 * 于是 `fui` 变 `fu` + `i`、主人刚点的那个词就没了。声母+韵母能盖住
 * 「声母合法、韵母合法，只是这个组合不在字典里」的情况——正是谐音变体。
 *
 * 另外还有两件事（都是从 rime 的源码里核对出来的）：
 *   1. 认出一串汉字前缀（rime 已转换的部分总在最前面）；
 *   2. 保留引擎原样的字母串（`raw`），改谐音时靠它拼前后，不信切分结果。
 */
package com.jimeng.ime.kb.utils

/**
 * 声母表。按长度从长到短排（zh/ch/sh 必须先试，否则会被 z/c/s 抢走）。
 *
 * 注意 `y`、`w` 也算声母：`ya`、`wu` 这种在切分时就是 y/w + 韵母。
 */
private val INITIALS: List<String> = listOf(
    "zh", "ch", "sh",
    "b", "p", "m", "f", "d", "t", "n", "l",
    "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"
)

/**
 * 韵母表。按长度从长到短排（iang/iong/uang 必须排在 an/in 前面）。
 *
 * `v` 是 ü 的输入习惯写法（lve、nve），保留着。
 */
private val FINALS: List<String> = listOf(
    "iang", "iong", "uang",
    "ang", "eng", "ing", "ong",
    "ian", "iao", "uai", "uan",
    "ai", "ei", "ui", "ao", "ou", "iu", "ie", "ve", "er",
    "an", "en", "in", "un", "vn",
    "ia", "ua", "uo",
    "a", "o", "e", "i", "u", "v"
)

/** 最长的声母 2 个字母（zh/ch/sh） */
private const val MAX_INITIAL_LENGTH = 2

/**
 * 把一串连续字母切成拼音音节：**先找声母，再找韵母**。
 *
 *   "hui"      -> ["hui"]
 *   "fui"      -> ["fui"]         ← 声母 f + 韵母 ui，谐音变体也切得动
 *   "buhaowan" -> ["bu", "hao", "wan"]
 *   "fangan"   -> ["fang", "an"]  ← 和 rime 引擎自己的切法一致
 *   "fuiz"     -> ["fui", "z"]    ← 剩下的单个字母自己一段，不丢
 *
 * 认不出的字母单独成段，保证一定能切完，不会卡住也不会丢字。
 */
fun syllabify(raw: String): List<String> {
    if (raw.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    var i = 0
    while (i < raw.length) {
        // 先试声母：2 个字母（zh/ch/sh）优先，其次 1 个，最后是没有声母
        var best: String? = null
        var bestLen = 0
        for (iniLen in MAX_INITIAL_LENGTH downTo 0) {
            if (i + iniLen > raw.length) continue
            if (iniLen > 0) {
                val ini = raw.substring(i, i + iniLen)
                if (ini !in INITIALS) continue
            }
            val rest = i + iniLen
            // 韵母：长的优先（iang 要先于 an 试，否则 iang 会被切成 i + ang）
            for (fin in FINALS) {
                if (raw.startsWith(fin, rest)) {
                    val total = iniLen + fin.length
                    // 拼得越长越可信；一样长时取韵母更长的那个
                    if (total > bestLen) {
                        best = raw.substring(i, i + total)
                        bestLen = total
                    }
                    break
                }
            }
        }
        if (best != null && bestLen > 0) {
            out.add(best)
            i += bestLen
        } else {
            // 认不出（手滑、或者字母表外的字符）→ 单个字母成段，绝不丢字
            out.add(raw[i].toString())
            i++
        }
    }
    return out
}

/**
 * preedit 拆出来的两截。
 *
 * @param convertedPrefix 已经转成汉字的那部分（可能是空串）
 * @param syllables 还没转的拼音音节（按顺序，第一个就是引擎正在转换的那一段）
 * @param raw 还没转的那串原始字母（去掉分隔符、已小写）。
 *            改谐音时要靠它把「这一节前后」原样接回去——
 *            切分可能和引擎不一致（如 fangan），但引擎自己的字母不会有假。
 */
data class ParsedPreedit(
    val convertedPrefix: String,
    val syllables: List<String>,
    val raw: String = ""
) {
    /** 一个拼音都不剩了（全选成汉字了） */
    val isEmpty: Boolean get() = syllables.isEmpty()

    /** 还是纯拼音，一个字都没选过 */
    val isPure: Boolean get() = convertedPrefix.isEmpty()

    /** 最前面那个音节（引擎正在转换的那一段） */
    val current: String get() = syllables.firstOrNull() ?: ""
}

/**
 * 拆 preedit。
 *
 * 汉字总在最前面（rime 从左往右转换），所以找第一个字母的位置当分界：
 * 它之前是已转换的汉字，之后是还没转的拼音。
 *
 * 分隔符（空格、小撇号）会被丢掉——rime 的分段靠自己的输入记录，
 * 不靠 preedit 里的空格，我们按声母+韵母重新切更可靠。
 */
fun parsePreedit(preedit: String): ParsedPreedit {
    if (preedit.isEmpty()) return ParsedPreedit("", emptyList())
    var latinStart = -1
    for ((i, ch) in preedit.withIndex()) {
        if (ch in 'a'..'z' || ch in 'A'..'Z') {
            latinStart = i
            break
        }
    }
    // 整串都是汉字（或者全是符号），没有待输入的拼音了
    if (latinStart < 0) return ParsedPreedit(preedit, emptyList())
    val prefix = preedit.substring(0, latinStart)
    // 先按「引擎原样」把那串字母收好（去掉分隔符、统一小写），
    // 改谐音时用它拼前后，不信切分结果。
    val raw = buildString {
        for (ch in preedit.substring(latinStart)) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') append(ch)
        }
    }.lowercase()
    return ParsedPreedit(prefix, syllabify(raw), raw)
}
