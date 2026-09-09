package com.example.speedmonitor

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var selectedPackage: String? = null
    private var selectedLabel: String? = null

    private lateinit var edtThreshold: EditText
    private lateinit var edtMovingThreshold: EditText
    private lateinit var edtInterval: EditText
    private lateinit var recyclerApps: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Thiết lập"
        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        edtThreshold = findViewById(R.id.edtThreshold)
        edtMovingThreshold = findViewById(R.id.edtMovingThreshold)
        edtInterval = findViewById(R.id.edtInterval)
        recyclerApps = findViewById(R.id.recyclerApps)

        edtThreshold.setText(prefs.getFloat("threshold", 5f).toString())
        edtMovingThreshold.setText(prefs.getFloat("moving_threshold", 15f).toString())
        edtInterval.setText((prefs.getLong("interval_ms", 2000L) / 1000).toString())

        selectedPackage = prefs.getString("target_package", null)
        selectedLabel = prefs.getString("target_label", null)

        recyclerApps.layoutManager = LinearLayoutManager(this)
        loadApps()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettings()
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { getAllLaunchableApps() }
            recyclerApps.adapter = AppAdapter(apps, selectedPackage) { app ->
                selectedPackage = app.packageName
                selectedLabel = app.label
                Toast.makeText(this@SettingsActivity, "Đã chọn: ${app.label}", Toast.LENGTH_SHORT).show()
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

    private fun saveSettings() {
        val threshold = edtThreshold.text.toString().toFloatOrNull() ?: 5f
        val movingThreshold = edtMovingThreshold.text.toString().toFloatOrNull() ?: 15f
        val intervalSec = (edtInterval.text.toString().toIntOrNull() ?: 2).coerceIn(1, 10)

        if (movingThreshold <= threshold) {
            Toast.makeText(
                this,
                "Lưu ý: ngưỡng kích hoạt lại nên LỚN HƠN ngưỡng mở app để logic hoạt động đúng như thiết kế.",
                Toast.LENGTH_LONG
            ).show()
        }

        val editor = prefs.edit()
            .putFloat("threshold", threshold)
            .putFloat("moving_threshold", movingThreshold)
            .putLong("interval_ms", intervalSec * 1000L)

        if (selectedPackage != null) {
            editor.putString("target_package", selectedPackage)
            editor.putString("target_label", selectedLabel)
        }
        editor.apply()

        Toast.makeText(this, "Đã lưu thiết lập", Toast.LENGTH_SHORT).show()
        finish()
    }
}
