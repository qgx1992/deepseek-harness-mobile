package com.qgx1992.dshmobile.ui.screens.main

/**
 * When a finished turn's work folds away — the second axis of the desktop's "work details".
 *
 * The desktop keeps this next to the level itself, under `ui-chat.collapseTiming`, and the two are
 * genuinely independent: the level says *how much* folds, this says *when*. On a phone the
 * difference is the one that matters while reading a conversation:
 *
 * - [COMPLETION] folds the moment the answer lands, so a settled turn is always one header.
 * - [NEXT_INPUT] waits: the turn you just read keeps its steps on screen until you send the next
 *   message. Ask a follow-up and the turn above it folds, which is exactly when you stopped needing
 *   it.
 *
 * Only a *settled* turn has a choice to make — a running one is governed by
 * [TranscriptDetail.foldsLiveTurn] — and only the newest turn is the one whose next prompt has not
 * arrived yet, so that is the only turn [NEXT_INPUT] holds open.
 *
 * The ids are the desktop's stored values; never rename one.
 */
internal enum class CollapseTiming(
    /** Value stored in the preferences; what an update reads back. */
    val id: String,
) {
    /** Fold as soon as the turn settles. The desktop's default, and this app's. */
    COMPLETION("completion"),

    /** Keep the newest finished turn open until a newer one starts. */
    NEXT_INPUT("next-input"),
    ;

    /**
     * Whether a settled turn folds now.
     *
     * @param hasNewerTurn whether any turn started after this one — false only for the newest turn
     *   in the transcript, which is the turn [NEXT_INPUT] leaves open.
     */
    fun foldsSettledTurn(hasNewerTurn: Boolean): Boolean = when (this) {
        COMPLETION -> true
        NEXT_INPUT -> hasNewerTurn
    }

    companion object {
        val DEFAULT: CollapseTiming = COMPLETION

        /** Reads a stored value; anything unrecognised (or absent) falls back to [DEFAULT]. */
        fun of(value: String?): CollapseTiming {
            val stored = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == stored } ?: DEFAULT
        }
    }
}
