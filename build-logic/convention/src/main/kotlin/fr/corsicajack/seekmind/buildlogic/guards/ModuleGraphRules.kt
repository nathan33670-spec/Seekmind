package fr.corsicajack.seekmind.buildlogic.guards

/** A project dependency declared in a configuration: `from` depends on `to`. */
data class ModuleEdge(val from: String, val configuration: String, val to: String) {
    fun serialize() = "$from|$configuration|$to"

    companion object {
        fun parse(text: String) = text.split('|').let { ModuleEdge(it[0], it[1], it[2]) }
    }
}

/** Allowed module graph of docs/03-architecture.md §3.2. */
object ModuleGraphRules {
    private const val ADS = ":engine:ads"
    private const val TESTING = ":core:testing"

    private val engineToEngine = mapOf(
        ":engine:capture" to setOf(":engine:privacy", ":engine:ocr", ":engine:index"),
        ":engine:index" to setOf(":engine:embedding"),
        ":engine:search" to setOf(":engine:embedding"),
    )
    private val coreToCore = mapOf(
        ":core:common" to emptySet(),
        ":core:model" to setOf(":core:common"),
        ":core:database" to setOf(":core:common", ":core:model"),
        ":core:designsystem" to setOf(":core:common", ":core:model"),
    )

    fun violations(edges: Collection<ModuleEdge>): List<String> = edges.mapNotNull { edge ->
        reason(edge)?.let { "${edge.from} -> ${edge.to} (${edge.configuration}): $it" }
    }.sorted()

    private fun reason(edge: ModuleEdge): String? {
        val (from, configuration, to) = edge
        val isTest = configuration.contains("test", ignoreCase = true)
        return when {
            // AGP makes a module's own test classpaths depend on the module itself.
            from == to -> null

            to == ADS && !(from == ":app" && configuration.startsWith("ads")) ->
                ":engine:ads may only be used by :app in the ads variant"

            from == ADS && to !in setOf(":core:common", ":core:model") && !(isTest && to == TESTING) ->
                ":engine:ads may only depend on :core:common and :core:model"

            isTest && to == TESTING -> null

            to == TESTING -> ":core:testing is for test configurations only"

            from == ":app" -> null

            from == ":fixtureapp" -> ":fixtureapp must stay independent of the product modules"

            // Microbenchmarks of the core modules (ticket M1-07), shipped nowhere.
            from == ":benchmark" -> if (to.startsWith(":core:")) null else ":benchmark may only measure core modules"

            from.startsWith(":feature:") -> when {
                to.startsWith(
                    ":feature:",
                ) -> "a feature module must not depend on another feature (navigation goes through :app)"

                to.startsWith(":engine:") || to.startsWith(":core:") -> null

                else -> "a feature module may only depend on engine and core modules"
            }

            from.startsWith(":engine:") -> when {
                to.startsWith(":core:") && to != ":core:designsystem" -> null
                to in engineToEngine[from].orEmpty() -> null
                else -> "not an allowed dependency of an engine module"
            }

            from == TESTING -> if (to.startsWith(":core:")) null else ":core:testing may only depend on core modules"

            from.startsWith(":core:") -> if (to in
                coreToCore[from].orEmpty()
            ) {
                null
            } else {
                "not an allowed dependency of a core module"
            }

            else -> "module not covered by the architecture"
        }
    }
}
