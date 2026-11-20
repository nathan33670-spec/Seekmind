package fr.corsicajack.seekmind

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.DISCLOSURE_VERSION
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.di.CaptureEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource

/**
 * The set-up of the end-to-end capture tests (docs/06-qualite-tests-ci.md §4.1): SeekMind's own
 * accessibility service on, the consent and every family given, no pause, no rule for the fixture, and
 * the events of the pipeline collected from the start. Settings and rules are put back afterwards.
 * Create it before any other UiAutomation, or accessibility services are suppressed.
 *
 * Families are given by package: the fixture's `SENDTO smsto:` filter puts the whole fixture, the
 * sentinel PlainTextActivity included, in « messageries ». Disabling that family stops every fixture screen.
 */
class CaptureHarness : ExternalResource() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation: UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val context = instrumentation.targetContext
    val graph: CaptureEntryPoint = EntryPointAccessors.fromApplication(context, CaptureEntryPoint::class.java)
    private val service = "${context.packageName}/fr.corsicajack.seekmind.engine.capture.SeekMindAccessibilityService"
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<PipelineEvent>(Channel.UNLIMITED)
    private var services: String? = null
    private var accessibility: String? = null

    /** When the test started: captures seen before belong to earlier tests. */
    var startedAt = 0L
        private set

    override fun before() = runBlocking<Unit> {
        startedAt = System.currentTimeMillis()
        services = setting("enabled_accessibility_services")
        accessibility = setting("accessibility_enabled")
        scope.launch { graph.pipeline().events.collect { events.send(it) } }
        val database = database()
        database.secureProbes.deleteOlderThan(Long.MAX_VALUE)
        database.appRules.remove(FIXTURE)
        graph.settings().acceptConsent(Consent(DISCLOSURE_VERSION, startedAt), AppFamily.entries.toSet())
        graph.settings().setPausedUntil(null)
        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
    }

    override fun after() = runBlocking<Unit> {
        shell("am force-stop $FIXTURE")
        restore("enabled_accessibility_services", services)
        restore("accessibility_enabled", accessibility)
        database().appRules.remove(FIXTURE)
        graph.settings().reset()
        scope.cancel()
    }

    fun database(): DatabaseHandle = (graph.database() as DatabaseState.Ready).database

    fun open(activity: String) {
        shell("am start -W -S -n $FIXTURE/$FIXTURE.$activity")
    }

    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    /** The first event matching [condition] within [timeoutMs]; the others are skipped, and reported on failure. */
    suspend fun awaitEvent(
        what: String,
        timeoutMs: Long = TIMEOUT_MS,
        condition: suspend (PipelineEvent) -> Boolean,
    ): PipelineEvent {
        val skipped = mutableListOf<PipelineEvent>()
        return try {
            withTimeout(timeoutMs) {
                var event = events.receive()
                while (!condition(event)) {
                    skipped += event
                    event = events.receive()
                }
                event
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "timed out waiting for $what; events seen: $skipped; " +
                    "${graph.router().diagnostics()}; ${graph.loop().diagnostics()}",
                e,
            )
        }
    }

    /** The capture of a `Stored` or `Deduplicated` event when it belongs to the fixture and holds [marker]. */
    suspend fun fixtureCapture(event: PipelineEvent, marker: String): Capture? {
        val id = when (event) {
            is PipelineEvent.Stored -> event.captureId
            is PipelineEvent.Deduplicated -> event.captureId
            is PipelineEvent.Dropped -> return null
        }
        return database().captures.get(id)?.takeIf {
            it.packageName == FIXTURE &&
                it.text.contains(marker, ignoreCase = true)
        }
    }

    /** Waits for the capture of the fixture screen showing [marker], stored or deduplicated. */
    suspend fun awaitCapture(marker: String): Pair<PipelineEvent, Capture> =
        awaitCaptureAfter(marker).let { (event, capture, _) -> event to capture }

    /** As [awaitCapture], with the events of the pipeline that came before it. */
    suspend fun awaitCaptureAfter(marker: String): Triple<PipelineEvent, Capture, List<PipelineEvent>> {
        var capture: Capture? = null
        val before = mutableListOf<PipelineEvent>()
        val event = awaitEvent("a capture holding $marker") {
            val found = fixtureCapture(it, marker)?.also { c -> capture = c } != null
            if (!found) before += it
            found
        }
        return Triple(event, checkNotNull(capture), before)
    }

    /** Captures holding [marker] seen since the start of the test. */
    suspend fun seenSinceStart(marker: String): List<Capture> {
        val ids = database().search.lexical("\"$marker\"", SearchFilters(), LIMIT).map { it.captureId }
        return database().search.captures(ids).filter { it.lastSeenAt >= startedAt }
    }

    private fun setting(name: String): String? = shell("settings get secure $name").trim().takeIf {
        it.isNotEmpty() &&
            it != "null"
    }

    private fun restore(name: String, value: String?) {
        shell(if (value == null) "settings delete secure $name" else "settings put secure $name $value")
    }

    companion object {
        const val FIXTURE = "fr.corsicajack.seekmind.fixture"
        const val TIMEOUT_MS = 60_000L
        private const val LIMIT = 50
    }
}
