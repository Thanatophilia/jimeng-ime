/*
 * 从 fcitx5-android 的 data/InputFeedbacks.kt 移植而来。
 *
 * 原版逻辑（按键音 + 震动，跟随系统或强制开关）完整保留，
 * 只把 AppPrefs 的取值路径换成本工程的 AppPrefs，并删掉 appContext 的 Application 依赖。
 */
package com.jimeng.ime.kb

import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.jimeng.ime.kb.prefs.AppPrefs
import com.jimeng.ime.kb.prefs.InputFeedbackMode
import com.jimeng.ime.kb.utils.appContext
import com.jimeng.ime.kb.utils.audioManager
import com.jimeng.ime.kb.utils.getSystemSettings
import com.jimeng.ime.kb.utils.vibrator

object InputFeedbacks {

    private var systemSoundEffects = false
    private var systemHapticFeedback = false

    /** 跟随系统设置时需要先同步一次系统值 */
    fun syncSystemPrefs() {
        systemSoundEffects =
            (getSystemSettings<Int>(Settings.System.SOUND_EFFECTS_ENABLED) ?: 1) == 1
        @Suppress("DEPRECATION")
        systemHapticFeedback =
            (getSystemSettings<Int>(Settings.System.HAPTIC_FEEDBACK_ENABLED) ?: 1) == 1
    }

    private val keyboardPrefs get() = AppPrefs.getInstance().keyboard

    private val vibrator by lazy { appContext.vibrator }
    private val hasAmplitudeControl by lazy {
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) && vibrator.hasAmplitudeControl()
    }

    fun hapticFeedback(view: View, longPress: Boolean = false, keyUp: Boolean = false) {
        val prefs = keyboardPrefs
        when (prefs.hapticOnKeyPress.getValue()) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemHapticFeedback) return
        }
        if (keyUp && !prefs.hapticOnKeyUp.getValue()) return

        val duration: Long
        val amplitude: Int
        val hfc: Int
        if (longPress) {
            duration = prefs.buttonLongPressVibrationMilliseconds.getValue().toLong()
            amplitude = prefs.buttonLongPressVibrationAmplitude.getValue()
            hfc = HapticFeedbackConstants.LONG_PRESS
        } else {
            duration = prefs.buttonPressVibrationMilliseconds.getValue().toLong()
            amplitude = prefs.buttonPressVibrationAmplitude.getValue()
            hfc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && keyUp) {
                HapticFeedbackConstants.KEYBOARD_RELEASE
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        }

        if (duration != 0L) {
            if (hasAmplitudeControl && amplitude != 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else {
            var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (prefs.hapticOnKeyPress.getValue() == InputFeedbackMode.Enabled) {
                @Suppress("DEPRECATION")
                flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            }
            view.performHapticFeedback(hfc, flags)
        }
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    private val audioManager by lazy { appContext.audioManager }

    fun soundEffect(effect: SoundEffect) {
        val prefs = keyboardPrefs
        when (prefs.soundOnKeyPress.getValue()) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemSoundEffects) return
        }
        val fx = when (effect) {
            SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
            SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
            SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
        }
        val volume = prefs.soundOnKeyPressVolume.getValue()
        if (volume == 0) {
            audioManager.playSoundEffect(fx, -1f)
        } else {
            audioManager.playSoundEffect(fx, volume / 100f)
        }
    }
}