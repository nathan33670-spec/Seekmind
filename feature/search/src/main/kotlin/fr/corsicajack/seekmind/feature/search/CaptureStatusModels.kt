package fr.corsicajack.seekmind.feature.search

/** What the user can do from the capture status banner (docs/specs/ui-ux.md §3.2, EF-CAP-05). */
enum class StatusAction {
    /** « Reprendre »: ends the pause. */
    RESUME,

    /** « Réactiver »: opens the system accessibility settings. */
    REACTIVATE,

    /** « Activer la mémorisation »: reopens the disclosure (M6-02). */
    ENABLE,

    /** « Le service s'arrête tout seul ? »: the troubleshooting page. */
    HELP,
}

/** Time since the last capture, as « Dernière mémorisation il y a … » says it. */
sealed interface Elapsed {
    data object JustNow : Elapsed

    data class Minutes(val count: Int) : Elapsed

    data class Hours(val count: Int) : Elapsed

    data class Days(val count: Int) : Elapsed

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS

        /** Under a minute: just now; a clock that went backwards counts as just now too. */
        fun between(from: Long, now: Long): Elapsed {
            val ms = now - from
            return when {
                ms < MINUTE_MS -> JustNow
                ms < HOUR_MS -> Minutes((ms / MINUTE_MS).toInt())
                ms < DAY_MS -> Hours((ms / HOUR_MS).toInt())
                else -> Days((ms / DAY_MS).toInt())
            }
        }
    }
}
