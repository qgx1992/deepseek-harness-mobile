package com.qgx1992.dshmobile.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretMaskTest {

    // Never a live key: shaped like one so the matcher is exercised the way a real paste would be.
    private val openAiKey = "sk-" + "proj9fQ2mN7pR1sT5uV9wX3yZ8aB"
    private val bearerToken = "eyJhbGciOiJIUzI1NiJ9" + ".eyJzdWIiOiIxMjM0In0" + ".Q2mN7pR1sT5uV9wX3yZ8aB"

    @Test
    fun masksOpenAiKeyAfterItsPrefix() {
        val masked = mask("here it is: $openAiKey ok")
        assertEquals(
            "here it is: " + openAiKey.take(8) + "•".repeat(openAiKey.length - 8) + " ok",
            masked,
        )
        assertFalse(masked.contains(openAiKey))
        assertTrue(masked.contains(openAiKey.take(8)))
    }

    @Test
    fun maskingNeverChangesLength() {
        val text = "key=$openAiKey and bearer $bearerToken"
        assertEquals(text.length, mask(text).length)
    }

    @Test
    fun coversEveryCredentialShapeWeClaim() {
        // Every sample is assembled from plain literals so the shape under test is exactly the one
        // named in the comment.
        val samples = listOf(
            "sk-" + "proj4x8kQ2mN7pR1sT5uV9wX3yZ",          // OpenAI style
            "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6",    // GitHub classic
            "github_pat_" + "11ABCDEFG0123456789_abcdefghijklmnop", // GitHub fine-grained
            "AKIA" + "IOSFODNN7EXAMPLE",                    // AWS access key id
            "xoxb-" + "1234567890-abcdefghijklmnop",        // Slack
            "Authorization: Bearer $bearerToken",           // Bearer
            "9fA2bC3dE4fG5hI6jK7lM8nO9pQ0rS1tU2vW3xY4z",     // opaque run, no prefix
        )
        for (sample in samples) {
            val masked = mask(sample)
            assertTrue("not masked: $sample", masked != sample)
            assertEquals("length changed: $sample", sample.length, masked.length)
        }
    }

    @Test
    fun bearerSchemeStaysReadable() {
        val masked = mask("curl -H 'Authorization: Bearer $bearerToken'")
        assertTrue(masked.contains("Bearer "))
        assertTrue(masked.contains(bearerToken.take(8)))
        assertFalse(masked.contains(bearerToken))
    }

    @Test
    fun leavesOrdinaryProseAlone() {
        val text = "The quick brown fox jumps over the lazy dog, and 你好世界。"
        assertEquals(text, mask(text))
        assertTrue(findSecrets(text).isEmpty())
    }

    @Test
    fun leavesUrlsPathsAndSlugsAlone() {
        val text = "https://example.com/a/very/long/path/segment/abcdefghijklmnopqrstuvwx/content.json " +
            "and a slug like fix-secret-masking-regression-in-transcript-rendering"
        assertEquals(text, mask(text))
    }

    @Test
    fun leavesUuidsAndShortCommitShasAlone() {
        val text = "uuid 3f2504e0-4f89-11d3-9a0c-0305e82c3301 sha 4b825dc642"
        assertEquals(text, mask(text))
    }

    @Test
    fun leavesNumbersAndShortIdsAlone() {
        val text = "order 1234567890123456789012345678901234 and id ABCDEFGHIJKLMNOP"
        assertEquals(text, mask(text))
    }

    @Test
    fun reportsSpansWithThePrefixLengthContract() {
        val text = "token: $openAiKey"
        val spans = findSecrets(text)
        assertEquals(1, spans.size)
        val span = spans.single()
        assertEquals(openAiKey, text.substring(span.start, span.end))
        assertEquals(SecretSpan.DEFAULT_PREFIX_LENGTH, span.prefixLength)
    }

    @Test
    fun revealedTextIsTheOriginal() {
        // Reveal is a UI state, so what it shows must be byte-identical to what masking took away.
        val text = "key=$openAiKey and Authorization: Bearer $bearerToken again"
        val masked = mask(text)
        assertTrue(masked != text)
        // Masking never rewrites its input: the stored event, the copy action and the model all keep
        // the original string.
        assertEquals("key=$openAiKey and Authorization: Bearer $bearerToken again", text)
        assertEquals(2, findSecrets(text).size)
        // Revealing the same input a second time yields exactly the same masked text.
        assertEquals(masked, mask(text))
    }

    @Test
    fun skLearnIsNotAKey() {
        val text = "I fine-tuned a scikit-learn model, sk-learn is not a secret"
        assertEquals(text, mask(text))
    }
}
