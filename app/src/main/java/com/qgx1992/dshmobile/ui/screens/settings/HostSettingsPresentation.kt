package com.qgx1992.dshmobile.ui.screens.settings

import com.qgx1992.dshmobile.core.wire.dto.SettingsNamespaceView
import com.qgx1992.dshmobile.core.wire.dto.SettingsSecretView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * What a settings field can be edited as.
 *
 * [ReadOnly] is not an error state: it is the honest answer for a schema node this build cannot
 * edit (an array, a map, an intersection, a node kind from a newer host). The form still draws it,
 * with its current value, because a field that silently disappears reads as a bug and a field that
 * throws is worse.
 */
internal enum class HostSettingKind { Toggle, Text, Number, Choice, Secret, ReadOnly }

/** One option of a [HostSettingKind.Choice] field. [label] is the schema's own, when it ships one. */
internal data class HostSettingOption(val value: String, val label: String? = null)

/**
 * One row of the settings form: a schema node flattened to a path plus the value it holds now.
 *
 * [path] is the address `settings/mutate` takes, so a nested node is editable without the form
 * ever rebuilding an enclosing object. [value] is the text the row shows — the effective value,
 * never a secret's.
 */
internal data class HostSettingField(
    val path: List<String>,
    val kind: HostSettingKind,
    val label: String,
    val description: String? = null,
    val value: String = "",
    val options: List<HostSettingOption> = emptyList(),
    /** For [HostSettingKind.Secret]: the reference `credentials/set` writes. */
    val secretRef: String? = null,
    /** For [HostSettingKind.Secret]: whether the host reports a value behind the reference. */
    val secretSet: Boolean = false,
    /** For [HostSettingKind.ReadOnly]: the schema's declared node type, so the row can say why. */
    val typeLabel: String? = null,
) {
    /** The path as one string: a row's identity, and what a test can address it by. */
    val key: String get() = path.joinToString(".")

    /** Whether this row opens an editor rather than writing on the spot. */
    val editable: Boolean get() = kind != HostSettingKind.ReadOnly
}

/**
 * The schema-to-form mapping, kept out of the composable so it can be tested without one.
 *
 * Everything here is defensive on purpose. A namespace's `schema` is opaque JSON from a host this
 * client does not control, so each step treats a surprise as data rather than as an error: a node
 * that is not an object, a `dict` that is missing, a value that is not the shape its schema
 * promises. None of them throw, all of them land on a readable row.
 */
internal object HostSettingsPresentation {
    /** How deep a nested `object` may be followed before the rest of it becomes one read-only row. */
    private const val MAX_DEPTH = 6

    private val NUMBER_TYPES = setOf("number", "natural", "percent", "posint", "int", "integer")

    /** Meta keys through which a schema may name its own credential reference, most specific first. */
    private val REF_META_KEYS = listOf("credentialsRef", "credentialRef", "ref", "env")

    /** Flattens one namespace's schema into the rows its form draws, in declaration order. */
    fun fields(view: SettingsNamespaceView): List<HostSettingField> {
        val root = view.schema as? JsonObject ?: return wholeValue(view, null)
        val dict = root["dict"] as? JsonObject ?: return wholeValue(view, root.string("type"))
        val value = view.value as? JsonObject ?: JsonObject(emptyMap())
        val secrets = view.secrets.associateBy { it.path }
        val out = mutableListOf<HostSettingField>()
        flatten(dict, value, emptyList(), secrets, out, 0, view.ns)
        // A value the schema does not declare is still a fact about the effective configuration:
        // shown as a read-only row rather than dropped, so the form never under-reports the host.
        val declared = dict.keys
        for ((key, element) in value) {
            if (key in declared) continue
            out += HostSettingField(
                path = listOf(key),
                kind = HostSettingKind.ReadOnly,
                label = key,
                value = compact(element),
                typeLabel = "undeclared",
            )
        }
        return out
    }

    /**
     * Encodes a text edit as the JSON `settings/mutate` carries, or null when it is not encodable.
     *
     * A number that does not parse is refused here rather than sent, because the host's answer to
     * `"12abc"` would be a boundary refusal that says nothing about which row was wrong.
     */
    fun encode(kind: HostSettingKind, raw: String): JsonElement? = when (kind) {
        HostSettingKind.Toggle -> booleanJson(raw)
        HostSettingKind.Number -> numberJson(raw)
        HostSettingKind.Text, HostSettingKind.Choice -> JsonPrimitive(raw)
        HostSettingKind.Secret, HostSettingKind.ReadOnly -> null
    }

    /** The JSON a toggle writes for [checked]. */
    fun toggleValue(checked: Boolean): JsonElement = JsonPrimitive(checked)

    /**
     * The credential reference a secret slot writes through when its schema names none.
     *
     * The host addresses credentials by an identifier-shaped name (`^[A-Za-z_][A-Za-z0-9_]*$`), so
     * a path has to become one: joined with `_`, every other character folded to `_`, upper-cased,
     * and prefixed when it would otherwise start with a digit or be empty. A schema that ships a
     * `credentialsRef` (or `ref`/`env`) overrides this entirely — see [secretRefOf].
     */
    fun credentialName(path: List<String>): String {
        val folded = path.joinToString("_").map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        val trimmed = folded.trim('_')
        val prefixed = if (trimmed.isEmpty() || trimmed.first().isDigit()) "SETTINGS_$trimmed" else trimmed
        return prefixed.uppercase()
    }

    // ------------------------------------------------------------------ schema walking

