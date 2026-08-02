package com.srtcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.interfaces.releaseBlocking
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

enum class StatusColor { IDLE, STREAMING, ERROR, CONNECTING, WARNING }

data class StatusUpdate(val text: String, val color: StatusColor)

/**
 * Foreground Service that owns the SingleStreamer and all streaming logic.
 *
 * Phase 3 enhancements:
 * - Performance logging (CPU, memory, network, battery, thermal)
 * - Network state monitoring (auto-pause on disconnect, auto-resume on reconnect)
 * - Thermal throttling mitigation (reduce fps/bitrate on overheating)
 * - Watchdog timer (detect stalled streams and force reconnect)
 * - Exponential backoff reconnection (capped at 60s)
 * - Battery level monitoring with low-battery warning
 */
class StreamService : Service() {

    companion object {
        private const val TAG = "SrtCamera"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "srt_camera_stream"
        private const val MAX_RECONNECT_ATTEMPTS = 20
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val WATCHDOG_STALL_THRESHOLD_MS = 20_000L
        private const val THERMAL_ADJUST_THRESHOLD = 3 // THERMAL_STATUS_MODERATE
        private const val LOW_BATTERY_PCT = 15

        const val ACTION_STOP = "com.srtcamera.action.STOP"
    }

    // --- Coroutine scope ---
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // --- StreamPack streamer ---
    private var streamer: SingleStreamer? = null
    private val _streamerFlow = MutableStateFlow<SingleStreamer?>(null)
    val streamerFlow: StateFlow<SingleStreamer?> = _streamerFlow

    // --- Status for UI ---
    private val _statusFlow = MutableStateFlow(StatusUpdate("Initializing...", StatusColor.IDLE))
    val statusFlow: StateFlow<StatusUpdate> = _statusFlow

    // --- Streaming state for UI ---
    private val _isStreamingFlow = MutableStateFlow(false)
    val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow

    // --- Performance logger ---
    val perfLogger = PerformanceLogger(this)
    val statsFlow: StateFlow<PerformanceLogger.Stats> get() = perfLogger.statsFlow

    // --- Internal state ---
    private var streamJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var userRequestedStop = false
    private var isReconnecting = false
    private var currentConfig: StreamConfig? = null
    private var lastStreamActiveTime: Long = 0  // ElapsedRealtime of last isStreaming=true
    private var originalFps: Int = 15
    private var originalBitrate: Int = 2_000_000
    private var thermalAdjusted = false

    /** Exposed so the Activity can update its button state. */
    fun isReconnectingState(): Boolean = isReconnecting

    // --- WakeLock ---
    private var wakeLock: PowerManager.WakeLock? = null

    // --- Network monitoring ---
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hasNetwork = true

    // --- Battery monitoring ---
    private var batteryReceiver: BroadcastReceiver? = null

