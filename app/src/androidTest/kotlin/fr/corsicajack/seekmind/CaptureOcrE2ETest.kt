package fr.corsicajack.seekmind

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.TextSource
import fr.corsicajack.seekmind.di.CaptureEntryPoint
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E-03, E2E-05 and E2E-06 (docs/06-qualite-tests-ci.md §4, ticket M3-05) with SeekMind's own
 * accessibility service and the real pipeline: a secure window is dropped, the text of an image is
 * stored from the OCR and found by the keyword search, and nothing but the database is written.
 * Synchronised on [CapturePipeline.events][fr.corsicajack.seekmind.engine.capture.pipeline.CapturePipeline.events],
 * never on fixed delays.
 */
@RunWith(AndroidJUnit4::class)
class CaptureOcrE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Must be obtained before any other UiAutomation, otherwise accessibility services are suppressed.
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext
    private val graph = EntryPointAccessors.fromApplication(context, CaptureEntryPoint::class.java)
    private val service = "${context.packageName}/fr.corsicajack.seekmind.engine.capture.SeekMindAccessibilityService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<PipelineEvent>(Channel.UNLIMITED)

    private var services: String? = null
    private var accessibility: String? = null

    @Before
    fun setUp() = runBlocking {
        services = setting("enabled_accessibility_services")
        accessibility = setting("accessibility_enabled")
        // The receiver is subscribed before anything can be captured.
        scope.launch { graph.pipeline().events.collect { events.send(it) } }
        graph.settings().acceptConsent(Consent(version = 1, acceptedAt = System.currentTimeMillis()))
        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
    }

    @After
    fun tearDown() = runBlocking {
        shell("am force-stop $FIXTURE")
        restore("enabled_accessibility_services", services)
        restore("accessibility_enabled", accessibility)
        graph.settings().reset()
        scope.cancel()
    }

    @Test
    fun e2e03_aSecureWindowIsDropped() = runBlocking {
        open("SecureActivity")
        awaitEvent("SECURE_WINDOW") { it == PipelineEvent.Dropped(DropReason.SECURE_WINDOW) }
        // The marker of the secure screen is nowhere in the database.
        assertThat(database().search.lexical("\"SEEKMIND_FIXTURE_SECURE\"", SearchFilters(), 10)).isEmpty()
    }

    @Test
    fun e2e05and06_theTextOfAnImageIsStoredFromTheOcrAndNothingElseIsWritten() = runBlocking {
        // Opened once, so that the database exists before the snapshot of the files.
        open("PlainTextActivity")
        awaitEvent("a stored plain screen") { it is PipelineEvent.Stored || it is PipelineEvent.Deduplicated }
        val before = files()

        open("ImageTextActivity")
        val stored = awaitEvent("the image screen stored") { event ->
            event is PipelineEvent.Stored && database().captures.get(event.captureId)?.packageName == FIXTURE
        } as PipelineEvent.Stored
        val capture = checkNotNull(database().captures.get(stored.captureId))
        assertThat(capture.textSource and TextSource.OCR).isEqualTo(TextSource.OCR)
        // E2E-05: the code printed in the image is found by the keyword search.
        assertThat(database().search.lexical("\"4821\"", SearchFilters(), 10).map { it.captureId })
            .contains(stored.captureId)

        // E2E-06: no file outside the database files.
        val created = files() - before
        assertWithMessage("files created during the capture").that(created).isEmpty()
    }

    private fun database() = (graph.database() as DatabaseState.Ready).database

    private fun open(activity: String) {
        shell("am start -W -S -n $FIXTURE/$FIXTURE.$activity")
    }

    /** The first event matching [condition], within [TIMEOUT_MS]; the others are skipped. */
    private suspend fun awaitEvent(what: String, condition: suspend (PipelineEvent) -> Boolean): PipelineEvent = try {
        withTimeout(TIMEOUT_MS) {
            var event = events.receive()
            while (!condition(event)) event = events.receive()
            event
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for $what", e)
    }

    /** Every file of the application's data directory but the databases (Room, SQLCipher, WorkManager). */
    private fun files(): Set<String> {
        val root = context.dataDir
        return root.walkTopDown()
            .onEnter { it.name != "databases" }
            .filter(File::isFile)
            .map { it.relativeTo(root).path }
            .toSet()
    }

    private fun setting(name: String): String? = shell("settings get secure $name").trim().takeIf {
        it.isNotEmpty() &&
            it != "null"
    }

    private fun restore(name: String, value: String?) {
        shell(if (value == null) "settings delete secure $name" else "settings put secure $name $value")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    private companion object {
        const val FIXTURE = "fr.corsicajack.seekmind.fixture"
        const val TIMEOUT_MS = 60_000L
    }
}
