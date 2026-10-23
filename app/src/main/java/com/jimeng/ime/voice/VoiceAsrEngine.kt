/*
 * SenseVoice 语音识别引擎（进程级单例）。
 *
 * 和 fcitx5-android 那套 RecognitionService 路线不同：霁梦不走系统语音服务，
 * 也不做独立输入法，直接在自己的 IME 进程里加载 sherpa-onnx。
 *
 * 为什么是单例 + 预热：
 *   · model.int8.onnx 有 239MB，冷加载实测 1~4 秒；
 *   · 输入法进程是常驻的（系统会一直保着键盘进程），所以只需要付一次代价；
 *   · 键盘一弹出来就在后台线程里 [preload]，等用户真按麦克风时通常已经就绪；
 *   · 万一还没就绪，录音线程先跑、音频先进队列，引擎好了立刻补算，
 *     不会把用户已经说出口的话丢掉（这一点跟 LocalVoiceIME 的做法一致）。
 *
 * 模型放哪：**不打进 APK**。用户在扩展商店里下载，装到
 * `filesDir/store/sensevoice-small-int8/`；也认 `filesDir/models/`（手工推）
 * 和老版本的 `assets/sense-voice/`（兼容）。见 [Source]。
 *
 * 没有模型是一种正常状态：键盘按麦克风会引导去商店，不报错、不空转。
 */
package com.jimeng.ime.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

object VoiceAsrEngine {
    private const val TAG = "JimengVoice"

    /** SenseVoice / VAD 都只吃 16k 单声道。 */
    const val SAMPLE_RATE = 16000

    /** Silero VAD v5 的固定窗长（采样点）。 */
    const val VAD_WINDOW = 512

    private const val ASSET_DIR = "sense-voice/"

    /** 商店里的模型 id，和 `assets/store/index.json` 里那条的 id 一致。 */
    private const val STORE_ID = "sensevoice-small-int8"

    /** 三件套的文件名。商店装、手推、assets 三处都用同一套名字。 */
    private const val MODEL_FILE = "model.int8.onnx"
    private const val VAD_FILE = "silero_vad_v5.onnx"
    private const val TOKENS_FILE = "tokens.txt"

    private val loadLock = Any()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var vad: Vad? = null

    @Volatile
    private var loadFailed = false

    /** 最近一次加载失败的原因，给状态条用。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 引擎是否已经可以干活。 */
    val isReady: Boolean
        get() = recognizer != null && vad != null

    /**
     * 本地有没有可用的模型文件（不看是否已加载进内存）。
     *
     * 键盘按麦克风前用它做一次快判：没模型就引导去商店，
     * 别开麦让用户对着空气说话。只 stat 三个文件，很便宜。
     */
    fun hasModel(context: Context): Boolean = locateModel(context.applicationContext) != null

    /**
     * 共享的 VAD 句柄。
     *
     * 原生对象不是线程安全的，识别会话之间必须串行使用，
     * 拿它之前一律先 [lockVad]。只有一份 VAD 是因为它会自己维护
     * 音频缓冲，多份反而会各切一段、句子被切碎。
     */
    val vadHandle: Vad?
        get() = vad

    private val vadLock = ReentrantLock()

    /** 独占 VAD 句柄，超时返回 false（避免识别线程互相卡死）。 */
    fun lockVad(timeoutMs: Long): Boolean = try {
        vadLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        false
    }

    fun unlockVad() {
        try {
            // 没拿到锁就 unlock 会抛 IllegalMonitorStateException，
            // 那种情况只可能是上层逻辑写错，兜住别让它把输入法带崩。
            vadLock.unlock()
        } catch (t: Throwable) {
            Log.w(TAG, "unlockVad failed", t)
        }
    }

