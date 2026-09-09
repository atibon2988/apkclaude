package com.example.speedmonitor

import android.Manifest
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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var selectedPackage: String? = null

    private lateinit var edtThreshold: EditText
    private lateinit var edtInterval: EditText
    private lateinit var recyclerApps: RecyclerView

    private lateinit var tvDebugSpeed: TextView
    private lateinit var tvDebugCarState: TextView
    private lateinit var tvDebugCond1: TextView
    private lateinit var tvDebugCond2: TextView
    private lateinit var tvDebugCond3: TextView
    private lateinit var tvDebugCond4: TextView
    private lateinit var tvDebugTrigger: TextView

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

    // Xin quyền vị trí foreground trước
    private val requestForegroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestBackgroundLocationIfNeeded()
        } else {
            Toast.makeText(this, "Cần quyền vị trí để hoạt động", Toast.LENGTH_LONG).show()
        }
    }

    // Xin quyền vị trí nền (phải xin riêng, sau bước trên, theo yêu cầu của Android 10+)
    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestNotificationIfNeeded()
        } else {
            Toast.makeText(
                this,
                "Không có quyền vị trí nền, app sẽ không hoạt động khi tắt màn hình",
                Toast.LENGTH_LONG
            ).show()
            requestNotificationIfNeeded()
        }
    }

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startSpeedService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        edtThreshold = findViewById(R.id.edtThreshold)
        edtInterval = findViewById(R.id.edtInterval)
        recyclerApps = findViewById(R.id.recyclerApps)

        tvDebugSpeed = findViewById(R.id.tvDebugSpeed)
        tvDebugCarState = findViewById(R.id.tvDebugCarState)
        tvDebugCond1 = findViewById(R.id.tvDebugCond1)
        tvDebugCond2 = findViewById(R.id.tvDebugCond2)
        tvDebugCond3 = findViewById(R.id.tvDebugCond3)
        tvDebugCond4 = findViewById(R.id.tvDebugCond4)
        tvDebugTrigger = findViewById(R.id.tvDebugTrigger)

        edtThreshold.setText(prefs.getFloat("threshold", 5f).toString())
        edtInterval.setText((prefs.getLong("interval_ms", 2000L) / 1000).toString())
        selectedPackage = prefs.getString("target_package", null)

        recyclerApps.layoutManager = LinearLayoutManager(this)
        loadApps()

        findViewById<android.widget.Button>(R.id.btnStart).setOnClickListener {
            if (selectedPackage == null) {
                Toast.makeText(this, "Chọn 1 app trong danh sách trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val threshold = edtThreshold.text.toString().toFloatOrNull() ?: 5f
            val intervalSec = (edtInterval.text.toString().toIntOrNull() ?: 2).coerceIn(1, 10)

            prefs.edit()
                .putFloat("threshold", threshold)
                .putLong("interval_ms", intervalSec * 1000L)
                .putString("target_package", selectedPackage)
                .apply()

            checkPermissionsAndStart()
        }

        findViewById<android.widget.Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, SpeedMonitorService::class.java))
            Toast.makeText(this, "Đã dừng theo dõi", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SpeedMonitorService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // receiver chưa từng được đăng ký - bỏ qua an toàn
        }
    }

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

        tvDebugCond1.text = "1. Đã từng chạy >15km/h: ${if (condMoving) "Đạt" else "Chưa đạt"}"
        tvDebugCond2.text = "2. Tốc độ trong vùng kích hoạt: ${if (condInZone) "Đạt" else "Chưa đạt"}"
        tvDebugCond3.text = "3. Không bị Pause: ${if (condNotPaused) "Đạt" else "Đang Pause"}"
        tvDebugCond4.text = "4. App đích không hiển thị: ${if (!appForeground) "Đạt" else "Đang hiển thị"}"

        tvDebugTrigger.text = "Trạng thái trigger tổng thể: ${if (allConditionsMet) "ĐẠT" else "CHƯA ĐẠT"}"
        tvDebugTrigger.setTextColor(if (allConditionsMet) 0xFF2E7D32.toInt() else 0xFFE65100.toInt())
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { getAllLaunchableApps() }
            recyclerApps.adapter = AppAdapter(apps, selectedPackage) { app ->
                selectedPackage = app.packageName
                prefs.edit().putString("target_package", app.packageName).apply()
                Toast.makeText(this@MainActivity, "Đã chọn: ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getAllLaunchableApps(): List<AppInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                AppInfo(
                    packageName = it.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Cần bật quyền 'Hiển thị trên ứng dụng khác' để dùng nút Pause nổi. " +
                    "Bật lên trong Settings sắp mở, rồi quay lại bấm Bắt đầu lần nữa.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        if (!hasUsageAccess()) {
            Toast.makeText(
                this,
                "Cần bật quyền 'Usage access' để tránh mở app lặp lại. " +
                    "Ở màn hình Settings sắp mở, tìm 'Speed Monitor' và bật lên, rồi quay lại bấm Bắt đầu lần nữa.",
                Toast.LENGTH_LONG
            ).show()
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
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }
        requestNotificationIfNeeded()
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
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
}
