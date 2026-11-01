package com.qgx1992.dshmobile.ui.screens.main

/**
 * How much of the work a turn did the transcript shows by default.
 *
 * The desktop Web client calls this "work details" and stores it under `ui-chat.transcriptView`;
 * this is the same four points on the same axis, resolved into the four questions a phone transcript
 * actually has to answer:
 *
 * - does a *finished* turn fold its steps into one process row ([foldsCompletedTurns]);
 * - does the turn that is *running* fold too, or show its steps as they happen ([foldsLiveTurn]);
 * - do the steps inside an opened process row start behind their own headers, or already open
 *   ([expandsHistoryGroups]);
 * - is the last thought of a finished turn repeated above the fold as a one-line preview
 *   ([previewsReasoning]).
 *
 * Every mode keeps the final answer, the user's own messages and injected context reachable: the
 * fold only ever hides *work*, never what was said or what was answered.
 *
 * The ids are the durable values written to [com.qgx1992.dshmobile.connection.AppSettings]; the
 * desktop's retired `normal` / `expanded` values read as [DETAILED] so a half-migrated preference
 * never lands on a mode that shows nothing.
 */
internal enum class TranscriptDetail(
    /** Value stored in the preferences; never rename one — it is what an update reads back. */
    val id: String,
    /** A completed turn's steps fold into one process row. */
    val foldsCompletedTurns: Boolean,
    /** The turn still running folds as well; false shows its steps as they land. */
    val foldsLiveTurn: Boolean,
    /** Steps inside an opened process row start expanded — history, so it is already written. */
    val expandsHistoryGroups: Boolean,
    /** A finished turn repeats its last thought as one line above the fold. */
    val previewsReasoning: Boolean,
) {
    /**
     * The least the transcript can say: one row per turn however much work it did, and no preview.
     * Matches the desktop's "compact".
     */
    COMPACT(
        id = "compact",
        foldsCompletedTurns = true,
        foldsLiveTurn = true,
        expandsHistoryGroups = false,
        previewsReasoning = false,
    ),

    /**
     * One row per turn, but the live turn still counts and a finished turn keeps one line of its
     * reasoning. Matches the desktop's "standard".
     */
    STANDARD(
        id = "standard",
        foldsCompletedTurns = true,
        foldsLiveTurn = true,
        expandsHistoryGroups = false,
        previewsReasoning = true,
    ),

    /**
     * Finished turns fold, the running turn shows its steps as they happen, and an opened process
     * row is already open all the way down. Matches the desktop's "detailed", which is what the
     * desktop Web client defaults to.
     */
    DETAILED(
        id = "detailed",
        foldsCompletedTurns = true,
        foldsLiveTurn = false,
        expandsHistoryGroups = true,
        previewsReasoning = true,
    ),

    /**
     * The default. Nothing folds: every step is its own row in the order it happened, so the
     * transcript reads the way a desktop transcript does and no turn hides its work behind a
     * process header. Matches the desktop's "verbose".
     */
    VERBOSE(
        id = "verbose",
        foldsCompletedTurns = false,
        foldsLiveTurn = false,
        expandsHistoryGroups = true,
        previewsReasoning = true,
    ),
    ;

    /** Whether grouping adjacent tool calls behind one header is worth doing in this mode. */
    val groupsSteps: Boolean get() = this != VERBOSE

    companion object {
        /**
         * No fold by default: the transcript lists the work step by step, the way the desktop
         * client does at its own default, instead of collapsing each finished turn into a single
         * process row. Every other level is one tap away in Settings.
         */
        val DEFAULT: TranscriptDetail = VERBOSE

        /** Reads a stored value; anything unrecognised (or absent) falls back to [DEFAULT]. */
        fun of(value: String?): TranscriptDetail {
            val stored = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == stored }
                // The desktop's two-mode generation of this setting; read as detailed, never written.
                ?: if (stored == "normal" || stored == "expanded") DETAILED else DEFAULT
        }
    }
}
