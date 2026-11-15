package fr.corsicajack.seekmind.engine.privacy

import android.content.pm.ApplicationInfo
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.corsicajack.seekmind.core.common.IntRect
import fr.corsicajack.seekmind.core.database.store.AppRuleMode
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.DropReason
import fr.corsicajack.seekmind.core.model.GateDecision
import fr.corsicajack.seekmind.core.model.ScreenMeta
import fr.corsicajack.seekmind.core.model.TreeExtraction
import fr.corsicajack.seekmind.core.model.config.PrivacyConfig
import fr.corsicajack.seekmind.engine.privacy.apps.AppFamilies
import fr.corsicajack.seekmind.engine.privacy.apps.FamilyLists
import fr.corsicajack.seekmind.engine.privacy.apps.PackageFacts
import fr.corsicajack.seekmind.engine.privacy.apps.SensitiveApps
import org.junit.Test

/** docs/specs/confidentialite.md §2 to §4 (ticket M2-05). */
class PrivacyGateTest {
    private val sensitive = SensitiveApps(
        strictPackages = setOf(STRICT),
        strictPatterns = listOf(Regex("authenticator")),
        softPackages = setOf(SOFT),
        softPatterns = listOf(Regex("bank")),
    )
    private val facts = FakeFacts()
    private val families = AppFamilies(facts, FamilyLists(setOf(CHAT), setOf(SOCIAL)), setOf(BROWSER), sensitive)
    private var choices: UserPrivacyChoices? = UserPrivacyChoices(emptyMap(), AppFamily.entries.toSet())
    private val gate = gate(PrivacyConfig())

    // --- checkPackage: the full decision table ---------------------------------------------------

    /** The list a package is in, as the table's first dimension. */
    private enum class Listed(val packageName: String) { STRICT_LIST(STRICT), SOFT_LIST(SOFT), NONE(PLAIN) }

    @Test
    fun `decision table of checkPackage`() {
        var rows = 0
        for (listed in Listed.entries) {
            for (rule in listOf(null, AppRuleMode.EXCLUDED, AppRuleMode.INCLUDED)) {
                for (game in listOf(false, true)) {
                    for (familyEnabled in listOf(true, false)) {
                        val pkg = listed.packageName
                        facts.categories = if (game) mapOf(pkg to ApplicationInfo.CATEGORY_GAME) else emptyMap()
                        families.invalidate()
                        choices = UserPrivacyChoices(
                            rules = rule?.let { mapOf(pkg to it) } ?: emptyMap(),
                            enabledFamilies = if (familyEnabled) AppFamily.entries.toSet() else emptySet(),
                        )
                        val expected = expected(listed, rule, game, familyEnabled)
                        assertWithMessage("list=%s rule=%s game=%s family=%s", listed, rule, game, familyEnabled)
                            .that(gate.checkPackage(meta(pkg))).isEqualTo(expected)
                        rows++
                    }
                }
            }
        }
        assertThat(rows).isEqualTo(36)
    }

    /** §2, in the order PG-02, PG-03, PG-04, PG-05, PG-06, PG-14. */
    private fun expected(listed: Listed, rule: AppRuleMode?, game: Boolean, familyEnabled: Boolean): GateDecision =
        when {
            listed == Listed.STRICT_LIST -> GateDecision.Deny(DropReason.APP_SENSITIVE)
            rule == AppRuleMode.EXCLUDED -> GateDecision.Deny(DropReason.APP_EXCLUDED)
            rule == AppRuleMode.INCLUDED -> GateDecision.Allow
            listed == Listed.SOFT_LIST -> GateDecision.Deny(DropReason.APP_SENSITIVE)
            game -> GateDecision.Deny(DropReason.APP_CATEGORY)
            !familyEnabled -> GateDecision.Deny(DropReason.APP_FAMILY_DISABLED)
            else -> GateDecision.Allow
        }

    @Test
    fun `always-ignored packages come first, whatever the user's rule`() {
        choices = UserPrivacyChoices(mapOf(LAUNCHER to AppRuleMode.INCLUDED), AppFamily.entries.toSet())
        assertThat(gate.checkPackage(meta(LAUNCHER))).isEqualTo(GateDecision.Deny(DropReason.APP_IGNORED))
    }

    @Test
    fun `a strict-list package cannot be included by the user`() {
        choices = UserPrivacyChoices(mapOf("com.example.authenticator" to AppRuleMode.INCLUDED), emptySet())
        assertThat(gate.checkPackage(meta("com.example.authenticator")))
            .isEqualTo(GateDecision.Deny(DropReason.APP_SENSITIVE))
    }

    @Test
    fun `a soft-list match by pattern is sensitive unless included`() {
        assertThat(gate.checkPackage(meta("com.mybank.mobile"))).isEqualTo(GateDecision.Deny(DropReason.APP_SENSITIVE))
        choices = UserPrivacyChoices(mapOf("com.mybank.mobile" to AppRuleMode.INCLUDED), emptySet())
        assertThat(gate.checkPackage(meta("com.mybank.mobile"))).isEqualTo(GateDecision.Allow)
    }

    @Test
    fun `games are allowed when the rule is off`() {
        facts.categories = mapOf(PLAIN to ApplicationInfo.CATEGORY_GAME)
        assertThat(gate(PrivacyConfig(excludeGames = false)).checkPackage(meta(PLAIN))).isEqualTo(GateDecision.Allow)
    }

