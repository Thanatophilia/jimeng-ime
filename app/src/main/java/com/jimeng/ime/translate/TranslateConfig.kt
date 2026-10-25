/*
 * 翻译服务的两套配置：内置（作者配好的）和外接（用户自己填的）。
 *
 * ── 为什么分成两套 ─────────────────────────────
 * 内置这条是「开箱能用」：开发者把 url / key / model 写死在 BuildConfig 里，
 * 用户不用懂什么是 API 就能点翻译。它同时也是可关闭的——key 是要花钱的，
 * 谁都不希望被人拿去白嫖，所以给全局开关，默认开、能关。
 *
 * 外接这条是「你自己有 key 就用你的」：用户填自己的 url 和 key，
 * 走他自己的额度，跟内置的互不影响。两条各存各的字段，
 * 不共用一个 key 的存储位——否则用户填错一次就把内置的覆盖了。
 *
 * ── 关于 key 存在哪 ────────────────────────────
 * 内置 key 编译进 BuildConfig，装机就能用，但反编译能挖出来。
 * 这是所有「客户端内置 key」方案的共同问题，没法真正解决——
 * 真要防只能自建代理，那是另一件事。这里至少做到：
 * 运行时不让它出现在日志里、界面上只显示后四位。
 */

package com.jimeng.ime.translate

import android.content.Context
import com.jimeng.ime.BuildConfig

/** 内置服务的出厂配置。BuildConfig 里没填就是空串，等于「内置服务不可用」。 */
data class BuiltInEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    /** 三项都齐了才算配好。少一项就当没有内置服务，别拿半套配置去发请求。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 外接服务：用户自己填的一条。
 *
 * 允许「只换模型不换地址」这种用法，所以三项分开判断，
 * 但发请求前要求三项都非空。
 */
data class CustomEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 界面上显示用：key 只露后四位，避免截图泄露 */
    val maskedKey: String
        get() = when {
            apiKey.isBlank() -> ""
            apiKey.length <= 4 -> "****"
            else -> "****" + apiKey.takeLast(4)
        }
}

/**
 * 翻译用哪条通道。
 *
 * 默认 Auto：用户填了外接就用外接（他自己的额度，他做主），
 * 没填就用内置。这个顺序是对的——用户配了自己的 key，
 * 多半就是不希望用内置那条。
 */
enum class TranslateChannel {
    /** 有外接配置走外接，没有走内置 */
    Auto,

    /** 只用内置，内置不可用就报错 */
    BuiltIn,

    /** 只走外接 */
    Custom
}

/**
 * 互译语言对。
 *
 * 为什么不用「目标语言」这个说法：实际用起来，用户要的是
 * 「中日互译」这种**一对语言**，而不是单向的「译成日文」。
 * 他打中文就出日语、打日语就出中文，同一个选项两头都能用，
 * 不必在翻译前先想「这次该选哪个目标语言」。
 *
 * 具体往哪边翻由模型按原文判断（提示词里写清），
 * 所以枚举本身只需要记住这一对是哪两种语言。
 */
enum class TranslatePair(val label: String, val primary: String, val secondary: String) {
    /** 中文 ↔ 英文（默认） */
    ZhEn("中英互译", "中文", "英文"),

    /** 中文 ↔ 日文 */
    ZhJa("中日互译", "中文", "日文"),

    /** 中文 ↔ 韩文 */
    ZhKo("中韩互译", "中文", "韩文");

    /** 给提示词用的完整描述 */
    val promptHint: String
        get() = "如果原文主要是$primary，就译为$secondary；" +
            "如果原文主要是$secondary，就译为$primary。"

    companion object {
        /** 从存储里读出来，认不出的值退回默认的中英互译 */
        fun fromName(name: String?): TranslatePair =
            entries.firstOrNull { it.name == name } ?: ZhEn
    }
}

/** 翻译偏好：语言对等 */
data class TranslateSettings(
    val channel: TranslateChannel,
    /** 内置通道总开关。关掉之后即使 BuildConfig 有 key 也不发请求 */
    val builtInEnabled: Boolean,
    /** 互译语言对：中英 / 中日 / 中韩 */
    val pair: TranslatePair,
    /** 超时秒数 */
    val timeoutSeconds: Int
)

/**
 * 配置读写。
 *
 * 不用 [com.jimeng.ime.kb.prefs.AppPrefs] 那套 ManagedPreference，
 * 因为那套是给「键盘行为」那种长期稳定、要在键盘上实时响应的开关用的，
 * 里面每个字段都要挂监听。翻译配置改完之后下次翻译才读，不需要监听，
 * 用独立的一个 SharedPreferences 文件更清爽——也免得把 API Key
 * 混进键盘的那份 xml 里。
 */
object TranslateConfigStore {

    private const val SP_NAME = "jimeng_translate"

    private const val K_CUSTOM_URL = "custom_base_url"
    private const val K_CUSTOM_KEY = "custom_api_key"
    private const val K_CUSTOM_MODEL = "custom_model"

