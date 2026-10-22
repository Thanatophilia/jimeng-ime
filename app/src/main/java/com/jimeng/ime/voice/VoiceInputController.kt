/*
 * 语音输入的「总开关」：把录音条、录音会话、上屏串成一条线。
 *
 * ── 交互（主人定下的那套）─────────────────────────────────
 *   · 空闲时按麦克风    → 开麦，候选栏里出现录音条
 *   · 说话停 0.8 秒     → VAD 判定一句说完，识别结果**立刻上屏**，麦克风不关
 *   · 录音中再按麦克风  → 「说完了」，把最后半句 flush 出来上屏，然后干净地收条（不提示「已上屏」）
 *   · 录音中点 ✕        → 停止录音、收条（已上屏的字留在输入框里，不倒删）
 *
 * ── 为什么「上屏」和「收条」必须拆开 ──────────────────────
 * 旧版是「整场说完 → 一次性上屏 → 收条」，上屏必然伴随收条，写在一起没事。
 * 现在一句一上屏，如果上完屏就收条，主人每说一句录音条就消失一次，
 * 下一句得重新按麦克风——那就完全不是「接着说」了。
 * 所以：上屏只归 [commitSentence]，收条只归 [onFinished]，两边互不干涉。
 */
package com.jimeng.ime.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jimeng.ime.kb.theme.Theme

