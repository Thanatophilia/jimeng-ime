/*
 * 录音权限的透明宿主 Activity。
 *
 * 输入法（InputMethodService）自己不能弹系统权限对话框，只能在按下麦克风时
 * 拉起这个 Activity 去要一次 RECORD_AUDIO；用户点完立刻 finish，回到键盘继续。
 *
 * 照 fcitx5-android 的 VoicePermissionActivity，但多做了一点：
 * 把「用户到底给没给」回传出去，键盘据此决定是继续录音还是提示去设置里开。
 */
package com.jimeng.ime.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // 已经给过了（比如在设置里手动开的），不用再弹
            publish(true)
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        publish(granted)
        finish()
    }

    /**
     * 不管用户点允许还是拒绝都要发一次：键盘那边在等这个结果，
     * 不发的话它会一直停在「等你授权」的状态里。
     */
    private fun publish(granted: Boolean) {
        try {
            sendBroadcast(
                Intent(ACTION_PERMISSION_RESULT)
                    .setPackage(packageName)
                    .putExtra(EXTRA_GRANTED, granted)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "publish permission result failed", t)
        }
    }

    companion object {
        private const val TAG = "JimengVoice"
        private const val REQUEST_CODE = 1

        const val ACTION_PERMISSION_RESULT = "com.jimeng.ime.voice.PERMISSION_RESULT"
        const val EXTRA_GRANTED = "granted"

        /** 拉起授权页。键盘调用；Flags 用 NEW_TASK 是因为调用方是 Service 上下文。 */
        fun start(context: Context) {
            try {
                context.startActivity(
                    Intent(context, VoicePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "start permission activity failed", t)
            }
        }
    }
}