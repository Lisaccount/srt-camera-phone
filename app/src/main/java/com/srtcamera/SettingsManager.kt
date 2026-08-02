package com.srtcamera

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user settings across app restarts using SharedPreferences.
 * Stores: server IP, port, streamId, bitrate, resolution selection.
 */
object SettingsManager {

    private const val PREFS_NAME = "srt_camera_settings"

    private const val KEY_SERVER = "server"
    private const val KEY_PORT = "port"
    private const val KEY_STREAM_ID = "stream_id"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_RESOLUTION_INDEX = "resolution_index"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Settings(
        val server: String,
        val port: Int,
        val streamId: String,
        val bitrate: Int,
        val resolutionIndex: Int
    )

    fun load(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            server = p.getString(KEY_SERVER, "") ?: "",
            port = p.getInt(KEY_PORT, StreamConfig.DEFAULT.port),
            streamId = p.getString(KEY_STREAM_ID, StreamConfig.DEFAULT.streamId)
                ?: StreamConfig.DEFAULT.streamId,
            bitrate = p.getInt(KEY_BITRATE, StreamConfig.DEFAULT.bitrate),
            resolutionIndex = p.getInt(KEY_RESOLUTION_INDEX, 0)
        )
    }

    fun save(
        context: Context,
        server: String,
        port: Int,
        streamId: String,
        bitrate: Int,
        resolutionIndex: Int
    ) {
        prefs(context).edit()
            .putString(KEY_SERVER, server)
            .putInt(KEY_PORT, port)
            .putString(KEY_STREAM_ID, streamId)
            .putInt(KEY_BITRATE, bitrate)
            .putInt(KEY_RESOLUTION_INDEX, resolutionIndex)
            .apply()
    }
}
