package fr.corsicajack.seekmind.engine.privacy

import android.content.pm.ApplicationInfo
import fr.corsicajack.seekmind.core.database.store.AppRuleMode
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.GateDecision
import fr.corsicajack.seekmind.core.model.Redaction
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.PrivacyConfig
import fr.corsicajack.seekmind.core.model.port.PrivacyGate
import fr.corsicajack.seekmind.engine.privacy.apps.AppFamilies
import fr.corsicajack.seekmind.engine.privacy.apps.PackageFacts
import fr.corsicajack.seekmind.engine.privacy.apps.SensitiveApps
import fr.corsicajack.seekmind.engine.privacy.redaction.Redactor

/** The user's choices the gate reads: rules per package (§3.3) and enabled families (§3.4). */
data class UserPrivacyChoices(val rules: Map<String, AppRuleMode>, val enabledFamilies: Set<AppFamily>)

/**
 * The privacy gate (docs/specs/confidentialite.md §2, INV-05). In doubt, it rejects: before the user's
 * choices are read, every package is denied. Nothing disables it: it has no switch, and [PrivacyConfig]
 * only tunes the mark, the games rule and the markers.
 *
 * [ignoredPackages] is the always-ignored set of the capture service (PG-01); [browserPackages] those of
 * the browser table (PG-08); [choices] the latest user choices, null until first read.
 */
class DefaultPrivacyGate(
    private val config: PrivacyConfig,
    private val sensitive: SensitiveApps,
    private val families: AppFamilies,
    private val facts: PackageFacts,
    private val browserPackages: Set<String>,
    private val ignoredPackages: () -> Set<String>,
    private val choices: () -> UserPrivacyChoices?,
) : PrivacyGate {
    private val redactor = Redactor(config.redactionMark)
    private val markers = config.privateMarkers.map { it.lowercase() }

    /** PG-01, PG-02, PG-03, PG-04, PG-05, PG-06, PG-14, in this order. */
    override fun checkPackage(meta: ScreenMeta): GateDecision {
        val packageName = meta.packageName
        if (packageName in ignoredPackages()) return deny(DropReason.APP_IGNORED)
        val level = sensitive.levelOf(packageName)
        if (level == SensitiveApps.Level.STRICT) return deny(DropReason.APP_SENSITIVE)
        // The user's choices are not known yet: in doubt, reject (§1).
        val choices = choices() ?: return deny(DropReason.APP_EXCLUDED)
        when (choices.rules[packageName]) {
            AppRuleMode.EXCLUDED -> return deny(DropReason.APP_EXCLUDED)
            AppRuleMode.INCLUDED -> return GateDecision.Allow
            null -> Unit
        }
        if (level == SensitiveApps.Level.SOFT) return deny(DropReason.APP_SENSITIVE)
        if (config.excludeGames && facts.category(packageName) == ApplicationInfo.CATEGORY_GAME) {
            return deny(DropReason.APP_CATEGORY)
        }
        val family = families.familyOf(packageName) ?: return deny(DropReason.APP_SENSITIVE)
        if (family !in choices.enabledFamilies) return deny(DropReason.APP_FAMILY_DISABLED)
        return GateDecision.Allow
    }

    /** PG-07, PG-08. */
    override fun checkTree(meta: ScreenMeta, extraction: TreeExtraction): GateDecision {
        if (extraction.hasPasswordField) return deny(DropReason.PASSWORD_FIELD)
        if (meta.packageName in browserPackages && extraction.viewIds.any(::isPrivateMarker)) {
            return deny(DropReason.PRIVATE_BROWSING)
        }
        return GateDecision.Allow
    }

    /** PG-11 to PG-13. */
    override fun redact(text: String): Redaction = redactor.redact(text)

    private fun isPrivateMarker(viewId: String): Boolean {
        val name = viewId.substringAfter(":id/").lowercase()
        return markers.any { it in name }
    }

    private fun deny(reason: DropReason) = GateDecision.Deny(reason)
}
