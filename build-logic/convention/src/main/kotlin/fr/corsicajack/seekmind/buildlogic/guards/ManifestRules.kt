package fr.corsicajack.seekmind.buildlogic.guards

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * Rules applied to a merged release manifest (INV-01, INV-09, INV-12) and to the accessibility
 * service configuration (INV-02). Pure functions: tested without Gradle.
 */
object ManifestRules {
    private val componentTags = listOf("activity", "activity-alias", "service", "receiver", "provider")
    private val allowedCapabilities = setOf("canRetrieveWindowContent", "canTakeScreenshot")

    /**
     * @param allowedPermissions permission names; `${applicationId}` is replaced by [applicationId].
     * @param exportedAllowlist entries `component.Name` or `component.Name required.Permission`.
     */
    fun checkManifest(
        manifestXml: String,
        applicationId: String,
        allowedPermissions: List<String>,
        exportedAllowlist: List<String>,
    ): List<String> {
        val root = parse(manifestXml)
        val violations = mutableListOf<String>()

        val allowed = allowedPermissions.map { it.replace("\${applicationId}", applicationId) }.toSet()
        listOf("uses-permission", "uses-permission-sdk-23").forEach { tag ->
            root.elements(tag).map { it.android("name") }.forEach { permission ->
                if (permission !in allowed) violations += "Permission not allowed: $permission"
            }
        }

        val exported = exportedAllowlist.associate { entry ->
            val parts = entry.split(Regex("\\s+"))
            parts[0] to parts.getOrNull(1)
        }
        componentTags.forEach { tag ->
            root.elements(tag).forEach { component ->
                val name = component.android("name")
                val exportedAttr = component.android("exported")
                val hasFilter = component.getElementsByTagName("intent-filter").length > 0
                val isExported = exportedAttr == "true" || (exportedAttr.isEmpty() && hasFilter)
                if (!isExported) return@forEach
                when {
                    name !in exported -> violations += "Exported component not allowed: $tag $name"

                    exported[name] != null && component.android("permission") != exported[name] ->
                        violations += "Exported component $name must require permission ${exported[name]}"
                }
            }
        }

        val application = root.elements("application").firstOrNull()
        if (application?.android("allowBackup") != "false") {
            violations += "application must declare android:allowBackup=\"false\""
        }
        return violations
    }

    /** INV-02: only the two read capabilities, and never the accessibility-tool flag (D-10). */
    fun checkAccessibilityConfig(path: String, configXml: String): List<String> {
        val root = parse(configXml)
        val violations = mutableListOf<String>()
        val attributes = root.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute.namespaceURI != ANDROID_NS) continue
            val name = attribute.localName
            when {
                name == "isAccessibilityTool" && attribute.nodeValue == "true" ->
                    violations += "$path: isAccessibilityTool must not be declared (decision D-10)"

                name.startsWith("can") && attribute.nodeValue == "true" && name !in allowedCapabilities ->
                    violations += "$path: capability not allowed: $name"
            }
        }
        return violations
    }

    private fun parse(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(xml.byteInputStream()).documentElement
    }

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(attribute: String): String = getAttributeNS(ANDROID_NS, attribute)
}
