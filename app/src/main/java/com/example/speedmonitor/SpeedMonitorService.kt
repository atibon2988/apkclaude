package com.example.speedmonitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.abs

class SpeedMonitorService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var prefs: android.content.SharedPreferences

    // Mốc coi là "đang di chuyển" (km/h) - xác nhận đã từng chạy đủ nhanh trước khi xét trigger
    private val movingThreshold = 15f

    // Sàn tối thiểu - dưới mức này coi là đứng yên hẳn, không trigger dù ngưỡng người dùng đặt bao nhiêu
    private val minTriggerSpeed = 1f

    private val requiredBelowReadings = 2
    private var belowCount = 0
    private var isMoving = false

    // ---- Pause/Resume thủ công qua nút nổi ----
    private var isPaused = false

    // ---- Tiết kiệm pin khi đỗ xe lâu ----
    private val parkTimeoutMs = 5 * 60 * 1000L
    private val parkSpeedThreshold = 2f
    private var stoppedSinceMs: Long = 0L
    private var isPowerSaveMode = false

    // ---- Nút nổi ----
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            if (!location.hasSpeed()) return

            val speedKmh = location.speed * 3.6f
            val userThreshold = prefs.getFloat("threshold", 5f)
            val targetPkg = prefs.getString("target_package", null)

            Log.d("SpeedMonitor", "Speed=$speedKmh moving=$isMoving paused=$isPaused powerSave=$isPowerSaveMode")

            handleParkingPowerSave(speedKmh, SystemClock.elapsedRealtime())
            handleFloatingButtonVisibility(speedKmh)
            handleTriggerLogic(speedKmh, userThreshold, targetPkg)
        }
    }

    private fun handleParkingPowerSave(speedKmh: Float, now: Long) {
        if (speedKmh < parkSpeedThreshold) {
            if (stoppedSinceMs == 0L) {
                stoppedSinceMs = now
            } else if (!isPowerSaveMode && now - stoppedSinceMs >= parkTimeoutMs) {
                switchToPowerSaveMode()
            }
        } else {
            stoppedSinceMs = 0L
            if (isPowerSaveMode) {
                switchToNormalMode()
            }
        }
    }

    /**
     * Logic chính:
     * - Sàn tối thiểu: tốc độ <= 1km/h coi là đứng yên hẳn -> không trigger.
     * - Đỗ xe (power-save mode): tắt hẳn trigger.
     * - Đang Pause thủ công: tắt hẳn trigger, chỉ tự Resume khi tốc độ vượt lại 15km/h.
     * - App đích đang hiển thị: không mở lại (check qua UsageStatsManager).
     */
    private fun handleTriggerLogic(speedKmh: Float, userThreshold: Float, targetPkg: String?) {
        if (targetPkg == null) return

        if (speedKmh > movingThreshold) {
            isMoving = true
            if (isPaused) {
                isPaused = false
                updateButtonIcon()
                Log.d("SpeedMonitor", "Tốc độ vượt lại $movingThreshold km/h -> tự động Resume")
            }
        }

        if (!isMoving || isPaused || isPowerSaveMode) {
            belowCount = 0
            return
        }

        if (speedKmh <= minTriggerSpeed) {
            belowCount = 0 // đứng yên hẳn -> không trigger
            return
        }

        if (speedKmh <= userThreshold) {
            belowCount++
            if (belowCount >= requiredBelowReadings) {
                if (!isTargetAppInForeground(targetPkg)) {
                    launchTargetApp(targetPkg)
                }
            }
        } else {
            belowCount = 0
        }
    }

    private fun isTargetAppInForeground(pkg: String): Boolean {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val end = System.currentTimeMillis()
        val begin = end - 10_000L

        val events = usm.queryEvents(begin, end)
        var lastForegroundPkg: String? = null
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> lastForegroundPkg = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (event.packageName == lastForegroundPkg) lastForegroundPkg = null
                }
            }
        }
        return lastForegroundPkg == pkg
    }

    // ==================== NÚT NỔI ====================

    private fun handleFloatingButtonVisibility(speedKmh: Float) {
        if (speedKmh > minTriggerSpeed) {
            showFloatingButton()
        } else {
            hideFloatingButton()
        }
    }

    private fun showFloatingButton() {
        if (floatingView != null) return
        if (!Settings.canDrawOverlays(this)) return

        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        val view = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        floatingView = view

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val displayMetrics = resources.displayMetrics
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        params.x = prefs.getInt("btn_x", 50)
        params.y = prefs.getInt("btn_y", displayMetrics.heightPixels - 300)

        setupDragAndClick(view, params)
        windowManager?.addView(view, params)
        updateButtonIcon()
    }

    private fun hideFloatingButton() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w("SpeedMonitor", "Lỗi khi ẩn nút nổi: ${e.message}")
            }
        }
        floatingView = null
    }

    private fun setupDragAndClick(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 15 || abs(dy) > 15) isDragging = true
                        if (isDragging) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager?.updateViewLayout(v, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            prefs.edit().putInt("btn_x", params.x).putInt("btn_y", params.y).apply()
                        } else {
                            togglePause()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun togglePause() {
        isPaused = !isPaused
        belowCount = 0
        updateButtonIcon()
        Log.d("SpeedMonitor", if (isPaused) "Đã Pause trigger" else "Đã Resume trigger (thủ công)")
    }

    private fun updateButtonIcon() {
        val icon = floatingView?.findViewById<ImageView>(R.id.btnIcon)
        val bg = floatingView?.findViewById<View>(R.id.btnBackground)
        if (isPaused) {
            icon?.setImageResource(android.R.drawable.ic_media_play)
            (bg?.background as? GradientDrawable)?.setColor(0xFFFF9800.toInt())
        } else {
            icon?.setImageResource(android.R.drawable.ic_media_pause)
            (bg?.background as? GradientDrawable)?.setColor(0xFF4CAF50.toInt())
        }
    }

    // ==================== VÒNG ĐỜI SERVICE ====================

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        startForeground(NOTIFICATION_ID, buildNotification(false))
        startLocationUpdates(normalRequest())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun normalRequest(): LocationRequest {
        val intervalMs = prefs.getLong("interval_ms", 2000L)
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun powerSaveRequest(): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(30_000L)
            .build()
    }

    private fun startLocationUpdates(request: LocationRequest) {
        val hasPermission = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } else {
            Log.e("SpeedMonitor", "Không có quyền vị trí, dừng service")
            stopSelf()
        }
    }

    private fun switchToPowerSaveMode() {
        fusedClient.removeLocationUpdates(callback)
        startLocationUpdates(powerSaveRequest())
        isPowerSaveMode = true
        hideFloatingButton()
        updateNotification(true)
    }

    private fun switchToNormalMode() {
        fusedClient.removeLocationUpdates(callback)
        startLocationUpdates(normalRequest())
        isPowerSaveMode = false
        updateNotification(false)
    }

    private fun launchTargetApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            Log.d("SpeedMonitor", "Đã mở app: $pkg")
        }
    }

    private fun buildNotification(powerSave: Boolean): Notification {
        val channelId = "speed_monitor_channel"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Speed Monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val text = if (powerSave) {
            "Đã đỗ xe - đang tiết kiệm pin (GPS quét chậm hơn)"
        } else {
            "Đang theo dõi tốc độ - sẽ tự mở app khi chạy chậm lại"
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Speed Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(powerSave: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(powerSave))
    }

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(callback)
        hideFloatingButton()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
