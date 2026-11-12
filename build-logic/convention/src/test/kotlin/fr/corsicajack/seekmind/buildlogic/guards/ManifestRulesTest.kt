package fr.corsicajack.seekmind.buildlogic.guards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestRulesTest {
    private val permissions =
        listOf("\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "android.permission.WAKE_LOCK")
    private val exported = listOf(
        "app.MainActivity",
        "app.A11yService android.permission.BIND_ACCESSIBILITY_SERVICE",
    )

    private fun manifest(body: String, backup: String = "false") = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app">
            $body
            <application android:allowBackup="$backup">
                <activity android:name="app.MainActivity" android:exported="true">
                    <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>
                </activity>
                <provider android:name="lib.Provider" android:exported="false" />
            </application>
        </manifest>
    """.trimIndent()

    private fun check(xml: String) = ManifestRules.checkManifest(xml, "app.id", permissions, exported)

    @Test
    fun `accepts allowed permissions, with the application id placeholder resolved`() {
        val xml = manifest(
            """
            <uses-permission android:name="app.id.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
            <uses-permission android:name="android.permission.WAKE_LOCK" />
            """,
        )
        assertEquals(emptyList(), check(xml))
    }

    @Test
    fun `rejects INTERNET when it is not on the allowlist`() {
        val violations = check(manifest("""<uses-permission android:name="android.permission.INTERNET" />"""))
        assertEquals(listOf("Permission not allowed: android.permission.INTERNET"), violations)
    }

    @Test
    fun `rejects backups`() {
        assertTrue(check(manifest("", backup = "true")).single().contains("allowBackup"))
    }

    @Test
    fun `rejects an exported component that is not allowed, including implicit export by intent filter`() {
        val xml = manifest("").replace(
            "</application>",
            """
            <receiver android:name="lib.Explicit" android:exported="true" />
            <service android:name="lib.Implicit"><intent-filter><action android:name="x" /></intent-filter></service>
            </application>
            """,
        )
        assertEquals(
            listOf(
                "Exported component not allowed: service lib.Implicit",
                "Exported component not allowed: receiver lib.Explicit",
            ),
            check(xml),
        )
    }

    @Test
    fun `requires the declared permission on an allowed exported component`() {
        val unprotected = manifest("").replace(
            "</application>",
            """<service android:name="app.A11yService" android:exported="true" /></application>""",
        )
        assertTrue(check(unprotected).single().contains("must require permission"))
        val protected = unprotected.replace(
            "android:name=\"app.A11yService\"",
            "android:name=\"app.A11yService\" android:permission=\"android.permission.BIND_ACCESSIBILITY_SERVICE\"",
        )
        assertEquals(emptyList(), check(protected))
    }

    private fun a11y(attributes: String) =
        """<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android" $attributes />"""

    @Test
    fun `accessibility config may only read the window and take screenshots`() {
        assertEquals(
            emptyList(),
            ManifestRules.checkAccessibilityConfig(
                "cfg.xml",
                a11y("""android:canRetrieveWindowContent="true" android:canTakeScreenshot="true""""),
            ),
        )
        assertEquals(
            listOf("cfg.xml: capability not allowed: canPerformGestures"),
            ManifestRules.checkAccessibilityConfig("cfg.xml", a11y("""android:canPerformGestures="true"""")),
        )
    }

    @Test
    fun `accessibility config must not claim to be an accessibility tool`() {
        val violations = ManifestRules.checkAccessibilityConfig(
            "cfg.xml",
            a11y("""android:isAccessibilityTool="true""""),
        )
        assertTrue(violations.single().contains("isAccessibilityTool"))
    }
}