    private const val K_CHANNEL = "channel"
    private const val K_BUILTIN_ENABLED = "builtin_enabled"
    private const val K_TARGET_LANG = "target_language"
    /** 互译语言对。新字段，老的 K_TARGET_LANG 只用来做一次迁移 */
    private const val K_PAIR = "pair"
    private const val K_TIMEOUT = "timeout_seconds"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------- 内置

    /**
     * 出厂内置配置。
     *
     * 读 BuildConfig，这几个字段在 app/build.gradle.kts 里用
     * `buildConfigField` 注入，源码里不放真 key——这样仓库是干净的，
     * 你本地填自己的、构建机填你们的，各不影响。
     */
    fun builtIn(): BuiltInEndpoint = BuiltInEndpoint(
        baseUrl = BuildConfig.TRANSLATE_BUILTIN_URL,
        apiKey = BuildConfig.TRANSLATE_BUILTIN_KEY,
        model = BuildConfig.TRANSLATE_BUILTIN_MODEL
    )

    // ---------------------------------------------------------- 外接

    fun custom(ctx: Context): CustomEndpoint {
        val p = sp(ctx)
        return CustomEndpoint(
            baseUrl = p.getString(K_CUSTOM_URL, "").orEmpty(),
            apiKey = p.getString(K_CUSTOM_KEY, "").orEmpty(),
            model = p.getString(K_CUSTOM_MODEL, "").orEmpty()
        )
    }

    fun saveCustom(ctx: Context, endpoint: CustomEndpoint) {
        sp(ctx).edit()
            .putString(K_CUSTOM_URL, endpoint.baseUrl.trim())
            .putString(K_CUSTOM_KEY, endpoint.apiKey.trim())
            .putString(K_CUSTOM_MODEL, endpoint.model.trim())
            .apply()
    }

    fun clearCustom(ctx: Context) {
        sp(ctx).edit()
            .remove(K_CUSTOM_URL)
            .remove(K_CUSTOM_KEY)
            .remove(K_CUSTOM_MODEL)
            .apply()
    }

    // ---------------------------------------------------------- 偏好

    fun settings(ctx: Context): TranslateSettings {
        val p = sp(ctx)
        val channel = p.getString(K_CHANNEL, TranslateChannel.Auto.name)
            ?.let { runCatching { TranslateChannel.valueOf(it) }.getOrNull() }
            ?: TranslateChannel.Auto
        return TranslateSettings(
            channel = channel,
            builtInEnabled = p.getBoolean(K_BUILTIN_ENABLED, true),
            pair = TranslatePair.fromName(p.getString(K_PAIR, null)),
            // 夹一下：低于 5 秒基本必然超时，高于 120 秒用户等不下去
            timeoutSeconds = p.getInt(K_TIMEOUT, 30).coerceIn(5, 120)
        )
    }
    fun saveSettings(ctx: Context, s: TranslateSettings) {
        sp(ctx).edit()
            .putString(K_CHANNEL, s.channel.name)
            .putBoolean(K_BUILTIN_ENABLED, s.builtInEnabled)
            .putString(K_PAIR, s.pair.name)
            .putInt(K_TIMEOUT, s.timeoutSeconds.coerceIn(5, 120))
            .apply()
    }
    /**
     * 单独存语言对。
     *
     * 键盘上那个下拉要能立刻生效、又不想把整份 settings 读一遍改写，
     * 所以给一个窄接口。
     */
    fun savePair(ctx: Context, pair: TranslatePair) {
        sp(ctx).edit().putString(K_PAIR, pair.name).apply()
    }

    // ---------------------------------------------------------- 选用

    /**
     * 决定这次翻译走哪条，并把可用的 endpoint 交出去。
     *
     * 返回 null 表示「一条都用不了」，调用方这时应该退回系统翻译
     * （`ACTION_PROCESS_TEXT`），而不是弹错误——用户没配过 API 是很正常的状态，
     * 不该被打扰。
     */
    fun resolve(ctx: Context): ResolvedEndpoint? {
        val s = settings(ctx)
        val builtInRaw = builtIn()
        val builtIn = if (s.builtInEnabled && builtInRaw.isConfigured) builtInRaw else null
        val custom = custom(ctx).takeIf { it.isConfigured }

        return when (s.channel) {
            TranslateChannel.Custom -> custom?.let {
                ResolvedEndpoint(it.baseUrl, it.apiKey, it.model, TranslateChannel.Custom)
            }

            TranslateChannel.BuiltIn -> builtIn?.let {
                ResolvedEndpoint(it.baseUrl, it.apiKey, it.model, TranslateChannel.BuiltIn)
            }

            TranslateChannel.Auto -> when {
                custom != null ->
                    ResolvedEndpoint(custom.baseUrl, custom.apiKey, custom.model, TranslateChannel.Custom)

                builtIn != null ->
                    ResolvedEndpoint(builtIn.baseUrl, builtIn.apiKey, builtIn.model, TranslateChannel.BuiltIn)

                else -> null
            }
        }
    }
}

/** 解析完成的、可以直接拿去发请求的一条配置 */
data class ResolvedEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val channel: TranslateChannel
)
