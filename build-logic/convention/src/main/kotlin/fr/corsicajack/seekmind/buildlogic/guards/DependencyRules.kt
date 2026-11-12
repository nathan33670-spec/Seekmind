package fr.corsicajack.seekmind.buildlogic.guards

/** INV-11: every external runtime dependency must be on the allowlist of its variant. */
object DependencyRules {
    /** @param allowlist entries `group:name` or `group:*`. Versions are governed by the catalog. */
    fun violations(modules: Collection<String>, allowlist: List<String>): List<String> {
        val exact = allowlist.filterNot { it.endsWith(":*") }.toSet()
        val groups = allowlist.filter { it.endsWith(":*") }.map { it.removeSuffix(":*") }.toSet()
        return modules.toSortedSet()
            .filterNot { it in exact || it.substringBefore(':') in groups }
            .map { "Dependency not allowed: $it" }
    }
}
