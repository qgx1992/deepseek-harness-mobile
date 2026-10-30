package com.qgx1992.dshmobile.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.wire.RpcResult
import com.qgx1992.dshmobile.core.wire.dto.SpeechCatalogView
import com.qgx1992.dshmobile.core.wire.dto.SpeechTranscriptionView
import com.qgx1992.dshmobile.ui.media.VoiceRecorder
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsSpacing
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the microphone affordance is doing right now. */
internal enum class VoicePhase { IDLE, RECORDING, TRANSCRIBING }

/**
 * Everything the composer needs to draw the voice affordance.
 *
 * The state is a snapshot rather than a session: it holds the phase, the current meter reading and
 * the last thing that went wrong, and forwards [start]/[stop]/[cancel] to the [VoiceController]
 * that owns the microphone. Splitting it that way is what lets `Composer` stay a drawing function
 * and lets the recording lifecycle live somewhere it can be tested by holding a button, instead of
 * being scattered through the layout.
 */
@Stable
internal class VoiceInputState internal constructor(
    initialPhase: VoicePhase,
    initialLevel: Float,
    initialMessage: Int?,
) {
    /** IDLE draws the microphone, RECORDING draws [VoiceRecordingBar], TRANSCRIBING spins. */
    var phase: VoicePhase by mutableStateOf(initialPhase)
        internal set

    /** The recorder's last reading, 0..1 of full scale; drawn by the meter. */
    var level: Float by mutableStateOf(initialLevel)
        internal set

    /** The string resource of the last failure, or null when there is nothing to report. */
    var message: Int? by mutableStateOf(initialMessage)
        internal set

    /** Set once by [rememberVoiceInput]; the state is inert until there is a microphone behind it. */
    internal var controller: VoiceController? = null

    /** Begin a take, asking for the microphone first when the permission is not held yet. */
    fun start() {
        controller?.start()
    }

    /** Stop the take and transcribe it. */
    fun stop() {
        controller?.stop()
    }

    /** Throw the take away. */
    fun cancel() {
        controller?.cancel()
    }

    /** Ack the failure line, so it goes away once it has been shown. */
    fun clearMessage() {
        message = null
    }
}

/**
 * Owns the recorder, the permission request and the round trip to the recognizer.
 *
 * Kept out of the composable body because the interesting part is a small state machine — "was the
 * microphone already allowed, is a take running, is a request in flight" — and a state machine that
 * lives inside a `@Composable` cannot be reasoned about without a composition to run it in. The
 * composable only wires callbacks in and polls the meter; the decisions are here.
 */
