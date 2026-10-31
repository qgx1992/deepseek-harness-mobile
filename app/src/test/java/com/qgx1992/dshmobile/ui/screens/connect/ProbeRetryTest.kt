package com.qgx1992.dshmobile.ui.screens.connect

import com.qgx1992.dshmobile.connection.ProbeOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which probe verdicts are worth a second attempt.
 *
 * The retry was added for a cold start whose first request dies on a radio that is still attaching.
 * A refusal is not that: it is the far end answering, and asking again changes nothing but the time
 * the user spends looking at a spinner before being told to pair.
 */
class ProbeRetryTest {

    @Test
    fun `misses a network can produce are retried`() {
        assertTrue(ProbeOutcome.Timeout.retryable())
        assertTrue(ProbeOutcome.Unreachable.retryable())
        assertTrue(ProbeOutcome.Refused.retryable())
        assertTrue(ProbeOutcome.TlsFailure.retryable())
        assertTrue(ProbeOutcome.Other("socket closed").retryable())
    }

    @Test
    fun `answers that end the conversation are not`() {
        assertFalse(ProbeOutcome.PairingRequired.retryable())
        assertFalse(ProbeOutcome.CertificateChanged.retryable())
        assertFalse(ProbeOutcome.TrustFence.retryable())
        assertFalse(ProbeOutcome.Unauthenticated.retryable())
        assertFalse(ProbeOutcome.RelayUnauthenticated.retryable())
        assertFalse(ProbeOutcome.NotAHarness.retryable())
        assertFalse(ProbeOutcome.DnsFailure.retryable())
        assertFalse(ProbeOutcome.Reachable(description = null).retryable())
    }
}
