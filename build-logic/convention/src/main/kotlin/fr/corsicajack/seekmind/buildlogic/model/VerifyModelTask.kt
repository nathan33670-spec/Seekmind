package fr.corsicajack.seekmind.buildlogic.model

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Fails unless the model set in [modelDirectory] matches [lockFile] (docs/specs/embeddings.md §2). */
@DisableCachingByDefault(because = "Hashes files that are not declared as outputs; runs every time.")
abstract class VerifyModelTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockFile: RegularFileProperty

    @get:Internal
    abstract val modelDirectory: DirectoryProperty

    @get:Input
    abstract val model: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val directory = modelDirectory.get().asFile
        val problems = ModelLock.problems(lockFile.get().asFile.readText(), model.get(), directory)
        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The '${model.get()}' embedding model in $directory does not match model.lock.json:")
                    problems.forEach { appendLine("  - $it") }
                    appendLine(
                        "Build and install it: python tools/model/build_model.py --model ${model.get()} --install",
                    )
                    append("Tasks that do not need the model can run with -PskipModel.")
                },
            )
        }
    }
}
