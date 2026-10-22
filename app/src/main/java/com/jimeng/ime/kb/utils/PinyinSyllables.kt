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
 *
 * 还有一件最要紧的（后鼻音「吞字母」那个 bug 就是栽在这里）：
 * **引擎在段与段之间自己插了分隔符，不能丢。**
 *
 * librime 的 `ScriptSyllabifier::GetPreeditString`
 * （src/rime/gear/script_translator.cc:417-438）拼 preedit 时，每换一个音节
 * 都会先补一个 `delimiters.at(0)`（雾凇的 speller/delimiter 第一位就是空格），
 * 所以引擎给的本来就是 `shan gou`、`wan gong`、`bu hao wan` 这种一段一段的。
 *
 * 那套边界是引擎**在词库里查出来的**：`Syllabifier::BuildSyllableGraph`
 * （src/rime/algo/syllabifier.cc:40-180）用 prism 做前缀搜索，词库里真有
 * 这个词条才连边，再按词的权重挑最像的那条路。它比下面这张
 * 「声母 + 韵母」表准得多——后者只是词法上的合法，压根不知道有没有这个词。
 *
 * 丢了自己重切，后鼻音的 `ng` 就会跨过真边界跑到前一段去：
 *
 *     shan gou（引擎：shan + gou） → 重切成 shang + ou
 *     wan gong（引擎：wan + gong） → 重切成 wang + ong
 *
 * 左边编码区于是按「多一个字母」的格子显示，主人点这格换谐音时，
 * [com.jimeng.ime.view.ExpandedCandidateView.planFuzzyPick] 按格子长度算起点，
 * 就把那个 g 一并替换掉了：`shan gou` → `shanou`、`wan gong` → `wangong` 变 `wanong`。
 * 后者引擎再一切就成了 `wa + nong`，于是「选的是 wan，出的字读 wa」。
 *
 * 所以 [parsePreedit] 现在的顺序是：**引擎的分隔符优先**，
 * [syllabify] 只在引擎没给分隔符（整串就一个音节，或者引擎自己切不出分段）
 * 时才兜底。谐音变体（`fui` 这种）走的正是兜底那条路，行为不变。
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
 * preedit 拆出来的结果。
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
)

/** 字母（拼音里能出现的字符） */
private fun isLetter(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'

/**
 * 引擎给的一段字母能拆成哪些音节。
 *
 * 正常情况下一段就是一个音节（引擎是按音节插分隔符的），所以：
 *   1. 整段就是个合法音节 → 原样一段，哪怕它看着像两个（`xian` 不许拆成 xi + an）；
 *   2. 整段是某个合法音节的**前缀**（`sh`、`zh`、`b` 这种简拼）→ 也原样一段，
 *      否则会退化成 `s` + `h` 两个单字母格；
 *   3. 都不沾（`fui` 这种谐音变体、或者引擎给了一长串）→ 才交给 [syllabify]。
 *
 * 不管走哪条路，字符都原样保留、顺序不变——这样 [syllables] 拼起来一定
 * 等于 [ParsedPreedit.raw]，改谐音算起点才不会错位。
 */
private fun splitEnginePiece(piece: String): List<String> {
    if (isValidSyllable(piece)) return listOf(piece)
    if (piece.length <= 3 && RIME_SYLLABLES.any { it.startsWith(piece) }) return listOf(piece)
    return syllabify(piece)
}

/**
 * 拆 preedit。
 *
 * 汉字总在最前面（rime 从左往右转换），所以找第一个字母的位置当分界：
 * 它之前是已转换的汉字，之后是还没转的拼音。
 *
 * **切分原则：引擎的分隔符优先。**
 *
 * 引擎给的 preedit 本来就是一段一段的（`shan gou`、`wan gong`、`bu hao wan`），
 * 那些空格是 `ScriptSyllabifier::GetPreeditString` 在段间插的 `delimiters.at(0)`，
 * 而段边界是 `Syllabifier::BuildSyllableGraph` 拿词库做前缀搜索查出来的——
 * 是真边界。所以这里按分隔符切，一个音节能对上格，绝不自己重切。
 *
 * 只有「整串连一个分隔符都没有」时才退回 [syllabify]（这时多半就一个音节，
 * 比如谐音变体 `fui`）。后鼻音那个吞字母的 bug，根子就是以前无条件丢分隔符、
 * 一律重切：`shan gou` 被切成了 `shang` + `ou`，`wan gong` 被切成了 `wang` + `ong`。
 */
fun parsePreedit(preedit: String): ParsedPreedit {
    if (preedit.isEmpty()) return ParsedPreedit("", emptyList())
    var latinStart = -1
    for ((i, ch) in preedit.withIndex()) {
        if (isLetter(ch)) {
            latinStart = i
            break
        }
    }
    // 整串都是汉字（或者全是符号），没有待输入的拼音了
    if (latinStart < 0) return ParsedPreedit(preedit, emptyList())

    val prefix = preedit.substring(0, latinStart)

    // 把后面的字母按分隔符切成段，同时收一份「去掉分隔符的整串」（raw）
    val pieces = mutableListOf<String>()
    val rawBuilder = StringBuilder()
    val current = StringBuilder()
    for (ch in preedit.substring(latinStart)) {
        if (isLetter(ch)) {
            val lower = ch.lowercaseChar()
            current.append(lower)
            rawBuilder.append(lower)
        } else if (current.isNotEmpty()) {
            // 分隔符（空格、单引号……）→ 一段结束
            pieces.add(current.toString())
            current.setLength(0)
        }
    }
    if (current.isNotEmpty()) pieces.add(current.toString())
    val raw = rawBuilder.toString()

    // 一段都没有（不该发生，防御一下）
    if (pieces.isEmpty()) return ParsedPreedit(prefix, emptyList(), raw)

    // 逐个段还原成音节。
    //
    // 段边界是引擎给的（词库里查出来的），段里面的内容再交给 [splitEnginePiece]
    // 判断要不要细化——正常情况一段就是一个音节，原样收下；
    // 只有引擎确实给了一长串（或者 `fui` 这种词库里没有的谐音拼法）才动它。
    val out = mutableListOf<String>()
    for (piece in pieces) out.addAll(splitEnginePiece(piece))
    return ParsedPreedit(prefix, out, raw)
}
