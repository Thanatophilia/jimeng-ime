/*
 * 从 fcitx5-android 移植过来的工具函数。
 * 原版依赖 FcitxApplication，这里改成从全局持有 Application Context。
 */
package com.jimeng.ime.kb.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.media.AudioManager
import android.os.Build
import android.os.Vibrator
import android.provider.Settings
import android.util.TypedValue
import android.view.View

/** 全局 Application Context，由 JimengIME.onCreate 里初始化 */
@SuppressLint("StaticFieldLeak")
object AppHolder {
    lateinit var appContext: Context
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }
    val isInited get() = this::appContext.isInitialized
}

val appContext: Context get() = AppHolder.appContext

val Context.audioManager: AudioManager
    get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

val Context.vibrator: Vibrator
    get() {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

inline fun <reified T> getSystemSettings(name: String): T? {
    val ctx = if (AppHolder.isInited) appContext else return null
    val raw = try {
        Settings.System.getString(ctx.contentResolver, name)
    } catch (e: Exception) {
        null
    } ?: return null
    return when (T::class) {
        Int::class -> raw.toIntOrNull()
        Boolean::class -> (raw.toIntOrNull() ?: 0) != 0
        String::class -> raw
        Float::class -> raw.toFloatOrNull()
        else -> null
    } as? T
}

/** 从主题属性里取 dimension 并转成 px */
inline fun Context.styledFloat(attrRes: Int): Float = withResolvedThemeAttribute(attrRes) {
    TypedValue.complexToFloat(data)
}

inline fun View.styledFloat(attrRes: Int): Float = context.styledFloat(attrRes)

inline fun <R> Context.withResolvedThemeAttribute(attrRes: Int, block: TypedValue.() -> R): R {
    val value = TypedValue()
    theme.resolveAttribute(attrRes, value, true)
    return value.block()
}

/**
 * ConstraintLayout 的"取消约束"常量。
 * fcitx5 里是自定义的，实际就是 ConstraintLayout.LayoutParams.UNSET = -1。
 */
const val unset = -1
