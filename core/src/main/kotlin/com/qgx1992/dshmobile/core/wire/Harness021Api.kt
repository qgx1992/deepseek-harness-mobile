package com.qgx1992.dshmobile.core.wire

import com.qgx1992.dshmobile.core.wire.dto.*
import kotlinx.serialization.serializer
import kotlinx.serialization.json.*

private suspend inline fun <reified T> DshApiClient.featureCall(endpoint: String, args: JsonObject = JsonObject(emptyMap())): RpcResult<T> =
    call(endpoint, args, serializer<T>())

private fun named(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
private fun str(value: String) = JsonPrimitive(value)
private fun request(value: JsonElement) = named("request" to value)

fun AutomationTask.expectedRecord(): JsonObject = JsonObject(
    WireJson.encodeToJsonElement(AutomationTask.serializer(), this).jsonObject.filterKeys {
        it !in setOf("sessionId", "status", "lastDelivery")
    },
)

suspend fun DshApiClient.automationCatalog(): RpcResult<List<AutomationTask>> = featureCall("schedule/catalog")
suspend fun DshApiClient.automationList(sessionId: String): RpcResult<List<AutomationTask>> =
    featureCall("schedule/list", request(named("sessionId" to str(sessionId))))
suspend fun DshApiClient.automationHistory(sessionId: String, id: String, before: String? = null): RpcResult<AutomationHistory> =
    featureCall("schedule/history", request(buildJsonObject {
        put("sessionId", sessionId); put("id", id); put("limit", 25)
        before?.let { put("before", it) }
    }))
suspend fun DshApiClient.automationUpdate(value: AutomationUpdate): RpcResult<AutomationMutation> =
    featureCall("schedule/update", request(WireJson.encodeToJsonElement(AutomationUpdate.serializer(), value)))
suspend fun DshApiClient.automationDelete(sessionId: String, id: String): RpcResult<AutomationMutation> =
    featureCall("schedule/delete", request(named("sessionId" to str(sessionId), "id" to str(id))))

suspend fun DshApiClient.answerContinuedQuestion(sessionId: String, callId: String, answer: AskUserQuestionAnswer): RpcResult<Boolean> =
    featureCall("userQuestions/answer", named("agentId" to str(sessionId), "callId" to str(callId),
        "answer" to WireJson.encodeToJsonElement(AskUserQuestionAnswer.serializer(), answer)))

suspend fun DshApiClient.pluginBundles(): RpcResult<List<PluginBundle>> = featureCall("pluginManager/listBundles")
suspend fun DshApiClient.managedPlugins(): RpcResult<List<ManagedPlugin>> = featureCall("pluginManager/listPlugins")
suspend fun DshApiClient.pluginRegistries(): RpcResult<PluginRegistries> = featureCall("pluginManager/registries")
suspend fun DshApiClient.inspectPlugin(spec: String, registry: String? = null): RpcResult<PluginInspection> =
    featureCall("pluginManager/inspect", buildJsonObject {
        put("spec", spec)
        registry?.let { put("options", named("registry" to str(it))) }
    })
suspend fun DshApiClient.installPlugin(spec: String, options: PluginInstallOptions): RpcResult<PluginChangeResult> =
    featureCall("pluginManager/installBundle", named("spec" to str(spec), "options" to WireJson.encodeToJsonElement(PluginInstallOptions.serializer(), options)))
/**
 * Install [spec], replacing the installed bundle [replaces] when the host will not install over it.
 *
 * Harness 0.2.1-alpha.3 with hot reload refuses to install a package that is already installed
 * (`already-installed`): plugins are stopped by removing the package, so an update is remove and
 * then install. That is what its own plugin manager does for "Update", and what this does when the
 * refusal comes back. A host without hot reload installs over the old version and never refuses,
 * so nothing is removed there. A removal the host defers to the next restart (`stop-profile`) still
 * lets the install go ahead, as the web client does.
 */
suspend fun DshApiClient.installOrReplacePlugin(
    spec: String,
    options: PluginInstallOptions,
    replaces: String?,
): RpcResult<PluginChangeResult> {
    val first = installPlugin(spec, options)
    val refused = (first as? RpcResult.Ok)?.value?.error?.code == "already-installed"
    if (!refused || replaces == null) return first
    val removed = removePluginBundle(replaces)
    if (removed !is RpcResult.Ok) return removed
    val removeError = removed.value.error?.code
    if (removeError != null && removeError != "stop-profile") return removed
    return installPlugin(spec, options)
}
suspend fun DshApiClient.waitForPluginInstall(requestId: String): RpcResult<PluginChangeResult?> =
    featureCall("pluginManager/waitForInstall", named("requestId" to str(requestId)))
suspend fun DshApiClient.cancelPluginInstall(requestId: String): RpcResult<PluginInstallCancellation> =
    featureCall("pluginManager/cancelInstall", named("requestId" to str(requestId)))
suspend fun DshApiClient.setBundleEnabled(name: String, enabled: Boolean): RpcResult<PluginChangeResult> =
    featureCall("pluginManager/setBundleEnabled", named("name" to str(name), "enabled" to JsonPrimitive(enabled)))
suspend fun DshApiClient.setPluginEnabled(id: String, enabled: Boolean): RpcResult<PluginChangeResult> =
    featureCall("pluginManager/setPluginEnabled", named("id" to str(id), "enabled" to JsonPrimitive(enabled)))
suspend fun DshApiClient.removePluginBundle(name: String): RpcResult<PluginChangeResult> =
    featureCall("pluginManager/removeBundle", named("name" to str(name)))
suspend fun DshApiClient.sessionReferenceCandidates(sessionId: String, query: String): RpcResult<List<ReferenceCandidate>> =
    featureCall("sessionReferenceResolver/candidates", named("agentId" to str(sessionId), "query" to str(query)))

/*
 * Speech recognition (experimental `speech` namespace).
 *
 * The namespace exists only on a host that composed the speech-to-text plugin. A recording is
 * validated and transcribed entirely host-side and never becomes a session event: the answer is
 * the transcript, and putting it in the draft is the client's business.
 */

suspend fun DshApiClient.speechCatalog(): RpcResult<SpeechCatalogView> = featureCall("speech/catalog")

/** Transcribe one WAV, already base64-encoded by the caller; see [SpeechCatalogView.maxAudioBytes]. */
suspend fun DshApiClient.speechTranscribe(
    audioBase64: String,
    providerId: String? = null,
    language: String? = null,
): RpcResult<SpeechTranscriptionView> = featureCall("speech/transcribe", request(buildJsonObject {
    put("audioBase64", audioBase64)
    providerId?.takeIf { it.isNotBlank() }?.let { put("providerId", it) }
    language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
}))

/** Start (or join) the host-side preparation of one recognizer; the phase is reported by [speechCatalog]. */
suspend fun DshApiClient.speechPrepare(providerId: String, downloadSource: String? = null): RpcResult<JsonElement> =
    featureCall("speech/prepare", buildJsonObject {
        put("providerId", providerId)
        downloadSource?.takeIf { it.isNotBlank() }?.let { put("options", named("downloadSource" to str(it))) }
    })

suspend fun DshApiClient.speechCancelPreparation(providerId: String): RpcResult<JsonElement> =
    featureCall("speech/cancelPreparation", named("providerId" to str(providerId)))

/** Persist the recognizer and language hint the host should use next time. */
suspend fun DshApiClient.speechConfigure(providerId: String? = null, language: String? = null): RpcResult<JsonElement> =
    featureCall("speech/configure", named("patch" to buildJsonObject {
        providerId?.takeIf { it.isNotBlank() }?.let { put("providerId", it) }
        language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
    }))

/*
 * Plugin market (the `pluginMarket` namespace behind the desktop Plugins → Discover tab).
 *
 * The host validates, fetches, searches, and annotates; this side only renders. Installation is not
 * part of the namespace — an entry carries the exact `install.spec` that `pluginManager/installBundle`
 * takes.
 */

suspend fun DshApiClient.marketIndexes(): RpcResult<List<MarketIndexSourceView>> =
    featureCall("pluginMarket/listIndexes")

/**
 * One page of one market.
 *
 * [MarketQuery] always writes `sort`, `offset` and `limit`: the host's schema requires all three and
 * rejects unknown members, so the shape of the query is fixed rather than inferred.
 */
suspend fun DshApiClient.marketSearch(url: String, query: MarketQuery): RpcResult<MarketSearchResultView> =
    featureCall("pluginMarket/search", named("url" to str(url), "query" to query.toJson()))

/** Replace the configured index sources; the host validates each URL and refuses duplicates. */
suspend fun DshApiClient.marketSetIndexes(urls: List<String>): RpcResult<JsonElement> =
    featureCall("pluginMarket/setIndexes", named("urls" to JsonArray(urls.map(::str))))

class HarnessFeatureException(val code: String, message: String) : Exception(message)
fun <T> RpcResult<T>.featureValue(): T = when (this) {
    is RpcResult.Ok -> value
    is RpcResult.Err -> throw HarnessFeatureException(error.code, error.message)
}
