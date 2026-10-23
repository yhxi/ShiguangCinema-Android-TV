package com.lshai.cloudscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 后台播放通知上的「暂停 / 继续 / 停止」按钮。
 *
 * 没引 media3-session（会把 APK 再撑大一圈，而且 TV 端不需要锁屏控制），
 * 用最朴素的方式：广播 → 静态持有者转给正在跑的 PlayerActivity。
 * Activity 不在（已经被系统回收）时什么都不做 —— 那时本来也没有声音在放。
 */
class PlaybackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> PlayerActivity.command(PlayerActivity.CMD_TOGGLE)
            ACTION_STOP -> PlayerActivity.command(PlayerActivity.CMD_STOP)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.lshai.cloudscreen.PLAYBACK_TOGGLE"
        const val ACTION_STOP = "com.lshai.cloudscreen.PLAYBACK_STOP"
    }
}
