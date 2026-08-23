package edu.fnosari.momedm.protocol

import kotlinx.serialization.Serializable

/**
 * The kinds of managed-configuration setting a form can render.
 *
 * Mirrors the subset of `android.content.RestrictionEntry` types worth showing a parent. Nested
 * bundles (`TYPE_BUNDLE`, `TYPE_BUNDLE_ARRAY`) map to [UNSUPPORTED]: they describe tree-shaped
 * configuration that no simple form can edit honestly, and silently flattening them would write
 * values the app never expected.
 */
enum class EntryType { BOOLEAN, CHOICE, MULTI_SELECT, INTEGER, STRING, UNSUPPORTED }

/**
 * One managed-configuration setting an app declares, as the child reports it to the parent.
 *
 * Deliberately not carrying the app's long description text: it is the bulk of a schema's bytes
 * (Chrome declares 158 settings) and a form's label plus the key itself say enough. Choices come as
 * parallel label/value lists because that is how `RestrictionEntry` exposes them.
 */
@Serializable
data class SchemaEntry(
    val key: String,
    val type: EntryType,
    /** Human-readable label, resolved on the child in its own locale; falls back to [key]. */
    val title: String = key,
    val choiceLabels: List<String> = emptyList(),
    val choiceValues: List<String> = emptyList(),
) {
    /** True when this entry can be rendered and written back safely. */
    val editable: Boolean get() = type != EntryType.UNSUPPORTED
}
