package com.jimeng.ime.kb.utils

/**
 * 雾凇词库里**真实存在**的拼音音节表（419 个）。
 *
 * 来源：从 assets/rime/cn_dicts/ 下 base / 8105 / ext / others 四个词库
 * 的音节列（第二列，空格分隔）扫出全部音节、去重排序得到。
 * 重新生成：
 *   cat assets/rime/cn_dicts 下四个 dict.yaml 文件
 *
 * 为什么需要这张表：
 * 展开页左侧编码区会按「换声母 / 换韵母」推谐音变体。
 * 但推出来的拼法引擎不一定认识——典型例子 `fui`：声母 f 合法、
 * 韵母 ui 也合法，可组合起来词库里 **0 条词条**。
 * 把输入串换成它，rime 切不出音节、一个候选都查不出来，
 * 界面看起来就是「点了变体没反应」。
 *
 * 所以变体生成完必须过一遍这张表：不在表里的直接不给。
 * 宁可少列几个格子，也不能给出按下去没效果的格子。
 */
val RIME_SYLLABLES: Set<String> = setOf(
    "a", "ai", "an", "ang", "ao", "ba", "bai", "ban", "bang", "bao",
    "bei", "ben", "beng", "bi", "bian", "biang", "biao", "bie", "bin", "bing",
    "bo", "bu", "ca", "cai", "can", "cang", "cao", "ce", "cei", "cen",
    "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi",
    "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo",
    "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo", "da", "dai",
    "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian",
    "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun",
    "duo", "e", "ei", "en", "eng", "er", "fa", "fan", "fang", "fei",
    "fen", "feng", "fiao", "fo", "fou", "fu", "ga", "gai", "gan", "gang",
    "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai",
    "guan", "guang", "gui", "gun", "guo", "ha", "hai", "han", "hang", "hao",
    "he", "hei", "hen", "heng", "hm", "hng", "hong", "hou", "hu", "hua",
    "huai", "huan", "huang", "hui", "hun", "huo", "ji", "jia", "jian", "jiang",
    "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
    "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong",
    "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo", "la",
    "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian",
    "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu",
    "luan", "lun", "luo", "lv", "lve", "m", "ma", "mai", "man", "mang",
    "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min",
    "ming", "miu", "mo", "mou", "mu", "n", "na", "nai", "nan", "nang",
    "nao", "ne", "nei", "nen", "neng", "ng", "ni", "nian", "niang", "niao",
    "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nuo", "nv",
    "nve", "o", "ou", "pa", "pai", "pan", "pang", "pao", "pei", "pen",
    "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
    "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu",
    "qu", "quan", "que", "qun", "ran", "rang", "rao", "re", "ren", "reng",
    "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run", "ruo", "sa",
    "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan",
    "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua",
    "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su",
    "suan", "sui", "sun", "suo", "ta", "tai", "tan", "tang", "tao", "te",
    "tei", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu",
    "tuan", "tui", "tun", "tuo", "wa", "wai", "wan", "wang", "wei", "wen",
    "weng", "wo", "wu", "xi", "xia", "xian", "xiang", "xiao", "xie", "xin",
    "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun", "ya", "yan", "yang",
    "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan",
    "yue", "yun", "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen",
    "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng",
    "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun",
    "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo",
)

/** 这个拼法在雾凇词库里真的有词条吗 */
fun isValidSyllable(s: String): Boolean = s in RIME_SYLLABLES
