package fr.corsicajack.seekmind.feature.journal

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.feature.journal.FakeCaptureStore.Companion.summary
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val zone = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 11, 13)
    private val noon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val deletions = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    private val told = mutableListOf<Long>()

    private fun viewModel(store: FakeCaptureStore?) = JournalViewModel(
        store = { store },
        labels = { pkg -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } },
        zone = { zone },
        io = dispatcher,
        commitScope = CoroutineScope(dispatcher),
        deletions = deletions,
        onDeleted = { told += it },
    )

    private fun JournalViewModel.ids() = state.value.days.flatMap { day -> day.entries.map { it.captureId } }

    @Test
    fun `the first page has 50 lines and the next ones come on demand`() = runTest(dispatcher) {
        val store = FakeCaptureStore((1L..120L).map { summary(it, noon - it * 60_000) })
        val model = viewModel(store)

        assertThat(model.ids()).containsExactlyElementsIn(1L..50L).inOrder()
        assertThat(model.state.value.endReached).isFalse()

        model.loadMore()
        model.loadMore()
        assertThat(model.ids()).containsExactlyElementsIn(1L..120L).inOrder()
        assertThat(model.state.value.endReached).isTrue()
        // Three pages, each from the last line of the previous one.
        assertThat(store.pageRequests.map { it.first?.captureId }).containsExactly(null, 50L, 100L).inOrder()

        model.loadMore()
        assertThat(store.pageRequests).hasSize(3)
    }

    @Test
    fun `lines are grouped by day, most recent first`() = runTest(dispatcher) {
        val store = FakeCaptureStore(
            listOf(
                summary(1, noon),
                summary(2, noon - 3_600_000),
                summary(3, noon - 24 * 3_600_000),
            ),
        )
        val days = viewModel(store).state.value.days
        assertThat(days.map { it.date }).containsExactly(day, day.minusDays(1)).inOrder()
        assertThat(days[0].entries.map { it.captureId }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `a line shows the window title, or else the first line of the text`() = runTest(dispatcher) {
        val store = FakeCaptureStore(
            listOf(
                summary(1, noon, title = "  Commande  "),
                summary(2, noon - 1, title = " ", preview = "\n  Première ligne\nSeconde"),
            ),
        )
        val entries = viewModel(store).state.value.days.single().entries
        assertThat(entries.map { it.headline }).containsExactly("Commande", "Première ligne").inOrder()
        assertThat(entries.first().appLabel).isEqualTo("Notes")
    }

    @Test
    fun `the filter keeps one application and lists those of the journal`() = runTest(dispatcher) {
        val store = FakeCaptureStore(
            listOf(
                summary(1, noon, pkg = "com.example.mail"),
                summary(2, noon - 1),
                summary(3, noon - 2, pkg = "com.example.mail"),
            ),
        )
        val model = viewModel(store)
        assertThat(
            model.state.value.apps.map {
                it.packageName
            },
        ).containsExactly("com.example.mail", "com.example.notes").inOrder()

        model.setFilter("com.example.mail")
        assertThat(model.ids()).containsExactly(1L, 3L).inOrder()
        assertThat(store.pageRequests.last()).isEqualTo(null to "com.example.mail")

        model.setFilter(null)
        assertThat(model.ids()).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `undoing a deletion brings the line back and deletes nothing`() = runTest(dispatcher) {
        val store = FakeCaptureStore(listOf(summary(1, noon), summary(2, noon - 1)))
        val model = viewModel(store)

        model.requestDelete(1)
        assertThat(model.ids()).containsExactly(2L)
        assertThat(model.state.value.pendingDeletion).isEqualTo(1L)

        model.undoDelete()
        assertThat(model.ids()).containsExactly(1L, 2L).inOrder()
        assertThat(store.rows.map { it.id }).containsExactly(1L, 2L)
    }

    @Test
    fun `without undo the capture is deleted from the database`() = runTest(dispatcher) {
        val store = FakeCaptureStore(listOf(summary(1, noon), summary(2, noon - 1)))
        val model = viewModel(store)

        model.requestDelete(1)
        model.commitPending()
        assertThat(model.ids()).containsExactly(2L)
        assertThat(store.rows.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `a deletion here is told to the other screens`() = runTest(dispatcher) {
        val store = FakeCaptureStore(listOf(summary(1, noon), summary(2, noon - 1)))
        val model = viewModel(store)

        model.requestDelete(1)
        assertThat(told).isEmpty()
        model.commitPending()
        assertThat(told).containsExactly(1L)
    }

    @Test
    fun `a capture deleted from its detail leaves the journal`() = runTest(dispatcher) {
        val store = FakeCaptureStore(listOf(summary(1, noon), summary(2, noon - 1)))
        val model = viewModel(store)

        deletions.emit(1)
        assertThat(model.ids()).containsExactly(2L)
        assertThat(told).isEmpty()
    }

    @Test
    fun `a second deletion confirms the first one`() = runTest(dispatcher) {
        val store = FakeCaptureStore(listOf(summary(1, noon), summary(2, noon - 1), summary(3, noon - 2)))
        val model = viewModel(store)

        model.requestDelete(1)
        model.requestDelete(2)
        assertThat(store.rows.map { it.id }).containsExactly(2L, 3L)
        assertThat(model.ids()).containsExactly(3L)

        model.undoDelete()
        assertThat(model.ids()).containsExactly(2L, 3L).inOrder()
    }

    @Test
    fun `deleting a day removes every capture seen that day`() = runTest(dispatcher) {
        val store = FakeCaptureStore(
            listOf(summary(1, noon), summary(2, noon - 11 * 3_600_000), summary(3, noon - 24 * 3_600_000)),
        )
        val model = viewModel(store)

        model.deleteDay(day)
        assertThat(store.rows.map { it.id }).containsExactly(3L)
        assertThat(model.ids()).containsExactly(3L)
    }

    @Test
    fun `an unavailable database is reported`() = runTest(dispatcher) {
        val state = viewModel(null).state.value
        assertThat(state.unavailable).isTrue()
        assertThat(state.loading).isFalse()
    }

    @Test
    fun `an empty journal says so`() = runTest(dispatcher) {
        val state = viewModel(FakeCaptureStore(emptyList())).state.value
        assertThat(state.empty).isTrue()
        assertThat(state.endReached).isTrue()
    }
}
