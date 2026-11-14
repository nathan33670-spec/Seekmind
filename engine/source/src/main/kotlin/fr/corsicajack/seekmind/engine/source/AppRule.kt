package fr.corsicajack.seekmind.engine.source

import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.SourceLevel

/**
 * A way to open a precise screen or a search of an application from the content of a capture
 * (docs/specs/retour-source.md §4). The rules file and the substitution of its variables come with
 * ticket M7-02; the chain only needs this contract.
 */
interface AppRule {
    val packageName: String

    /** [SourceLevel.APP_LINK] or [SourceLevel.APP_SEARCH]. */
    val level: SourceLevel

    /** The scheme of the URIs of the rule, admitted by the launcher (§5) unless it is forbidden. */
    val scheme: String

    /** The URI to open for [capture], or null when one of its variables is missing. */
    fun uriFor(capture: Capture): String?
}
