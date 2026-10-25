/*
 * 语音输入期间的音频焦点。
 *
 * 只做一件事：请求 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK，请别的应用把音量压低。
 *
 * ## 为什么只留这一条
 *
 * 试过三种，最后只剩这一种：
 *   · GAIN_TRANSIENT（请对方暂停）—— 对方**必须**停，视频也跟着断。
 *     按个麦克风把正在看的剧掐了，说完还得手动点回去，代价太重，已删。
 *   · VOICE_COMMUNICATION + AcousticEchoCanceler（回声消除）——
 *     实测这台 OPPO 上系统本来就给 VOICE_RECOGNITION 挂了 Qualcomm Fluence 的 AEC，
 *     我们显式再挂一层没有可感知的收益，反而白跑一遍 AGC/降噪，识别率还更差，已删。
 *   · MAY_DUCK（现在这个）—— 请对方压低音量继续播。请不动也不影响录音。
 *
 * ## 接受它的局限
 *
 * duck 压到多少**由对方播放器自己决定**，这个值在 Android 公开 API 里既读不到也设不了。
 * 实测抖音（com.ss.android.ugc.aweme）的焦点栈里 Notify on duck: true，
 * 但 ducked players 是空的 —— 它收到回调照原音量接着放，一声都不降。
 * 这就是这个方案的全部余地：我们能做的只有「把请求发出去」。
 *
 * ## 不做的事
 *
 * · 不自己调 setStreamVolume。那改的是用户的全局音量滑条，不是临时压低：
 *   得自己记原值、自己还原，进程中途被杀就把用户音量永久留在小档；
 *   还得加 MODIFY_AUDIO_SETTINGS。而且实测本机 ColorOS 的 AudioHardening
 *   会直接忽略输入法的音量控制请求（日志：volume control for api 100 would be ignored
 *   for com.jimeng.ime, level: partial），写了也是白写。
 * · 不做「duck 失败再要 TRANSIENT」的降级：那就是把「暂停」那条路偷偷捡回来了。
 * · 不因为拿不到焦点就拒绝录音。焦点只管「别人让不让路」，不影响麦克风能不能开。
 */
package com.jimeng.ime.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class VoiceAudioFocus(context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)

    /** 请求用和释放用必须是同一个实例，否则 abandon 不生效。 */
    private var request: AudioFocusRequest? = null

    /**
     * 别的应用把焦点抢走了（来电 / 导航播报 / 另一个语音助手）。
     * 只发通知，不在这里碰资源——收尾交给会话自己做。
     */
    var onFocusLost: (() -> Unit)? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "focus lost")
                onFocusLost?.invoke()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 来电、导航播报这类。语音输入没有「暂停一下再接着录」的说法：
                // 用户已经开口了，中断就只能整场作废，否则交出去的是半句话，
                // 中途那几秒还全是别人的声音。
                Log.i(TAG, "focus lost transient")
                onFocusLost?.invoke()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 轮到我们 duck。录音没法「小声录」，忽略这次请求，按原音量接着采。
                // 不调 onFocusLost：录音本身没受影响，弹个错只会白吓用户。
                Log.d(TAG, "focus can duck, recording continues")
            }

            AudioManager.AUDIOFOCUS_GAIN -> Log.d(TAG, "focus gained again")
        }
    }

    /**
     * 申请焦点。true = 拿到了，false = 被拒 / 对方选择不让。
     *
     * 被拒**不阻断录音**：照样开麦，交给上层用全 0 检测去判断麦克风是不是真被占了。
     */
    fun acquire(): Boolean {
        release()
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            // USAGE_VOICE_COMMUNICATION：明说「我要讲话」，别的播放器才让路。
            // 用 USAGE_MEDIA 或 USAGE_UNKNOWN 的话，不少播放器会当没听见。
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // 我是录音，被 duck 了也没法「小声录」，所以不接管暂停
            .setWillPauseWhenDucked(false)
            // 不接受延迟授予：通话结束后突然自己开麦，比没反应更糟
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
        request = r
        val result = try {
            am.requestAudioFocus(r)
        } catch (t: Throwable) {
            Log.w(TAG, "requestAudioFocus failed", t)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "focus: duck granted")
            return true
        }
        // 对方拿着焦点不让，或者直接拒了。不阻断录音
        Log.w(TAG, "focus: duck refused ($result), recording anyway")
        return false
    }

    /** 放手。反复调用安全——正常停、取消、失败全走这里。 */
    fun release() {
        val r = request ?: return
        request = null
        try {
            am.abandonAudioFocusRequest(r)
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusRequest failed", t)
        }
    }

    companion object {
        private const val TAG = "JimengVoice"
    }
}