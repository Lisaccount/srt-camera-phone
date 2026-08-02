package com.srtcamera

import android.media.AudioFormat
import android.util.Size

/**
 * Stream configuration parsed from UI inputs.
 * Passed to StreamPack's SrtMediaDescriptor and VideoConfig/AudioConfig.
 */
data class StreamConfig(
    val server: String,
    val port: Int,
    val streamId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val latency: Int = 200
) {
    val resolution: Size get() = Size(width, height)

    companion object {
        val DEFAULT = StreamConfig(
            server = "",
            port = 8890,
            streamId = "publish:phone",
            width = 1280,
            height = 720,
            fps = 15,
            bitrate = 2_000_000
        )
    }
}
