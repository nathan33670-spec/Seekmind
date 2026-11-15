package fr.corsicajack.seekmind.engine.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.Capture
import fr.corsicajack.seekmind.core.model.SourceLevel
import fr.corsicajack.seekmind.core.model.TextSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E-14 (docs/06-qualite-tests-ci.md §3), at the level of this module: a capture of the fixture
 * application resolves, through a test rule, to `seekmind-fixture://item/<id>`, and the launcher opens
 * DeepLinkActivity on that identifier. The rule lives in the test, not in the shipped rules file.
 */
@RunWith(AndroidJUnit4::class)
class SourceReturnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)

    private val rules = SourceRules.parse(
        """
        {"version": 1, "rules": [
          {"package": "$FIXTURE", "level": "APP_LINK", "uri": "seekmind-fixture://item/{handle}", "requires": ["handle"]}
        ]}
        """.trimIndent(),
    )
    private val source = SourceReturn(PackageManagerApps(context.packageManager), rules)

    @After
    fun home() {
        device.pressHome()
    }

    @Test
    fun theReturnOpensDeepLinkActivityOnTheRightIdentifier() {
        val ref = source.resolver.resolve(capture(FIXTURE, "Fiche de l'article @item42 dans le catalogue"))
        assertThat(ref.level).isEqualTo(SourceLevel.APP_LINK)
        assertThat(ref.appLabel).isEqualTo("SeekMind test fixtures")

        assertThat(source.launcher.launch(context, ref)).isEqualTo(LaunchResult.Launched)
        val shown = device.wait(Until.hasObject(By.pkg(FIXTURE).text("Fixture item item42")), TIMEOUT_MS)
        assertThat(shown).isTrue()
    }

    @Test
    fun withoutItsVariableTheRuleGivesWayToTheApplication() {
        // The fixture application has no launcher activity: nothing else leads to it.
        val ref = source.resolver.resolve(capture(FIXTURE, "Aucun identifiant"))
        assertThat(ref.level).isEqualTo(SourceLevel.UNAVAILABLE)
        assertThat(ref.appLabel).isEqualTo("SeekMind test fixtures")
    }

    @Test
    fun anUninstalledApplicationIsUnavailable() {
        val ref = source.resolver.resolve(capture("fr.corsicajack.seekmind.absent", "@item42"))
        assertThat(ref.level).isEqualTo(SourceLevel.UNAVAILABLE)
        assertThat(ref.intentUri).isNull()
        assertThat(source.launcher.launch(context, ref)).isEqualTo(LaunchResult.Failed)
    }

    private fun capture(packageName: String, text: String) = Capture(
        id = 1,
        firstSeenAt = 0,
        lastSeenAt = 0,
        seenCount = 1,
        packageName = packageName,
        activityName = null,
        windowTitle = null,
        url = null,
        text = text,
        textSource = TextSource.TREE,
        charCount = text.length,
        simhash = 0,
    )

    private companion object {
        const val FIXTURE = "fr.corsicajack.seekmind.fixture"
        const val TIMEOUT_MS = 10_000L
    }
}
