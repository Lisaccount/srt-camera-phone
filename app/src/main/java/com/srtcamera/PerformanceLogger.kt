package com.srtcamera

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Continuous performance monitoring for the SRT streaming service.
 *
 * Collects at a fixed interval:
 * - App memory (native + Java heap, via Debug.MemoryInfo)
 * - CPU usage (approximate, via /proc/self/stat)
 * - Network throughput (tx bytes delta)
 * - Battery level, temperature, thermal throttling state
 * - Stream uptime
 *
 * Exposes latest snapshot via [statsFlow] for UI display.
 * Also appends each sample to a CSV file for offline analysis.
 */
class PerformanceLogger(private val context: Context) {

    companion object {
        private const val TAG = "PerfLogger"
        private const val INTERVAL_MS = 5000L  // 5 seconds per sample
        private const val MAX_BUFFER_LINES = 5000  // ~7 hours at 5s interval
    }

    data class Stats(
        val timestamp: Long = System.currentTimeMillis(),
        val uptimeMs: Long = 0,
        val appMemMb: Float = 0f,
        val nativeMemMb: Float = 0f,
        val cpuPercent: Float = 0f,
        val netTxKbps: Float = 0f,
        val batteryPct: Int = 0,
        val batteryTempC: Int = 0,
        val thermalState: String = "Unknown",
        val isCharging: Boolean = false,
        val streamActive: Boolean = false
    )

    private val _statsFlow = MutableStateFlow(Stats())
    val statsFlow: StateFlow<Stats> = _statsFlow

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    // Network tracking
    private var lastTxBytes: Long = 0
    private var lastSampleTime: Long = 0

    // CPU tracking
    private var lastCpuTime: Long = 0
    private var lastAppCpuTime: Long = 0

    // Stream start time
    private var streamStartTime: Long = 0

    // CSV log file
    private var logWriter: PrintWriter? = null
    private var logLineCount = 0

