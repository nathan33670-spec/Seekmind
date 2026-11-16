package fr.corsicajack.seekmind.engine.source

import android.content.Context

/**
 * The resolver and the launcher of the return to the source, built from the same rules and therefore
 * the same admitted schemes: an Intent the resolver builds from a rule is one the launcher lets out.
 */
class SourceReturn(apps: InstalledApps, rules: List<AppRule>) {
    val schemes: SourceSchemes = ChainSourceResolver.schemesFor(rules)
    val resolver: ChainSourceResolver = ChainSourceResolver.create(apps, rules, schemes)
    val launcher: SourceLauncher = SourceLauncher(apps, schemes)

    companion object {
        /** With the rules of `assets/source_rules.json` and the [Context]'s package manager. */
        fun create(context: Context): SourceReturn =
            SourceReturn(PackageManagerApps(context.packageManager), SourceRules.load(context.assets))
    }
}
