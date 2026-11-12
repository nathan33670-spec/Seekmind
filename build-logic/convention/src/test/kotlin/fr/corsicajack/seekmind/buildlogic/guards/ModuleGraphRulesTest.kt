package fr.corsicajack.seekmind.buildlogic.guards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleGraphRulesTest {
    private fun violations(vararg edges: String) = ModuleGraphRules.violations(edges.map(ModuleEdge::parse))

    @Test
    fun `accepts the allowed architecture`() {
        assertEquals(
            emptyList(),
            violations(
                ":app|implementation|:feature:search",
                ":app|adsImplementation|:engine:ads",
                ":feature:search|implementation|:engine:search",
                ":feature:search|implementation|:core:designsystem",
                ":engine:capture|implementation|:engine:privacy",
                ":engine:search|implementation|:engine:embedding",
                ":engine:index|implementation|:core:database",
                ":engine:ads|implementation|:core:model",
                ":core:model|api|:core:common",
                ":engine:search|testImplementation|:core:testing",
                ":engine:search|debugUnitTestCompileClasspath|:engine:search",
            ),
        )
    }

    @Test
    fun `rejects an engine module depending on a feature module`() {
        assertTrue(
            violations(
                ":engine:search|implementation|:feature:search",
            ).single().startsWith(":engine:search -> :feature:search"),
        )
    }

    @Test
    fun `rejects the ads module depending on the database`() {
        assertTrue(violations(":engine:ads|implementation|:core:database").single().contains("may only depend on"))
    }

    @Test
    fun `rejects the ads module outside the ads variant of the app`() {
        assertEquals(
            2,
            violations(":app|implementation|:engine:ads", ":engine:monetization|implementation|:engine:ads").size,
        )
    }

    @Test
    fun `rejects feature to feature and undeclared engine to engine dependencies`() {
        assertEquals(
            2,
            violations(
                ":feature:search|implementation|:feature:journal",
                ":engine:privacy|implementation|:engine:search",
            ).size,
        )
    }

    @Test
    fun `rejects test helpers in production configurations`() {
        assertTrue(
            violations(":engine:search|implementation|:core:testing").single().contains("test configurations only"),
        )
    }
}
