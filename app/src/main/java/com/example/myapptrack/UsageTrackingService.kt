package com.example.myapptrack

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale

class UsageTrackingService : Service() {

    // 单一 Handler，保证所有集合操作都在此线程执行，避免 ConcurrentModificationException
    private val serviceHandler = Handler(Looper.getMainLooper())

    // UsageStats 相关
    private var isTracking = false
    private val BACKGROUND_TIMEOUT = 15000L

    // 数据结构
    private val appUsageTimes = mutableMapOf<String, Long>()
    private val appOpenTime = mutableMapOf<String, Long>()
    private val activeApps = mutableSetOf<String>()

    // CSV 相关
    private var csvFile: File? = null
    private var csvWriter: FileWriter? = null

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var lastForegroundTime: Long = System.currentTimeMillis()
    private var startTrackingMillis: Long = 0

    // 每 5秒 执行一次
    private val trackUsageRunnable = object : Runnable {
        override fun run() {
            if (!isTracking) return
            handleTrackUsage()
            serviceHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("UsageTrackingService", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(1, createNotification())  // Android 8.0+ 需要前台服务通知
        }

        serviceHandler.post {
            when (intent?.action) {
                "ACTION_START_CSV" -> startTrackingInternal()  // 处理 "开始统计"
                "ACTION_STOP_CSV" -> stopTrackingInternal()  // 处理 "停止统计"
                "ACTION_PAUSE_CSV" -> handlePause()
                else -> Log.d("UsageTrackingService", "Unknown action: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    private fun handlePause() {
        val packageName = "com.example.myapptrack"
        if (activeApps.contains(packageName)) {
            markAppClosed(packageName)
            activeApps.remove(packageName)
        }
    }

    private fun createNotification(): Notification {
        val channelId = "UsageTrackingServiceChannel"

        // 创建 NotificationChannel（仅 Android 8.0+ 需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Usage Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Usage Tracking Running")
            .setContentText("Recording app usage in the background.")
            .setSmallIcon(R.drawable.ic_notification)  // 确保 ic_notification 存在

        // 仅在 Android 7.1 (API 25) 及以下添加 setPriority()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
        }

        return builder.build()
    }





    override fun onDestroy() {
        super.onDestroy()
        Log.d("UsageTrackingService", "onDestroy")
        serviceHandler.post { stopTrackingInternal() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("UsageTrackingService", "onTaskRemoved => forcibly close all apps")
        serviceHandler.post {
            val iterator = activeApps.iterator()
            while (iterator.hasNext()) {
                val pkg = iterator.next()
                markAppClosed(pkg)
                iterator.remove()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTrackingInternal() {
        if (isTracking) return
        isTracking = true
        startTrackingMillis = System.currentTimeMillis()

        csvFile = File(getExternalFilesDir(null), "usage_log.csv").apply {
            if (!exists()) createNewFile()
        }
        Log.d("UsageTrackingService", "CSV file path: ${csvFile?.absolutePath}")

        csvWriter = FileWriter(csvFile, true)

        // ✅ 修正 CSV 头部
        csvWriter?.appendLine("Package,Start_Time,End_Time,Duration")

        serviceHandler.post(trackUsageRunnable)
        Log.d("UsageTrackingService", "Start tracking usage, CSV: ${csvFile?.absolutePath}")
    }

    private fun stopTrackingInternal() {
        if (!isTracking) return
        isTracking = false
        serviceHandler.removeCallbacks(trackUsageRunnable)

        // 强制关掉所有 active apps
        val iterator = activeApps.iterator()
        while (iterator.hasNext()) {
            val pkg = iterator.next()
            markAppClosed(pkg)
            iterator.remove()
        }

        // ✅ 计算整个追踪时长，并按照格式写入
        val endMillis = System.currentTimeMillis()
        val startTimeStr = dateFormat.format(startTrackingMillis)
        val endTimeStr = dateFormat.format(endMillis)
        val totalSec = (endMillis - startTrackingMillis) / 1000

        Log.d("UsageTrackingService", "Stop tracking usage, total duration: ${totalSec}s")

        // ✅ 统一格式：Package, Start Time, End Time, Duration
        csvWriter?.appendLine("TrackingSummary,$startTimeStr,$endTimeStr,${totalSec}s")

        csvWriter?.flush()
        csvWriter?.close()
        csvWriter = null
    }


    /**
     * 核心，每 5秒 调用一次
     */
    private fun handleTrackUsage() {
        val now = System.currentTimeMillis()
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = usageStatsManager.queryEvents(now - 5000, now)

        val detectedApps = mutableSetOf<String>()
        var foundForegroundEvent = false

        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)

            val pkgName = event.packageName
            val eventTime = event.timeStamp
            val formattedTime = dateFormat.format(eventTime)

            if (isSystemPackage(pkgName)) continue

            detectedApps.add(pkgName)
            foundForegroundEvent = true
            lastForegroundTime = now

            // ✅ 替换 MOVE_TO_FOREGROUND -> ACTIVITY_RESUMED
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                // If the app is already marked as active, close the previous session first
                if (activeApps.contains(pkgName)) {
                    markAppClosed(pkgName)
                    activeApps.remove(pkgName)
                }
                Log.d("AppUsageTracking", "$formattedTime -> $pkgName opened")
                appOpenTime[pkgName] = eventTime
                activeApps.add(pkgName)
            }


            // ✅ 替换 MOVE_TO_BACKGROUND -> ACTIVITY_PAUSED
            if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                markAppClosed(pkgName)
            }
        }

        val timeSinceLastFG = now - lastForegroundTime
        val fallbackFG = getForegroundApp()

        // 如果 UsageStatsManager 没有检测到事件，使用 getForegroundApp() 作为回退方案
        if (!foundForegroundEvent) {
            if (MainActivity.MyAppTrackIsInForeground) {
                Log.d("AppUsageTracking", "fallback => MyAppTrack is still in foreground")
                return
            }
            if (!isSystemPackage(fallbackFG) && fallbackFG != "unknown") {
                Log.d("AppUsageTracking", "fallback => getForegroundApp() = $fallbackFG, so skip close")
                return
            }
        }

        // 处理应用退出逻辑
        if (!foundForegroundEvent) {
//            if (timeSinceLastFG > BACKGROUND_TIMEOUT) {
//                Log.d("AppUsageTracking", "No foreground app for 15s => close all active apps")
//                val iterator = activeApps.iterator()
//                while (iterator.hasNext()) {
//                    val pkg = iterator.next()
//                    markAppClosed(pkg)  // ✅ 记录 closed 并写入 CSV
//                    iterator.remove()
//                }
//            } else {
//                Log.d("AppUsageTracking", "No foreground app detected, waiting more time...")
//            }

            // ✅ 如果 fallbackFG 存在但 activeApps 为空，则关闭 fallbackFG
            if (fallbackFG != "unknown" && activeApps.isEmpty() && appOpenTime.containsKey(fallbackFG)) {
                Log.d("AppUsageTracking", "Fallback detected: Marking $fallbackFG as closed")
                markAppClosed(fallbackFG)
                appOpenTime.remove(fallbackFG) // 避免重复写入 CSV
            }
            return
        }

        // 识别哪些应用退到了后台
        val closedApps = activeApps - detectedApps
        for (pkg in closedApps) {
            markAppClosed(pkg)
            activeApps.remove(pkg)
        }
    }

    private fun markAppClosed(pkg: String) {
        val now = System.currentTimeMillis()
        val startT = appOpenTime[pkg] ?: return

        val durationMs = now - startT
        if (durationMs > 1000) {
            val startTimeStr = dateFormat.format(startT)
            val endTimeStr = dateFormat.format(now)
            Log.d("AppUsageTracking", "$pkg closed, usage=${durationMs / 1000}s")

            // ✅ 修正 CSV 格式：Package, Start_Time, End_Time, Duration
            csvWriter?.appendLine("$pkg,$startTimeStr,$endTimeStr,${durationMs / 1000}s")
            csvWriter?.flush()
        }
        appOpenTime.remove(pkg)
    }

    // 获取最近前台应用
    private fun getForegroundApp(): String {
        val now = System.currentTimeMillis()
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = usageStatsManager.queryEvents(now - 5000, now)

        var lastPkg: String? = null
        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = event.packageName
            }
        }
        return lastPkg ?: "unknown"
    }

    private fun isSystemPackage(pkg: String): Boolean {
        val systemApps = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher"
        )
        return pkg in systemApps
    }
}