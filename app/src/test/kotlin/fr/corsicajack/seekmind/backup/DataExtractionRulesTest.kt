package fr.corsicajack.seekmind.backup

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * INV-09 (ticket M10-05, sécurité §8): the extraction rules exclude every domain from the cloud backup
 * and from the transfer to a new device. On Android 12 and later, allowBackup="false" alone still lets a
 * device-to-device transfer copy the files.
 */
class DataExtractionRulesTest {
    private val rules: Element = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File("src/main/res/xml/data_extraction_rules.xml")).documentElement

    @Test
    fun everyDomainIsExcludedFromBothTheCloudAndTheTransfer() {
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.getElementsByTagName(section).item(0) as Element
            val excluded = (0 until element.childNodes.length).map { element.childNodes.item(it) }
                .filterIsInstance<Element>()
                .onEach { assertThat(it.tagName).isEqualTo("exclude") }
                .onEach { assertThat(it.getAttribute("path")).isEqualTo(".") }
                .map { it.getAttribute("domain") }
            assertThat(excluded).containsExactly("root", "file", "database", "sharedpref", "external")
        }
    }
}