    private val am by lazy { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
    private val pm by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val batteryManager by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        } else null
    }

    /**
     * Start logging. Call when streaming begins (or at service start).
     * Creates a new CSV file per session.
     */
    fun start() {
        if (monitorJob?.isActive == true) return

        openLogFile()
        streamStartTime = SystemClock.elapsedRealtime()
        lastTxBytes = TrafficStats.getTotalTxBytes()
        lastSampleTime = SystemClock.elapsedRealtime()
        lastCpuTime = readTotalCpuTime()
        lastAppCpuTime = readAppCpuTime()

        monitorJob = scope.launch {
            Log.i(TAG, "Performance monitoring started (interval=${INTERVAL_MS}ms)")
            while (true) {
                delay(INTERVAL_MS)
                sample()
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "Performance monitoring stopped")
    }

    fun setStreamActive(active: Boolean) {
        val current = _statsFlow.value
        _statsFlow.value = current.copy(streamActive = active)
        if (active && streamStartTime == 0L) {
            streamStartTime = SystemClock.elapsedRealtime()
        }
    }

    private fun sample() {
        try {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastSampleTime
            if (elapsed <= 0) return

            // Memory
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            val appMemMb = memInfo.totalPss / 1024f
            val nativeMemMb = memInfo.nativePss / 1024f

            // CPU (approximate)
            val totalCpu = readTotalCpuTime()
            val appCpu = readAppCpuTime()
            var cpuPct = 0f
            if (totalCpu > lastCpuTime && appCpu > lastAppCpuTime) {
                val totalDiff = totalCpu - lastCpuTime
                val appDiff = appCpu - lastAppCpuTime
                cpuPct = (appDiff.toFloat() / totalDiff.toFloat()) * 100f * Runtime.getRuntime().availableProcessors()
            }
            lastCpuTime = totalCpu
            lastAppCpuTime = appCpu

            // Network throughput
            val txBytes = TrafficStats.getTotalTxBytes()
            val txDiff = txBytes - lastTxBytes
            val netKbps = if (txDiff > 0) (txDiff.toFloat() * 8f / 1000f) / (elapsed / 1000f) else 0f
            lastTxBytes = txBytes
            lastSampleTime = now

            // Battery
            val batteryPct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            val batteryTemp = getBatteryTemperature()
            val isCharging = batteryManager?.isCharging ?: false

            // Thermal state
            val thermalState = getThermalStateString()

            // Uptime
            val uptimeMs = if (streamStartTime > 0) now - streamStartTime else 0

            val stats = Stats(
                timestamp = System.currentTimeMillis(),
                uptimeMs = uptimeMs,
                appMemMb = appMemMb,
                nativeMemMb = nativeMemMb,
                cpuPercent = cpuPct,
                netTxKbps = netKbps,
                batteryPct = batteryPct,
                batteryTempC = batteryTemp,
                thermalState = thermalState,
                isCharging = isCharging,
                streamActive = streamStartTime > 0
            )

            _statsFlow.value = stats
            writeLogLine(stats)
        } catch (e: Exception) {
            Log.e(TAG, "Sample failed", e)
        }
    }

    // --- CPU helpers ---

    private fun readTotalCpuTime(): Long {
        return try {
            val lines = File("/proc/stat").readLines()
            if (lines.isNotEmpty()) {
                val parts = lines[0].split("\\s+".toRegex())
                // user + nice + system + idle + iowait + irq + softirq + steal
                var total = 0L
                for (i in 1 until parts.size) {
                    total += parts[i].toLong()
                }
                total
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun readAppCpuTime(): Long {
        return try {
            val lines = File("/proc/self/stat").readLines()
            if (lines.isNotEmpty()) {
                // /proc/[pid]/stat: fields 14 (utime) and 15 (stime), 1-indexed
                // After comm (field 2, which is in parens), split by space
                val statStr = lines[0]
                val lastParen = statStr.lastIndexOf(')')
                if (lastParen >= 0 && lastParen + 1 < statStr.length) {
                    val afterComm = statStr.substring(lastParen + 2).trim().split("\\s+".toRegex())
                    // afterComm[0] = state, [1] = ppid, ..., [11] = utime, [12] = stime
                    if (afterComm.size > 12) {
                        afterComm[11].toLong() + afterComm[12].toLong()
                    } else 0
                } else 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    // --- Battery helpers ---

    private fun getBatteryTemperature(): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun getThermalStateString(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "None"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else {
            "N/A"
        }
    }

    // --- CSV log file ---

    private fun openLogFile() {
        try {
            val logDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "perf_logs")
            if (!logDir.exists()) logDir.mkdirs()

            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(logDir, "perf_$dateStr.csv")

            logWriter = PrintWriter(FileWriter(logFile, true))
            logWriter?.println(
                "timestamp,uptime_ms,app_mem_mb,native_mem_mb,cpu_pct,net_tx_kbps,battery_pct,battery_temp_c,thermal_state,is_charging,stream_active"
            )
            logLineCount = 1
            Log.i(TAG, "Log file: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open log file", e)
        }
    }

    private fun writeLogLine(stats: Stats) {
        val writer = logWriter ?: return
        try {
            writer.println(
                "${stats.timestamp},${stats.uptimeMs}," +
                    "${"%.1f".format(stats.appMemMb)},${"%.1f".format(stats.nativeMemMb)}," +
                    "${"%.1f".format(stats.cpuPercent)},${"%.0f".format(stats.netTxKbps)}," +
                    "${stats.batteryPct},${stats.batteryTempC}," +
                    "${stats.thermalState},${stats.isCharging},${stats.streamActive}"
            )
            writer.flush()
            logLineCount++

            // Rotate if too large
            if (logLineCount > MAX_BUFFER_LINES) {
                writer.close()
                openLogFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write log failed", e)
        }
    }

    fun close() {
        stop()
        logWriter?.flush()
        logWriter?.close()
        logWriter = null
    }

    /**
     * Format uptime as HH:MM:SS for display.
     */
    fun formatUptime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
