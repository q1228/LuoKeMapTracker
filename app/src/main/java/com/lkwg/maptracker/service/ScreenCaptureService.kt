package com.lkwg.maptracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.lkwg.maptracker.cv.MapMatcher
import com.lkwg.maptracker.data.MapRepository
import com.lkwg.maptracker.util.ConfigManager
import kotlinx.coroutines.*

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCapture"
        const val CHANNEL_ID = "map_tracker_capture"
        const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val MATCH_INTERVAL_MS = 200L
        const val ACTION_MATCH_RESULT = "com.lkwg.maptracker.MATCH_RESULT"
        const val EXTRA_POS_X = "pos_x"
        const val EXTRA_POS_Y = "pos_y"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_ROTATION = "rotation"
        const val ACTION_STATUS = "com.lkwg.maptracker.STATUS"
        const val EXTRA_STATUS_MSG = "status_msg"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private val matcher = MapMatcher()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null
    private var isCapturing = false
    private var mapLoaded = false
    private var lastKnownX = 0.0
    private var lastKnownY = 0.0
    private var consecutiveFails = 0

    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService(): ScreenCaptureService = this@ScreenCaptureService }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification("初始化中..."))
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData != null) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(projectionCallback, null)
            setupCapture()
            loadMap()
            startLoop()
        }
        return START_NOT_STICKY
    }

    private fun getScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupCapture() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay("MapTracker", screenWidth, screenHeight, screenDensity, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, mainHandler)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun loadMap() {
        serviceScope.launch {
            val bitmap = MapRepository.loadMap(this@ScreenCaptureService)
            if (bitmap != null) { matcher.loadFullMap(bitmap); mapLoaded = true }
        }
    }

    private fun startLoop() {
        isCapturing = true
        captureJob = serviceScope.launch {
            while (isActive && isCapturing) {
                if (!mapLoaded) { delay(1000); continue }
                val frame = captureFrame()
                if (frame != null) processFrame(frame)
                delay(MATCH_INTERVAL_MS)
            }
        }
    }

    private fun processFrame(frame: Bitmap) {
        try {
            val result = matcher.match(frame)
            frame.recycle()
            if (result != null && result.confidence >= ConfigManager.getConfidenceThreshold(this)) {
                lastKnownX = result.x; lastKnownY = result.y; consecutiveFails = 0; broadcastResult(result)
            }
        } catch (e: Exception) { frame.recycle() }
    }

    private fun captureFrame(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val fullBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(buffer)
            val rect = ConfigManager.getMinimapRect(this)
            val mini = Bitmap.createBitmap(fullBitmap, rect[0], rect[1], rect[2], rect[3])
            fullBitmap.recycle()
            return mini
        } catch (e: Exception) { return null } finally { image.close() }
    }

    private fun broadcastResult(result: MapMatcher.MatchResult) {
        sendBroadcast(Intent(ACTION_MATCH_RESULT).putExtra(EXTRA_POS_X, result.x).putExtra(EXTRA_POS_Y, result.y).putExtra(EXTRA_CONFIDENCE, result.confidence))
    }

    private val projectionCallback = object : MediaProjection.Callback() { override fun onStop() { stopCapture() } }
    fun stopCapture() { isCapturing = false; captureJob?.cancel(); stopSelf() }
    private fun createNotificationChannel() {}
    private fun buildNotification(text: String): Notification = Notification.Builder(this).setContentTitle("地图追踪").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_mapmode).setOngoing(true).build()
    override fun onDestroy() { super.onDestroy(); stopCapture(); matcher.release(); serviceScope.cancel() }
}
