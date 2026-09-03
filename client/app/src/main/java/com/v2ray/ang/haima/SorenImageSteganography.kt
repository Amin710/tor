package com.v2ray.ang.haima

import android.graphics.Bitmap

internal class SorenImageConfigException(
    val failureCode: String,
    cause: Throwable? = null
) : Exception(failureCode, cause)

/** Decoder compatible with github.com/auyer/steganography's PNG LSB format. */
internal object SorenImageSteganography {
    // The supplied 325x325 carrier and encoder safely support at most 39,601 bytes.
    const val MAX_HIDDEN_BYTES = 39_601

    fun decode(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = checkedPixelCount(width, height)
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return decodePixels(width, height, pixels)
    }

    /** Kept Android-free apart from the packed ARGB input so the exact bit contract is testable. */
    internal fun decodePixels(
        width: Int,
        height: Int,
        pixels: IntArray,
        maximumHiddenBytes: Int = MAX_HIDDEN_BYTES
    ): ByteArray {
        val pixelCount = checkedPixelCount(width, height)
        if (pixels.size != pixelCount) {
            throw SorenImageConfigException("IMAGE_PIXEL_BUFFER_INVALID")
        }
        val capacity = ((pixelCount.toLong() * RGB_CHANNELS) / BITS_PER_BYTE) - LENGTH_BYTES
        if (capacity < MIN_HIDDEN_BYTES) {
            throw SorenImageConfigException("IMAGE_CAPACITY_TOO_SMALL")
        }

        val reader = RgbLsbReader(width, height, pixels)
        val b0 = reader.readByte()
        val b1 = reader.readByte()
        val b2 = reader.readByte()
        val b3 = reader.readByte()
        val messageLength = ((b0.toLong() and 0xffL) shl 24) or
            ((b1.toLong() and 0xffL) shl 16) or
            ((b2.toLong() and 0xffL) shl 8) or
            (b3.toLong() and 0xffL)
        val permittedMaximum = minOf(capacity, maximumHiddenBytes.toLong())
        if (messageLength !in MIN_HIDDEN_BYTES.toLong()..permittedMaximum) {
            throw SorenImageConfigException("IMAGE_MESSAGE_LENGTH_INVALID")
        }

        return ByteArray(messageLength.toInt()) { reader.readByte().toByte() }
    }

    private fun checkedPixelCount(width: Int, height: Int): Int {
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
            throw SorenImageConfigException("IMAGE_DIMENSIONS_INVALID")
        }
        val count = width.toLong() * height.toLong()
        if (count !in 1..MAX_PIXELS.toLong()) {
            throw SorenImageConfigException("IMAGE_PIXEL_COUNT_INVALID")
        }
        return count.toInt()
    }

    private class RgbLsbReader(
        private val width: Int,
        private val height: Int,
        private val pixels: IntArray
    ) {
        private var x = 0
        private var y = 0
        private var channel = 0

        fun readByte(): Int {
            var value = 0
            repeat(BITS_PER_BYTE) {
                value = (value shl 1) or nextBit()
            }
            return value
        }

        private fun nextBit(): Int {
            if (x >= width) throw SorenImageConfigException("IMAGE_MESSAGE_TRUNCATED")
            val pixel = pixels[y * width + x]
            val shift = when (channel) {
                0 -> 16 // red
                1 -> 8 // green
                else -> 0 // blue
            }
            val bit = (pixel ushr shift) and 1
            channel += 1
            if (channel == RGB_CHANNELS) {
                channel = 0
                y += 1
                if (y == height) {
                    y = 0
                    x += 1
                }
            }
            return bit
        }
    }

    private const val RGB_CHANNELS = 3
    private const val BITS_PER_BYTE = 8
    private const val LENGTH_BYTES = 4L
    private const val MIN_HIDDEN_BYTES = 16
    private const val MAX_DIMENSION = 2_048
    private const val MAX_PIXELS = 4_000_000
}
