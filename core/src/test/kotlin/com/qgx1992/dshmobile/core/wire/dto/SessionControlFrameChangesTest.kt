package com.qgx1992.dshmobile.core.wire.dto

import com.qgx1992.dshmobile.core.wire.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `session/control` projection frames from dsh-v0.2.2-rc.3.
 *
 * The 0.2.2 line batched the deltas: what was one `{key, value}` frame is now a single frame
 * carrying a `changes` array of `{key, val}` entries (`SessionProjectionUpdate.changes`,
 * `control.ts:22-30`). The client must fan the batch out, and must still read the old single shape
 * a pre-0.2.2 host sends.
 */
class SessionControlFrameChangesTest {
    private fun decode(raw: String) = WireJson.decodeFromJsonElement(
        SessionControlFrame.serializer(),
        WireJson.parseToJsonElement(raw),
    )

    @Test
    fun `a batched rc2 projection frame keeps every change`() {
        val frame = decode(
            """{"type":"projection","sessionId":"s1","seq":7,"changes":[
               {"key":"inbox","val":{"next-turn":[],"next-step":[]}},
               {"key":"agentPreset","val":{"preset":"careful"}}]}""",
        )
        assertTrue(frame is SessionControlFrame.Projection)
        frame as SessionControlFrame.Projection
        assertEquals(listOf("inbox", "agentPreset"), frame.changes.map { it.key })
        assertEquals("""{"preset":"careful"}""", frame.changes[1].value.toString())
    }

    @Test
    fun `a legacy single key frame still decodes to one change`() {
        val frame = decode(
            """{"type":"projection","sessionId":"s1","key":"inbox","value":{"next-turn":[],"next-step":[]},"seq":9}""",
        )
        assertTrue(frame is SessionControlFrame.Projection)
        frame as SessionControlFrame.Projection
        assertEquals(9, frame.seq)
        assertEquals(listOf("inbox"), frame.changes.map { it.key })
    }

    @Test
    fun `a value spelling of the change payload is accepted`() {
        val frame = decode(
            """{"type":"projection","sessionId":"s1","seq":1,"changes":[{"key":"inbox","value":{}}]}""",
        )
        frame as SessionControlFrame.Projection
        assertEquals(1, frame.changes.size)
    }
}