internal class VoiceController(
    private val context: Context,
    private val recorder: VoiceRecorder,
    private val state: VoiceInputState,
    private val scope: CoroutineScope,
    private val maxDurationSeconds: Int,
    private val maxAudioBytes: Int,
) {
    /** Launches the system permission dialog; set by the composable, which is where a launcher lives. */
    internal var launchPermission: () -> Unit = {}

    /** The RPC, supplied by the screen that owns the client; null until the wiring lands. */
    internal var transcribe: (suspend (ByteArray) -> RpcResult<SpeechTranscriptionView>)? = null

    internal var onTranscript: (String) -> Unit = {}

    internal var onMessage: (Int) -> Unit = {}

    /**
     * Whether the user asked for a take and is waiting on the permission dialog.
     *
     * The click is remembered instead of the take starting immediately: the dialog is asynchronous,
     * and making the user press the microphone a second time after allowing it is the kind of
     * double-confirmation that reads as the button being broken.
     */
    private var pendingPermissionStart = false

    private var transcribeJob: Job? = null

    fun start() {
        // Ignore a second press while a take or a request is live; the buttons for both states are
        // replaced in the composer, so this only guards against a stale handler being invoked.
        if (state.phase != VoicePhase.IDLE) return
        if (!hasMicrophone()) {
            pendingPermissionStart = true
            launchPermission()
            return
        }
        begin()
    }

    fun onPermissionResult(granted: Boolean) {
        val wanted = pendingPermissionStart
        pendingPermissionStart = false
        if (!wanted) return
        if (granted) begin() else report(R.string.voice_input_permission)
    }

    fun stop() {
        if (state.phase != VoicePhase.RECORDING) return
        state.phase = VoicePhase.TRANSCRIBING
        state.level = 0f
        transcribeJob = scope.launch {
            // `recorder.stop()` joins the capture thread, and that thread is parked in a blocking
            // `read` until the record is stopped — so this belongs on IO, never on the frame clock.
            val wav = withContext(Dispatchers.IO) { recorder.stop() }
            if (wav == null) {
                // No samples at all: the microphone opened but nothing was captured, which is a
                // recognizer answer of "nothing there" rather than a transport failure.
                abandon(R.string.voice_input_empty)
                return@launch
            }
            // The request carries the audio as base64, a third larger than the WAV, so the
            // catalog's cap is checked against what the wire will actually hold. Encoding here
            // keeps a multi-megabyte string off the main thread, and an oversized take fails
            // before a round trip instead of after one.
            val encoded = withContext(Dispatchers.Default) { Base64.encodeToString(wav, Base64.NO_WRAP) }
            val encodedCap = maxAudioBytes.toLong() * 4 / 3 + 4
            if (wav.size > maxAudioBytes || encoded.length > encodedCap) {
                abandon(R.string.voice_input_too_long)
                return@launch
            }
            val call = transcribe
            if (call == null) {
                abandon(R.string.voice_input_failed)
                return@launch
            }
            when (val result = call(wav)) {
                is RpcResult.Ok -> {
                    val text = result.value.text
                    state.phase = VoicePhase.IDLE
                    // A recognizer that heard nothing answers with an empty string rather than an
                    // error, so "empty" has to be decided here: passing "" on would clear the
                    // draft and look like the feature silently ate the sentence.
                    if (text.isBlank()) report(R.string.voice_input_empty) else onTranscript(text)
                }

                is RpcResult.Err -> {
                    state.phase = VoicePhase.IDLE
                    // The host reports both a truncated file and one past its size limit as
                    // `speech/invalid-audio`; only its message says which, and only the length
                    // case has something useful to tell the user. Everything else (no model, host
                    // unreachable) is the generic failure.
                    val tooLong = result.error.code == CODE_INVALID_AUDIO &&
                        mentionsLength(result.error.message)
                    report(if (tooLong) R.string.voice_input_too_long else R.string.voice_input_failed)
                }
            }
        }
    }

    fun cancel() {
        when (state.phase) {
            VoicePhase.TRANSCRIBING -> {
                // The audio is already on its way; dropping the job is how the answer stops
                // mattering. The host may still finish the request, which costs nothing here.
                transcribeJob?.cancel()
                transcribeJob = null
                state.phase = VoicePhase.IDLE
            }

            VoicePhase.RECORDING -> {
                // `cancel()` stops the record before joining, and stopping is what unblocks the
                // capture thread's `read`, so this returns in the time one buffer takes to fill.
                recorder.cancel()
                state.phase = VoicePhase.IDLE
                state.level = 0f
                state.message = null
            }

            VoicePhase.IDLE -> Unit
        }
    }

    /** The composer is leaving composition: drop everything, including a take still running. */
    fun dispose() {
        transcribeJob?.cancel()
        transcribeJob = null
        pendingPermissionStart = false
        state.phase = VoicePhase.IDLE
        state.level = 0f
        state.message = null
        recorder.release()
    }

    private fun begin() {
        state.message = null
        state.level = 0f
        if (!recorder.start()) {
            // Permission is held by now, so a refusal here is the device: no capture device, or a
            // format the HAL will not open. There is no specific string for that, and the generic
            // failure is what the user can act on (try again, use another input).
            report(R.string.voice_input_failed)
            return
        }
        state.phase = VoicePhase.RECORDING
    }

    /** End the take on the state's behalf, reporting why. */
    private fun abandon(message: Int) {
        state.phase = VoicePhase.IDLE
        report(message)
    }

    private fun report(message: Int) {
        state.message = message
        onMessage(message)
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun mentionsLength(message: String): Boolean {
        val text = message.lowercase()
        return LENGTH_WORDS.any { text.contains(it) }
    }

    private companion object {
        /** The one code the host uses for audio it could not decode or would not accept. */
        const val CODE_INVALID_AUDIO = "speech/invalid-audio"

        /**
         * Words the host's own message uses when the cause is size. Narrow on purpose: a
         * `speech/invalid-audio` about a corrupted payload must not be reported as "too long",
         * because the advice for the two is different.
         */
        val LENGTH_WORDS = listOf("long", "length", "duration", "size", "large", "bytes", "exceed")
    }
}

/**
 * Wires a [VoiceRecorder] into composition and returns the state to draw from, or null when there
 * is no recognizer to record for.
 *
 * Null rather than a disabled state: a deployment whose harness composes no speech service cannot
 * do anything with a microphone button, and offering one that always fails is worse than the
 * composer simply having one fewer control.
 *
 * [catalog] is the `speech/catalog` answer; it carries both the provider to transcribe with (the
 * caller applies it in [onTranscribe]) and the two limits — how long a take may run and how many
 * bytes it may weigh — which is why this composable takes it instead of a bare "enabled" flag.
 */
@Composable
internal fun rememberVoiceInput(
    catalog: SpeechCatalogView?,
    onTranscribe: suspend (wav: ByteArray) -> RpcResult<SpeechTranscriptionView>,
    onTranscript: (String) -> Unit,
    onMessage: (Int) -> Unit = {},
): VoiceInputState? {
    val speechCatalog = catalog?.takeIf { it.usable } ?: return null
    val maxDurationSeconds = speechCatalog.maxDurationSeconds

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember(context) { VoiceRecorder(context) }
    val state = remember(recorder) { VoiceInputState(VoicePhase.IDLE, 0f, null) }
    val controller = remember(recorder, maxDurationSeconds, speechCatalog.maxAudioBytes) {
        VoiceController(
            context = context,
            recorder = recorder,
            state = state,
            scope = scope,
            maxDurationSeconds = maxDurationSeconds,
            maxAudioBytes = speechCatalog.maxAudioBytes,
        ).also { state.controller = it }
    }

    // The RPC lambda is a fresh closure every recomposition (it closes over the client and the
    // chosen provider), and the controller keeps it across a take — so what is handed over is the
    // latest one, not the one that existed when the recorder was remembered.
    val currentTranscribe by rememberUpdatedState(onTranscribe)
    val currentTranscript by rememberUpdatedState(onTranscript)
    val currentMessage by rememberUpdatedState(onMessage)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> controller.onPermissionResult(granted) }

    SideEffect {
        controller.transcribe = currentTranscribe
        controller.onTranscript = currentTranscript
        controller.onMessage = currentMessage
        controller.launchPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }

    // The meter is polled, not pushed: `AudioRecord` has no per-block callback, only a blocking
    // `read` on the capture thread, and a listener interface of our own would be a thread hand-off
    // per 20ms buffer for a bar that redraws ten times a second anyway. Polling the volatile field
    // at the frame rate the eye can follow costs one read per tick and no extra thread.
    LaunchedEffect(state.phase) {
        if (state.phase != VoicePhase.RECORDING) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            state.level = recorder.level()
            // Elapsed time, not an accumulated tick count: the limit has to be the limit even when
            // a frame is late or the app is paused behind a dialog.
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= maxDurationSeconds * 1_000L) {
                state.stop()
                break
            }
            delay(LEVEL_POLL_MS)
        }
    }

    // Leaving the composer mid-take is a cancel: there is nothing left to hand a transcript to, and
    // a recorder that keeps the microphone open after the UI is gone holds the device's audio
    // input for every other app.
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    return state
}

