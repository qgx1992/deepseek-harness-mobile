package com.qgx1992.dshmobile.ui.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.qgx1992.dshmobile.core.audio.WavWriter
import kotlin.math.sqrt

/**
 * Microphone capture for dictation: 16 kHz mono PCM in, one WAV out.
 *
 * [AudioRecord] rather than [MediaRecorder] because the harness wants a WAV, not an encoded file:
 * MediaRecorder would write an AMR or AAC container the host cannot decode, and re-wrapping that
 * would need a media muxer for no gain. Raw PCM also means the level meter can read the same
 * samples that are being kept instead of opening a second tap on the microphone.
 *
 * The source is [MediaRecorder.AudioSource.VOICE_RECOGNITION], not `MIC`: `MIC` on most devices
 * applies no processing and picks up whatever the room is doing, while `VOICE_RECOGNITION` is the
 * source the platform tunes for speech — the same choice every dictation surface on Android makes.
 * It does mean the recording is not a faithful field recording, which is exactly the point.
 *
 * Capture runs on its own thread: `read()` blocks until the driver has samples, so calling it on
 * the main thread would freeze the composer for the length of the take.
 */
@SuppressLint("MissingPermission")
internal class VoiceRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val sampleRate = WavWriter.DEFAULT_SAMPLE_RATE

    /**
     * Whether the capture loop should keep running.
     *
     * Volatile because it is the one field the UI thread and the capture thread both touch: the
     * loop reads it every iteration, and `stop`/`cancel` flip it from whatever thread the user
     * pressed the button on.
     */
    @Volatile
    private var running = false

    @Volatile
    private var level = 0f

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    /**
     * Captured blocks, appended only by the capture thread while it runs.
     *
     * Plain [ArrayList] is safe here without a lock because every reader joins the thread before
     * looking at it — the join is the happens-before edge.
     */
    private val chunks = ArrayList<ShortArray>()

    /** Whether a recording is in progress. */
    val active: Boolean get() = running

    /**
     * The RMS of the last block, normalised to full scale.
     *
     * This is the raw measurement, not a display value: speech on a phone microphone sits at a few
     * percent of full scale, so whoever draws a meter is expected to compress it. Keeping that
     * decision out of the recorder leaves one place that explains what the bar means.
     */
    fun level(): Float = level

    /**
     * Open the microphone and start capturing.
     *
     * Returns false when the device cannot provide a usable capture: no permission, a minimum
     * buffer the platform will not name, a recorder that comes up uninitialised, or a driver that
     * refuses to start. Every one of those is a state the composer has to survive, so the caller
     * gets a boolean rather than an exception.
     */
    fun start(): Boolean {
        if (running) return true
        // The screen checks the permission before it calls this, but `start` is also reached from
        // the permission callback, and a permission revoked in Settings between the check and the
        // call would otherwise leave the AudioRecord constructor throwing SecurityException.
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // A non-positive minimum means the platform cannot record at this format at all.
        if (minimum <= 0) return false
        // Two seconds, not the minimum: the minimum only covers the driver's own chunk, so a
        // buffer that size makes `read` return a handful of milliseconds at a time. The extra
        // slack is what keeps a recording from dropping blocks while the rest of the app is busy.
        val bufferSize = maxOf(minimum, sampleRate * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (_: IllegalArgumentException) {
            // The device rejected the format: no capture device, or a HAL that cannot do 16 kHz
            // mono. Either way there is nothing to record into.
            null
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: SecurityException) {
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            return false
        }
        try {
            recorder.startRecording()
        } catch (_: IllegalStateException) {
            // Initialised but no longer startable — another app holds the input, or the HAL died.
            runCatching { recorder.release() }
            return false
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { recorder.release() }
            return false
        }
        record = recorder
        chunks.clear()
        level = 0f
        running = true
        worker = Thread({ capture(recorder, bufferSize) }, "dsh-voice-recorder").apply {
            // Daemon: a take that is still running when the process goes away must not keep it up.
            isDaemon = true
            start()
        }
        return true
    }

    private fun capture(recorder: AudioRecord, bufferSize: Int) {
        // One tenth of a second per `read`, not half the buffer: `read` blocks until it has the
        // count it was asked for, so a request sized against the two-second buffer above would
        // hand the meter one new reading per second and hold the join in `stop` behind a whole
        // second of audio. Ten reads a second matches what the meter polls at, and still leaves the
        // driver's own buffer room to stay ahead of the consumer.
        val chunk = ShortArray(minOf(maxOf(sampleRate / 10, 1), maxOf(bufferSize / 2, 1)))
        while (running) {
            val read = try {
                recorder.read(chunk, 0, chunk.size)
            } catch (_: IllegalStateException) {
                -1
            }
            if (read < 0) {
                // A negative count is `AudioRecord`'s error path (bad value, dead object, record
                // already stopped), and carrying on would spin the capture thread against a driver
                // that is never going to hand over another sample.
                running = false
                break
            }
            if (read == 0) {
                // No data yet rather than a failure: yield briefly instead of retrying in a hot
                // loop, and let the `running` check at the top of the loop notice a stop.
                runCatching { Thread.sleep(NO_DATA_BACKOFF_MS) }
                continue
            }
            chunks.add(chunk.copyOf(read))
            level = rms(chunk, read)
        }
    }

    private fun rms(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (index in 0 until count) {
            val value = samples[index] / 32768.0
            sum += value * value
        }
        return sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Stop capturing and hand back the recording, or null when nothing was captured.
     *
     * The sequence matters: the flag is cleared, then the record is stopped, and only then is the
     * capture thread joined. `read` blocks until the driver has samples, so stopping the record
     * first is what unblocks it; joining before stopping would wait on a call that is still live.
     */
    fun stop(): ByteArray? {
        val recorder = record
        if (recorder == null) {
            chunks.clear()
            running = false
            level = 0f
            return null
        }
        shutdown(recorder)
        val total = chunks.sumOf { it.size }
        if (total == 0) {
            chunks.clear()
            return null
        }
        val samples = ShortArray(total)
        var at = 0
        for (block in chunks) {
            block.copyInto(samples, at, 0, block.size)
            at += block.size
        }
        chunks.clear()
        return WavWriter.encode(samples, sampleRate)
    }

    /** Throw the take away and put the recorder back to idle. */
    fun cancel() {
        shutdown(record)
        chunks.clear()
    }

    /**
     * Release the microphone.
     *
     * Called when the composer leaves composition. It is the same teardown as [cancel] because a
     * navigate-away is a cancel by another name: there is nobody left to hand a transcript to.
     */
    fun release() = cancel()

    private fun shutdown(recorder: AudioRecord?) {
        running = false
        // Unblocks the capture thread's `read` before the join below.
        runCatching { recorder?.stop() }
        val thread = worker
        worker = null
        record = null
        if (thread != null) runCatching { thread.join(JOIN_TIMEOUT_MS) }
        runCatching { recorder?.release() }
        level = 0f
    }

    private companion object {
        /**
         * Long enough for a blocked `read` to come back after `stop`, short enough that a wedged
         * driver cannot hang the UI thread for the rest of the session.
         */
        const val JOIN_TIMEOUT_MS = 1_000L

        /** How long to wait before asking again after a `read` that produced no samples. */
        const val NO_DATA_BACKOFF_MS = 20L
    }
}
