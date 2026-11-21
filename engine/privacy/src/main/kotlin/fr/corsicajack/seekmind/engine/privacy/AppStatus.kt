package fr.corsicajack.seekmind.engine.privacy

import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.engine.privacy.apps.SensitiveApps

/** Why an application is remembered or not (docs/specs/confidentialite.md §2, §3.3): the rule that decided. */
enum class AppStatusReason(val dropReason: DropReason?) {
    /** PG-01: SeekMind, the system interface, the launcher, the keyboard. */
    IGNORED(DropReason.APP_IGNORED),

    /** PG-02: password managers, authentication; the user cannot override it. */
    STRICT_LIST(DropReason.APP_SENSITIVE),

    /** The user's choices are not read yet: in doubt, rejected. */
    CHOICES_UNKNOWN(DropReason.APP_EXCLUDED),

    /** PG-03. */
    USER_EXCLUDED(DropReason.APP_EXCLUDED),

    /** PG-04. */
    USER_INCLUDED(null),

    /** PG-05: banking, payment, health, administration. */
    SOFT_LIST(DropReason.APP_SENSITIVE),

    /** PG-06. */
    GAME(DropReason.APP_CATEGORY),

    /** PG-14. */
    FAMILY_DISABLED(DropReason.APP_FAMILY_DISABLED),

    /** Remembered because its family is enabled. */
    FAMILY_ENABLED(null),
}

/**
 * The effective state of an application and its origin, as Settings → Applications shows it. [family] is
 * the family of the package, null for a sensitive one; [level] its sensitive list, if any.
 */
data class AppStatus(val reason: AppStatusReason, val family: AppFamily?, val level: SensitiveApps.Level?) {
    val remembered: Boolean get() = reason.dropReason == null

    /** No user rule can change it: the always-ignored packages and the strict list. */
    val locked: Boolean get() = reason == AppStatusReason.IGNORED || level == SensitiveApps.Level.STRICT
}
