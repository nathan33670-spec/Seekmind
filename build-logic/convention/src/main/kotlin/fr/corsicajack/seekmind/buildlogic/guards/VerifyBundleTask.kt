package fr.corsicajack.seekmind.buildlogic.guards

import com.android.tools.build.bundletool.commands.GetSizeCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Download size of a bundle, as `bundletool get-size total` prints it (bytes, over all devices). */
data class DownloadSize(val min: Long, val max: Long) {
    companion object {
        /** Parses the `MIN,MAX` table of `get-size total`. */
        fun parse(csv: String): DownloadSize {
            val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() }
            require(lines.size == 2 && lines[0] == "MIN,MAX") { "unexpected get-size output: $csv" }
            val (min, max) = lines[1].split(',').map { it.trim().toLong() }
            return DownloadSize(min, max)
        }
    }
}

/** What the APK set of a release bundle must hold (ticket M4-06, docs/specs/embeddings.md §2, §5.2). */
object BundleRules {
    const val BASE_MASTER = "splits/base-master.apk"
    const val MODEL_PACK = "asset-slices/modelpack_multi-master.apk"
    private const val BUNDLED_MODEL = "assets/model/fr/model.onnx"
    private val PACK_FILES = listOf("model.onnx", "vocab.bin", "MODEL_MANIFEST.json").map { "assets/model/multi/$it" }

    /** [apks]: for each APK of the set, its entries and whether each one is stored uncompressed. */
    fun violations(apks: Map<String, Map<String, Boolean>>): List<String> = buildList {
        val base = apks[BASE_MASTER]
        when {
            base == null -> add("no $BASE_MASTER in the APK set")
            base[BUNDLED_MODEL] == null -> add("the base has no $BUNDLED_MODEL")
            base[BUNDLED_MODEL] == false -> add("$BUNDLED_MODEL is compressed in the base")
        }
        apks.filterKeys { it != MODEL_PACK }.forEach { (apk, entries) ->
            if (entries.keys.any { it.startsWith("assets/model/multi/") }) add("$apk holds the multi model")
        }
        val pack = apks[MODEL_PACK]
        if (pack == null) {
            add("no on-demand pack $MODEL_PACK")
        } else {
            PACK_FILES.filter { it !in pack }.forEach { add("the model pack has no $it") }
        }
    }

    /** Reads the entries of the APKs in an `.apks` archive. */
    fun read(archive: File): Map<String, Map<String, Boolean>> = ZipFile(archive).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".apk") }.associate { apk ->
            apk.name to ZipInputStream(zip.getInputStream(apk)).use { input ->
                generateSequence { input.nextEntry }.associate { it.name to (it.method == ZipEntry.STORED) }
            }
        }
    }
}

/**
 * Checks the APK set of a release bundle against [BundleRules], then measures the download size of the base of a release bundle (ENF-STO-02, ticket M4-06): the install-time
 * modules, without the on-demand model pack, on the largest device configuration. Fails above [limitBytes].
 * Uses the bundletool that the Android Gradle plugin ships.
 */
@DisableCachingByDefault(because = "Check whose value is its output")
abstract class VerifyBundleTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apks: RegularFileProperty

    @get:Input
    abstract val limitBytes: Property<Long>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val content = BundleRules.violations(BundleRules.read(apks.get().asFile))
        if (content.isNotEmpty()) {
            throw GradleException("The release bundle is malformed:\n" + content.joinToString("\n") { "  - $it" })
        }
        val output = ByteArrayOutputStream()
        PrintStream(output, true, Charsets.UTF_8).use { stream ->
            GetSizeCommand.builder()
                .setApksArchivePath(apks.get().asFile.toPath())
                .setGetSizeSubCommand(GetSizeCommand.GetSizeSubcommand.TOTAL)
                .build()
                .getSizeTotal(stream)
        }
        val size = DownloadSize.parse(output.toString(Charsets.UTF_8))
        val limit = limitBytes.get()
        val line = "download size: min ${size.min} bytes, max ${size.max} bytes, limit $limit bytes"
        report.get().asFile.writeText(line + "\n")
        if (size.max > limit) throw GradleException("The base download is too large: $line")
        logger.lifecycle("Bundle $line: OK")
    }
}