    /**
     * 确保引擎已加载。会阻塞，只能在后台线程调。
     *
     * 模型放哪（**不随 APK 走**，用户从扩展商店下）。查找顺序：
     *   1. `filesDir/store/<商店 id>/`  ← 商店下载装的，常态
     *   2. `filesDir/models/`           ← 手工推进来的（调试/换模型用）
     *   3. APK assets/sense-voice/      ← 老版本内置过，兼容一下（新包已经没有）
     *
     * 三个地方都没有就报「没装模型」，不假装能识别。
     *
     * @return null 表示成功，否则是错误描述。
     */
    fun ensureLoaded(context: Context): String? {
        if (isReady) return null
        // 上次已经明确失败（比如模型文件损坏）就不再反复重试，
        // 否则每弹一次键盘都要白等几秒。
        if (loadFailed) return lastError ?: "语音引擎不可用"

        synchronized(loadLock) {
            if (isReady) return null
            if (loadFailed) return lastError ?: "语音引擎不可用"
            return try {
                val t0 = System.currentTimeMillis()
                val app = context.applicationContext
                val source = locateModel(app)
                if (source == null) {
                    // 没下模型不是「错误」，是一种正常的初始状态：
                    // 键盘上按麦克风会引导去商店，不该反复重试刷日志。
                    loadFailed = true
                    lastError = "还没下载语音模型"
                    Log.i(TAG, "no voice model installed, skipped loading")
                    return lastError
                }
                val rec = OfflineRecognizer(
                    assetManager = if (source == Source.Assets) app.assets else null,
                    config = buildRecognizerConfig(app, source),
                )
                val v = Vad(
                    assetManager = if (source == Source.Assets) app.assets else null,
                    config = buildVadConfig(app, source),
                )
                recognizer = rec
                vad = v
                lastError = null
                Log.i(
                    TAG,
                    "engine ready in ${System.currentTimeMillis() - t0} ms (source=$source)"
                )
                null
            } catch (t: Throwable) {
                loadFailed = true
                lastError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "engine load failed", t)
                lastError
            }
        }
    }

    /**
     * 后台预热。键盘一露面就叫它，用户真按麦克风时基本已经热了。
     *
     * 失败不重试：多半是模型缺失/损坏，重试也不会变好。
     */
    fun preload(context: Context) {
        if (isReady || loadFailed) return
        Thread({
            try {
                ensureLoaded(context)
            } catch (t: Throwable) {
                Log.e(TAG, "preload failed", t)
            }
        }, "jimeng-voice-preload").start()
    }

    /**
     * 识别一整段语音（VAD 已经切好的一段）。
     *
     * 只允许在单一识别线程里调 —— [recognizer] 内部没有做并发保护。
     * 空音频直接返回空串，别白跑一次推理。
     */
    fun recognizeSegment(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        if (samples.isEmpty()) return ""
        return try {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recognize failed", t)
            ""
        }
    }

    /** 释放引擎。输入法一般不需要，留着给「关掉语音功能」用。 */
    fun release() {
        synchronized(loadLock) {
            try {
                vad?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "vad release failed", t)
            }
            try {
                recognizer?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "recognizer release failed", t)
            }
            vad = null
            recognizer = null
            // 失败状态和错误文案一起清掉：否则「卸了模型 → 又装回来」之后
            // 管理页还会挂着上一次那句旧错误，看着像没修好
            loadFailed = false
            lastError = null
        }
    }

    private fun buildRecognizerConfig(context: Context, source: Source): OfflineRecognizerConfig {
        val dir = source.prefix(context)
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$dir$MODEL_FILE",
                    // 空串 = 自动判语种（中/英/日/韩/粤），用户不用手动切
                    language = "",
                    // ITN 会把「二零二五年」写成「2025年」，输入法要这个
                    useInverseTextNormalization = true,
                ),
                tokens = "$dir$TOKENS_FILE",
                numThreads = MODEL_THREADS,
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
    }

    private fun buildVadConfig(context: Context, source: Source): VadModelConfig {
        val dir = source.prefix(context)
        return VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$dir$VAD_FILE",
                threshold = VAD_THRESHOLD,
                // 句中的短停顿不要切段，一停顿就断句会让「我觉得…那个…」碎成好几段
                minSilenceDuration = VAD_MIN_SILENCE,
                minSpeechDuration = VAD_MIN_SPEECH,
                windowSize = VAD_WINDOW,
                // 一口气说得太长（超过 15 秒）也强制切一刀，免得延时越拖越大
                maxSpeechDuration = VAD_MAX_SPEECH,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
    }

    // ------------------------------------------------------------ 模型在哪

    /** 模型的三件套从哪来。 */
    enum class Source {
        /** 扩展商店下载，装在 `filesDir/store/<id>/` —— 常态 */
        Store,

        /** 手工推进 `filesDir/models/` —— 调试用 */
        Files,

        /** 老版本内置在 assets —— 兼容，新包已经没有 */
        Assets;

        /** sherpa 要的路径前缀：文件模式是绝对目录 + "/"，assets 模式是空串。 */
        fun prefix(context: Context): String = when (this) {
            Store -> storeModelDir(context).absolutePath + "/"
            Files -> legacyModelDir(context).absolutePath + "/"
            Assets -> ""
        }
    }

    /**
     * 找一个能用的模型来源，找不到返回 null。
     *
     * 判定标准是三件套齐全。只看文件在不在，不校验哈希 —— 哈希在下载时
     * 已经对过一遍了，每次弹键盘再算 239MB 的 sha256 会把键盘拖死。
     */
    private fun locateModel(context: Context): Source? = when {
        hasModelFiles(storeModelDir(context)) -> Source.Store
        hasModelFiles(legacyModelDir(context)) -> Source.Files
        hasModelInAssets(context) -> Source.Assets
        else -> null
    }

    private fun hasModelFiles(dir: File): Boolean =
        File(dir, MODEL_FILE).isFile &&
            File(dir, VAD_FILE).isFile &&
            File(dir, TOKENS_FILE).isFile

    private fun hasModelInAssets(context: Context): Boolean = try {
        context.assets.open("$ASSET_DIR$MODEL_FILE").close()
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * 商店装的模型目录。
     *
     * 这里写死了商店 id —— 以后商店里要是出现第二个语音模型，
     * 得改成「读清单挑一个已装的」，那时再动。
     */
    private fun storeModelDir(context: Context): File =
        File(File(context.filesDir, "store"), STORE_ID)

    /** 老的手推目录，保留兼容。 */
    private fun legacyModelDir(context: Context): File = File(context.filesDir, "models")

    // ---- 调参放在一处，改这里就够了 ----
    /** 推理线程数。2 是实测在手机上「快点」和「别太烫」之间比较稳的值。 */
    private const val MODEL_THREADS = 2
    /** VAD 判定「在说话」的门限。调高更抗噪，调低更灵敏。 */
    private const val VAD_THRESHOLD = 0.5f
    /**
     * VAD 切句的静音门槛 —— **0.4 秒**。
     *
     * 这个值只管「什么时候把这一段音频切出来送去识别」，
     * **不管什么时候上屏**。上屏那 0.8 秒由 VoiceRecognitionSession 单独计：
     *   切句 0.4 秒 → 识别 → 字出现在候选栏（用户先看见）
     *   → 出字后再安静 0.8 秒 → 才搬进输入框
     *
     * 所以这里要短一点：切得快，字才早出现在候选栏，用户不用干等。
     * 不更短（0.2 以下）是因为 VAD 会在字中间就切，识别出来的半截词更多。
     */
    private const val VAD_MIN_SILENCE = 0.4f
    /** 短于这个时长的话音当作噪声丢掉。 */
    private const val VAD_MIN_SPEECH = 0.25f
    /** 单段最长时长，超了强制切段。 */
    private const val VAD_MAX_SPEECH = 15.0f
}
