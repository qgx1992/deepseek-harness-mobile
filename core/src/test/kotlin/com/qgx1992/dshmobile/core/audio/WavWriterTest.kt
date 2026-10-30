package com.qgx1992.dshmobile.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WavWriterTest {
    // Little-endian readers, written out here instead of reusing the production code: a test that
    // decodes with the same helpers it is checking cannot catch a byte-order mistake.
    private fun intAt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun shortAt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun textAt(bytes: ByteArray, at: Int, length: Int): String =
        String(bytes, at, length, Charsets.US_ASCII)

    @Test fun headerDescribesSixteenBitMono() {
        val wav = WavWriter.encode(ShortArray(4))
        assertEquals("RIFF", textAt(wav, 0, 4))
        assertEquals(36 + 8, intAt(wav, 4))
        assertEquals("WAVE", textAt(wav, 8, 4))
        assertEquals("fmt ", textAt(wav, 12, 4))
        assertEquals(16, intAt(wav, 16))
        assertEquals(1, shortAt(wav, 20)) // PCM
        assertEquals(1, shortAt(wav, 22)) // mono
        assertEquals(16_000, intAt(wav, 24))
        assertEquals(32_000, intAt(wav, 28)) // sampleRate * blockAlign
        assertEquals(2, shortAt(wav, 32)) // blockAlign
        assertEquals(16, shortAt(wav, 34)) // bits per sample
        assertEquals("data", textAt(wav, 36, 4))
        assertEquals(8, intAt(wav, 40))
    }

    @Test fun dataSizeAndLengthFollowTheSampleCount() {
        val wav = WavWriter.encode(ShortArray(1_000))
        assertEquals(2_000, intAt(wav, 40))
        assertEquals(44 + 2_000, wav.size)
        assertEquals(36 + 2_000, intAt(wav, 4))
        assertEquals(1_000, WavWriter.sampleCount(wav))
    }

    @Test fun samplesAreLittleEndian() {
        val wav = WavWriter.encode(shortArrayOf(1, -1, 32_767, -32_768))
        assertEquals(1, shortAt(wav, 44))
        assertEquals(-1, shortAt(wav, 46).let { if (it > 32_767) it - 65_536 else it })
        assertEquals(32_767, shortAt(wav, 48))
        assertEquals(32_768, shortAt(wav, 50)) // -32768 as an unsigned 16-bit pattern
    }

    @Test fun isWaveSniffsTheSignature() {
        assertTrue(WavWriter.isWave(WavWriter.encode(ShortArray(0))))
        assertFalse(WavWriter.isWave(ByteArray(0)))
        assertFalse(WavWriter.isWave("RIFF".toByteArray(Charsets.US_ASCII)))
        assertFalse(WavWriter.isWave(WavWriter.encode(ShortArray(4)).copyOfRange(0, 11)))
        assertFalse(WavWriter.isWave("NOPE0000WAVE".toByteArray(Charsets.US_ASCII)))
    }

    @Test fun sampleCountRejectsMalformedBytes() {
        assertEquals(0, WavWriter.sampleCount(ByteArray(0)))
        assertEquals(0, WavWriter.sampleCount("not a wave at all".toByteArray(Charsets.US_ASCII)))
        // A signature with the data length cut short: the chunk walk has to refuse it rather than
        // read a count out of whatever follows.
        val truncated = WavWriter.encode(ShortArray(100)).copyOfRange(0, 48)
        assertEquals(0, WavWriter.sampleCount(truncated))
    }

    @Test fun durationOfThreeSecondsAtSixteenKilohertz() {
        val wav = WavWriter.encode(ShortArray(48_000))
        assertEquals(3.0, WavWriter.durationSeconds(wav), 0.0)
        assertEquals(1.5, WavWriter.durationSeconds(wav, 32_000), 0.0)
        assertEquals(0.0, WavWriter.durationSeconds(wav, 0), 0.0)
    }

    @Test fun emptyRecordingStillHasAWellFormedHeader() {
        val wav = WavWriter.encode(ShortArray(0))
        assertEquals(44, wav.size)
        assertTrue(WavWriter.isWave(wav))
        assertEquals(0, intAt(wav, 40))
        assertEquals(36, intAt(wav, 4))
        assertEquals(0, WavWriter.sampleCount(wav))
        assertEquals(0.0, WavWriter.durationSeconds(wav), 0.0)
        assertEquals(16_000, intAt(wav, 24))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroSampleRateIsRefusedRatherThanWritten() {
        WavWriter.encode(ShortArray(4), 0)
    }
}