    private fun flatten(
        dict: JsonObject,
        value: JsonObject,
        prefix: List<String>,
        secrets: Map<List<String>, SettingsSecretView>,
        out: MutableList<HostSettingField>,
        depth: Int,
        ns: String,
    ) {
        for ((key, element) in dict) {
            val path = prefix + key
            val node = element as? JsonObject
            if (node == null) {
                out += readOnly(path, key, value[key], typeLabel = null)
                continue
            }
            val meta = node["meta"] as? JsonObject
            if (hidden(meta)) continue
            val label = meta?.string("label")?.takeIf { it.isNotBlank() } ?: key
            val description = meta?.string("description")
            if (meta?.string("role") == "secret") {
                out += HostSettingField(
                    path = path,
                    kind = HostSettingKind.Secret,
                    label = label,
                    description = description,
                    // A redacted value never arrives, so the row shows its state, not its content.
                    value = "",
                    // The name is qualified with the namespace: two namespaces may each own an
                    // `apiKey`, and a path-relative name would point both at one credential.
                    secretRef = secretRefOf(listOf(ns) + path, meta),
                    secretSet = secrets[path]?.set == true,
                )
                continue
            }
            val type = node.string("type")
            val current = value[key]
            val options = optionsOf(node, meta)
            when {
                type == "object" && node["dict"] is JsonObject -> {
                    val child = current as? JsonObject ?: JsonObject(emptyMap())
                    if (depth >= MAX_DEPTH) {
                        out += readOnly(path, label, current, typeLabel = type, description = description)
                    } else {
                        flatten(node.getValue("dict") as JsonObject, child, path, secrets, out, depth + 1, ns)
                    }
                }
                type == "boolean" -> out += HostSettingField(
                    path = path, kind = HostSettingKind.Toggle, label = label, description = description,
                    value = (current as? JsonPrimitive)?.booleanOrNull?.toString() ?: "",
                )
                type == "string" && options.isNotEmpty() -> out += choice(path, label, description, current, options)
                type == "string" -> out += HostSettingField(
                    path = path, kind = HostSettingKind.Text, label = label, description = description,
                    value = (current as? JsonPrimitive)?.contentOrNull ?: "",
                )
                type != null && type in NUMBER_TYPES -> out += HostSettingField(
                    path = path, kind = HostSettingKind.Number, label = label, description = description,
                    value = (current as? JsonPrimitive)?.contentOrNull ?: "",
                )
                options.isNotEmpty() -> out += choice(path, label, description, current, options)
                else -> out += readOnly(path, label, current, typeLabel = type, description = description)
            }
        }
    }

    private fun choice(
        path: List<String>,
        label: String,
        description: String?,
        current: JsonElement?,
        options: List<HostSettingOption>,
    ) = HostSettingField(
        path = path, kind = HostSettingKind.Choice, label = label, description = description,
        value = (current as? JsonPrimitive)?.contentOrNull ?: "",
        options = options,
    )

    private fun readOnly(
        path: List<String>,
        label: String,
        current: JsonElement?,
        typeLabel: String?,
        description: String? = null,
    ) = HostSettingField(
        path = path, kind = HostSettingKind.ReadOnly, label = label, description = description,
        value = compact(current), typeLabel = typeLabel,
    )

    /** A namespace whose schema is not a form-shaped object still shows its value, once, verbatim. */
    private fun wholeValue(view: SettingsNamespaceView, typeLabel: String?): List<HostSettingField> = listOf(
        HostSettingField(
            path = emptyList(),
            kind = HostSettingKind.ReadOnly,
            label = view.ns,
            value = compact(view.value),
            typeLabel = typeLabel ?: "schema",
        ),
    )

    /** Options a node declares, from `meta.options` when it has them and from `const` branches otherwise. */
    private fun optionsOf(node: JsonObject, meta: JsonObject?): List<HostSettingOption> {
        (meta?.get("options") as? JsonArray)?.let { declared ->
            val options = declared.mapNotNull { option ->
                when (option) {
                    is JsonPrimitive -> option.contentOrNull?.let { HostSettingOption(it) }
                    is JsonObject -> option.string("value")?.let { HostSettingOption(it, option.string("label")) }
                    else -> null
                }
            }
            if (options.isNotEmpty()) return options
        }
        // A union whose every branch is a const is an enum the host expressed as a union.
        val branches = (node["list"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        if (branches.isEmpty() || branches.any { it.string("type") != "const" }) return emptyList()
        return branches.mapNotNull { branch -> (branch["value"] as? JsonPrimitive)?.contentOrNull?.let { HostSettingOption(it) } }
    }

    /** A schema-declared secret's reference, or the name its path implies when it declares none. */
    private fun secretRefOf(path: List<String>, meta: JsonObject?): String {
        val declared = REF_META_KEYS.firstNotNullOfOrNull { key -> meta?.string(key)?.takeIf { it.isNotBlank() } }
        return declared ?: credentialName(path)
    }

    private fun hidden(meta: JsonObject?): Boolean = meta?.let { meta ->
        meta["hidden"]?.let { element ->
            (element as? JsonPrimitive)?.let { it.booleanOrNull == true || it.contentOrNull == "true" }
        } == true
    } ?: false

    private fun booleanJson(raw: String): JsonElement? =
        raw.trim().toBooleanStrictOrNull()?.let { JsonPrimitive(it) }

    /** An integer stays an integer: a `natural` node refuses `4.0` the way it refuses `"4"`. */
    private fun numberJson(raw: String): JsonElement? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return JsonPrimitive(it) }
        return text.toDoubleOrNull()?.let { JsonPrimitive(it) }
    }

    /** Compact JSON text for a value a row cannot edit; never blank, so no row renders empty. */
    fun compact(element: JsonElement?): String = when {
        element == null || element is JsonNull -> ""
        element is JsonPrimitive -> element.contentOrNull ?: element.toString()
        else -> element.toString()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
