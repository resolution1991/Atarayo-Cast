package com.atarayocast.app.service

import kotlin.math.floor
import kotlin.math.min

internal data class NegotiatedDisplaySize(
    val width: Int,
    val height: Int,
    val fps: Int
)

internal object DisplaySizePolicy {
    private const val MAX_LONG_EDGE = 3840
    private const val MAX_SHORT_EDGE = 2160
    private const val HIGH_FRAME_RATE_PIXEL_LIMIT = 2560 * 1600
    private const val LEGACY_LONG_EDGE = 1280
    private const val LEGACY_SHORT_EDGE = 800
    private const val LEGACY_MAX_FPS = 30

    /**
     * Keeps the physical display orientation and aspect ratio while constraining
     * the long and short edges to the receiver's 4K decoding envelope.
     */
    fun fromPhysicalDisplay(width: Int, height: Int): NegotiatedDisplaySize {
        require(width > 0 && height > 0) { "Display dimensions must be positive" }

        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        val scale = min(
            1.0,
            min(
                MAX_LONG_EDGE.toDouble() / longEdge,
                MAX_SHORT_EDGE.toDouble() / shortEdge
            )
        )

        val scaledWidth = evenFloor(width * scale)
        val scaledHeight = evenFloor(height * scale)
        val fps = if (
            scaledWidth.toLong() * scaledHeight <= HIGH_FRAME_RATE_PIXEL_LIMIT.toLong()
        ) 60 else 30

        return NegotiatedDisplaySize(scaledWidth, scaledHeight, fps)
    }

    /** Scales a requested stream size down to a decoder envelope without rotating it. */
    fun fitWithin(
        requested: NegotiatedDisplaySize,
        maxWidth: Int,
        maxHeight: Int
    ): NegotiatedDisplaySize {
        require(maxWidth > 0 && maxHeight > 0) { "Decoder bounds must be positive" }

        val scale = min(
            1.0,
            min(
                maxWidth.toDouble() / requested.width,
                maxHeight.toDouble() / requested.height
            )
        )
        return requested.copy(
            width = evenFloor(requested.width * scale),
            height = evenFloor(requested.height * scale)
        )
    }

    /**
     * Stable live-mirroring envelope for old IMG/PowerVR hardware decoders.
     * Their reported MediaCodec caps can be more optimistic than sustained
     * decoding throughput, especially with 60fps 16:10 streams.
     */
    fun legacyDecoderSafe(requested: NegotiatedDisplaySize): NegotiatedDisplaySize =
        fitWithin(requested, LEGACY_LONG_EDGE, LEGACY_SHORT_EDGE).copy(
            fps = min(requested.fps, LEGACY_MAX_FPS)
        )

    private fun evenFloor(value: Double): Int {
        val floored = floor(value).toInt().coerceAtLeast(2)
        return floored and -2
    }
}
