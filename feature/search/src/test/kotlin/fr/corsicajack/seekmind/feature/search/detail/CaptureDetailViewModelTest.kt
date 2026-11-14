package fr.corsicajack.seekmind.feature.search.detail

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.database.store.CaptureStore
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.SourceLevel
import fr.corsicajack.seekmind.core.model.SourceRef
import fr.corsicajack.seekmind.core.model.port.SourceResolver
import fr.corsicajack.seekmind.core.testing.Models
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val captures = mutableMapOf<Long, Capture>()
    private val deletedFromStore = mutableListOf<Long>()
    private val told = mutableListOf<Long>()
    private var available = true
    private var failDelete = false

    private val resolver = object : SourceResolver {
        override fun resolve(capture: Capture) = SourceRef(SourceLevel.APP_SEARCH, "intent:#Intent;end", "Notes")

        override fun linksIn(capture: Capture) = listOf("https://exemple.fr/menu")
    }

    /** Only get and delete are used by the detail. */
    private val store: CaptureStore = Proxy.newProxyInstance(
        CaptureStore::class.java.classLoader,
        arrayOf(CaptureStore::class.java),
    ) { _, method, args ->
        when (method.name) {
            "get" -> captures[args[0] as Long]

            "delete" -> {
                if (failDelete) throw IllegalStateException("disk full")
                val id = args[0] as Long
                captures.remove(id)
                deletedFromStore += id
                Unit
            }

            else -> error("unexpected ${method.name}")
        }
    } as CaptureStore

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(id: Long, terms: List<String> = emptyList()) = CaptureDetailViewModel(
        captureId = id,
        terms = terms,
        store = { if (available) store else null },
        labels = { "Notes" },
        resolver = resolver,
        io = StandardTestDispatcher(testScheduler),
        onDeleted = { told += it },
    )

    @Test
    fun showsTheTextWithTheTermsAndTheLinks() = runTest(dispatcher) {
        captures[7] = Models.capture("La tarte du jour, voir https://exemple.fr/menu.", id = 7)
        val vm = viewModel(7, listOf("tarte"))
        advanceUntilIdle()
        val state = vm.state.value
        assertThat(state.capture?.id).isEqualTo(7)
        assertThat(state.appLabel).isEqualTo("Notes")
        assertThat(state.highlights).containsExactly(3..7)
        assertThat(state.links).containsExactly("https://exemple.fr/menu")
        assertThat(state.source?.level).isEqualTo(SourceLevel.APP_SEARCH)
    }

    @Test
    fun aCaptureDeletedMeanwhileIsMissing() = runTest(dispatcher) {
        val vm = viewModel(99)
        advanceUntilIdle()
        assertThat(vm.state.value.missing).isTrue()
    }

    @Test
    fun anUnavailableDatabase() = runTest(dispatcher) {
        available = false
        val vm = viewModel(7)
        advanceUntilIdle()
        assertThat(vm.state.value.unavailable).isTrue()
    }

    @Test
    fun deletingRemovesTheCaptureAndTellsTheOtherScreens() = runTest(dispatcher) {
        captures[7] = Models.capture("texte", id = 7)
        val vm = viewModel(7)
        advanceUntilIdle()
        vm.delete()
        advanceUntilIdle()
        assertThat(deletedFromStore).containsExactly(7L)
        assertThat(told).containsExactly(7L)
        assertThat(vm.state.value.deleted).isTrue()
    }

    @Test
    fun aFailedDeletionKeepsTheScreenAndSaysSo() = runTest(dispatcher) {
        captures[7] = Models.capture("texte", id = 7)
        failDelete = true
        val vm = viewModel(7)
        advanceUntilIdle()
        vm.delete()
        advanceUntilIdle()
        assertThat(told).isEmpty()
        assertThat(vm.state.value.deleted).isFalse()
        assertThat(vm.state.value.deleteFailed).isTrue()
    }
}
