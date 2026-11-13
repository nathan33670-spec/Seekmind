package fr.corsicajack.seekmind.feature.journal

import android.os.Build
import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.designsystem.theme.SeekMindTheme
import fr.corsicajack.seekmind.core.testing.SyntheticDatabase
import fr.corsicajack.seekmind.core.testing.TemporaryDatabase
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Criterion 1 of M6-05: scrolling the journal of a synthetic database of 100,000 captures loads pages
 * of 50 as the scroll goes, never the whole journal.
 */
@RunWith(AndroidJUnit4::class)
class JournalScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = TemporaryDatabase(context, "journal_scroll")
    private val commitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        val ms = runBlocking { SyntheticDatabase(database.open()).populate(COUNT) }
        Log.i(TAG, "m6_05_populate_ms=$ms")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) compose.enableAccessibilityChecks()
    }

    @After
    fun tearDown() {
        commitScope.cancel()
        database.delete()
    }

    @Test
    fun scrollingLoadsPagesOnDemand() {
        val handle = database.open()
        val zone = ZoneId.systemDefault()
        val model = JournalViewModel(
            store = { handle.captures },
            labels = { it.substringAfterLast('.') },
            zone = { zone },
            io = Dispatchers.IO,
            commitScope = commitScope,
        )
        val listState = LazyListState()
        compose.setContent {
            SeekMindTheme(dynamicColor = false) {
                val state by model.state.collectAsStateWithLifecycle()
                JournalScreen(
                    state = state,
                    zone = zone,
                    today = { LocalDate.now(zone) },
                    onLoadMore = model::loadMore,
                    onFilter = model::setFilter,
                    onDelete = model::requestDelete,
                    onUndo = model::undoDelete,
                    onCommit = model::commitPending,
                    onDeleteDay = model::deleteDay,
                    listState = listState,
                )
            }
        }
        compose.waitUntil(TIMEOUT_MS) { loaded(model) > 0 }
        assertThat(loaded(model)).isEqualTo(JournalViewModel.PAGE_SIZE)

        var target = 0
        repeat(STEPS) {
            target += 60
            compose.onNodeWithTag(JOURNAL_LIST_TAG).performScrollToIndex(target)
            compose.waitUntil(TIMEOUT_MS) { listState.layoutInfo.totalItemsCount > target + 1 }
        }
        compose.waitForIdle()

        val entries = model.state.value.days.flatMap { it.entries }
        Log.i(TAG, "m6_05_loaded_after_scroll=${entries.size}")
        // Lines (and day headers) up to the target, plus at most a page and the prefetch distance.
        assertThat(entries.size % JournalViewModel.PAGE_SIZE).isEqualTo(0)
        assertThat(entries.size).isAtLeast(target - 100)
        assertThat(entries.size).isAtMost(target + 2 * JournalViewModel.PAGE_SIZE)
        assertThat(entries.size).isLessThan(COUNT / 100)
        // Most recent first, by (last seen, id), without duplicate across pages.
        assertThat(entries.map { it.captureId }.toSet()).hasSize(entries.size)
        entries.zipWithNext().forEach { (a, b) ->
            assertThat(
                a.lastSeenAt > b.lastSeenAt || (a.lastSeenAt == b.lastSeenAt && a.captureId > b.captureId),
            ).isTrue()
        }
    }

    private fun loaded(model: JournalViewModel) = model.state.value.days.sumOf { it.entries.size }

    private companion object {
        const val TAG = "SeekMindBench"
        const val COUNT = 100_000
        const val STEPS = 5
        const val TIMEOUT_MS = 20_000L
    }
}
