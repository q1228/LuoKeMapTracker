package com.lkwg.maptracker.service

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

/**
 * 游戏检测器
 * 监控洛克王国手游是否在前台运行
 */
class GameDetector(private val context: Context) {

    companion object {
        private const val TAG = "GameDetector"
        // 洛克王国世界可能的包名
        val GAME_PACKAGES = setOf(
            "com.tencent.lkworld",       // 洛克王国世界
            "com.tencent.lk5",           // 洛克王国5
            "com.tencent.洛克王国",        // 备用
            "com.tencent.LoKeWang",      // 英文名
            "com.rlk.game",              // 其他可能
            "com.tencent.nrc"          // ← 加上这一行！
        )
        val GAME_KEYWORDS = setOf("洛克", "lkworld", "lkworld", "洛克王国", "lk5")

        const val ACTION_GAME_STARTED = "com.lkwg.maptracker.GAME_STARTED"
        const val ACTION_GAME_STOPPED = "com.lkwg.maptracker.GAME_STOPPED"
    }

    private var isGameRunning = false
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var onGameStateChanged: ((Boolean) -> Unit)? = null

    /**
     * 启动游戏监控（每 3 秒检查一次）
     */
    fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = scope.launch {
            Log.d(TAG, "游戏监控已启动")
            while (isActive) {
                val running = isGameInForeground()
                if (running != isGameRunning) {
                    isGameRunning = running
                    Log.d(TAG, "游戏状态变化: ${if (running) "启动" else "关闭"}")
                    withContext(Dispatchers.Main) {
                        onGameStateChanged?.invoke(running)
                    }
                    // 发送广播
                    context.sendBroadcast(Intent(
                        if (running) ACTION_GAME_STARTED else ACTION_GAME_STOPPED
                    ))
                }
                delay(3000)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        isGameRunning = false
    }

    fun isGameCurrentlyRunning(): Boolean = isGameRunning

    /**
     * 检测游戏是否在前台
     */
    private fun isGameInForeground(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGameInForegroundUsageStats()
        } else {
            isGameInForegroundActivityManager()
        }
    }

    /**
     * API 29+ 使用 UsageStatsManager
     */
    private fun isGameInForegroundUsageStats(): Boolean {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5000, now)
            val event = UsageEvents.Event()
            var lastForegroundPackage: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForegroundPackage = event.packageName
                }
            }

            if (lastForegroundPackage != null) {
                return matchesGamePackage(lastForegroundPackage)
            }
        } catch (e: Exception) {
            Log.w(TAG, "UsageStats 查询失败: ${e.message}")
        }
        return false
    }

    /**
     * API < 29 使用 ActivityManager（可能不可靠）
     */
    @Suppress("DEPRECATION")
    private fun isGameInForegroundActivityManager(): Boolean {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(1)
            if (tasks.isNotEmpty()) {
                val topPackage = tasks[0].topActivity?.packageName ?: return false
                return matchesGamePackage(topPackage)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ActivityManager 查询失败: ${e.message}")
        }
        return false
    }

    private fun matchesGamePackage(packageName: String): Boolean {
        if (GAME_PACKAGES.contains(packageName)) return true
        val lower = packageName.lowercase()
        return GAME_KEYWORDS.any { lower.contains(it) }
    }

    fun destroy() {
        stopMonitoring()
        scope.cancel()
    }
}
