package com.qgx1992.dshmobile.connection

import com.qgx1992.dshmobile.core.wire.DshApiClient
import com.qgx1992.dshmobile.core.wire.OkHttpRpcTransport
import com.qgx1992.dshmobile.core.wire.RpcResult
import com.qgx1992.dshmobile.core.wire.dto.SettingsNamespaceView
import com.qgx1992.dshmobile.core.wire.dto.SettingsPathOpView
import com.qgx1992.dshmobile.core.wire.dto.SettingsSecretView
import com.qgx1992.dshmobile.mockharness.MockHarness
import com.qgx1992.dshmobile.mockharness.RpcErrorData
import com.qgx1992.dshmobile.mockharness.SettingsScenario
import com.qgx1992.dshmobile.ui.screens.settings.HostSettingKind
import com.qgx1992.dshmobile.ui.screens.settings.HostSettingsPresentation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The settings plane over real HTTP, plus the schema-to-form mapping every row goes through.
 *
 * The mock refuses what the host refuses — a write without its CAS revision, a write against a
 * revision that moved, a credential reference that is not identifier-shaped — so a green run here
 * means the client sent the shape `dsh-api-settings-controller` accepts, not merely that it
 * compiled. The presentation half runs without a socket: it is the piece that decides what the form
 * draws, and it has to survive documents it was not written for.
 */
class SettingsContractsEndToEndTest {
    /** Unwrap a call, reporting the host's own error code instead of a bare cast failure. */
    private fun <T> RpcResult<T>.ok(): T = when (this) {
        is RpcResult.Ok -> value
        is RpcResult.Err -> fail("expected a value, got ${error.code}: ${error.message}") as Nothing
    }

    /** The failure a call produced, plus its message when the assertion cares about the wording. */
    private fun <T> RpcResult<T>.failure(): com.qgx1992.dshmobile.core.wire.RpcError = when (this) {
        is RpcResult.Ok -> fail("expected a failure, got $value") as Nothing
        is RpcResult.Err -> error
    }

    /** Run [block] against a freshly started harness with the settings fixture registered. */
    private fun withSettings(block: suspend (DshApiClient, SettingsScenario) -> Unit) = runBlocking {
        val harness = MockHarness()
        val scenario = SettingsScenario(harness)
        val port = harness.start()
        try {
            block(DshApiClient(OkHttpRpcTransport("http://127.0.0.1:$port", OkHttpClient(), 5000, 5000)), scenario)
        } finally {
            harness.stop()
        }
    }

    // ------------------------------------------------------------------ wire contracts

    @Test
    fun describeCarriesSchemaValueAndSecretFlagsButNoSecretValue() = withSettings { api, _ ->
        val document = api.settingsDescribe().ok()
        assertTrue(document.writable)
        assertTrue(document.hasDocument)
        assertEquals(listOf(SettingsScenario.WEB_SEARCH, SettingsScenario.AGENT, SettingsScenario.LLM), document.namespaces.map { it.ns })

        val web = document.namespaces.first { it.ns == SettingsScenario.WEB_SEARCH }
        assertEquals(1L, web.revision)
        assertEquals("live", web.applies)
        assertEquals(listOf(listOf("apiKey")), web.secrets.map { it.path })
        assertFalse(web.secrets.single().set)

        // Redacted: the secret is a flag on the sidecar, never a member of the value.
        assertNull((web.value as JsonObject)["apiKey"])
        assertFalse(web.value.toString().contains(SettingsScenario.API_KEY_VALUE))
    }

    @Test
    fun mutateWritesAScalarAndComesBackWithTheFreshView() = withSettings { api, scenario ->
        val before = scenario.revision(SettingsScenario.WEB_SEARCH)
        val view = api.settingsMutate(
            SettingsScenario.WEB_SEARCH,
            listOf(SettingsPathOpView.Set(listOf("maxResults"), JsonPrimitive(9))),
            before.toInt(),
        ).ok()

        assertEquals(before + 1, view.revision)
        assertEquals(9, scenario.value(SettingsScenario.WEB_SEARCH).getValue("maxResults").toString().toInt())
        assertEquals(before + 1, scenario.revision(SettingsScenario.WEB_SEARCH))
        // The answered view is the reloaded document, not an echo of the patch.
        assertEquals(9, ((view.value as JsonObject).getValue("maxResults") as JsonPrimitive).content.toInt())
    }