class VoiceInputController(
    private val context: Context,
    private val theme: Theme,
) {

    /** 一句话定稿了，交给输入法上屏。 */
    var onCommit: ((String) -> Unit)? = null

    /** 需要主人去系统设置里手动开权限时回调（第二次拒绝之后）。 */
    var onOpenPermissionSettings: (() -> Unit)? = null

    /**
     * 本地还没有语音模型时回调。
     *
     * 键盘上不能直接弹商店（输入法进程 UI 会被限制），交给输入法那边
     * 用一个能跳转的入口处理。没有模型是**正常状态**——用户可能就是想省下
     * 231MB，这时候不该装作正在听。
     */
    var onModelMissing: (() -> Unit)? = null

    /**
     * 语音条露脸 / 收起。
     *
     * 键盘靠它在候选栏里「让位 / 还位」——语音条占的就是中间那一格，
     * 一露脸就得把候选区、工具栏盖住。
     */
    var onBarVisibleChanged: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * 录音条界面。不再是飘在键盘上方的浮层卡片，而是直接嵌进候选栏
     * 中间那一格（见 VoiceBarUi）——主人要的就是这个效果：不挡字、
     * 不挡应用，键盘高度也不变。
     */
    val bar = VoiceBarUi(context, theme)

    private var session: VoiceRecognitionSession? = null

    /** 上一次被拒后系统不再弹框，只能引导去设置。 */
    private var permissionPermanentlyDenied = false

    private var receiver: BroadcastReceiver? = null

    /** 这一场有没有句子已经落进输入框。收尾提示靠它区分「已上屏」和「没听清」。 */
    private var committedInThisSession = false

    /** 主人已经按过「说完了」：之后别再喊「正在听」，改成「正在识别」。 */
    private var stopping = false

    /** 这一场被取消了（✕ / 键盘收起 / 换视图），收尾回调别再碰界面。 */
    private var discarding = false

    /** 上一次落进输入框的最后一个字，用来决定两句之间要不要补空格。 */
    private var lastChar = ' '

    /** 现在是不是正在录（含正在等识别结果）。 */
    val isActive: Boolean
        get() = session != null

    init {
        bar.onCancelClicked = { cancel() }
        bar.onVisibleChanged = { visible ->
            // 语音条自己先改好可见性，再让键盘去动候选栏布局，顺序不能反
            onBarVisibleChanged?.invoke(visible)
        }
    }

    /**
     * 麦克风按钮被按。一次调用一个语义：没在录就开始，在录就收尾。
     */
    fun toggle() {
        val cur = session
        if (cur != null) {
            // 已经在录：这一下是「说完了」。最后那半句会在 flush 里补上屏
            stopping = true
            main.post { bar.setStatus("正在识别…") }
            cur.stopAndFinalize()
            return
        }
        // 没有模型就直接说清楚，别开麦、别让用户对着空气说话
        if (!VoiceAsrEngine.hasModel(context)) {
            main.post {
                bar.showError("还没下载语音模型")
                postHideDelayed(ERROR_LINGER_MS)
            }
            onModelMissing?.invoke()
            return
        }
        if (!hasMicPermission()) {
            if (permissionPermanentlyDenied) {
                // 系统不会再弹，只能引到设置
                main.post {
                    bar.showError("麦克风权限被关了，去系统设置里打开")
                    postHideDelayed(ERROR_LINGER_MS)
                }
                onOpenPermissionSettings?.invoke()
            } else {
                main.post { bar.showWaiting("等待麦克风授权…") }
                registerPermissionReceiver()
                VoicePermissionActivity.start(context)
            }
            return
        }
        startSession()
    }

    /**
     * 中途放弃：停止录音、收起录音条。
     *
     * 已经上屏的句子**不撤回**——那些是主人看着它一句一句落进输入框的字，
     * 替他删掉比留着更冒犯。要删的话主人自己按退格。
     */
    fun cancel() {
        discarding = true
        session?.cancel()
        session = null
        main.post { bar.hideOverlay() }
    }

    /** 键盘收起 / 换输入框：彻底停掉，别留后台麦克风。 */
    fun shutdown() {
        discarding = true
        session?.cancel()
        session = null
        main.post { bar.hideOverlay() }
        unregisterPermissionReceiver()
    }

    private fun startSession() {
        // 引擎预热交给输入法的 onCreateInputView 做过了，这里再兜一次，
        // 覆盖「进程刚起、键盘一出来就按麦克风」这种情况
        VoiceAsrEngine.preload(context)

        // 一场新的开始：状态全部归零，别把上一场的「已上屏」带过来
        committedInThisSession = false
        stopping = false
        discarding = false
        lastChar = ' '

        main.post {
            bar.showListening()
            bar.setStatus("正在听…")
            if (!VoiceAsrEngine.isReady) {
                // 冷启动：说实话比假装正常好，主人知道要等一下
                bar.setStatus("正在加载语音模型…")
            }
        }

        val s = VoiceRecognitionSession(context, object : VoiceRecognitionSession.Listener {
            override fun onRecordingStarted() {
                // 模型可能还在加载，提示换成「在听了」让主人敢说话
                main.post { bar.setStatus("正在听…") }
            }

            override fun onLevel(db: Float) {
                main.post { bar.setLevel(db) }
            }

            override fun onSentence(text: String) {
                // 这段字在候选栏里挂够 0.8 秒了，现在才搬进输入框。
                // 麦克风不断，接着听下一句——主人不用再按一下。
                main.post { commitSentence(text) }
            }

            override fun onPartial(fullText: String) {
                // 候选栏里那串字：**还没上屏**，只是先给主人看。
                // 会话层会等「出字后安静满 0.8 秒」才把它搬进输入框，
                // 所以这里只管显示，一个字都不能往输入框里写。
                main.post {
                    if (discarding) return@post
                    bar.setText(fullText)
                    // 有字 → 让它自己显示；清空了 → 说明刚上完屏，等下一句
                    if (fullText.isBlank() && !stopping) bar.setStatus("正在听…")
                }
            }

            override fun onFinished(text: String, error: String?) {
                main.post {
                    session = null
                    stopping = false
                    // 注意：句子已经在 [onSentence] 里逐句上屏了，
                    // 这里**绝不能再 commit 一次**，否则最后一句会写两遍。
                    // 这里只管「把界面收干净 / 报个错」。
                    if (discarding) {
                        discarding = false
                        return@post
                    }
                    if (error != null && !committedInThisSession) {
                        Log.w(TAG, "voice finished with error: ${error}")
                        bar.showError(error)
                        postHideDelayed(ERROR_LINGER_MS)
                        return@post
                    }
                    if (error != null) {
                        // 字都已经落进输入框了，错误不值得再打扰主人：只记一笔日志
                        Log.w(TAG, "voice finished with error after commit: ${error}")
                    } else if (!committedInThisSession) {
                        // 开了麦但一句都没听出来：多半是没说话 / 太小声
                        bar.showError("没听清，再说一次？")
                        postHideDelayed(ERROR_LINGER_MS)
                        return@post
                    }
                    // 上屏成功：不提示任何字，直接收起来。
                    // 主人看着字一句句进输入框就够了，多一句「已上屏」反而碍事
                    bar.hideOverlay()
                }
            }

            override fun onPermissionMissing() {
                main.post {
                    session = null
                    bar.showError("没有麦克风权限")
                    postHideDelayed(ERROR_LINGER_MS)
                }
            }
        })
        session = s
        s.start()
    }

    /**
     * 一句定稿：立刻写进输入框，**不收条、不关麦**。
     *
     * 这是「边说边上屏」的落点。注意两件事：
     *   1. 不调 [VoiceBarUi.hideOverlay]——录音条要继续挂着听下一句；
     *      收条是 [onFinished] 的事。
     *   2. 上屏内容不用再做去重：会话层保证 Listener.onSentence
     *      给的是增量，前面说过的话不会再来一遍。
     *
     * 英文逐句听写时句子会黏成一坨（hello + world → helloworld），
     * 所以两句都是 ASCII 可见字符时补一个空格；中文不加，加了反而难看。
     */
    private fun commitSentence(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return

        val needSpace = needsSpaceBetween(lastChar, text.first())
        onCommit?.invoke(if (needSpace) " $text" else text)

        lastChar = text.last()
        committedInThisSession = true
        // 已经按过「说完了」就别再喊「正在听」，那会骗主人继续讲
        bar.setStatus(if (stopping) "正在识别…" else "正在听…")
    }

    /** 两个字符都是 ASCII 可见字符（字母、数字、标点）时，中间该有空格。 */
    private fun needsSpaceBetween(a: Char, b: Char): Boolean =
        a.code in 0x21..0x7E && b.code in 0x21..0x7E

    private fun postHideDelayed(delay: Long) {
        main.postDelayed({
            // 期间主人可能又按了一次，别把新会话的录音条收掉
            if (session == null) bar.hideOverlay()
        }, delay)
    }

    private fun hasMicPermission(): Boolean = try {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    // ---- 权限结果回传 ----

    private fun registerPermissionReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val granted = intent?.getBooleanExtra(
                    VoicePermissionActivity.EXTRA_GRANTED, false
                ) ?: false
                unregisterPermissionReceiver()
                if (granted) {
                    permissionPermanentlyDenied = false
                    main.post { bar.hideOverlay() }
                    // 用户刚点完允许，直接续上这次语音输入，不用再按一下
                    startSession()
                } else {
                    permissionPermanentlyDenied = true
                    main.post {
                        bar.showError("没拿到麦克风权限")
                        postHideDelayed(ERROR_LINGER_MS)
                    }
                }
            }
        }
        receiver = r
        try {
            val filter = IntentFilter(VoicePermissionActivity.ACTION_PERMISSION_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(r, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "register permission receiver failed", t)
            receiver = null
        }
    }

    private fun unregisterPermissionReceiver() {
        val r = receiver ?: return
        receiver = null
        try {
            context.unregisterReceiver(r)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister permission receiver failed", t)
        }
    }

    companion object {
        private const val TAG = "JimengVoice"

        /** 出错提示停留多久。 */
        private const val ERROR_LINGER_MS = 1600L
    }
}
