package com.example.speedmonitor

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var tvTargetApp: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private lateinit var tvDebugSpeed: TextView
    private lateinit var tvDebugCarState: TextView
    private lateinit var tvDebugCond1: TextView
    private lateinit var tvDebugCond2: TextView
    private lateinit var tvDebugCond3: TextView
    private lateinit var tvDebugCond4: TextView
    private lateinit var tvDebugTrigger: TextView

    // true trong lúc đang đi qua từng bước xin quyền, để onResume() tự động
    // tiếp tục bước tiếp theo khi quay lại từ màn hình Settings, không cần
    // người dùng bấm "Bắt đầu" lại nhiều lần.
    private var permissionFlowActive = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val speed = intent.getFloatExtra(SpeedMonitorService.EXTRA_SPEED, 0f)
            val condMoving = intent.getBooleanExtra(SpeedMonitorService.EXTRA_COND_MOVING, false)
            val condInZone = intent.getBooleanExtra(SpeedMonitorService.EXTRA_COND_IN_ZONE, false)
            val condNotPaused = intent.getBooleanExtra(SpeedMonitorService.EXTRA_COND_NOT_PAUSED, true)
            val condNotPowerSave = intent.getBooleanExtra(SpeedMonitorService.EXTRA_COND_NOT_POWERSAVE, true)
            val appForeground = intent.getBooleanExtra(SpeedMonitorService.EXTRA_APP_FOREGROUND, false)
            val allMet = intent.getBooleanExtra(SpeedMonitorService.EXTRA_ALL_MET, false)
            val isPowerSave = intent.getBooleanExtra(SpeedMonitorService.EXTRA_IS_POWERSAVE_MODE, false)

            updateDebugUi(speed, condMoving, condInZone, condNotPaused, condNotPowerSave, appForeground, allMet, isPowerSave)
        }
    }

    private val requestForegroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestNextMissingPermission()
        } else {
            permissionFlowActive = false
            Toast.makeText(this, "Cần quyền vị trí để hoạt động", Toast.LENGTH_LONG).show()
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Dù được cấp hay không cũng tiếp tục bước sau - nếu từ chối, service vẫn
        // chạy được lúc app đang mở, chỉ hạn chế khi khóa máy (đã cảnh báo ở bước sau)
        requestNextMissingPermission()
    }

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestNextMissingPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        tvTargetApp = findViewById(R.id.tvTargetApp)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        tvDebugSpeed = findViewById(R.id.tvDebugSpeed)
        tvDebugCarState = findViewById(R.id.tvDebugCarState)
        tvDebugCond1 = findViewById(R.id.tvDebugCond1)
        tvDebugCond2 = findViewById(R.id.tvDebugCond2)
        tvDebugCond3 = findViewById(R.id.tvDebugCond3)
        tvDebugCond4 = findViewById(R.id.tvDebugCond4)
        tvDebugTrigger = findViewById(R.id.tvDebugTrigger)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnStart.setOnClickListener {
            if (prefs.getString("target_package", null) == null) {
                Toast.makeText(this, "Vào Thiết lập để chọn app trước đã", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (allSpecialPermissionsGranted()) {
                startSpeedService()
            } else {
                showPermissionExplanationDialog()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, SpeedMonitorService::class.java))
            Toast.makeText(this, "Đã dừng theo dõi", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTargetAppLabel()

        val filter = IntentFilter(SpeedMonitorService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }

        // Vừa quay lại app (ví dụ từ màn hình Settings cấp quyền) và đang giữa
        // luồng xin quyền -> tự động tiếp tục bước kế tiếp, không cần bấm Bắt đầu lại.
        if (permissionFlowActive) {
            requestNextMissingPermission()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // chưa từng đăng ký - bỏ qua an toàn
        }
    }

    private fun refreshTargetAppLabel() {
        val label = prefs.getString("target_label", null)
        tvTargetApp.text = if (label != null) {
            "Ứng dụng đang theo dõi: $label"
        } else {
            "Chưa chọn ứng dụng - vào Thiết lập để chọn"
        }
    }

    // ==================== LUỒNG XIN QUYỀN GỘP 1 LẦN ====================

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cần cấp một số quyền")
            .setMessage(
                "App cần các quyền sau để hoạt động đúng:\n\n" +
                    "• Hiển thị trên ứng dụng khác (nút nổi)\n" +
                    "• Usage access (tránh mở app lặp lại)\n" +
                    "• Vị trí (GPS) - luôn cho phép\n" +
                    "• Thông báo\n\n" +
                    "Bấm Tiếp tục, các màn hình Cài đặt tương ứng sẽ lần lượt hiện ra - " +
                    "bạn chỉ cần bật từng quyền rồi quay lại app, KHÔNG cần bấm Bắt đầu lại nhiều lần."
            )
            .setPositiveButton("Tiếp tục") { _, _ ->
                permissionFlowActive = true
                requestNextMissingPermission()
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun allSpecialPermissionsGranted(): Boolean {
        val overlayOk = Settings.canDrawOverlays(this)
        val usageOk = hasUsageAccess()
        val fineOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val bgOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        return overlayOk && usageOk && fineOk && bgOk && notifOk
    }

    /**
     * Kiểm tra lần lượt từng quyền còn thiếu, xin đúng 1 quyền tại 1 thời điểm rồi return.
     * Được gọi lại từ onResume() (khi quay lại từ Settings) hoặc từ callback của các
     * runtime permission launcher ở trên - tạo thành 1 chuỗi tự động đi hết các bước.
     */
    private fun requestNextMissingPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Bật 'Hiển thị trên ứng dụng khác' cho Speed Monitor rồi quay lại", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        if (!hasUsageAccess()) {
            Toast.makeText(this, "Bật 'Usage access' cho Speed Monitor rồi quay lại", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            requestForegroundLocation.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // Đủ hết quyền
        permissionFlowActive = false
        startSpeedService()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startSpeedService() {
        val intent = Intent(this, SpeedMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Đã bắt đầu theo dõi tốc độ", Toast.LENGTH_SHORT).show()
    }

    // ==================== DEBUG UI ====================

    private fun updateDebugUi(
        speedKmh: Float,
        condMoving: Boolean,
        condInZone: Boolean,
        condNotPaused: Boolean,
        condNotPowerSave: Boolean,
        appForeground: Boolean,
        allConditionsMet: Boolean,
        isPowerSave: Boolean
    ) {
        tvDebugSpeed.text = "Tốc độ GPS hiện tại: ${"%.1f".format(speedKmh)} km/h"

        val carState = when {
            isPowerSave -> "Đã đỗ (đang tiết kiệm pin)"
            speedKmh <= 1f -> "Đang đứng yên"
            else -> "Đang di chuyển"
        }
        tvDebugCarState.text = "Trạng thái xe: $carState"

        val movingThreshold = prefs.getFloat("moving_threshold", 15f)
        tvDebugCond1.text = "1. Đã từng chạy >${"%.0f".format(movingThreshold)}km/h: ${if (condMoving) "Đạt" else "Chưa đạt"}"
        tvDebugCond2.text = "2. Tốc độ trong vùng kích hoạt: ${if (condInZone) "Đạt" else "Chưa đạt"}"
        tvDebugCond3.text = "3. Không bị Pause: ${if (condNotPaused) "Đạt" else "Đang Pause"}"
        tvDebugCond4.text = "4. App đích không hiển thị: ${if (!appForeground) "Đạt" else "Đang hiển thị"}"

        tvDebugTrigger.text = "Trạng thái trigger tổng thể: ${if (allConditionsMet) "ĐẠT" else "CHƯA ĐẠT"}"
        tvDebugTrigger.setTextColor(if (allConditionsMet) 0xFF2E7D32.toInt() else 0xFFE65100.toInt())
    }
}