    // --- Binder ---
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }

    // =========================================================================
    // Service lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "StreamService onCreate")
        createNotificationChannel()
        acquireWakeLock()
        startForegroundCompat()
        registerNetworkMonitor()
        registerBatteryMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "StreamService onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.i(TAG, "StreamService onDestroy")
        unregisterNetworkMonitor()
        unregisterBatteryMonitor()
        stopWatchdog()
        perfLogger.close()
        serviceScope.cancel()
        releaseWakeLock()
        try {
            streamer?.releaseBlocking()
        } catch (e: Exception) {
            Log.e(TAG, "Release failed", e)
        }
        streamer = null
        _streamerFlow.value = null
        super.onDestroy()
    }

    // =========================================================================
    // StreamPack Setup
    // =========================================================================

    fun setupStreamer() {
        if (streamer != null) {
            Log.i(TAG, "Streamer already set up, skipping")
            return
        }

        val cameraId = getBackCameraId()

        serviceScope.launch {
            try {
                streamer = SingleStreamer(
                    context = this@StreamService,
                    audioSourceFactory = MicrophoneSourceFactory(),
                    videoSourceFactory = CameraSourceFactory(cameraId)
                )

                val s = streamer!!
                val audioStreamer = s as? IAudioSingleStreamer

                s.setVideoConfig(
                    VideoConfig(
                        startBitrate = StreamConfig.DEFAULT.bitrate,
                        resolution = StreamConfig.DEFAULT.resolution,
                        fps = StreamConfig.DEFAULT.fps
                    )
                )
                Log.i(TAG, "Video config set: 720p 15fps 2Mbps")

                audioStreamer?.setAudioConfig(
                    AudioConfig(
                        startBitrate = 128_000,
                        sampleRate = 44_100,
                        channelConfig = android.media.AudioFormat.CHANNEL_IN_STEREO
                    )
                )
                Log.i(TAG, "Audio config set: AAC 44.1kHz Stereo 128kbps")

                _streamerFlow.value = s

                // Monitor streaming state
                launch {
                    s.isStreamingFlow.collect { isStreaming ->
                        _isStreamingFlow.value = isStreaming
                        if (isStreaming) {
                            lastStreamActiveTime = SystemClock.elapsedRealtime()
                            if (!isReconnecting) {
                                _statusFlow.value = StatusUpdate("Streaming", StatusColor.STREAMING)
                            }
                            updateNotification(getString(R.string.notification_streaming))
                            startWatchdog()
                        } else {
                            stopWatchdog()
                            if (!isReconnecting && !userRequestedStop) {
                                _statusFlow.value = StatusUpdate("Stopped", StatusColor.IDLE)
                            }
                            updateNotification(getString(R.string.notification_ready))
                        }
                    }
                }

                // Monitor connection open state
                launch {
                    s.isOpenFlow.collect { isOpen ->
                        Log.i(TAG, "isOpen: $isOpen")
                        if (isOpen) {
                            lastStreamActiveTime = SystemClock.elapsedRealtime()
                        }
                    }
                }

                // Monitor errors
                launch {
                    s.throwableFlow.filterNotNull().collect { error ->
                        Log.e(TAG, "Streamer error", error)
                        val isConnectionLost = isConnectionError(error)

                        if (isConnectionLost && !userRequestedStop) {
                            _statusFlow.value = StatusUpdate(
                                "Connection lost. Reconnecting...", StatusColor.ERROR
                            )
                            startReconnect()
                        } else if (!isConnectionLost) {
                            _statusFlow.value = StatusUpdate(
                                "Error: ${error.message ?: error::class.java.simpleName}",
                                StatusColor.ERROR
                            )
                        }
                    }
                }

                _statusFlow.value = StatusUpdate("Ready - fill in server IP and tap Start", StatusColor.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                _statusFlow.value = StatusUpdate("Setup failed: ${e.message}", StatusColor.ERROR)
            }
        }
    }

    // =========================================================================
    // Streaming Control
    // =========================================================================

    fun startStream(config: StreamConfig) {
        currentConfig = config
        userRequestedStop = false
        originalFps = config.fps
        originalBitrate = config.bitrate
        thermalAdjusted = false

        perfLogger.start()
        perfLogger.setStreamActive(true)

        streamJob = serviceScope.launch {
            try {
                _statusFlow.value = StatusUpdate(
                    "Connecting to ${config.server}:${config.port}...", StatusColor.CONNECTING
                )

                streamer?.setVideoConfig(
                    VideoConfig(
                        startBitrate = config.bitrate,
                        resolution = config.resolution,
                        fps = config.fps
                    )
                )

                val descriptor = SrtMediaDescriptor(
                    host = config.server,
                    port = config.port,
                    streamId = config.streamId,
                    latency = config.latency
                )

                streamer?.startStream(descriptor)
                Log.i(TAG, "Stream started: ${config.server}:${config.port} streamId=${config.streamId}")
            } catch (e: Exception) {
                Log.e(TAG, "Start stream failed", e)
                _statusFlow.value = StatusUpdate(
                    "Failed: ${e.message ?: e::class.java.simpleName}",
                    StatusColor.ERROR
                )
                perfLogger.setStreamActive(false)
            }
        }
    }

    fun stopStream() {
        userRequestedStop = true
        isReconnecting = false
        reconnectJob?.cancel()
        streamJob?.cancel()
        stopWatchdog()

        perfLogger.setStreamActive(false)

        serviceScope.launch {
            try {
                streamer?.stopStream()
                streamer?.close()
                Log.i(TAG, "Stream stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Stop stream error", e)
            }
            _statusFlow.value = StatusUpdate("Stopped", StatusColor.IDLE)
            updateNotification(getString(R.string.notification_ready))
            // Keep perf logger running for a bit to capture post-stop state, then stop
            delay(2000)
            perfLogger.stop()
        }
    }

    private fun stopEverything() {
        userRequestedStop = true
        isReconnecting = false
        reconnectJob?.cancel()
        streamJob?.cancel()
        stopWatchdog()

        perfLogger.setStreamActive(false)

        serviceScope.launch {
            try {
                streamer?.stopStream()
                streamer?.close()
                streamer?.releaseBlocking()
            } catch (e: Exception) {
                Log.e(TAG, "Stop/release error", e)
            }
            streamer = null
            _streamerFlow.value = null
            _isStreamingFlow.value = false
            _statusFlow.value = StatusUpdate("Stopped", StatusColor.IDLE)
            perfLogger.stop()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // =========================================================================
    // Auto-Reconnect with exponential backoff
    // =========================================================================

    private fun startReconnect() {
        if (isReconnecting || userRequestedStop) return
        isReconnecting = true

        reconnectJob = serviceScope.launch {
            var attempt = 0
            while (isReconnecting && !userRequestedStop && attempt < MAX_RECONNECT_ATTEMPTS) {
                attempt++

                // Exponential backoff: 3s, 6s, 12s, 24s, 48s, 60s, 60s, ...
                val delayMs = (3000L * (1L shl (attempt - 1))).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                Log.i(TAG, "Reconnect attempt $attempt in ${delayMs}ms")

                _statusFlow.value = StatusUpdate(
                    "Reconnecting (attempt $attempt) in ${delayMs / 1000}s...",
                    StatusColor.CONNECTING
                )

                delay(delayMs)

                if (userRequestedStop) break

                // Check network availability
                if (!hasNetwork) {
                    Log.i(TAG, "No network, waiting...")
                    _statusFlow.value = StatusUpdate(
                        "No network. Waiting for connection...",
                        StatusColor.WARNING
                    )
                    continue
                }

                try {
                    val config = currentConfig ?: break

                    val descriptor = SrtMediaDescriptor(
                        host = config.server,
                        port = config.port,
                        streamId = config.streamId,
                        latency = config.latency
                    )

                    streamer?.startStream(descriptor)
                    Log.i(TAG, "Reconnected on attempt $attempt")
                    isReconnecting = false
                    _statusFlow.value = StatusUpdate("Reconnected!", StatusColor.STREAMING)
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect attempt $attempt failed", e)
                    if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                        isReconnecting = false
                        _statusFlow.value = StatusUpdate(
                            "Reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts. Tap Start to retry.",
                            StatusColor.ERROR
                        )
                    }
                }
            }
            isReconnecting = false
        }
    }

    // =========================================================================
    // Watchdog: detect stalled streams
    // =========================================================================

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (userRequestedStop || isReconnecting) continue

                val elapsedSinceActive = SystemClock.elapsedRealtime() - lastStreamActiveTime
                if (elapsedSinceActive > WATCHDOG_STALL_THRESHOLD_MS) {
                    Log.w(TAG, "Watchdog: stream stalled for ${elapsedSinceActive}ms, forcing reconnect")
                    _statusFlow.value = StatusUpdate(
                        "Stream stalled. Auto-reconnecting...",
                        StatusColor.WARNING
                    )
                    try {
                        streamer?.stopStream()
                        streamer?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Watchdog stop failed", e)
                    }
                    startReconnect()
                    break
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // =========================================================================
    // Network Monitoring
    // =========================================================================

    private fun registerNetworkMonitor() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cm = connectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                hasNetwork = true
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost")
                hasNetwork = false
                if (!userRequestedStop && _isStreamingFlow.value) {
                    _statusFlow.value = StatusUpdate(
                        "Network lost. Will reconnect when available.",
                        StatusColor.WARNING
                    )
                    serviceScope.launch {
                        try {
                            streamer?.stopStream()
                            streamer?.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Stop on network lost failed", e)
                        }
                    }
                    startReconnect()
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    hasNetwork = true
                }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun unregisterNetworkMonitor() {
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        networkCallback = null
    }

    // =========================================================================
    // Battery Monitoring
    // =========================================================================

    private fun registerBatteryMonitor() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
                val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL

                if (pct <= LOW_BATTERY_PCT && !isCharging && _isStreamingFlow.value) {
                    _statusFlow.value = StatusUpdate(
                        "Low battery ($pct%)! Plug in charger for 24h streaming.",
                        StatusColor.WARNING
                    )
                    Log.w(TAG, "Low battery: $pct%")
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun unregisterBatteryMonitor() {
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        batteryReceiver = null
    }

    // =========================================================================
    // Thermal Throttling Mitigation
    // =========================================================================

    fun checkThermalState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val status = pm.currentThermalStatus

        if (status >= PowerManager.THERMAL_STATUS_MODERATE && !thermalAdjusted && currentConfig != null) {
            // Reduce fps and bitrate to lower heat generation
            val reducedFps = (originalFps * 0.6f).toInt().coerceAtLeast(10)
            val reducedBitrate = (originalBitrate * 0.7f).toInt().coerceAtLeast(500_000)
            Log.w(TAG, "Thermal throttling: reducing fps=$originalFps->$reducedFps, bitrate=$originalBitrate->$reducedBitrate")

            serviceScope.launch {
                try {
                    streamer?.setVideoConfig(
                        VideoConfig(
                            startBitrate = reducedBitrate,
                            resolution = currentConfig!!.resolution,
                            fps = reducedFps
                        )
                    )
                    thermalAdjusted = true
                    _statusFlow.value = StatusUpdate(
                        "Thermal mitigation: fps=${reducedFps}, bitrate=${reducedBitrate / 1000}kbps",
                        StatusColor.WARNING
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Thermal adjustment failed", e)
                }
            }
        } else if (status <= PowerManager.THERMAL_STATUS_LIGHT && thermalAdjusted && currentConfig != null) {
            // Restore original settings
            Log.i(TAG, "Thermal recovered: restoring fps=$originalFps, bitrate=$originalBitrate")
            serviceScope.launch {
                try {
                    streamer?.setVideoConfig(
                        VideoConfig(
                            startBitrate = originalBitrate,
                            resolution = currentConfig!!.resolution,
                            fps = originalFps
                        )
                    )
                    thermalAdjusted = false
                    if (_isStreamingFlow.value) {
                        _statusFlow.value = StatusUpdate("Streaming (restored)", StatusColor.STREAMING)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Thermal restore failed", e)
                }
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun getBackCameraId(): String {
        val manager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        return manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun isConnectionError(t: Throwable): Boolean {
        val className = t::class.java.simpleName.lowercase()
        val message = t.message?.lowercase() ?: ""
        return className.contains("closed") ||
                className.contains("connection") ||
                className.contains("socket") ||
                className.contains("timeout") ||
                className.contains("srt") ||
                message.contains("closed") ||
                message.contains("broken pipe") ||
                message.contains("connection reset") ||
                message.contains("timed out") ||
                message.contains("timeout")
    }

    // =========================================================================
    // Notification
    // =========================================================================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, StreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingContentIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.notification_stop), stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.notification_ready))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // =========================================================================
    // WakeLock
    // =========================================================================

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SrtCamera::StreamWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24 hours max
        }
        Log.i(TAG, "WakeLock acquired (24h)")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
