package fr.corsicajack.seekmind.buildlogic.guards

import kotlin.test.Test
import kotlin.test.assertEquals

class DependencyRulesTest {
    private val allowlist = parseAllowlist(
        """
        # comment
        androidx.core:*            # whole group
        com.google.dagger:dagger
        """.trimIndent(),
    )

    @Test
    fun `accepts exact entries and whole groups`() {
        assertEquals(
            emptyList(),
            DependencyRules.violations(listOf("androidx.core:core-ktx", "com.google.dagger:dagger"), allowlist),
        )
    }

    @Test
    fun `rejects a dependency that is not on the allowlist`() {
        assertEquals(
            listOf("Dependency not allowed: com.squareup.okhttp3:okhttp"),
            DependencyRules.violations(listOf("com.squareup.okhttp3:okhttp", "androidx.core:core"), allowlist),
        )
    }

    @Test
    fun `a group wildcard does not cover sub-groups`() {
        assertEquals(
            listOf("Dependency not allowed: androidx.core.uwb:uwb"),
            DependencyRules.violations(listOf("androidx.core.uwb:uwb"), allowlist),
        )
    }
}
