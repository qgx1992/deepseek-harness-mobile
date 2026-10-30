package com.qgx1992.dshmobile.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The RIFF/WAVE container the speech endpoint accepts.
 *
 * `speech/transcribe` takes a recording as base64 and decodes it on the host before anything else
 * touches it, so the bytes have to be a real WAV rather than raw PCM with a hint attached. The
 * format is fixed and small enough that writing it by hand costs less than pulling in a media
 * library: 16-bit mono little-endian, one 44-byte header, then the samples, with nothing the
 * decoder has to guess.
 *
 * The header is written even for an empty recording. A zero-length take is a legitimate answer to
 * "did the microphone open?", and a 44-byte file with `dataSize = 0` stays a well-formed WAV that
 * a parser can reject on its own terms instead of failing on a truncated file.
 */
object WavWriter {
    /** 16 kHz is what every recognizer the host offers was trained on, and the recorder opens at it. */
    const val DEFAULT_SAMPLE_RATE = 16_000

    private const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1
    private const val PCM_FORMAT = 1

    /** Bytes per frame: both channels and both bytes of every sample. */
    private const val BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8

    private val MAGIC_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val MAGIC_WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    private val MAGIC_FMT = "fmt ".toByteArray(Charsets.US_ASCII)
    private val MAGIC_DATA = "data".toByteArray(Charsets.US_ASCII)

    /**
     * Encode [samples] as a mono WAV at [sampleRate].
     *
     * The byte order is little-endian on every field and every sample, independent of the host JVM,
     * because the WAV spec is little-endian and the recognizer parses the bytes, not Java values.
     */
    fun encode(samples: ShortArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): ByteArray {
        // A zero or negative rate would put a byteRate of 0 in the header, which the host reads as
        // an unusable file much later. Failing here names the real problem at the caller.
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC_RIFF)
        // The RIFF chunk length counts everything after this field: the "WAVE" form type, the fmt
        // chunk and the data chunk. 36 is the header with its own two length fields removed.
        buffer.putInt(36 + dataSize)
        buffer.put(MAGIC_WAVE)
        buffer.put(MAGIC_FMT)
        buffer.putInt(16) // PCM fmt chunk body length
        buffer.putShort(PCM_FORMAT.toShort())
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * BLOCK_ALIGN) // byteRate
        buffer.putShort(BLOCK_ALIGN.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put(MAGIC_DATA)
        buffer.putInt(dataSize)
        // The short view starts where the header ended and converts each sample to two
        // little-endian bytes, which saves a per-sample putShort loop over what can be a
        // two-minute recording.
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    /**
     * The frame count in [wav], or 0 when the bytes are not a readable WAV.
     *
     * The chunks are walked rather than assumed at a fixed offset: nothing guarantees the fmt chunk
     * comes first or that the file on the other end was written by this class, and a decoder that
     * hard-codes offset 44 is exactly how a recording with an extra chunk reads as silence.
     */
    fun sampleCount(wav: ByteArray): Int {
        if (!isWave(wav)) return 0
        var offset = 12 // past "RIFF", the chunk length and "WAVE"
        while (offset + 8 <= wav.size) {
            val size = readInt(wav, offset + 4)
            // A negative length would run the walk backwards forever, and one past the end means a
            // truncated file; either way there is no honest count to report.
            if (size < 0 || offset + 8 + size > wav.size) return 0
            if (matches(wav, offset, "data")) return size / (BITS_PER_SAMPLE / 8)
            // Chunks are word-aligned: an odd-sized chunk is followed by one pad byte that is not
            // part of its length.
            offset += 8 + size + (size and 1)
        }
        return 0
    }

    /** How long [wav] plays for at [sampleRate]; 0.0 when it cannot be read. */
    fun durationSeconds(wav: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE): Double {
        if (sampleRate <= 0) return 0.0
        return sampleCount(wav).toDouble() / sampleRate
    }

    /**
     * Whether [bytes] opens with the RIFF/WAVE signature.
     *
     * This is a sniff, not a validation: it answers "should I try to parse this as a WAV" so a
     * caller can refuse an MP3 picker result before any chunk walking, which is why 12 bytes is
     * enough. [sampleCount] is what reports a file that is signed but malformed.
     */
    fun isWave(bytes: ByteArray): Boolean =
        bytes.size >= 12 && matches(bytes, 0, "RIFF") && matches(bytes, 8, "WAVE")

    private fun matches(bytes: ByteArray, at: Int, magic: String): Boolean {
        if (at < 0 || at + magic.length > bytes.size) return false
        for (index in magic.indices) {
            if (bytes[at + index] != magic[index].code.toByte()) return false
        }
        return true
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
