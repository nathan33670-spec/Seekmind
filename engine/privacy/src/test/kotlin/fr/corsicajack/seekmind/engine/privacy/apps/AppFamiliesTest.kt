package fr.corsicajack.seekmind.engine.privacy.apps

import android.content.pm.ApplicationInfo
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.engine.privacy.PrivacyGateTest.FakeFacts
import org.junit.Test

/** docs/specs/confidentialite.md §3.4 (ticket M2-05). */
class AppFamiliesTest {
    private val facts = FakeFacts()
    private val sensitive =
        SensitiveApps(setOf("com.example.pass"), emptyList(), setOf("com.example.bank"), emptyList())
    private val families = AppFamilies(
        facts,
        FamilyLists(messaging = setOf("com.example.chat"), social = setOf("com.example.social")),
        browserPackages = setOf("com.example.browser"),
        sensitive = sensitive,
    )

    @Test
    fun `the default SMS application is a messaging app`() {
        facts.defaultSms = "com.example.sms"
        assertThat(families.familyOf("com.example.sms")).isEqualTo(AppFamily.MESSAGING)
    }

    @Test
    fun `mailto and smsto handlers and the built-in list are messaging apps`() {
        facts.messaging = setOf("com.example.mail")
        assertThat(families.familyOf("com.example.mail")).isEqualTo(AppFamily.MESSAGING)
        assertThat(families.familyOf("com.example.chat")).isEqualTo(AppFamily.MESSAGING)
    }

    @Test
    fun `browsers come from the table and from https handlers`() {
        facts.browsers = setOf("com.example.other.browser")
        assertThat(families.familyOf("com.example.browser")).isEqualTo(AppFamily.BROWSERS)
        assertThat(families.familyOf("com.example.other.browser")).isEqualTo(AppFamily.BROWSERS)
    }

    @Test
    fun `social and video categories and the built-in list are social media`() {
        facts.categories = mapOf(
            "com.example.video" to ApplicationInfo.CATEGORY_VIDEO,
            "com.example.network" to ApplicationInfo.CATEGORY_SOCIAL,
        )
        assertThat(families.familyOf("com.example.video")).isEqualTo(AppFamily.SOCIAL_MEDIA)
        assertThat(families.familyOf("com.example.network")).isEqualTo(AppFamily.SOCIAL_MEDIA)
        assertThat(families.familyOf("com.example.social")).isEqualTo(AppFamily.SOCIAL_MEDIA)
    }

    @Test
    fun `the first family that applies wins`() {
        // A mail app that also opens web links and is in the social category.
        facts.messaging = setOf("com.example.all")
        facts.browsers = setOf("com.example.all", "com.example.web")
        facts.categories = mapOf(
            "com.example.all" to ApplicationInfo.CATEGORY_SOCIAL,
            "com.example.web" to ApplicationInfo.CATEGORY_SOCIAL,
        )
        assertThat(families.familyOf("com.example.all")).isEqualTo(AppFamily.MESSAGING)
        assertThat(families.familyOf("com.example.web")).isEqualTo(AppFamily.BROWSERS)
    }

    @Test
    fun `everything else is other, sensitive apps are in no family`() {
        assertThat(families.familyOf("com.example.maps")).isEqualTo(AppFamily.OTHER)
        assertThat(families.familyOf("com.example.pass")).isNull()
        assertThat(families.familyOf("com.example.bank")).isNull()
    }

    @Test
    fun `answers are cached until invalidated`() {
        assertThat(families.familyOf("com.example.new")).isEqualTo(AppFamily.OTHER)
        facts.defaultSms = "com.example.new"
        assertThat(families.familyOf("com.example.new")).isEqualTo(AppFamily.OTHER)
        families.invalidate()
        assertThat(families.familyOf("com.example.new")).isEqualTo(AppFamily.MESSAGING)
    }
}
