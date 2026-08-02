package com.srtcamera

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Phase 3: MainActivity with settings persistence and performance monitoring.
 *
 * Improvements:
 * - Saves/loads all settings via SettingsManager (SharedPreferences)
 * - Shows live performance stats bar (CPU, memory, network, battery, thermal, uptime)
 * - Stats bar appears during streaming, hidden when idle
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SrtCamera"
        private const val REQUEST_PERMISSIONS_CODE = 100
    }

    // Bound service
    private var streamService: StreamService? = null
    private var isBound = false

    // UI elements
    private lateinit var preview: PreviewView
    private lateinit var serverInput: EditText
    private lateinit var portInput: EditText
    private lateinit var streamIdInput: EditText
    private lateinit var resolutionSpinner: Spinner
    private lateinit var bitrateInput: EditText
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var settingsScroll: ScrollView

    // Performance stats bar
    private lateinit var perfBar: LinearLayout
    private lateinit var statUptime: TextView
    private lateinit var statCpu: TextView
    private lateinit var statMem: TextView
    private lateinit var statNet: TextView
    private lateinit var statBatt: TextView
    private lateinit var statThermal: TextView

    // Saved settings
    private val resolutions = arrayOf("1280x720", "1920x1080", "640x480")

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StreamService.LocalBinder ?: return
            streamService = binder.getService()
            isBound = true
            Log.i(TAG, "Service connected")

            streamService?.setupStreamer()
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
            isBound = false
            Log.i(TAG, "Service disconnected")
        }
    }

    // =========================================================================
    // Permission handling
    // =========================================================================

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startAndBindService()
        } else {
            requestPermissions(notGranted.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val cameraAndMicGranted = permissions.indices
                .filter { permissions[it] == Manifest.permission.CAMERA || permissions[it] == Manifest.permission.RECORD_AUDIO }
                .all { grantResults[it] == PackageManager.PERMISSION_GRANTED }

            if (cameraAndMicGranted) {
                startAndBindService()
            } else {
                updateStatus("Camera and microphone permissions are required", StatusColor.ERROR)
            }
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSavedSettings()
        checkAndRequestPermissions()
    }

    override fun onDestroy() {
        saveSettings()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    // =========================================================================
    // Service management
    // =========================================================================

    private fun startAndBindService() {
        val intent = Intent(this, StreamService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "Service start + bind requested")
    }

    // =========================================================================
    // Observe service flows
    // =========================================================================

    private fun observeService() {
        val s = streamService ?: return

        // Connect PreviewView to the streamer once it's ready
        lifecycleScope.launch {
            val streamer = s.streamerFlow.filterNotNull().first()
            try {
                preview.setVideoSourceProvider(streamer)
                Log.i(TAG, "PreviewView connected to streamer")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect preview", e)
            }
        }

        // Observe status updates
        lifecycleScope.launch {
            s.statusFlow.collect { status ->
                runOnUiThread {
                    statusText.text = status.text
                    statusText.setTextColor(
                        when (status.color) {
                            StatusColor.IDLE -> ContextCompat.getColor(this@MainActivity, R.color.status_idle)
                            StatusColor.STREAMING -> ContextCompat.getColor(this@MainActivity, R.color.status_streaming)
                            StatusColor.ERROR -> ContextCompat.getColor(this@MainActivity, R.color.status_error)
                            StatusColor.CONNECTING -> ContextCompat.getColor(this@MainActivity, R.color.status_connecting)
                            StatusColor.WARNING -> ContextCompat.getColor(this@MainActivity, R.color.status_connecting)
                        }
                    )
                }
            }
        }

        // Observe streaming state for button updates + perf bar visibility
        lifecycleScope.launch {
            s.isStreamingFlow.collect { isStreaming ->
                runOnUiThread {
                    if (isStreaming || s.isReconnectingState()) {
                        startButton.text = getString(R.string.stop_streaming)
                        startButton.setBackgroundColor(
                            ContextCompat.getColor(this@MainActivity, R.color.button_stop)
                        )
                        perfBar.visibility = View.VISIBLE
                    } else {
                        startButton.text = getString(R.string.start_streaming)
                        startButton.setBackgroundColor(
                            ContextCompat.getColor(this@MainActivity, R.color.button_start)
                        )
                        perfBar.visibility = View.GONE
                    }
                }
            }
        }

        // Observe performance stats
        lifecycleScope.launch {
            s.statsFlow.collect { stats ->
                runOnUiThread {
                    updatePerfStats(stats)
                }
            }
        }
    }

    // =========================================================================
    // UI Setup
    // =========================================================================

    private fun initViews() {
        preview = findViewById(R.id.preview)
        serverInput = findViewById(R.id.server)
        portInput = findViewById(R.id.port)
        streamIdInput = findViewById(R.id.streamId)
        resolutionSpinner = findViewById(R.id.resolution)
        bitrateInput = findViewById(R.id.bitrate)
        startButton = findViewById(R.id.startButton)
        statusText = findViewById(R.id.status)
        settingsScroll = findViewById(R.id.settingsScroll)

        perfBar = findViewById(R.id.perfBar)
        statUptime = findViewById(R.id.statUptime)
        statCpu = findViewById(R.id.statCpu)
        statMem = findViewById(R.id.statMem)
        statNet = findViewById(R.id.statNet)
        statBatt = findViewById(R.id.statBatt)
        statThermal = findViewById(R.id.statThermal)

        resolutionSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, resolutions
        )

        startButton.setOnClickListener {
            val s = streamService
            if (s == null || !isBound) {
                updateStatus("Service not ready yet", StatusColor.ERROR)
                return@setOnClickListener
            }

            if (s.isStreamingFlow.value || s.isReconnectingState()) {
                s.stopStream()
            } else {
                val config = getConfigFromUI() ?: return@setOnClickListener
                saveSettings()
                s.startStream(config)
            }
        }
    }

    // =========================================================================
    // Settings persistence
    // =========================================================================

    private fun loadSavedSettings() {
        val settings = SettingsManager.load(this)
        serverInput.setText(settings.server)
        portInput.setText(settings.port.toString())
        streamIdInput.setText(settings.streamId)
        bitrateInput.setText(settings.bitrate.toString())
        resolutionSpinner.setSelection(settings.resolutionIndex.coerceIn(0, resolutions.lastIndex))
    }

    private fun saveSettings() {
        SettingsManager.save(
            context = this,
            server = serverInput.text.toString().trim(),
            port = portInput.text.toString().trim().toIntOrNull() ?: StreamConfig.DEFAULT.port,
            streamId = streamIdInput.text.toString().trim().ifEmpty { StreamConfig.DEFAULT.streamId },
            bitrate = bitrateInput.text.toString().trim().toIntOrNull() ?: StreamConfig.DEFAULT.bitrate,
            resolutionIndex = resolutionSpinner.selectedItemPosition
        )
    }

    // =========================================================================
    // Performance stats display
    // =========================================================================

    private fun updatePerfStats(stats: PerformanceLogger.Stats) {
        val s = streamService ?: return
        statUptime.text = s.perfLogger.formatUptime(stats.uptimeMs)
        statCpu.text = "CPU: ${stats.cpuPercent.toInt()}%"
        statMem.text = "MEM: ${stats.appMemMb.toInt()}M"
        statNet.text = "NET: ${stats.netTxKbps.toInt()}k"

        val battStr = if (stats.isCharging) "+" else ""
        statBatt.text = "BAT: ${stats.batteryPct}%$battStr"

        // Color battery red if low
        statBatt.setTextColor(
            if (stats.batteryPct <= 15 && !stats.isCharging)
                ContextCompat.getColor(this, R.color.status_error)
            else
                ContextCompat.getColor(this, R.color.text_secondary)
        )

        // Thermal indicator
        statThermal.text = stats.thermalState.take(4)
        statThermal.setTextColor(
            when (stats.thermalState) {
                "None", "Light", "N/A" -> ContextCompat.getColor(this, R.color.text_secondary)
                "Moderate" -> ContextCompat.getColor(this, R.color.status_connecting)
                "Severe", "Critical", "Emergency", "Shutdown" ->
                    ContextCompat.getColor(this, R.color.status_error)
                else -> ContextCompat.getColor(this, R.color.text_secondary)
            }
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun getConfigFromUI(): StreamConfig? {
        val server = serverInput.text.toString().trim()
        if (server.isEmpty()) {
            updateStatus("Please enter server IP address", StatusColor.ERROR)
            return null
        }

        val port = portInput.text.toString().trim().toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            updateStatus("Invalid port number", StatusColor.ERROR)
            return null
        }

        val streamId = streamIdInput.text.toString().trim()
        if (streamId.isEmpty()) {
            updateStatus("Please enter Stream ID (e.g. publish:phone)", StatusColor.ERROR)
            return null
        }

        val resolutionParts = resolutionSpinner.selectedItem.toString().split("x")
        val width = resolutionParts[0].toInt()
        val height = resolutionParts[1].toInt()

        val bitrate = bitrateInput.text.toString().trim().toIntOrNull() ?: StreamConfig.DEFAULT.bitrate

        return StreamConfig(
            server = server,
            port = port,
            streamId = streamId,
            width = width,
            height = height,
            fps = StreamConfig.DEFAULT.fps,
            bitrate = bitrate
        )
    }

    // =========================================================================
    // Status display (local fallback when service not bound)
    // =========================================================================

    private fun updateStatus(text: String, color: StatusColor) {
        runOnUiThread {
            statusText.text = text
            statusText.setTextColor(
                when (color) {
                    StatusColor.IDLE -> ContextCompat.getColor(this, R.color.status_idle)
                    StatusColor.STREAMING -> ContextCompat.getColor(this, R.color.status_streaming)
                    StatusColor.ERROR -> ContextCompat.getColor(this, R.color.status_error)
                    StatusColor.CONNECTING -> ContextCompat.getColor(this, R.color.status_connecting)
                    StatusColor.WARNING -> ContextCompat.getColor(this, R.color.status_connecting)
                }
            )
        }
    }
}
