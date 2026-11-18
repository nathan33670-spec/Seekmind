package fr.corsicajack.seekmind

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.di.OfflineEntryPoint
import fr.corsicajack.seekmind.engine.monetization.license.ActivationResult
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test of the `offline` variant (ticket M8-06, ADR-0009): no network permission and no ads code,
 * daily free credits, activation by code, and the disclosure of the variant. The capture and the search
 * are covered by the end-to-end tests of :app, run on this variant too.
 */
@RunWith(AndroidJUnit4::class)
class OfflineVariantTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph = EntryPointAccessors.fromApplication(context, OfflineEntryPoint::class.java)

    @After
    fun tearDown() = runBlocking<Unit> { graph.activator().deactivate() }

    @Test
    fun noNetworkNorAdPermissionIsRequested() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertThat(requested).containsNoneOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "com.google.android.gms.permission.AD_ID",
            "android.permission.ACCESS_ADSERVICES_AD_ID",
        )
    }

    @Test
    fun noAdsCodeIsShipped() {
        listOf("fr.corsicajack.seekmind.engine.ads.AdMobRewardProvider", "com.google.android.gms.ads.MobileAds")
            .forEach { name ->
                val found = runCatching { Class.forName(name) }.isSuccess
                assertWithMessage(name).that(found).isFalse()
            }
    }

    @Test
    fun noAdIsOfferedAndFiveSearchesAreGivenEachDay() {
        assertThat(graph.rewardFlow().isSupported).isFalse()
        assertThat(graph.monetizationConfig().dailyFreeCredits).isEqualTo(5)
    }

    @Test
    fun anActivationCodeWorks() = runBlocking<Unit> {
        val json = instrumentation.context.assets.open("vectors.json").bufferedReader().use { it.readText() }
        val vectors = JSONObject(json).getJSONArray("vectors")
        val code = (0 until vectors.length()).map(vectors::getJSONObject).single { it.getString("name") == "valid" }
            .getString("token")
        assertThat(graph.activator().activate(code)).isEqualTo(ActivationResult.Activated("TEST-0001", "lifetime"))
    }

    @Test
    fun theDisclosureIsTheOneOfTheVariant() {
        val expected = mapOf(
            Locale.ENGLISH to "The app does not have permission to access the Internet.",
            Locale.FRENCH to "L'application n'a pas l'autorisation d'accéder à Internet.",
        )
        for ((locale, text) in expected) {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val localized = context.createConfigurationContext(configuration)
            val id = context.resources.getIdentifier("onboarding_disclosure_data_2", "string", context.packageName)
            assertThat(localized.getString(id)).isEqualTo(text)
        }
    }
}
