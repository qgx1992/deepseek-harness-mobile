package com.qgx1992.dshmobile.core.wire

import com.qgx1992.dshmobile.core.wire.dto.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.json.*

private suspend inline fun <reified T> DshApiClient.featureCall(endpoint: String, args: JsonObject = JsonObject(emptyMap())): RpcResult<T> =
    call(endpoint, args, serializer<T>())

private fun named(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
private fun str(value: String) = JsonPrimitive(value)
private fun request(value: JsonElement) = named("request" to value)

/**
 * Codes an older host answers when it does not understand the newer `schedule` request shape.
 *
 * 0.2.2-rc.1 reshaped `schedule/list` and `schedule/history`, so a host that predates it fails the
 * gateway's boundary validation (`gateway/input-invalid`, or `gateway/arguments-invalid`) as well as
 * refusing an unknown method (`capability-unavailable`). Any of the three means "send the legacy
 * field set instead", so the fallbacks below cover all of them.
 */
private val LEGACY_SCHEDULE_REJECTED = setOf(
    "capability-unavailable",
    "gateway/arguments-invalid",
    "gateway/input-invalid",
)

/**
 * The complete observed record, without the decorations a host adds to a catalogue row.
 *
 * Only the pre-0.2.2 `schedule/update` takes this object; `schedule/edit` takes the named `patch`
 * instead, so the newer decorations (`target`, `involvedSessionIds`, `executionActive`) are left
 * out rather than sent to a host that would refuse them. `updatedAt` stays: an editor sends it
 * back as `expectedUpdatedAt`.
 */
fun AutomationTask.expectedRecord(): JsonObject = JsonObject(
    WireJson.encodeToJsonElement(AutomationTask.serializer(), this).jsonObject.filterKeys {
        it !in setOf("sessionId", "status", "lastDelivery", "target", "involvedSessionIds", "executionActive")
    },
)

private inline fun <T, R> RpcResult<T>.mapValue(transform: (T) -> R): RpcResult<R> = when (this) {
    is RpcResult.Err -> this
    is RpcResult.Ok -> RpcResult.Ok(transform(value))
}

/** Read a list answer: 0.2.2 wraps it in `{items}`, older hosts answered with the array itself. */
private fun JsonElement.automationTasks(): List<AutomationTask> {
    val rows = (this as? JsonObject)?.get("items") ?: this
    return runCatching {
        WireJson.decodeFromJsonElement(ListSerializer(AutomationTask.serializer()), rows)
    }.getOrDefault(emptyList())
}

/**
 * Read a `schedule/edit` answer, which is a union of the stored record, `{deleted:true}` and the
 * two typed refusals. The old `schedule/update`/`schedule/delete` answers map the same way, so one
 * reader covers both generations.
 *
 * The old pair answers with a wrapper (`{id, updated, record}`) rather than the bare stored record,
 * so a `record` member or an explicit `updated` flag also reads as an update.
 */
internal fun automationMutation(value: JsonElement): AutomationMutation {
    val obj = value as? JsonObject ?: return AutomationMutation(message = value.toString())
    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
    fun storedRecord(): AutomationTask? = runCatching {
        val row = (obj["record"] as? JsonObject) ?: obj
        WireJson.decodeFromJsonElement(AutomationTask.serializer(), row)
    }.getOrNull()
    return when {
        obj.containsKey("deleted") ->
            AutomationMutation(id = id, deleted = (obj["deleted"] as? JsonPrimitive)?.booleanOrNull ?: true)
        obj.containsKey("code") -> AutomationMutation(
            id = id,
            code = (obj["code"] as? JsonPrimitive)?.contentOrNull,
            message = (obj["message"] as? JsonPrimitive)?.contentOrNull,
        )
        obj.containsKey("kind") || obj.containsKey("record") ||
            (obj["updated"] as? JsonPrimitive)?.booleanOrNull == true ->
            AutomationMutation(
                id = id,
                updated = (obj["updated"] as? JsonPrimitive)?.booleanOrNull ?: true,
                record = storedRecord(),
            )
        else -> AutomationMutation(id = id, message = value.toString())
    }
}

/**
 * The `schedule/edit` update branch: named `patch` members plus the optimistic revision.
 *
 * [AutomationUpdate.change] is already the `timing` object [automationTiming] builds, which is the
 * vocabulary `patch.timing` declares; [AutomationUpdate.expected] carries `updatedAt` when the row
 * came from a host that reports it.
 */
private fun editUpdateRequest(value: AutomationUpdate): JsonObject = buildJsonObject {
    put("id", value.id)
    put("action", "update")
    put("patch", buildJsonObject {
        value.title?.let { put("title", it) }
        value.prompt?.let { put("prompt", it) }
        value.change?.let { put("timing", it) }
    })
    value.expected["updatedAt"]?.let { it as? JsonPrimitive }?.contentOrNull?.let { put("expectedUpdatedAt", it) }
}

suspend fun DshApiClient.automationCatalog(): RpcResult<List<AutomationTask>> = featureCall("schedule/catalog")

/**
 * Every stored task, newest first.
 *
 * 0.2.2-rc.1 lists at Host level: `schedule/list` takes only paging and never a Session, so the
 * caller filters on [AutomationTask.destinationSessionId]. A host that predates that rejects the
 * paging-only shape (see [LEGACY_SCHEDULE_REJECTED]), and [sessionId] then restores the per-Session
 * listing.
 */
suspend fun DshApiClient.automationList(sessionId: String? = null): RpcResult<List<AutomationTask>> {
    val page = call("schedule/list", request(buildJsonObject { put("limit", 500) }), JsonElement.serializer())
    if (page is RpcResult.Err) {
        if (page.error.code !in LEGACY_SCHEDULE_REJECTED || sessionId == null) return page
        return call(
            "schedule/list",
            request(named("sessionId" to str(sessionId))),
            JsonElement.serializer(),
        ).mapValue { it.automationTasks() }
    }
    return page.mapValue { it.automationTasks() }
}

/**
 * One page of one task's saved deliveries.
 *
 * 0.2.2-rc.1 dropped the `sessionId` argument; [legacySessionId] sends it again for an older host.
 */
suspend fun DshApiClient.automationHistory(
    id: String,
    before: String? = null,
    legacySessionId: String? = null,
): RpcResult<AutomationHistory> {
    val page = call(
        "schedule/history",
        request(buildJsonObject {
            put("id", id); put("limit", 25)
            before?.let { put("before", it) }
        }),
        AutomationHistory.serializer(),
    )
    if (page is RpcResult.Err && page.error.code in LEGACY_SCHEDULE_REJECTED && legacySessionId != null) {
        return call(
            "schedule/history",
            request(buildJsonObject {
                put("sessionId", legacySessionId); put("id", id); put("limit", 25)
                before?.let { put("before", it) }
            }),
            AutomationHistory.serializer(),
        )
    }
    return page
}

/**
 * Save one task through `schedule/edit`.
 *
 * Harness 0.2.2-rc.1 folded `schedule/update` and `schedule/delete` into `schedule/edit`, whose
 * update branch takes a named `patch` rather than the whole observed record. A host that only
 * knows the old pair refuses `schedule/edit` (see [LEGACY_SCHEDULE_REJECTED]), so the observation
 * [AutomationUpdate.expected] is sent to `schedule/update` as it was before.
 */
suspend fun DshApiClient.automationUpdate(value: AutomationUpdate): RpcResult<AutomationMutation> {
    val edit = call("schedule/edit", request(editUpdateRequest(value)), JsonElement.serializer())
    if (edit is RpcResult.Err) {
        if (edit.error.code !in LEGACY_SCHEDULE_REJECTED) return edit
        return call(
            "schedule/update",
            request(WireJson.encodeToJsonElement(AutomationUpdate.serializer(), value)),
            JsonElement.serializer(),
        ).mapValue { automationMutation(it) }
    }
    return edit.mapValue { automationMutation(it) }
}

/**
 * Delete one task through `schedule/edit`'s delete branch, with its deliveries.
 *
 * [expectedUpdatedAt] is the row's last seen revision; a newer stored revision answers
 * `schedule_conflict` instead of deleting. Older hosts keep `schedule/delete`, which needs the
 * [legacySessionId].
 */
suspend fun DshApiClient.automationDelete(
    id: String,
    expectedUpdatedAt: String? = null,
    legacySessionId: String? = null,
): RpcResult<AutomationMutation> {
    val edit = call(
        "schedule/edit",
        request(buildJsonObject {
            put("id", id); put("action", "delete")
            expectedUpdatedAt?.let { put("expectedUpdatedAt", it) }
        }),
        JsonElement.serializer(),
    )
    if (edit is RpcResult.Err) {
        if (edit.error.code !in LEGACY_SCHEDULE_REJECTED || legacySessionId == null) return edit
        return call(
            "schedule/delete",
            request(named("sessionId" to str(legacySessionId), "id" to str(id))),
            JsonElement.serializer(),
        ).mapValue { automationMutation(it) }
    }
    return edit.mapValue { automationMutation(it) }
}

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
