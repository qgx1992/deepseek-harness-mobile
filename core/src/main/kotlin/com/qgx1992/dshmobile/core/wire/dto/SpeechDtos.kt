package com.qgx1992.dshmobile.core.wire.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Views over the `speech` namespace of the experimental speech-to-text plugin.
 *
 * The namespace exists only when the host composed that plugin; a host without it answers 404 and
 * the client hides the microphone rather than offering a button that cannot work. Every field
 * carries a default, for the same reason the projections do: a host one version ahead must not
 * crash the phone.
 */

/** The `speech/catalog` answer: recognizers the host can offer, the stored choice, and the limits. */
@Serializable
data class SpeechCatalogView(
    @SerialName("providers") val providers: List<SpeechProviderView> = emptyList(),
    @SerialName("selection") val selection: SpeechSelectionView = SpeechSelectionView(),
    /** Largest accepted WAV, enforced by the host before it decodes anything. */
    @SerialName("maxAudioBytes") val maxAudioBytes: Int = DEFAULT_MAX_AUDIO_BYTES,
    /** Longest accepted recording; the host refuses a longer WAV as `speech/invalid-audio`. */
    @SerialName("maxDurationSeconds") val maxDurationSeconds: Int = DEFAULT_MAX_DURATION_SECONDS,
) {
    /** The provider the host would use right now, or the first one when the stored id is gone. */
    val selectedProvider: SpeechProviderView?
        get() = providers.firstOrNull { it.id == selection.providerId } ?: providers.firstOrNull()

    /** Whether a recording can be transcribed at all on this host. */
    val usable: Boolean get() = providers.isNotEmpty()

    companion object {
        const val DEFAULT_MAX_AUDIO_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_DURATION_SECONDS = 120
    }
}

/**
 * One recognizer.
 *
 * [location] is `host-local` (the model runs on the host and may have to be prepared) or `cloud`
 * (the host calls out). [preparation] says whether a recording can go straight through, needs a
 * download first, or is busy with one.
 */
@Serializable
data class SpeechProviderView(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("location") val location: String? = null,
    /** Accepted language hints; `auto` means the provider detects the language itself. */
    @SerialName("languages") val languages: List<String> = emptyList(),
    /** Origins offered for an explicit preparation download; empty when there is no choice. */
    @SerialName("downloadSources") val downloadSources: List<String> = emptyList(),
    @SerialName("preparation") val preparation: SpeechPreparationView = SpeechPreparationView(),
) {
    /** Whether the model runs on the host, where preparing it is a download. */
    val hostLocal: Boolean get() = location == "host-local"
}

/**
 * The readiness of one recognizer.
 *
 * `ready` and `standby` both transcribe on demand; `unprepared` and `cancelled` need a `prepare`
 * first; `checking`, `loading`, `waking`, `downloading` and `cancelling` are transient; `failed`
 * carries a [message].
 */
@Serializable
data class SpeechPreparationView(
    @SerialName("phase") val phase: String = PHASE_READY,
    @SerialName("message") val message: String? = null,
    @SerialName("resource") val resource: String? = null,
    @SerialName("completedBytes") val completedBytes: Long? = null,
    @SerialName("totalBytes") val totalBytes: Long? = null,
) {
    /** A recording can be transcribed now, with at most a wake-up on the host side. */
    val transcribable: Boolean get() = phase == PHASE_READY || phase == PHASE_STANDBY

    /** The host is already working on it; a second `prepare` would only join that task. */
    val busy: Boolean get() = phase in BUSY_PHASES

    /** The last attempt ended in failure, and [message] says why. */
    val failed: Boolean get() = phase == PHASE_FAILED

    /** Nothing is loaded and nothing is running: a `prepare` is the way forward. */
    val needsPreparation: Boolean get() = phase == PHASE_UNPREPARED || phase == PHASE_CANCELLED

    companion object {
        const val PHASE_READY = "ready"
        const val PHASE_STANDBY = "standby"
        const val PHASE_UNPREPARED = "unprepared"
        const val PHASE_CANCELLED = "cancelled"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_CHECKING = "checking"
        const val PHASE_LOADING = "loading"
        const val PHASE_WAKING = "waking"
        const val PHASE_CANCELLING = "cancelling"
        const val PHASE_FAILED = "failed"
        private val BUSY_PHASES = setOf(PHASE_CHECKING, PHASE_LOADING, PHASE_WAKING, PHASE_DOWNLOADING, PHASE_CANCELLING)
    }
}

/** The persisted choice: which recognizer, and which language hint. */
@Serializable
data class SpeechSelectionView(
    @SerialName("providerId") val providerId: String = "",
    @SerialName("language") val language: String = "auto",
)

/**
 * The `speech/transcribe` answer.
 *
 * The transcript is returned to the caller only: the host writes no session event, so inserting it
 * into the draft is the client's job and nothing reaches the model until the user sends.
 */
@Serializable
data class SpeechTranscriptionView(
    @SerialName("text") val text: String = "",
    @SerialName("audioSeconds") val audioSeconds: Double = 0.0,
    @SerialName("inferenceSeconds") val inferenceSeconds: Double = 0.0,
)
