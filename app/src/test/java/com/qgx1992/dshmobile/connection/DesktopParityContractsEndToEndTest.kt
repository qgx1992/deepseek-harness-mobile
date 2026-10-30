package com.qgx1992.dshmobile.connection

import com.qgx1992.dshmobile.core.wire.*
import com.qgx1992.dshmobile.core.wire.dto.*
import com.qgx1992.dshmobile.mockharness.MarketScenario
import com.qgx1992.dshmobile.mockharness.MockHarness
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/**
 * The three desktop-parity namespaces, over real HTTP against the mock gateway.
 *
 * The mock refuses what the host refuses — a missing `sort`/`offset`/`limit` in a market query, a
 * recording that is not canonical base64, an index URL that is not http(s) — so a green run here
 * means the client sent the shape the host's descriptors declare, not merely that it compiled.
 */
class DesktopParityContractsEndToEndTest {
    /** Unwrap a call, reporting the host's own error code instead of a bare cast failure. */
    private fun <T> RpcResult<T>.ok(): T = when (this) {
        is RpcResult.Ok -> value
        is RpcResult.Err -> fail("expected a value, got ${error.code}: ${error.message}") as Nothing
    }

    /** The error a call produced, which is the assertion most of these tests are making. */
    private fun <T> RpcResult<T>.failure(): String = when (this) {
        is RpcResult.Ok -> fail("expected a failure, got $value") as Nothing
        is RpcResult.Err -> error.code
    }

    /** A canonical WAV-shaped blob: a 44-byte header plus payload, which is all the mock inspects. */
    private fun wav(payload: Int = 256): ByteArray {
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        header[40] = (payload and 0xFF).toByte()
        return header + ByteArray(payload)
    }

