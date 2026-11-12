package fr.corsicajack.seekmind.core.model

/** Right to search (docs/specs/monetisation.md §2). */
sealed interface Entitlement {
    data class Free(val balance: Int) : Entitlement

    data class Activated(val licenseId: String, val plan: String) : Entitlement
}

sealed interface Authorization {
    data class Granted(val willCharge: Boolean) : Authorization

    data object NoCredit : Authorization
}

enum class RewardReadiness { READY, NO_FILL, OFFLINE, NOT_ALLOWED, ERROR }

enum class RewardOutcome { EARNED, DISMISSED, FAILED }
