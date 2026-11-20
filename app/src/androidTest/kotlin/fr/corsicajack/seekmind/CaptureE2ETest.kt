package fr.corsicajack.seekmind

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.CaptureHarness.Companion.FIXTURE
import fr.corsicajack.seekmind.core.database.settings.Consent
import fr.corsicajack.seekmind.core.database.settings.DISCLOSURE_VERSION
import fr.corsicajack.seekmind.core.database.store.AppRule
import fr.corsicajack.seekmind.core.database.store.AppRuleMode
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.PipelineEvent
import fr.corsicajack.seekmind.core.model.SearchFilters
import fr.corsicajack.seekmind.core.model.config.CaptureConfig
import fr.corsicajack.seekmind.core.model.config.PrivacyConfig
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end scenarios of the capture (docs/06-qualite-tests-ci.md §4.2, ticket M2-08) with SeekMind's
 * own accessibility service and the real pipeline, synchronised on the pipeline's events. A screen that
 * must not be captured is checked by opening another one afterwards: once that one is stored, the first
 * one has had its turn.
 */
@RunWith(AndroidJUnit4::class)
class CaptureE2ETest {
    @get:Rule
    val harness = CaptureHarness()

    private val settings get() = harness.graph.settings()

    /**
     * The screen [activity] opened while nothing may be captured, then [COVER] over it, so that no late
     * event of [activity] arrives once [allow] has run. Then the sentinel: before its capture, the
     * pipeline handled nothing but the cover (capture §8: the router lets no event through).
     */
    private suspend fun nothingWhileBlocked(activity: String, allow: suspend () -> Unit) {
        // The settings reach the router through a flow: until it is closed, events would still pass.
        harness.awaitRouterClosed()
        harness.open(activity)
        harness.open(COVER)
        allow()
        harness.awaitRouterOpen()
        harness.open("PlainTextActivity")
        val (_, _, before) = harness.awaitCaptureAfter(PLAIN)
        for (event in before) {
            assertWithMessage(
                "event before the sentinel: $event",
            ).that(harness.fixtureCapture(event, SCROLL)).isNotNull()
        }
    }

