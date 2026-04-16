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
        // 洛克王国世界可能的包名（已包含你正确的包名）
        val GAME_PACKAGES = setOf(
            "com.tencent.lkworld",
            "com.tencent.lk5",
            "com.tencent.洛克王国",
            "com.tencent.LoKeWang",
            "com.rlk.game",
            "com.tencent.nrc"  // <-- 你的游戏正确包名
        )
        val GAME_KEYWORDS = setOf("洛克", "lkworld", "洛克王国", "lk5")

        const val ACTION_GAME_STARTED = "com.lkwg.maptracker.GAME_STARTED"
        const val ACTION_GAME_STOPPED = "com.lkwg.maptracker.GAME_STOPPED"
    }

    private var isGameRunning = false
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var onGameStateChanged: ((Boolean) -> Unit)? = null

    /**
     * 启动游戏监控（每 1 秒检查一次，更快更准）
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
                    context.sendBroadcast(Intent(
                        if (running) ACTION_GAME_STARTED else ACTION_GAME_STOPPED
                    ))
                }
                delay(1000) // 改为1秒检测一次，更灵敏
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
     * 检测游戏是否在前台（安卓16 专用修复）
     */
    private fun isGameInForeground(): Boolean {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10000, now) // 查10秒内，更稳
            val event = UsageEvents.Event()
            var lastForegroundPackage: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForegroundPackage = event.packageName
                }
            }

            if (lastForegroundPackage != null) {
                val match = GAME_PACKAGES.contains(lastForegroundPackage)
                Log.d(TAG, "当前前台应用: $lastForegroundPackage, 匹配游戏: $match")
                return match
            }
        } catch (e: Exception) {
            Log.w(TAG, "检测失败: ${e.message}")
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
