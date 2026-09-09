package com.example.speedmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Tự khởi động lại SpeedMonitorService sau khi thiết bị khởi động,
 * nếu người dùng đã từng chọn app đích trước đó (tức đã dùng app này rồi).
 * Đầu Android ô tô thường khởi động lại mỗi khi bật/tắt máy, nên receiver này
 * giúp không phải mở app thủ công để bấm "Bắt đầu" mỗi lần.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val hasTarget = prefs.getString("target_package", null) != null
            if (hasTarget) {
                val serviceIntent = Intent(context, SpeedMonitorService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