    private fun encoded(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test fun speechNamespaceTranscribesAndRefusesWhatTheHostRefuses() = runBlocking {
        val harness = MockHarness()
        val scenario = MarketScenario(harness)
        val port = harness.start()
        try {
            val api = DshApiClient(OkHttpRpcTransport("http://127.0.0.1:$port", OkHttpClient(), 5000, 5000))

            val catalog = api.speechCatalog().ok()
            assertEquals(2, catalog.providers.size)
            assertEquals(MarketScenario.PROVIDER_LOCAL, catalog.selectedProvider?.id)
            assertEquals(4 * 1024 * 1024, catalog.maxAudioBytes)
            assertEquals(120, catalog.maxDurationSeconds)
            assertTrue(catalog.usable)
            assertTrue(catalog.providers.first().preparation.transcribable)

            val transcript = api.speechTranscribe(encoded(wav())).ok()
            assertEquals(MarketScenario.TRANSCRIPT, transcript.text)
            assertEquals(1.5, transcript.audioSeconds, 0.0001)
            assertEquals(1, scenario.transcriptions.size)
            assertTrue(scenario.transcriptions.single().containsKey("audioBase64"))

            // A recording the host cannot decode is a transcription failure, not an audio-size one.
            assertEquals("speech/transcription-failed", api.speechTranscribe("not base64 at all").failure())
            // Something that decodes but is too short to be a WAV is invalid audio instead.
            assertEquals("speech/invalid-audio", api.speechTranscribe(encoded(ByteArray(8))).failure())

            // Preparation and preferences are void remotes: the answer is the absence of a failure.
            api.speechPrepare(MarketScenario.PROVIDER_LOCAL).ok()
            api.speechCancelPreparation(MarketScenario.PROVIDER_LOCAL).ok()
            api.speechConfigure(language = "zh").ok()
            assertEquals("zh", api.speechCatalog().ok().selection.language)
        } finally {
            harness.stop()
        }
    }

    @Test fun marketSearchesPagesAndInstallsNothing() = runBlocking {
        val harness = MockHarness()
        val scenario = MarketScenario(harness)
        val port = harness.start()
        try {
            val api = DshApiClient(OkHttpRpcTransport("http://127.0.0.1:$port", OkHttpClient(), 5000, 5000))

            val sources = api.marketIndexes().ok()
            assertEquals(1, sources.size)
            assertTrue(sources.single().usable)
            // The title arrives in the per-language object form; the reader's language wins.
            assertEquals("示例市场", sources.single().title?.pick("zh-CN"))
            assertEquals("Fixture market", sources.single().title?.pick("fr"))

            val first = api.marketSearch(MarketScenario.MARKET_URL, MarketQuery(limit = 2)).ok()
            assertEquals(4, first.total)
            assertEquals(2, first.entries.size)
            assertEquals(2, first.nextOffset)
            assertEquals("Interface", first.metadata.categoryTitle("ui")?.pick("en"))

            // Every version carries the spec and the host's own compatibility verdict.
            val relay = api.marketSearch(MarketScenario.MARKET_URL, MarketQuery(text = "relay")).ok().entries.single()
            assertEquals("dsh-relay@0.2.1", relay.installable?.install?.spec)
            assertFalse(relay.installation.canInstall)
            assertEquals(MarketInstallationView.STATUS_PROVIDED, relay.installation.status)

            val second = api.marketSearch(MarketScenario.MARKET_URL, MarketQuery(offset = first.nextOffset!!, limit = 2)).ok()
            assertEquals(2, second.entries.size)
            assertNull(second.nextOffset)

            // A plugin this host cannot load still lists, with the reason and no default version.
            val archify = api.marketSearch(MarketScenario.MARKET_URL, MarketQuery(text = "archify")).ok().entries.single()
            assertNull(archify.defaultVersion)
            assertFalse(archify.installableHere)
            assertEquals(">=0.2.1-rc.5", archify.versions.single().compatibility.requirements["engines.dsh"])

            assertEquals(1, api.marketSearch(MarketScenario.MARKET_URL, MarketQuery(labels = listOf("experimental"))).ok().total)
            assertEquals(3, api.marketSearch(MarketScenario.MARKET_URL, MarketQuery(category = "ui")).ok().total)

            val newest = api.marketSearch(MarketScenario.MARKET_URL, MarketQuery(sort = MarketSort.NEWEST)).ok()
            assertEquals("dsh-relay", newest.entries.first().name)

            // The query the client writes always carries the three members the host requires.
            val sent = scenario.searches.last().second
            assertTrue(sent.containsKey("sort"))
            assertTrue(sent.containsKey("offset"))
            assertTrue(sent.containsKey("limit"))

            // A source that is not http(s), or is listed twice, is refused by the host and reported.
            assertEquals("plugin-market/invalid-index-url", api.marketSetIndexes(listOf("ftp://x.invalid/i.json")).failure())
            assertEquals(
                "plugin-market/invalid-index-url",
                api.marketSetIndexes(listOf(MarketScenario.MARKET_URL, MarketScenario.MARKET_URL)).failure(),
            )
            api.marketSetIndexes(listOf("https://other.invalid/index.json")).ok()
            assertEquals("https://other.invalid/index.json", api.marketIndexes().ok().single().url)
            // A market that is no longer configured is refused rather than searched.
            assertEquals("plugin-market/unknown-index", api.marketSearch(MarketScenario.MARKET_URL, MarketQuery()).failure())
        } finally {
            harness.stop()
        }
    }

    @Test fun agentTeamProjectionFoldsIntoMembersAndBoardView() {
        val team = WireJson.decodeFromJsonElement(AgentTeamView.serializer(), MarketScenario.agentTeamValue())
        assertEquals(3, team.members.size)
        assertEquals("lead", team.lead?.name)
        assertTrue(team.lead!!.isLead)
        assertEquals(listOf("sidebar-scout", "test-writer"), team.teammates.map { it.name })
        assertTrue(team.teammates.last().provisioning)
        assertFalse(team.failed)

        assertEquals(3, team.tasks.size)
        val port = team.tasks.first { it.id == "task-2" }
        assertTrue(port.inProgress)
        assertEquals("sidebar-scout", port.ownerName)
        assertEquals(listOf("task-1"), port.blockedBy)
        assertEquals(1, port.writeScopeWarnings.size)
        assertTrue(team.tasks.first { it.id == "task-3" }.blocked)
        assertTrue(team.tasks.first { it.id == "task-1" }.completed)
        assertEquals(2, team.openTasks.size)
        assertEquals("test-writer", team.member("test-writer")?.name)
        assertNull(team.member("nobody"))
    }

    @Test fun aTeamThatFailedToStartIsTerminal() {
        val failed = WireJson.decodeFromJsonElement(
            AgentTeamView.serializer(),
            WireJson.parseToJsonElement("""{"members":[],"tasks":[],"failure":"durable session storage is unavailable"}"""),
        )
        assertTrue(failed.failed)
        assertTrue(failed.members.isEmpty())
        assertTrue(failed.openTasks.isEmpty())
    }
}
