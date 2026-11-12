package fr.corsicajack.seekmind.core.testing

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.SearchFilters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The synthetic database generator (ticket M1-07). Durations are logged under `SeekMindBench`. */
@RunWith(AndroidJUnit4::class)
class SyntheticDatabaseTest {
    private val database = TemporaryDatabase(InstrumentationRegistry.getInstrumentation().targetContext, "synthetic")

    @After
    fun clean() = database.delete()

    @Test
    fun oneHundredThousandCapturesInLessThanTwoMinutes() = runBlocking<Unit> {
        database.delete()
        val handle = database.open()
        val ms = SyntheticDatabase(handle).populate(100_000)
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} generate_100k_captures_ms=$ms")
        assertThat(handle.captures.stats().captureCount).isEqualTo(100_000)
        assertThat(handle.search.lexical("facture", SearchFilters(), 10)).hasSize(10)
        assertThat(ms).isLessThan(120_000)
    }

    @Test
    fun capturesWithVectors() = runBlocking<Unit> {
        database.delete()
        val handle = database.open()
        val ms = SyntheticDatabase(handle).populate(10_000, vectors = true)
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} generate_10k_with_vectors_ms=$ms")
        assertThat(handle.vectors.pendingCount()).isEqualTo(0)
        val page = handle.vectors.binaryCodesAfter(0, 5_000)
        assertThat(page).hasSize(5_000)
        assertThat(page.first().vecBin).hasLength(48)
    }

    private companion object {
        const val TAG = "SeekMindBench"
    }
}
