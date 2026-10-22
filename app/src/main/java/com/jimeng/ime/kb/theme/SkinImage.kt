/*
 * 皮肤里的「一张图」。
 *
 * 纯色皮肤用不到这个类；带图的皮肤每一处图案都是一个 SkinImage。
 *
 * ── 图存在哪 ────────────────────────────────────────────
 * SkinImage 只存「相对路径」，不存绝对位置。真正的底座由皮肤来源决定：
 *   · 内置皮肤 → assets/skins/<skinId>/
 *   · 导入皮肤 → files/skins/<skinId>/   （安装时把 .jmskin 解压到这里）
 *
 * Theme 里带着 skinId，SkinCache 靠 (skinId, path) 两级就能定位到图，
 * 所以这里不需要记录来源，皮肤包换个存放位置也不用改数据结构。
 *
 * ── 为什么不用 NinePatchDrawable ─────────────────────────
 * 九宫格从 InputStream 构造要自己算 chunk，容易崩。
 * 这里只存「怎么切」的描述，真正构造 Drawable 交给 SkinCache，
 * 一层失败还能降级回纯色，不至于让键盘整个白掉。
 */
package com.jimeng.ime.kb.theme

/**
 * 一张皮肤贴图。
 *
 * @param path  相对路径，比如 "bg_dark.png"、"keys/key_light.9.png"
 * @param patch 九宫格切边（像素）。null 表示整图拉伸，不切。
 * @param alpha 总体透明度 0f–1f。1f 表示原样贴。
 * @param tile  是否平铺。true = 按原图大小重复铺满，false = 拉伸铺满。
 */
data class SkinImage(
    val path: String,
    val patch: NinePatchInsets? = null,
    val alpha: Float = 1f,
    val tile: Boolean = false,
)

/** 九宫格四个方向各留多少像素不拉伸 */
data class NinePatchInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** 左右加起来不能超过图片宽度，上下同理——校验用 */
    fun isSane(width: Int, height: Int): Boolean =
        left >= 0 && top >= 0 && right >= 0 && bottom >= 0 &&
            left + right < width && top + bottom < height

    companion object {
        /** 四边等宽，最常见的写法 */
        fun all(v: Int) = NinePatchInsets(v, v, v, v)
    }
}