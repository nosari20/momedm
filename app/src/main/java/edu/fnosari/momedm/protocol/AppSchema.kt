package edu.fnosari.momedm.protocol

import kotlinx.serialization.Serializable

/**
 * The kinds of managed-configuration setting a form can render.
 *
 * Mirrors `android.content.RestrictionEntry`'s types. [BUNDLE] is a group of settings and
 * [BUNDLE_ARRAY] a repeatable list of such groups — the shape apps use for things like a list of
 * servers or bookmarks, where each item has several fields of its own.
 */
enum class EntryType { BOOLEAN, CHOICE, MULTI_SELECT, INTEGER, STRING, BUNDLE, BUNDLE_ARRAY, UNSUPPORTED }

/**
 * One managed-configuration setting an app declares, as the child reports it to the parent.
 *
 * Deliberately not carrying the app's long description text: it is the bulk of a schema's bytes
 * (Chrome declares 158 settings) and a form's label plus the key itself say enough. Choices come as
 * parallel label/value lists because that is how `RestrictionEntry` exposes them.
 *
 * [nested] carries the children of a [EntryType.BUNDLE]. For a [EntryType.BUNDLE_ARRAY] it holds the
 * *template* for one item — Android declares the shape once and the list repeats it — so the form
 * knows what fields to offer when the parent adds an entry.
 */
@Serializable
data class SchemaEntry(
    val key: String,
    val type: EntryType,
    /** Human-readable label, resolved on the child in its own locale; falls back to [key]. */
    val title: String = key,
    val choiceLabels: List<String> = emptyList(),
    val choiceValues: List<String> = emptyList(),
    val nested: List<SchemaEntry> = emptyList(),
) {
    /** True when this entry can be rendered and written back. */
    val editable: Boolean get() = type != EntryType.UNSUPPORTED

    /**
     * The fields one item of this entry has.
     *
     * A bundle's own children, or — for a bundle array whose template is itself a single bundle, the
     * shape Android's own examples use — that bundle's children, so the form offers the real fields
     * rather than one nameless group wrapping them.
     */
    val itemFields: List<SchemaEntry>
        get() = when {
            type == EntryType.BUNDLE -> nested
            type == EntryType.BUNDLE_ARRAY && nested.size == 1 && nested[0].type == EntryType.BUNDLE -> nested[0].nested
            else -> nested
        }
}