/**
 * The compact recording strip that replaces the composer's middle slot while a take runs.
 *
 * It is laid out as one row rather than a dialog so the draft and the send button stay visible
 * behind it: dictating is a substitute for typing, not a mode switch, and a modal recorder hides
 * the thing the words are going into.
 */
@Composable
internal fun VoiceRecordingBar(state: VoiceInputState, modifier: Modifier = Modifier) {
    val colors = DsTheme.colors
    // Speech sits at a few percent of full scale on a phone microphone, so a linear meter would be
    // a hairline for a whole sentence. The gain is a fixed number rather than an automatic one so
    // that "louder than last time" still means something to the eye.
    val width = (state.level * LEVEL_GAIN).coerceIn(0f, 1f)
    val animatedWidth by animateFloatAsState(
        targetValue = width,
        animationSpec = tween(durationMillis = 120),
        label = "voiceInputLevel",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            stringResource(R.string.voice_input_recording),
            style = DsType.small13,
            color = colors.labelSecondary,
            maxLines = 1,
        )
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(DsShapes.pillFull)
                .background(colors.borderL3),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedWidth)
                    .clip(DsShapes.pillFull)
                    .background(colors.accent),
            )
        }
        // Cancel and stop live inside the strip, which is why the composer hides its microphone
        // button for the duration: two ways to do the same thing within a thumb's reach of each
        // other is how a recording gets stopped by accident.
        DsIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.voice_input_cancel),
            onClick = { state.cancel() },
            tint = colors.labelSecondary,
            iconSize = 16.dp,
        )
        DsIconButton(
            icon = Icons.Filled.Check,
            contentDescription = stringResource(R.string.voice_input_stop),
            onClick = { state.stop() },
            tint = colors.accent,
            iconSize = 20.dp,
        )
    }
}

/** How often the meter is refreshed, in milliseconds. Fast enough to look live, slow enough to be free. */
private const val LEVEL_POLL_MS = 100L

/** Fixed display gain applied to the RMS reading; see [VoiceRecordingBar]. */
private const val LEVEL_GAIN = 8f