    @Test
    fun aStaleRevisionIsAConflictAndTheReloadedDocumentWins() = withSettings { api, scenario ->
        val stale = scenario.revision(SettingsScenario.AGENT)
        scenario.bumpRevision(SettingsScenario.AGENT)

        val error = api.settingsMutate(
            SettingsScenario.AGENT,
            listOf(SettingsPathOpView.Set(listOf("maxParallelSubagents"), JsonPrimitive(8))),
            stale.toInt(),
        ).failure()
        assertEquals("settings/conflict", error.code)

        // Nothing was written, and describe now reports the other writer's revision.
        assertEquals(4, scenario.value(SettingsScenario.AGENT).getValue("maxParallelSubagents").toString().toInt())
        val reloaded = api.settingsDescribe().ok().namespaces.first { it.ns == SettingsScenario.AGENT }
        assertEquals(stale + 1, reloaded.revision)
    }

    @Test
    fun aProviderRefusalKeepsTheHostMessage() = withSettings { api, scenario ->
        scenario.rejectNextMutate = "shell timeout must be a positive integer"
        val error = api.settingsMutate(
            SettingsScenario.AGENT,
            listOf(SettingsPathOpView.Set(listOf("shellTimeoutMs"), JsonPrimitive(-1))),
            scenario.revision(SettingsScenario.AGENT).toInt(),
        ).failure()
        assertEquals("settings/rejected", error.code)
        assertEquals("shell timeout must be a positive integer", error.message)
    }

    @Test
    fun aWriteWithoutARevisionNeverReachesTheStore() = withSettings { api, scenario ->
        val before = scenario.revision(SettingsScenario.LLM)
        val error = api.settingsUpdate(
            SettingsScenario.LLM,
            buildJsonObject { put("temperature", JsonPrimitive(0.2)) },
        ).failure()
        assertEquals("gateway/arguments-invalid", error.code)
        assertEquals(before, scenario.revision(SettingsScenario.LLM))
    }

    @Test
    fun aSecretTravelsOnlyThroughCredentialsAndComesBackAsAFlag() = withSettings { api, scenario ->
        assertEquals(false, api.credentialsDescribe(listOf(SettingsScenario.API_KEY_REF)).ok().getValue(SettingsScenario.API_KEY_REF).configured)

        api.credentialsSet(SettingsScenario.API_KEY_REF, SettingsScenario.API_KEY_VALUE).ok()
        assertEquals(SettingsScenario.API_KEY_VALUE, scenario.credentialValue(SettingsScenario.API_KEY_REF))
        assertEquals(listOf(SettingsScenario.API_KEY_REF), scenario.credentialRefsWritten)

        val set = api.settingsDescribe().ok().namespaces.first { it.ns == SettingsScenario.WEB_SEARCH }
        assertTrue(set.secrets.single().set)
        assertFalse(set.value.toString().contains(SettingsScenario.API_KEY_VALUE))

        api.credentialsUnset(SettingsScenario.API_KEY_REF).ok()
        assertNull(scenario.credentialValue(SettingsScenario.API_KEY_REF))
        assertFalse(api.settingsDescribe().ok().namespaces.first().secrets.single().set)
    }

    @Test
    fun aReadOnlyDeploymentSaysSoAndTheFormFollowsTheWireSchema() = withSettings { api, scenario ->
        scenario.writable = false
        val document = api.settingsDescribe().ok()
        assertFalse(document.writable)

        // The same document the client just decoded must classify into the form actually drawn.
        val web = document.namespaces.first { it.ns == SettingsScenario.WEB_SEARCH }
        val rows = HostSettingsPresentation.fields(web)
        assertEquals(
            listOf("Enabled", "Provider", "API key", "Max results", "User agent", "Attempts", "Backoff (ms)", "Host allowlist"),
            rows.map { it.label },
        )
        assertEquals(
            listOf(
                HostSettingKind.Toggle, HostSettingKind.Choice, HostSettingKind.Secret, HostSettingKind.Number,
                HostSettingKind.Text, HostSettingKind.Number, HostSettingKind.Number, HostSettingKind.ReadOnly,
            ),
            rows.map { it.kind },
        )
        assertEquals(SettingsScenario.API_KEY_REF, rows[2].secretRef)
        assertEquals(listOf(listOf("retry", "attempts")), listOf(rows[5].path))
        assertEquals("5", rows[3].value)
        assertEquals("array", rows[7].typeLabel)
    }

    // ------------------------------------------------------------------ presentation

    /** A view built by hand: the presentation has to work on documents no harness produced. */
    private fun view(
        ns: String,
        schema: String,
        value: String,
        secrets: List<SettingsSecretView> = emptyList(),
    ) = SettingsNamespaceView(
        ns = ns,
        schema = Json.parseToJsonElement(schema),
        value = Json.parseToJsonElement(value),
        applies = "live",
        secrets = secrets,
        revision = 1L,
    )