    @Test
    fun `before the user's choices are read every package is denied`() {
        choices = null
        assertThat(gate.checkPackage(meta(PLAIN))).isInstanceOf(GateDecision.Deny::class.java)
        assertThat(gate.checkPackage(meta(CHAT))).isInstanceOf(GateDecision.Deny::class.java)
    }

    // --- Families ---------------------------------------------------------------------------------

    @Test
    fun `a disabled family denies its applications, others pass`() {
        choices = UserPrivacyChoices(emptyMap(), AppFamily.entries.toSet() - AppFamily.MESSAGING)
        assertThat(gate.checkPackage(meta(CHAT))).isEqualTo(GateDecision.Deny(DropReason.APP_FAMILY_DISABLED))
        assertThat(gate.checkPackage(meta(PLAIN))).isEqualTo(GateDecision.Allow)
    }

    @Test
    fun `a user include beats a disabled family`() {
        choices = UserPrivacyChoices(mapOf(CHAT to AppRuleMode.INCLUDED), emptySet())
        assertThat(gate.checkPackage(meta(CHAT))).isEqualTo(GateDecision.Allow)
    }

    // --- checkTree --------------------------------------------------------------------------------

    @Test
    fun `a password field rejects the screen`() {
        assertThat(gate.checkTree(meta(PLAIN), extraction(password = true)))
            .isEqualTo(GateDecision.Deny(DropReason.PASSWORD_FIELD))
    }

    @Test
    fun `a private-browsing marker in a browser rejects the screen`() {
        for (id in listOf("incognito_badge", "private_browsing_banner", "PRIVATE_MODE_ICON", "secret_mode_tab")) {
            assertWithMessage(id).that(gate.checkTree(meta(BROWSER), extraction(viewIds = setOf("$BROWSER:id/$id"))))
                .isEqualTo(GateDecision.Deny(DropReason.PRIVATE_BROWSING))
        }
    }

    @Test
    fun `markers only count in browsers, and only in the view name`() {
        val ids = setOf("$PLAIN:id/incognito_badge")
        assertThat(gate.checkTree(meta(PLAIN), extraction(viewIds = ids))).isEqualTo(GateDecision.Allow)
        assertThat(gate.checkTree(meta(BROWSER), extraction(viewIds = setOf("org.incognito.x:id/toolbar"))))
            .isEqualTo(GateDecision.Allow)
        assertThat(gate.checkTree(meta(BROWSER), extraction())).isEqualTo(GateDecision.Allow)
    }

    // --- The gate cannot be turned off ------------------------------------------------------------

    @Test
    fun `no configuration disables the gate`() {
        // PrivacyConfig has no switch: its only fields tune the mark, the games rule and the markers.
        val fields = PrivacyConfig::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }
        assertThat(fields).containsExactly("redactionMark", "excludeGames", "privateMarkers", "defaultEnabledFamilies")
        val lax =
            gate(PrivacyConfig(excludeGames = false, privateMarkers = emptyList(), defaultEnabledFamilies = emptySet()))
        assertThat(lax.checkPackage(meta(STRICT))).isEqualTo(GateDecision.Deny(DropReason.APP_SENSITIVE))
        assertThat(lax.checkTree(meta(PLAIN), extraction(password = true)))
            .isEqualTo(GateDecision.Deny(DropReason.PASSWORD_FIELD))
        assertThat(lax.redact("Carte 4111 1111 1111 1111").maskedCount).isEqualTo(1)
    }

    private fun gate(config: PrivacyConfig) = DefaultPrivacyGate(
        config = config,
        sensitive = sensitive,
        families = families,
        facts = facts,
        browserPackages = setOf(BROWSER),
        ignoredPackages = { setOf(LAUNCHER) },
        choices = { choices },
    )

    private fun meta(packageName: String) = ScreenMeta(packageName, null, null, 1, 0)

    private fun extraction(password: Boolean = false, viewIds: Set<String> = emptySet()) = TreeExtraction(
        lines = emptyList(),
        headings = emptyList(),
        hasPasswordField = password,
        viewIds = viewIds,
        nodeCount = 1,
        truncated = false,
        textChars = 0,
        textCoverage = 0f,
        windowBounds = IntRect(0, 0, 1, 1),
        durationMs = 1,
    )

    class FakeFacts : PackageFacts {
        var defaultSms: String? = null
        var messaging: Set<String> = emptySet()
        var browsers: Set<String> = emptySet()
        var categories: Map<String, Int> = emptyMap()

        override fun defaultSmsPackage() = defaultSms

        override fun messagingHandlers() = messaging

        override fun browserHandlers() = browsers

        override fun category(packageName: String) = categories[packageName] ?: ApplicationInfo.CATEGORY_UNDEFINED
    }

    companion object {
        const val STRICT = "com.example.vaultkeeper"
        const val SOFT = "fr.example.mutuelle"
        const val PLAIN = "com.example.notes"
        const val CHAT = "com.example.chat"
        const val SOCIAL = "com.example.social"
        const val BROWSER = "com.example.browser"
        const val LAUNCHER = "com.example.launcher"
    }
}
