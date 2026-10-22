/*
 * 一次语音输入会话：开麦 → VAD 切句 → SenseVoice 识别 → 回调文本。
 *
 * 结构照 LocalVoiceIME 的 SenseVoiceRecognitionService.Session，但砍掉了
 * RecognitionService 那层协议（那是给系统语音服务用的，霁梦不需要）：
 *   · 录音线程只负责读 PCM、丢进队列、算 RMS 给波形条
 *   · 识别线程等引擎就绪、独占 VAD、切段识别、把整段文本回吐
 *
 * 两个线程分开是关键：模型冷加载要 1~4 秒，这段时间录音不能停，
 * 否则主人开口的头几个字就没了。音频先在队列里排着，引擎一好立刻补算。
 */
package com.jimeng.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.Vad
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class VoiceRecognitionSession(
    private val context: Context,
    private val listener: Listener,
) {

    /**
     * 回调都在后台线程发出，上层自己 post 到主线程再碰 View。
     */
    interface Listener {
        /** 录音已经开始（麦克风真开了），这时才该把波形条显示出来。 */
        fun onRecordingStarted()

        /** 音量，dBFS，范围 [-80, -6]。 */
        fun onLevel(db: Float)

        /**
         * 这段字该落进输入框了。
         *
         * 触发条件由会话层判：字已经在候选栏里挂了至少 0.8 秒，
         * 并且这 0.8 秒里主人没再出声。候选栏里没字时永远不会走到这里。
         *
         * @param text 这一段的文本（增量，不含前面已经上过屏的）
         */
        fun onSentence(text: String)

        /**
         * 候选栏里该显示的那一串字（**还没上屏**）。
         *
         * 主人定的规矩是先看见再上屏：认出来的字先挂在这儿，
         * 停够 0.8 秒才搬到输入框。空串表示「已经搬走了，清空那一行」。
         */
        fun onPartial(fullText: String)

        /** 结束。text 是最终整段；error 非 null 表示失败。 */
        fun onFinished(text: String, error: String?)

        /** 设备上没给录音权限。上层要去拉起授权界面。 */
        fun onPermissionMissing()
    }

    @Volatile
    private var state = STATE_RECORDING

    private val audioQueue = ConcurrentLinkedQueue<FloatArray>()
    private val totalSamples = AtomicLong()
    /**
     * 待上屏的那一串字：先挂在候选栏里给主人看，安静满 0.8 秒才搬进输入框。
     *
     * 只由识别线程碰（同一个线程，不用加锁）。用 StringBuilder 是因为
     * 句中换气被 VAD 切成两段时要接回同一串，拼起来才是完整的一句。
     */
    private val pending = StringBuilder()

    /** 这一串待上屏的字是什么时候挂上去的：让预览至少能被看见 0.8 秒。 */
    private var pendingAtMs = 0L

    /** 最后一次听到人声的时刻。上屏的静音计时从这里算。 */
    private var lastSpeechAtMs = 0L

    private val texts = ArrayList<String>()
    private val textsLock = Any()

    @Volatile
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var workerThread: Thread? = null

    @Volatile
    private var resultSent = false

    /** 已经落地的文本，上层可随时取（比如用户中途取消也要保留已出的字）。 */
    val recognizedText: String
        get() = synchronized(textsLock) { texts.joinToString("") }

    fun start() {
        if (!hasMicPermission()) {
            state = STATE_ABORTED
            listener.onPermissionMissing()
            return
        }
        // 引擎可能在冷加载，worker 会等；录音线程必须立刻开跑
        workerThread = Thread({ runWorker() }, "jimeng-voice-worker").also { it.start() }
        if (!startRecording()) {
            fail("无法启动录音")
        }
    }

    /**
     * 主人松开/再按一下：停止采集，让 worker 把队列里剩下的音频算完再交结果。
     */
    fun stopAndFinalize() {
        if (state != STATE_RECORDING) return
        state = STATE_STOPPING
        try {
            audioRecord?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord.stop failed", t)
        }
        try {
            // 给读取线程一点时间把最后一包吐出来，别硬切断
            recordThread?.join(RECORD_JOIN_MS)
        } catch (_: InterruptedException) {
        }
        releaseRecorder()
        state = STATE_FINALIZING
    }

    /** 彻底放弃，不交任何结果。 */
    fun cancel() {
        if (state == STATE_DONE || state == STATE_ABORTED) return
        state = STATE_ABORTED
        releaseRecorder()
        audioQueue.clear()
        try {
            recordThread?.join(RECORD_JOIN_MS)
        } catch (_: InterruptedException) {
        }
        listener.onFinished("", null)
    }

    private fun hasMicPermission(): Boolean = try {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                VoiceAsrEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                Log.e(TAG, "minBufferSize=$minBuf")
                false
            } else {
                val record = AudioRecord.Builder()
                    // VOICE_RECOGNITION：关掉 AGC/降噪那套为通话优化的处理，
                    // 保留较宽的动态范围，识别模型更喜欢这种原始信号
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(VoiceAsrEngine.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    // 缓冲给两倍，避免读得稍慢就溢出丢样本（会听到「吞字」）
                    .setBufferSizeInBytes(max(minBuf * 2, MIN_BUFFER_BYTES))
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord not initialised")
                    record.release()
                    false
                } else {
                    record.startRecording()
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.e(TAG, "AudioRecord failed to start")
                        record.release()
                        false
                    } else {
                        audioRecord = record
                        recordThread =
                            Thread({ recordLoop(record) }, "jimeng-voice-record").also { it.start() }
                        listener.onRecordingStarted()
                        true
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            false
        }
    }

    private fun recordLoop(record: AudioRecord) {
        val buf = ShortArray(READ_CHUNK)
        var levelTick = 0
        try {
            while (state == STATE_RECORDING || state == STATE_STOPPING) {
                val n = record.read(buf, 0, buf.size)
                if (n < 0) {
                    Log.w(TAG, "read error $n")
                    break
                }
                if (n == 0) continue
                // PCM16 → float，sherpa-onnx 统一吃 [-1, 1]
                val samples = FloatArray(n) { buf[it] / 32768.0f }
                audioQueue.add(samples)
                totalSamples.addAndGet(n.toLong())
                // 每几包算一次音量就够，波形条 60fps 也不需要每包都喂
                if (++levelTick >= LEVEL_EVERY_CHUNKS) {
                    levelTick = 0
                    listener.onLevel(rmsToDb(samples))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "record loop failed", t)
        }
        Log.d(TAG, "record loop exited, state=$state")
    }

    private fun rmsToDb(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        val rms = sqrt(sum / samples.size)
        val db = if (rms < 1e-5) -80.0 else 20.0 * log10(rms)
        // 夹在 [-80, -6]：-6 以上几乎必然削波，不给波形条更夸张的输入
        return db.coerceIn(-80.0, -6.0).toFloat()
    }

    private fun runWorker() {
        // 这里允许阻塞等模型；协议层已经没有「3 秒内必须 ready」的约束了
        val error = VoiceAsrEngine.ensureLoaded(context)
        if (state == STATE_ABORTED) return
        if (error != null) {
            fail("语音引擎加载失败：$error")
            return
        }
        if (!VoiceAsrEngine.lockVad(VAD_LOCK_TIMEOUT_MS)) {
            fail("语音引擎正忙")
            return
        }
        try {
            val vad = VoiceAsrEngine.vadHandle
            if (vad == null) {
                fail("语音引擎不可用")
                return
            }
            processLoop(vad)
        } finally {
            VoiceAsrEngine.unlockVad()
        }
    }

    private fun processLoop(vad: Vad) {
        vad.reset()
        // 一开始当作「刚说过话」，免得冷启动那一刻就把空气当成一句说完
        lastSpeechAtMs = SystemClock.elapsedRealtime()
        while (true) {
            val chunk = audioQueue.poll()
            if (chunk != null) {
                vad.acceptWaveform(chunk)
                // 这一小包里有声音 → 把「安静计时」推到现在。
                // 两个判据取并集：VAD 觉得在说，或者能量明显高于底噪。
                // 不能只信 VAD：它对轻声、气声的尾巴不敏感，误判成安静会让字提前上屏
                if (vad.isSpeechDetected() || isLoud(chunk)) {
                    lastSpeechAtMs = SystemClock.elapsedRealtime()
                }
                drainSegments(vad)
                maybeCommitPending()
                continue
            }
            // 队列空了也要判：主人闭嘴的那段时间，正是该上屏的时候
            maybeCommitPending()
            when (state) {
                STATE_FINALIZING -> {
                    finalizeSession(vad)
                    return
                }
                STATE_ABORTED, STATE_DONE -> return
                // 录音还在继续但队列空了：稍等，别空转烧 CPU
                else -> try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    /** 这一小包的能量是不是明显高过底噪（约 -45 dBFS）。 */
    private fun isLoud(samples: FloatArray): Boolean {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size) > SPEECH_RMS_FLOOR
    }

    /**
     * 把 VAD 已经切完整的句子逐个送去识别，**每出一句立刻上屏**。
     *
     * 这就是主人要的「0.8 秒没说话就上屏，然后接着说」：
     * 静音满 0.8 秒 VAD 就把这一句切出来，识别完立刻 Listener.onSentence
     * 落到输入框，然后接着听下一句——不用松手、不用再按麦克风。
     *
     * 句子一交出去就从「待定稿」变「已上屏」，所以 Listener.onPartial
     * 后面拿到的永远是空串：界面上那一行该清空，等着听下一句。
     */
    /**
     * 把 VAD 切完整的句子逐个认出来，**先挂到候选栏，不急着上屏**。
     *
     * 主人定的规矩：
     *   · 先让字出现在候选栏里 —— 他得先看见，才知道自己说了什么
     *   · 出字之后再安静满 0.8 秒，才把这段搬进输入框（见 [maybeCommitPending]）
     *   · 候选栏里一个字都没有的时候，绝不上屏
     *
     * 切句的静音门槛（VAD_MIN_SILENCE = 0.4 秒）比上屏门槛短，是为了让
     * 「看见字」来得快；句中换气被切成两段的，在这里接回同一串 pending，
     * 所以最后搬进输入框的还是一整句，不会断成两句不相干的话。
     */
    private fun drainSegments(vad: Vad) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            if (state == STATE_ABORTED) return
            val text = VoiceAsrEngine.recognizeSegment(segment.samples).trim()
            if (text.isEmpty()) continue

            synchronized(textsLock) { texts.add(text) }
            appendPending(text)
            // 立刻挂到候选栏：主人看见了，才有「等 0.8 秒」这回事
            listener.onPartial(pending.toString())
        }
    }

    /** 把新认出来的一段接进待上屏的那一串。 */
    private fun appendPending(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // 英文逐段接会黏成一坨（hello + world），两段首尾都是 ASCII 时补空格
        val needSpace = pending.isNotEmpty() &&
            pending.last().code in 0x21..0x7E && t.first().code in 0x21..0x7E
        if (needSpace) pending.append(' ')
        pending.append(t)
        // 计时起点：从「主人看见这几个字」开始算，保证预览真的能被看见
        pendingAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * 该不该把候选栏里那串字搬进输入框。
     *
     * 两个条件都要满足（[force] 时跳过，那是主人按了「说完了」）：
     *   1. 出字之后已经安静满 0.8 秒 —— 主人没再出声
     *   2. 这一串字挂上去也满 0.8 秒 —— 保证预览来得及被看见
     * 候选栏里没字就直接返回：主人要求「还没识别出文字就不上屏」。
     */
    private fun maybeCommitPending(force: Boolean = false) {
        if (state == STATE_ABORTED || pending.isEmpty()) return
        if (!force) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSpeechAtMs < COMMIT_SILENCE_MS) return
            if (now - pendingAtMs < COMMIT_SILENCE_MS) return
        }
        val text = pending.toString()
        pending.setLength(0)
        // 到这里才真正落进输入框，然后候选栏清空等下一句
        listener.onSentence(text)
        listener.onPartial("")
    }


    private fun finalizeSession(vad: Vad) {
        // 最后一句话还没等到静音就松手了，flush 把它逼出来
        vad.flush()
        drainSegments(vad)
        if (state == STATE_ABORTED) return
        // 主人已经按了「说完了」：候着的那串立刻上屏，不再多等 0.8 秒
        maybeCommitPending(force = true)
        val full = synchronized(textsLock) { texts.joinToString("") }
        Log.i(TAG, "final: '$full'")
        sendResult(full)
    }


    private fun sendResult(text: String) {
        if (resultSent) return
        resultSent = true
        state = STATE_DONE
        listener.onFinished(text, null)
    }

    private fun fail(message: String) {
        if (resultSent || state == STATE_ABORTED) return
        resultSent = true
        state = STATE_DONE
        releaseRecorder()
        listener.onFinished(recognizedText, message)
    }

    private fun releaseRecorder() {
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
    }

    companion object {
        private const val TAG = "JimengVoice"

        private const val STATE_RECORDING = 0
        private const val STATE_STOPPING = 1
        private const val STATE_FINALIZING = 2
        private const val STATE_DONE = 3
        private const val STATE_ABORTED = 4

        /** 一次读多少采样点。20ms @16k = 320，取 1024 约 64ms，延迟和开销都合适。 */
        private const val READ_CHUNK = 1024

        /** 每读几包算一次音量。4 × 64ms ≈ 256ms，够波形条跳。 */
        private const val LEVEL_EVERY_CHUNKS = 4

        private const val MIN_BUFFER_BYTES = 16384

        /** 停止时等读取线程收尾的上限，超过就直接放掉。 */
        private const val RECORD_JOIN_MS = 1000L

        /** 抢 VAD 锁的超时：上一场如果卡住了，别把这个输入法也跟着卡死。 */
        /**
         * 出字之后安静多久，才算「这句说完了」、可以搬进输入框。
         *
         * 主人要的就是这个 0.8 秒：字先挂在候选栏里给他看见，
         * 停够 0.8 秒没再出声，才搬进输入框，然后接着听下一句。
         */
        private const val COMMIT_SILENCE_MS = 800L

        /** 能量低于这个值就当安静。约 -45 dBFS，比正常人声底噪留了余量。 */
        private const val SPEECH_RMS_FLOOR = 0.0056

        private const val VAD_LOCK_TIMEOUT_MS = 5000L
    }
}