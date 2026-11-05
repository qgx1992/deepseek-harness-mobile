package com.qgx1992.dshmobile.core.wire

import com.qgx1992.dshmobile.core.wire.dto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Harness021Test {
    @Test fun `all transport routes remain under a proxy prefix`() {
        for (route in listOf("/", "/api/host", "/api/remote-stream", "/api/file?x=1")) {
            assertEquals("https://example.com/harness/team" + route, resolveHarnessUrl("https://example.com/harness/team/?token=discard#fragment", route).toString())
        }
        assertEquals("http://[::1]:3080/api/host", resolveHarnessUrl("http://[::1]:3080", "/api/host").toString())
        assertThrows(IllegalArgumentException::class.java) { resolveHarnessUrl("https://host/prefix", "/../escape") }
        assertThrows(IllegalArgumentException::class.java) { resolveHarnessUrl("https://host/prefix", "//evil") }
    }
    @Test fun `calendar editing retains explicit zones and ISO weekdays`() {
        val weekly = automationTiming("weekly", "10:15:00", "Asia/Bangkok", "1,7")["weekly"]!!.jsonObject
        assertEquals("Asia/Bangkok", weekly["time_zone"]!!.jsonPrimitive.content)
        assertEquals(listOf(1,7), weekly["weekdays"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertThrows(IllegalArgumentException::class.java) { automationTiming("weekly", "10:00", "UTC", "1,1") }
        assertThrows(IllegalArgumentException::class.java) { automationTiming("every", "59", "", "") }
        assertThrows(Exception::class.java) { automationTiming("at", "2026-10-10T12:00:00", "", "") }
    }
    @Test fun `streamed arguments show complete and partial strings but ignore nested names`() {
        assertEquals("work/a", partialArgumentStrings("""{"path":"work/a""")["path"]!!.jsonPrimitive.content)
        assertEquals("hello\nworld", partialArgumentStrings("""{"description":"hello\nworld","nested":{"path":"bad"},"more":"x""")["description"]!!.jsonPrimitive.content)
        assertFalse(partialArgumentStrings("""{"nested":{"path":"bad"}}""").containsKey("path"))
        assertEquals("abc", partialArgumentStrings("""{"path":"abc\u0""")["path"]!!.jsonPrimitive.content)
    }
    @Test fun `expected schedule is the complete stored record without catalogue decorations`() {
        val task = AutomationTask("t", "weekly", "test", "prompt", "2099-01-01T00:00:00Z", sessionId = "s", time = "10:00:00.000", timeZone = "UTC", weekdays = listOf(1,3),
            target = AutomationTarget(kind = "session", sessionId = "s"), involvedSessionIds = listOf("s"), updatedAt = "2026-01-01T00:00:00Z", executionActive = true)
        val expected = task.expectedRecord()
        assertFalse(expected.containsKey("sessionId")); assertFalse(expected.containsKey("status")); assertFalse(expected.containsKey("lastDelivery"))
        assertFalse(expected.containsKey("target")); assertFalse(expected.containsKey("involvedSessionIds")); assertFalse(expected.containsKey("executionActive"))
        assertEquals("2026-01-01T00:00:00Z", expected["updatedAt"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(3))), expected["weekdays"])
    }
    @Test fun `a task reads its destination from the target before the flat session id`() {
        assertEquals("nested", AutomationTask("t", "every", "n", "p", "2099-01-01T00:00:00Z",
            target = AutomationTarget(kind = "session", sessionId = "nested")).destinationSessionId)
        assertEquals("flat", AutomationTask("t", "every", "n", "p", "2099-01-01T00:00:00Z", sessionId = "flat").destinationSessionId)
        assertEquals("", AutomationTask("t", "every", "n", "p", "2099-01-01T00:00:00Z",
            target = AutomationTarget(kind = "new")).destinationSessionId)
    }
    @Test fun `a schedule edit answer reads as a stored record a deletion or a refusal`() {
        val updated = automationMutation(WireJson.parseToJsonElement(
            """{"id":"t","kind":"every","title":"n","prompt":"p","scheduledAt":"2099-01-01T00:00:00Z","status":"active","target":{"kind":"session","sessionId":"s"},"updatedAt":"2026-01-01T00:00:00Z"}""",
        ))
        assertEquals(true, updated.updated)
        assertEquals("s", updated.record?.destinationSessionId)
        assertEquals(true, automationMutation(WireJson.parseToJsonElement("""{"id":"t","deleted":true}""")).deleted)
        assertEquals("schedule_conflict", automationMutation(WireJson.parseToJsonElement("""{"id":"t","code":"schedule_conflict"}""")).code)
        assertEquals("schedule_not_found", automationMutation(WireJson.parseToJsonElement("""{"id":"t","code":"schedule_not_found"}""")).code)
        // The pre-0.2.2 pair wrapped the stored row in `{updated, record}` rather than returning it bare.
        val legacy = automationMutation(WireJson.parseToJsonElement(
            """{"id":"t","updated":true,"record":{"id":"t","kind":"every","title":"n","prompt":"p","scheduledAt":"2099-01-01T00:00:00Z","sessionId":"s"}}""",
        ))
        assertEquals(true, legacy.updated)
        assertEquals("s", legacy.record?.destinationSessionId)
    }
    @Test fun `legacy question requests still decode without a wait`() {
        assertNull(WireJson.decodeFromString(AskUserQuestionRequestEvent.serializer(), """{"questions":[]}""").wait)
        assertEquals(QuestionWait("call", true), WireJson.decodeFromString(AskUserQuestionRequestEvent.serializer(), """{"questions":[],"wait":{"callId":"call","timed":true}}""").wait)
    }
    @Test fun `an attachment read sends the whole stored reference`() {
        val ref = ImageAttachmentRef(attachmentId = "sha256:abc", mediaType = "image/png", bytes = 12, width = 3, height = 4, name = "shot.png")
        val encoded = WireJson.encodeToString(SessionAttachmentRequest.serializer(), SessionAttachmentRequest("s1", ref))
        assertEquals(
            """{"sessionId":"s1","attachment":{"attachmentId":"sha256:abc","mediaType":"image/png","bytes":12,"width":3,"height":4,"name":"shot.png"}}""",
            encoded,
        )
        // The pre-0.2 shape stays flat so a host that predates the nested reference still answers.
        assertEquals(
            """{"sessionId":"s1","attachmentId":"sha256:abc"}""",
            WireJson.encodeToString(SessionAttachmentLegacyRequest.serializer(), SessionAttachmentLegacyRequest("s1", "sha256:abc")),
        )
    }
}