    @Test
    fun aConstUnionIsAChoiceAndAnUnknownNodeIsOneReadOnlyRow() {
        val union = view(
            "llm",
            """{"type":"object","dict":{"effort":{"type":"union","meta":{"label":"Effort"},"list":[
                {"type":"const","value":"standard"},{"type":"const","value":"high"}]},
                "budget":{"type":"transform","meta":{"label":"Budget"},"inner":{"type":"number"}}}}""",
            """{"effort":"high","budget":42}""",
        )
        val rows = HostSettingsPresentation.fields(union)
        assertEquals(HostSettingKind.Choice, rows[0].kind)
        assertEquals(listOf("standard", "high"), rows[0].options.map { it.value })
        assertEquals("high", rows[0].value)
        assertEquals(HostSettingKind.ReadOnly, rows[1].kind)
        assertEquals("transform", rows[1].typeLabel)
        assertEquals("42", rows[1].value)
    }

    @Test
    fun aSchemaThatIsNotAFormStillShowsItsValueOnce() {
        val rows = HostSettingsPresentation.fields(view("odd", """{"type":"string"}""", "\"verbatim\""))
        assertEquals(1, rows.size)
        assertEquals(HostSettingKind.ReadOnly, rows.single().kind)
        assertEquals("odd", rows.single().label)
        assertEquals("verbatim", rows.single().value)
        assertEquals("string", rows.single().typeLabel)
    }

    @Test
    fun anUndeclaredValueAndAHiddenFieldAreBothAccountedFor() {
        val rows = HostSettingsPresentation.fields(
            view(
                "custom",
                """{"type":"object","dict":{"visible":{"type":"string","meta":{"label":"Visible"}},
                    "secretish":{"type":"string","meta":{"label":"Secretish","hidden":true}}}}""",
                """{"visible":"yes","secretish":"x","surprise":1}""",
            ),
        )
        assertEquals(listOf("Visible", "surprise"), rows.map { it.label })
        assertEquals(HostSettingKind.Text, rows[0].kind)
        assertEquals(HostSettingKind.ReadOnly, rows[1].kind)
        assertEquals("undeclared", rows[1].typeLabel)
        assertEquals("1", rows[1].value)
    }

    @Test
    fun aSecretWithoutADeclaredReferenceNamesItsPath() {
        val declared = HostSettingsPresentation.fields(
            view(
                "webSearch",
                """{"type":"object","dict":{"apiKey":{"type":"string","meta":{"label":"API key","role":"secret","env":"MY_KEY"}}}}""",
                "{}",
                listOf(SettingsSecretView(listOf("apiKey"), true)),
            ),
        ).single()
        assertEquals("MY_KEY", declared.secretRef)
        assertTrue(declared.secretSet)
        // The value is redacted, so the row carries the flag and no text.
        assertEquals("", declared.value)

        val derived = HostSettingsPresentation.fields(
            view(
                "webSearch",
                """{"type":"object","dict":{"apiKey":{"type":"string","meta":{"label":"API key","role":"secret"}}}}""",
                "{}",
            ),
        ).single()
        assertEquals("WEBSEARCH_APIKEY", derived.secretRef)
        assertFalse(derived.secretSet)
        assertEquals("WEBSEARCH_APIKEY", HostSettingsPresentation.credentialName(listOf("webSearch", "apiKey")))
    }

    @Test
    fun encodeRefusesTextWhereANumberBelongsAndKeepsIntegersIntegral() {
        assertNull(HostSettingsPresentation.encode(HostSettingKind.Number, "12abc"))
        assertNull(HostSettingsPresentation.encode(HostSettingKind.Number, ""))
        assertEquals(JsonPrimitive(12), HostSettingsPresentation.encode(HostSettingKind.Number, " 12 "))
        assertEquals(JsonPrimitive(1.5), HostSettingsPresentation.encode(HostSettingKind.Number, "1.5"))
        assertEquals(JsonPrimitive("hello"), HostSettingsPresentation.encode(HostSettingKind.Text, "hello"))
        // A secret is written through credentials, never through a settings patch.
        assertNull(HostSettingsPresentation.encode(HostSettingKind.Secret, "sk-nope"))
    }

    /**
     * A host that never mounted the settings controller answers `gateway/definition-unavailable`;
     * the client folds that into `capability-unavailable`, which is the signal the entry row hides
     * on. Anything else would surface a startup error for a feature this host simply does not have.
     */
    @Test
    fun aHostWithoutTheSettingsControllerIsACapabilityGapNotAnError() = runBlocking {
        val harness = MockHarness()
        harness.onFail("settings/describe") {
            RpcErrorData("gateway/definition-unavailable", "settings is not mounted on this host")
        }
        val port = harness.start()
        try {
            val api = DshApiClient(OkHttpRpcTransport("http://127.0.0.1:$port", OkHttpClient(), 5000, 5000))
            assertEquals("capability-unavailable", api.settingsDescribe().failure().code)
        } finally {
            harness.stop()
        }
    }
}
