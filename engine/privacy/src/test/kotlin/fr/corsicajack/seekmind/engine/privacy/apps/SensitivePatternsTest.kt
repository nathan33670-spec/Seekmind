package fr.corsicajack.seekmind.engine.privacy.apps

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The patterns of sensitive_apps.json against real-looking package names (ticket M2-05): sensitive ones
 * are caught, common applications are not. Kept in step with the asset by SensitiveAppsAssetTest.
 */
class SensitivePatternsTest {
    private val apps = SensitiveApps(
        strictPackages = emptySet(),
        strictPatterns = STRICT.map(::Regex),
        softPackages = emptySet(),
        softPatterns = SOFT.map(::Regex),
    )

    @Test
    fun `sensitive names are caught by the patterns`() {
        val strict = listOf(
            "org.example.keepassdroid",
            "com.example.authenticator",
            "com.example.passwordsafe",
            "com.example.passwdmanager",
            "com.example.vault",
            "com.example.otp.app",
        )
        val soft = listOf(
            "com.mybank.mobile", "fr.example.banque.mobile", "com.example.banking", "com.example.wallet",
            "com.example.pay", "com.example.payments.app", "com.example.cryptotrader", "fr.example.credit.conso",
            "bank.example.app", "hu.otpbank.mobile", "uk.co.examplebanklondon",
        )
        for (name in strict) assertWithMessage(name).that(apps.levelOf(name)).isEqualTo(SensitiveApps.Level.STRICT)
        for (name in soft) assertWithMessage(name).that(apps.levelOf(name)).isEqualTo(SensitiveApps.Level.SOFT)
    }

    @Test
    fun `common applications are not caught`() {
        val common = listOf(
            "com.android.chrome", "org.mozilla.firefox", "com.whatsapp", "com.google.android.gm",
            "com.google.android.apps.maps", "com.google.android.youtube", "com.spotify.music",
            "org.wikipedia", "fr.leboncoin", "com.vsct.vsc.mobileapp", "deezer.android.app",
            "com.netflix.mediaclient", "com.ubercab.eats", "com.amazon.mshop.android.shopping", "fr.vinted",
            "com.waze", "com.comuto", "com.duolingo", "com.google.android.apps.photos", "com.passport.photo",
            "com.payot.livres", "com.pinterest", "com.discord", "com.microsoft.office.outlook", "fr.lemonde.android",
            "com.incredible.notes", "com.paysage.app",
        )
        for (name in common) assertWithMessage(name).that(apps.levelOf(name)).isNull()
    }

    companion object {
        /** The patterns of the asset, which SensitiveAppsAssetTest compares. */
        val STRICT = listOf("keepass", "authenticator", "password", "passwd", "\\.vault(\\.|$)", "\\.otp(\\.|$)")
        val SOFT =
            listOf("bank", "banque", "banking", "\\.wallet(\\.|$)", "\\.pay(ment)?s?(\\.|$)", "crypto", "credit")
    }
}
