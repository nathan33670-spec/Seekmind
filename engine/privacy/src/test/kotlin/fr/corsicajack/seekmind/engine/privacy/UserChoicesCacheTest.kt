package fr.corsicajack.seekmind.engine.privacy

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.AppRule
import fr.corsicajack.seekmind.core.database.store.AppRuleMode
import fr.corsicajack.seekmind.core.model.AppFamily
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The user's choices as the gate reads them (ticket M2-05). */
class UserChoicesCacheTest {
    @Test
    fun `null until both sources are read, then follows them`() = runTest {
        val rules = MutableSharedFlow<List<AppRule>>(replay = 1)
        val families = MutableStateFlow(setOf(AppFamily.OTHER))
        val cache = UserChoicesCache(backgroundScope, rules, families)
        runCurrent()
        assertThat(cache.current).isNull()

        rules.emit(listOf(AppRule("com.example.app", AppRuleMode.EXCLUDED, 0)))
        runCurrent()
        assertThat(cache.current).isEqualTo(
            UserPrivacyChoices(mapOf("com.example.app" to AppRuleMode.EXCLUDED), setOf(AppFamily.OTHER)),
        )

        families.value = setOf(AppFamily.MESSAGING)
        runCurrent()
        assertThat(cache.current?.enabledFamilies).containsExactly(AppFamily.MESSAGING)
    }

    @Test
    fun `a failing source leaves no stale choices`() = runTest {
        val rules = MutableSharedFlow<List<AppRule>>(replay = 1)
        val families = MutableStateFlow(setOf(AppFamily.OTHER))
        // The database fails when "boom" shows up.
        val failing = rules.map { list -> list.also { check(it.none { rule -> rule.packageName == "boom" }) } }
        val cache = UserChoicesCache(backgroundScope, failing, families)
        rules.emit(emptyList())
        runCurrent()
        assertThat(cache.current).isNotNull()
        rules.emit(listOf(AppRule("boom", AppRuleMode.EXCLUDED, -1)))
        runCurrent()
        assertThat(cache.current).isNull()
    }
}
