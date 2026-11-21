package fr.corsicajack.seekmind.feature.settings

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.Delivery
import fr.corsicajack.seekmind.core.model.ModelSpec
import fr.corsicajack.seekmind.core.model.PackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Settings → Langues de recherche (ticket M6-06, EF-LANG-02 to 04). */
class LanguageSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class FakePacks : LanguagePacks {
        override val models = listOf(FR, MULTI)
        override val active = MutableStateFlow(FR)
        val multiState = MutableStateFlow<PackState>(PackState.NotInstalled)
        val activated = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var installs = 0
        var validImport = true
        var failActivation = false
        override val importsFiles = true

        override fun state(id: String): Flow<PackState> =
            if (id == MULTI.id) multiState else flowOf(PackState.Installed)

        override suspend fun install(id: String): Flow<PackState> {
            installs++
            multiState.value = PackState.Failed(-1)
            return multiState
        }

        override suspend fun import(id: String, uri: String): Boolean {
            if (validImport) multiState.value = PackState.Installed
            return validImport
        }

        override suspend fun activate(id: String) {
            if (failActivation) error("damaged")
            activated += id
            active.value = models.first { it.id == id }
        }

        override suspend fun remove(id: String) {
            removed += id
            multiState.value = PackState.NotInstalled
            active.value = FR
        }
    }

    private val packs = FakePacks()
    private var pending = 0

    private fun model() = LanguageSettingsViewModel(packs = { packs }, pendingFragments = { pending }, pollMs = 10)

    private suspend fun LanguageSettingsViewModel.current(): LanguageUiState = state.filterNotNull().first()

    @Test
    fun `the rows show the active model and the state of the pack, nothing downloaded`() = runTest {
        val state = model().current()
        assertThat(state.rows.map { it.spec.id to it.active }).containsExactly("fr" to true, "multi" to false).inOrder()
        assertThat(state.rows.last().state).isEqualTo(PackState.NotInstalled)
        assertThat(packs.installs).isEqualTo(0)
    }

    @Test
    fun `changing the model asks for a confirmation first`() = runTest {
        packs.multiState.value = PackState.Installed
        val model = model()
        model.current()
        model.askToUse("multi")
        assertThat(model.current().confirming).isEqualTo(MULTI)
        assertThat(packs.activated).isEmpty()
        model.confirmUse()
        assertThat(packs.activated).containsExactly("multi")
        val state = model.current()
        assertThat(state.confirming).isNull()
        assertThat(state.rows.first { it.spec == MULTI }.active).isTrue()
    }

    @Test
    fun `a cancelled confirmation changes nothing`() = runTest {
        packs.multiState.value = PackState.Installed
        val model = model()
        model.current()
        model.askToUse("multi")
        model.cancelUse()
        assertThat(packs.activated).isEmpty()
        assertThat(model.current().confirming).isNull()
    }

    @Test
    fun `the rebuild progress is the count of the pending fragments`() = runTest {
        pending = 1_234
        assertThat(model().state.filterNotNull().first { it.pendingFragments > 0 }.pendingFragments).isEqualTo(1_234)
    }

    @Test
    fun `an invalid file is reported and installs nothing`() = runTest {
        packs.validImport = false
        val model = model()
        model.current()
        model.import("multi", "content://picked")
        val state = model.current()
        assertThat(state.error).isEqualTo(LanguageError.IMPORT_INVALID)
        assertThat(state.rows.last().state).isEqualTo(PackState.NotInstalled)
        model.errorShown()
        assertThat(model.current().error).isNull()
    }

    @Test
    fun `a failed download and a failed activation are reported`() = runTest {
        val model = model()
        model.current()
        model.download("multi")
        assertThat(model.current().error).isEqualTo(LanguageError.DOWNLOAD_FAILED)
        packs.multiState.value = PackState.Installed
        packs.failActivation = true
        model.askToUse("multi")
        model.confirmUse()
        assertThat(model.current().error).isEqualTo(LanguageError.ACTIVATION_FAILED)
        assertThat(packs.active.value).isEqualTo(FR)
    }

    @Test
    fun `removing the active pack brings French back`() = runTest {
        packs.multiState.value = PackState.Installed
        packs.active.value = MULTI
        val model = model()
        model.current()
        model.remove("multi")
        assertThat(packs.removed).containsExactly("multi")
        assertThat(model.current().rows.first().active).isTrue()
    }

    private companion object {
        val FR = ModelSpec("fr", 1, setOf("fr"), 37_000_000, Delivery.BUNDLED, null)
        val MULTI = ModelSpec("multi", 2, emptySet(), 118_000_000, Delivery.PACK, "modelpack_multi")
    }
}