    @Test
    fun e2e01_plainTextIsStoredAndFoundWithinASecond() = runBlocking<Unit> {
        harness.open("PlainTextActivity")
        val (event, capture) = harness.awaitCapture(PLAIN)
        val deadline = SystemClock.elapsedRealtime() + 1_000
        var found = false
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            found = harness.database().search.lexical("\"zanzibar\"", SearchFilters(), 10)
                .any { it.captureId == capture.id }
        }
        assertWithMessage("found by the keyword search within 1 s of $event").that(found).isTrue()
    }

    @Test
    fun e2e02_aPasswordScreenIsDroppedAndStoresNothing() = runBlocking<Unit> {
        harness.open("PasswordActivity")
        harness.awaitEvent("PASSWORD_FIELD") { it == PipelineEvent.Dropped(DropReason.PASSWORD_FIELD) }
        assertThat(harness.seenSinceStart("SEEKMIND_FIXTURE_PASSWORD")).isEmpty()
    }

    @Test
    fun e2e04_theStaticTextIsKeptAndTheFieldIsNot() = runBlocking<Unit> {
        harness.open("EditableActivity")
        val (_, capture) = harness.awaitCapture("SEEKMIND_FIXTURE_EDITABLE")
        assertThat(capture.text.lowercase()).contains("montgolfières")
        assertThat(capture.text.lowercase()).doesNotContain("seekmind_field_content")
    }

    @Test
    fun e2e07_aScreenScrolledFiveSecondsGivesFewCaptures() = runBlocking<Unit> {
        harness.open("ScrollActivity")
        harness.awaitCapture(SCROLL)
        val start = System.currentTimeMillis()
        val end = SystemClock.elapsedRealtime() + SCROLL_MS
        // From 70 % to 30 % of the height: the emulators' screens differ in size.
        val screen = harness.context.resources.displayMetrics
        val x = screen.widthPixels / 2
        val swipe = "input swipe $x ${screen.heightPixels * 7 / 10} $x ${screen.heightPixels * 3 / 10} 200"
        while (SystemClock.elapsedRealtime() < end) harness.shell(swipe)
        // Once the scrolling stops, the last screen is captured: the scrolling is over for the pipeline.
        harness.awaitCapture(SCROLL)
        val config = CaptureConfig()
        val captures = harness.seenSinceStart(SCROLL).filter { it.lastSeenAt >= start }
        // A screen that keeps moving is captured at least every maxWaitMs, plus the last one.
        val bound = (SCROLL_MS / config.maxWaitMs + 2).toInt()
        assertThat(bound).isAtMost(4)
        assertThat(captures.size).isAtMost(bound)
    }

    @Test
    fun e2e08_theSameScreenFiveTimesIsOneCaptureSeenFiveTimes() = runBlocking<Unit> {
        harness.open("PlainTextActivity")
        val (_, first) = harness.awaitCapture(PLAIN)
        repeat(4) {
            harness.open("PlainTextActivity")
            val (event, again) = harness.awaitCapture(PLAIN)
            assertThat(event).isInstanceOf(PipelineEvent.Deduplicated::class.java)
            assertThat(again.id).isEqualTo(first.id)
        }
        val last = checkNotNull(harness.database().captures.get(first.id))
        assertThat(last.seenCount - first.seenCount).isEqualTo(4)
    }

    @Test
    fun e2e09_withoutConsentNothingIsCaptured() = runBlocking<Unit> {
        settings.withdrawConsent()
        nothingWhileBlocked("EditableActivity") {
            settings.acceptConsent(Consent(DISCLOSURE_VERSION, System.currentTimeMillis()), AppFamily.entries.toSet())
        }
        assertThat(harness.seenSinceStart("SEEKMIND_FIXTURE_EDITABLE")).isEmpty()
    }

    @Test
    fun e2e10_whilePausedNothingIsCaptured() = runBlocking<Unit> {
        settings.setPausedUntil(Long.MAX_VALUE)
        nothingWhileBlocked("SensitiveDataActivity") { settings.setPausedUntil(null) }
        assertThat(harness.seenSinceStart("SEEKMIND_FIXTURE_SENSITIVE")).isEmpty()
    }

    @Test
    fun e2e11_anExcludedAppIsDropped() = runBlocking<Unit> {
        harness.database().appRules.set(AppRule(FIXTURE, AppRuleMode.EXCLUDED, System.currentTimeMillis()))
        harness.open("PlainTextActivity")
        harness.awaitEvent("APP_EXCLUDED") { it == PipelineEvent.Dropped(DropReason.APP_EXCLUDED) }
    }

    @Test
    fun e2e12_cardAndIbanAreMaskedAndTheTrackingNumberKept() = runBlocking<Unit> {
        harness.open("SensitiveDataActivity")
        val (_, capture) = harness.awaitCapture("SEEKMIND_FIXTURE_SENSITIVE")
        val text = capture.text
        assertThat(text).doesNotContain("4111")
        assertThat(text.uppercase().replace(" ", "")).doesNotContain("DE89370400440532013000")
        assertThat(text).contains(PrivacyConfig().redactionMark)
        assertThat(text.uppercase()).contains("1Z999AA10123456784")
    }

    @Test
    fun e2e13_theUrlOfTheWebScreenIsStoredWithTheCapture() = runBlocking<Unit> {
        harness.open("WebActivity")
        val (_, capture) = harness.awaitCapture("SEEKMIND_FIXTURE_WEB")
        assertThat(capture.url).isEqualTo("https://fixture.seekmind.test/article/42")
        // The address bar is an editable field: its text is not part of the capture.
        assertThat(capture.text).doesNotContain("fixture.seekmind.test/article/42")
    }

    @Test
    fun e2e19_aDisabledFamilyIsDroppedAndCapturedOnceEnabledAgain() = runBlocking<Unit> {
        settings.setEnabledFamilies(AppFamily.entries.toSet() - AppFamily.MESSAGING)
        harness.open("MessagingLikeActivity")
        harness.awaitEvent("APP_FAMILY_DISABLED") { it == PipelineEvent.Dropped(DropReason.APP_FAMILY_DISABLED) }
        settings.setEnabledFamilies(AppFamily.entries.toSet())
        harness.open("MessagingLikeActivity")
        harness.awaitCapture("SEEKMIND_FIXTURE_MESSAGING")
    }

    private companion object {
        const val PLAIN = "SEEKMIND_FIXTURE_PLAIN"
        const val SCROLL = "SEEKMIND_FIXTURE_SCROLL"
        const val COVER = "ScrollActivity"
        const val SCROLL_MS = 5_000L
    }
}
