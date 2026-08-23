package edu.fnosari.momedm.managed

import android.content.Context
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.util.Log
import edu.fnosari.momedm.protocol.EntryType
import edu.fnosari.momedm.protocol.SchemaEntry

/**
 * Reads the managed-configuration settings an installed app declares, so the parent can be shown a
 * form built from the app's own schema rather than a hardcoded list.
 *
 * Only a minority of apps declare anything: it is an Android Enterprise feature each app opts into.
 * On a typical phone Chrome and Gmail do; YouTube, Play, Maps and consumer apps generally do not, and
 * for those this returns an empty list — which the parent is told plainly, because "no settings" and
 * "failed to read" must not look the same.
 */
object AppSchemaReader {
    private const val LOG_TAG = "AppSchemaReader"

    /** Settings [pkg] declares, in the child's own locale. Empty when it declares none, or on failure. */
    fun read(context: Context, pkg: String): List<SchemaEntry> {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            ?: return emptyList()
        val entries = runCatching { rm.getManifestRestrictions(pkg) }
            .onFailure { Log.w(LOG_TAG, "Could not read the schema for $pkg: ${it::class.simpleName}") }
            .getOrNull() ?: return emptyList()
        val mapped = entries.mapNotNull { it.toSchemaEntry() }
        Log.d(LOG_TAG, "Schema for $pkg: ${mapped.size} setting(s)")
        return mapped
    }

    /**
     * Maps one platform entry to our wire type.
     *
     * Choice labels are what the app itself offers; values are kept as strings because that is how
     * `RestrictionEntry` stores them, and the parent writes back the value verbatim.
     */
    private fun RestrictionEntry.toSchemaEntry(): SchemaEntry? {
        val key = key ?: return null
        val kind = when (type) {
            RestrictionEntry.TYPE_BOOLEAN -> EntryType.BOOLEAN
            RestrictionEntry.TYPE_CHOICE -> EntryType.CHOICE
            RestrictionEntry.TYPE_MULTI_SELECT -> EntryType.MULTI_SELECT
            RestrictionEntry.TYPE_INTEGER -> EntryType.INTEGER
            RestrictionEntry.TYPE_STRING -> EntryType.STRING
            RestrictionEntry.TYPE_BUNDLE -> EntryType.BUNDLE
            RestrictionEntry.TYPE_BUNDLE_ARRAY -> EntryType.BUNDLE_ARRAY
            else -> EntryType.UNSUPPORTED
        }
        // Bundles carry their fields as child entries; a bundle array declares the shape of one item
        // once and repeats it. Recursing is what makes list-shaped settings — a list of servers, of
        // bookmarks — editable instead of merely visible.
        val children = if (kind == EntryType.BUNDLE || kind == EntryType.BUNDLE_ARRAY) {
            restrictions?.mapNotNull { it.toSchemaEntry() }.orEmpty()
        } else emptyList()
        return SchemaEntry(
            key = key,
            type = kind,
            title = title?.takeIf { it.isNotBlank() } ?: key,
            choiceLabels = choiceEntries?.toList().orEmpty(),
            choiceValues = choiceValues?.toList().orEmpty(),
            nested = children,
        )
    }
}
