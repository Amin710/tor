package com.v2ray.ang.haima

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SorenImageSteganographyTest {
    @Test
    fun decodesAuyerXMajorYMinorRgbLsbWithMsbFirstBytes() {
        val hidden = byteArrayOf(
            0x54, 0x43, 0x49, 0x31,
            0x01, 0x02, 0x03, 0x04,
            0x55, 0x2a, 0x7f, 0x00,
            0x11, 0x22, 0x33, 0x44
        )
        val width = 9
        val height = 8
        val pixels = encodeCarrier(width, height, hidden)

        assertArrayEquals(
            hidden,
            SorenImageSteganography.decodePixels(width, height, pixels)
        )
    }

    @Test
    fun rejectsHeaderLargerThanTheSuppliedCarrier() {
        val width = 8
        val height = 8
        val pixels = encodeHeaderOnly(width, height, declaredLength = 100)

        val error = assertThrows(SorenImageConfigException::class.java) {
            SorenImageSteganography.decodePixels(width, height, pixels)
        }

        assertEquals("IMAGE_MESSAGE_LENGTH_INVALID", error.failureCode)
    }

    @Test
    fun rejectsHeaderAboveGlobal39601ByteLimitBeforeAllocation() {
        val width = 400
        val height = 300
        val pixels = encodeHeaderOnly(
            width = width,
            height = height,
            declaredLength = SorenImageSteganography.MAX_HIDDEN_BYTES + 1
        )

        val error = assertThrows(SorenImageConfigException::class.java) {
            SorenImageSteganography.decodePixels(width, height, pixels)
        }

        assertEquals("IMAGE_MESSAGE_LENGTH_INVALID", error.failureCode)
    }

    private fun encodeCarrier(width: Int, height: Int, hidden: ByteArray): IntArray {
        val framed = byteArrayOf(
            (hidden.size ushr 24).toByte(),
            (hidden.size ushr 16).toByte(),
            (hidden.size ushr 8).toByte(),
            hidden.size.toByte()
        ) + hidden
        return encodeBytes(width, height, framed)
    }

    private fun encodeHeaderOnly(width: Int, height: Int, declaredLength: Int): IntArray =
        encodeBytes(
            width,
            height,
            byteArrayOf(
                (declaredLength ushr 24).toByte(),
                (declaredLength ushr 16).toByte(),
                (declaredLength ushr 8).toByte(),
                declaredLength.toByte()
            )
        )

    /** Independent implementation of github.com/auyer/steganography's bit order. */
    private fun encodeBytes(width: Int, height: Int, bytes: ByteArray): IntArray {
        require(bytes.size.toLong() * 8L <= width.toLong() * height.toLong() * 3L)
        val pixels = IntArray(width * height) { 0xffa4b6c8.toInt() }
        bytes.forEachIndexed { byteIndex, byte ->
            repeat(8) { bitInByte ->
                val streamBit = byteIndex * 8 + bitInByte
                val traversalPixel = streamBit / 3
                val channel = streamBit % 3
                val x = traversalPixel / height
                val y = traversalPixel % height
                val pixelIndex = y * width + x
                val shift = when (channel) {
                    0 -> 16
                    1 -> 8
                    else -> 0
                }
                val bit = (byte.toInt() ushr (7 - bitInByte)) and 1
                pixels[pixelIndex] = (pixels[pixelIndex] and (1 shl shift).inv()) or
                    (bit shl shift)
            }
        }
        return pixels
    }
}
