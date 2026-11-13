package fr.corsicajack.seekmind.engine.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.engine.privacy.apps.AndroidPackageFacts
import fr.corsicajack.seekmind.engine.privacy.apps.AppFamilies
import fr.corsicajack.seekmind.engine.privacy.apps.FamilyLists
import fr.corsicajack.seekmind.engine.privacy.apps.SensitiveApps
import org.junit.Test
import org.junit.runner.RunWith

/** The assets of docs/specs/confidentialite.md §3, parsed by org.json on a device (ticket M2-05). */
@RunWith(AndroidJUnit4::class)
class PrivacyAssetsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSensitiveListsParseAndHoldTheSpecifiedExamples() {
        val apps = SensitiveApps.load(context)
        for (name in STRICT_EXAMPLES) {
            assertWithMessage(name).that(apps.levelOf(name)).isEqualTo(SensitiveApps.Level.STRICT)
        }
        for (name in SOFT_EXAMPLES) {
            assertWithMessage(name).that(apps.levelOf(name)).isEqualTo(SensitiveApps.Level.SOFT)
        }
        // docs/specs/confidentialite.md §3.2: at least 250 entries for the seven countries of the launch.
        assertThat(apps.packageCount).isAtLeast(250)
        assertThat(apps.levelOf("com.android.chrome")).isNull()
        assertThat(apps.levelOf("com.whatsapp")).isNull()
    }

    @Test
    fun theFamilyListsParseAndNoneOfTheirAppsIsSensitive() {
        val lists = FamilyLists.load(context)
        val apps = SensitiveApps.load(context)
        assertThat(lists.messaging).contains("com.whatsapp")
        assertThat(lists.social).contains("com.instagram.android")
        for (name in lists.messaging + lists.social) assertWithMessage(name).that(apps.levelOf(name)).isNull()
    }

    @Test
    fun thePackageManagerFactsClassifyTheDeviceDefaults() {
        val facts = AndroidPackageFacts(context)
        val families = AppFamilies(facts, FamilyLists.load(context), emptySet(), SensitiveApps.load(context))
        facts.defaultSmsPackage()?.let { assertThat(families.familyOf(it)).isEqualTo(AppFamily.MESSAGING) }
        assertThat(families.familyOf(context.packageName)).isEqualTo(AppFamily.OTHER)
    }

    private companion object {
        /** docs/specs/confidentialite.md §3.2. */
        val STRICT_EXAMPLES = listOf(
            "com.x8bit.bitwarden", "com.onepassword.android", "com.lastpass.lpandroid",
            "com.dashlane", "keepass2android.keepass2android", "com.kunzisoft.keepass.free",
            "com.kunzisoft.keepass.libre", "proton.android.pass", "com.nordpass.android.app.password.manager",
            "com.samsung.android.samsungpass", "com.google.android.apps.authenticator2", "com.azure.authenticator",
            "com.authy.authy", "com.beemdevelopment.aegis",
        )
        val SOFT_EXAMPLES = listOf(
            "com.boursorama.android.clients", "fr.creditagricole.androidapp", "net.bnpparibas.mescomptes",
            "mobi.societegenerale.mobile.lappli", "fr.lcl.android.customerarea",
            "com.caisseepargne.android.mobilebanking", "fr.banquepopulaire.cyberplus",
            "com.fullsix.android.labanquepostale.accountaccess", "com.cm_prod.bad", "com.cic_prod.bad",
            "com.fortuneo.android", "com.revolut.revolut", "de.number26.android", "com.paypal.android.p2pmobile",
            "com.lydia", "com.transferwise.android", "com.google.android.apps.walletnfcrel",
            "com.samsung.android.spay", "com.android.settings",
            // One per country of the launch market, besides France.
            "be.bmid.itsme", "ch.twint.payment", "lu.bcee.snetmobile", "es.bizum.wallet", "de.fiduciagad.banking.vr",
            "com.posteitaliane.spim",
        )
    }
}
