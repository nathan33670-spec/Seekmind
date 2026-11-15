package fr.corsicajack.seekmind.engine.source

import android.content.Intent
import android.net.Uri
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.SourceLevel
import fr.corsicajack.seekmind.core.model.SourceRef
import fr.corsicajack.seekmind.core.model.port.SourceResolver

/** One link of the resolution chain (docs/specs/retour-source.md §3): null when it does not apply. */
fun interface SourceStep {
    fun resolve(capture: Capture): SourceRef?
}

/** 1. A page seen in a browser: its URL, in that browser if it is still installed (EF-SRC-01). */
class BrowserUrlResolver(private val apps: InstalledApps) : SourceStep {
    override fun resolve(capture: Capture): SourceRef? {
        val url = capture.url ?: return null
        if (SourceSchemes.schemeOf(url) !in SourceSchemes.WEB) return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val installed = apps.isInstalled(capture.packageName)
        if (installed) intent.setPackage(capture.packageName)
        return SourceRef(
            level = SourceLevel.EXACT_URL,
            intentUri = intent.toUri(Intent.URI_INTENT_SCHEME),
            appLabel = if (installed) apps.label(capture.packageName) else null,
        )
    }
}

/** 2. A rule of the application, when the application is installed and the rule has its variables. */
class AppRuleResolver(private val apps: InstalledApps, rules: List<AppRule>, private val schemes: SourceSchemes) :
    SourceStep {
    private val byPackage: Map<String, List<AppRule>> = rules
        .filter { it.level == SourceLevel.APP_LINK || it.level == SourceLevel.APP_SEARCH }
        .groupBy { it.packageName }

    override fun resolve(capture: Capture): SourceRef? {
        val rules = byPackage[capture.packageName] ?: return null
        if (!apps.isInstalled(capture.packageName)) return null
        for (rule in rules) {
            val uri = rule.uriFor(capture) ?: continue
            if (!schemes.isAllowed(SourceSchemes.schemeOf(uri))) continue
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(capture.packageName)
            return SourceRef(rule.level, intent.toUri(Intent.URI_INTENT_SCHEME), apps.label(capture.packageName))
        }
        return null
    }
}

/** 3. The application, on its home screen. */
class LaunchAppResolver(private val apps: InstalledApps) : SourceStep {
    override fun resolve(capture: Capture): SourceRef? {
        val intent = apps.launchIntent(capture.packageName) ?: return null
        return SourceRef(
            level = SourceLevel.APP_ONLY,
            intentUri = intent.toUri(Intent.URI_INTENT_SCHEME),
            appLabel = apps.label(capture.packageName),
        )
    }
}

/**
 * The resolution chain: the first step that answers wins, otherwise [SourceLevel.UNAVAILABLE]
 * (EF-SRC-04). Resolved at display time from the stored elements, never from a stored Intent (§2).
 */
class ChainSourceResolver(private val apps: InstalledApps, private val steps: List<SourceStep>) : SourceResolver {
    override fun resolve(capture: Capture): SourceRef = steps.firstNotNullOfOrNull { it.resolve(capture) }
        ?: SourceRef(SourceLevel.UNAVAILABLE, intentUri = null, appLabel = apps.label(capture.packageName))

    override fun linksIn(capture: Capture): List<String> = SourceLinks.linksIn(capture.text)

    companion object {
        /** The chain of docs/specs/retour-source.md §3, with the application [rules] (M7-02). */
        fun create(
            apps: InstalledApps,
            rules: List<AppRule> = emptyList(),
            schemes: SourceSchemes = schemesFor(rules),
        ): ChainSourceResolver = ChainSourceResolver(
            apps,
            listOf(BrowserUrlResolver(apps), AppRuleResolver(apps, rules, schemes), LaunchAppResolver(apps)),
        )

        /** The schemes admitted for [rules]: the built-in ones and those of the rules (§5). */
        fun schemesFor(rules: List<AppRule>): SourceSchemes = SourceSchemes(rules.map { it.scheme })
    }
}
