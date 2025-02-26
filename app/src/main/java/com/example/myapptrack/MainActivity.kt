package com.example.myapptrack

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        // 让 Service 也能访问这个标志位
        var MyAppTrackIsInForeground = false
    }

    private lateinit var btnStartTracking: Button
    private lateinit var btnStopTracking: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartTracking = findViewById(R.id.btnStartTracking)
        btnStopTracking = findViewById(R.id.btnStopTracking)

        btnStartTracking.setOnClickListener {
            Log.d("MainActivity", "Start Tracking clicked")
            if (!hasUsageStatsPermission()) {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            } else {
                val startIntent = Intent(this, UsageTrackingService::class.java)
                startIntent.action = "ACTION_START_CSV"
                startService(startIntent)
            }
        }

        btnStopTracking.setOnClickListener {
            Log.d("MainActivity", "Stop Tracking clicked")
            val stopIntent = Intent(this, UsageTrackingService::class.java)
            stopIntent.action = "ACTION_STOP_CSV"
            startService(stopIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        MyAppTrackIsInForeground = true
        Log.d("MainActivity", "MyAppTrack -> onResume: now in foreground")
    }

    override fun onPause() {
        super.onPause()
        MyAppTrackIsInForeground = false
        Log.d("MainActivity", "MyAppTrack -> onPause: now in background")

        val stopIntent = Intent(this, UsageTrackingService::class.java)
        stopIntent.action = "ACTION_PAUSE_CSV"
        startService(stopIntent) // ✅ 发送 Intent 让 Service 关闭应用
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            "android:get_usage_stats",
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}