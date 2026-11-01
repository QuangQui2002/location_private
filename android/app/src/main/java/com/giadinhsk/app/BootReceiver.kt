package com.giadinhsk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tự bật lại chia sẻ vị trí sau khi khởi động lại máy hoặc cập nhật app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).enabled) {
            try {
                LocationService.start(context)
            } catch (_: Exception) {
                // Hệ thống có thể chặn khởi động service nền; người dùng mở app sẽ bật lại.
            }
        }
    }
}
