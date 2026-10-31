package com.qgx1992.dshmobile.core.text

/**
 * One secret found in a piece of text, by character offsets.
 *
 * [prefixLength] is how much of the secret stays readable. Eight characters is enough for a reader
 * to recognise *which* key they pasted and useless to anyone reading over their shoulder.
 */
data class SecretSpan(
    val start: Int,
    val end: Int,
    val prefixLength: Int = DEFAULT_PREFIX_LENGTH,
) {
    init {
        require(start >= 0) { "start < 0: $start" }
        require(end >= start) { "end < start: $start..$end" }
    }

    val length: Int get() = end - start

    companion object {
        /** Characters kept in clear: `sk-abc12`, `ghp_AbCd` — distinguishable, not usable. */
        const val DEFAULT_PREFIX_LENGTH = 8
    }
}

/** The character a mask is drawn with. One mask character per hidden character. */
const val SECRET_MASK_CHAR = '•'

/**
 * Finds credential-shaped substrings in [text] — the ones a reader pastes into a chat without
 * thinking, which then sit in a transcript for good.
 *
 * This is a *display* filter. It never rewrites the message: the text sent to the model, the text
 * copied from the bubble and the stored event all keep the original. Only drawing goes through
 * [mask], which is the same contract the desktop client's `secret-mask` uses.
 *
 * The rules are deliberately anchored on a vendor prefix wherever one exists, because the generic
 * last rule (a long opaque run) is the one that can misfire. Everything a reader could mistake for a
 * secret — a word, a path inside a URL, a UUID, a short commit sha, a run of digits, Chinese prose —
 * is covered by a test that asserts it comes back untouched.
 */
private val PREFIXED_SECRETS = listOf(
    // OpenAI / Anthropic / OpenRouter style: `sk-` and anything after it, long enough to be a key
    // rather than a word ("sk-learn" is not a secret).
    Regex("""\bsk-[A-Za-z0-9_\-]{16,}"""),
    // GitHub tokens, classic and fine-grained.
    Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"""),
    Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
    // AWS access key ids: fixed shape, so this one can be exact.
    Regex("""\bAKIA[0-9A-Z]{16}\b"""),
    // Slack tokens.
    Regex("""\bxox[baprs]-[A-Za-z0-9\-]{10,}"""),
)

/** `Authorization: Bearer <token>` — only the token is secret, not the scheme. */
private val BEARER_SECRET = Regex("""(?i)\bbearer[ \t]+([A-Za-z0-9._~+/=\-]{12,})""")

/**
 * The catch-all: a long, unbroken run of base64/base64url/hex characters.
 *
 * [genericSecretCandidate] applies the human judgement a regex cannot — a digit and a letter in the
 * same run, no path separator before it — and only then is the run kept.
 */
private val GENERIC_SECRET = Regex("""[A-Za-z0-9+/=_-]{32,}""")

/** A canonical UUID: 36 characters of hyphens and hex, and never a credential. */
private val UUID_RUN = Regex(
    """[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""",
)

/** Characters that may not touch a secret on either side: a word, a path segment, a dotted name. */
private fun isWordish(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '/'

/**
 * Whether a run matched by [GENERIC_SECRET] is plausibly a secret rather than prose or a link.
 *
 * Rejects: runs glued to a path (`/`, `.`, `-`, `_` immediately before — a slug or a filename),
 * runs with no digit (an English or pinyin word, or a Chinese text rendered in ASCII pinyin), runs
 * that are all digits (a phone number, an id), and runs glued to a preceding letter or digit.
 */
private fun genericSecretCandidate(text: String, start: Int, end: Int): Boolean {
    if (start > 0) {
        val before = text[start - 1]
        if (isWordish(before)) return false
        // `=`, `+` continue a base64 payload; everything else up to here is a real boundary.
        if (before == '=' || before == '+') return false
    }
    if (end < text.length) {
        val after = text[end]
        if (isWordish(after)) return false
    }
    val run = text.substring(start, end)
    // A UUID is 36 characters of hex and hyphens, which clears the length bar for the opaque-run
    // rule. It is an identifier, it is everywhere in logs and issue text, and masking it would make
    // the one rule that has to be trusted look arbitrary.
    if (UUID_RUN.matches(run)) return false
    var hasDigit = false
    var hasLetter = false
    for (c in run) {
        when {
            c.isDigit() -> hasDigit = true
            c.isLetter() -> hasLetter = true
            else -> Unit
        }
    }
    // A pure-digit run is an id or a number; a run with letters but no digits is a word. A secret
    // is long *and* mixed, which is what makes the false-positive rate here acceptable.
    return hasDigit && hasLetter
}

/**
 * Every secret in [text], in order and without overlap.
 *
 * Overlaps happen: `sk-…` is also a long opaque run, and a bearer token can look prefixed. Taking
 * the union of the two is the safe reading — hiding a little more of a secret is never wrong.
 */
fun findSecrets(text: String): List<SecretSpan> {
    if (text.isEmpty()) return emptyList()
    val found = ArrayList<SecretSpan>()
    for (pattern in PREFIXED_SECRETS) {
        for (match in pattern.findAll(text)) {
            found += SecretSpan(match.range.first, match.range.last + 1)
        }
    }
    for (match in BEARER_SECRET.findAll(text)) {
        val token = match.groups[1] ?: continue
        found += SecretSpan(token.range.first, token.range.last + 1)
    }
    for (match in GENERIC_SECRET.findAll(text)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (genericSecretCandidate(text, start, end)) found += SecretSpan(start, end)
    }
    if (found.isEmpty()) return emptyList()
    found.sortWith(compareBy({ it.start }, { it.end }))
    val merged = ArrayList<SecretSpan>(found.size)
    for (span in found) {
        val last = merged.lastOrNull()
        if (last != null && span.start < last.end) {
            if (span.end > last.end) merged[merged.lastIndex] = last.copy(end = span.end)
        } else {
            merged += span
        }
    }
    return merged
}

/**
 * [text] with every character after a secret's readable prefix replaced by [SECRET_MASK_CHAR].
 *
 * Same length as the input, character for character: a transcript that reflows when a key is
 * revealed is a transcript that jumps under the reader's finger. The readable prefix is kept so the
 * reader can tell which key it was.
 */
fun mask(text: String): String {
    val spans = findSecrets(text)
    if (spans.isEmpty()) return text
    val out = StringBuilder(text)
    for (span in spans) {
        val from = span.start + span.prefixLength
        for (i in from until span.end) out.setCharAt(i, SECRET_MASK_CHAR)
    }
    return out.toString()
}

/** Whether [text] holds anything [mask] would hide — lets a caller skip the work when it does. */
fun containsSecret(text: String): Boolean = findSecrets(text).isNotEmpty()
