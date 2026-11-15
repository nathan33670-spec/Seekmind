package fr.corsicajack.seekmind.buildlogic

import fr.corsicajack.seekmind.buildlogic.model.VerifyModelTask
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Bundled embedding model of :engine:embedding (docs/specs/embeddings.md §2): `verifyModel` checks the
 * set in src/main/assets/model/fr against model.lock.json, and runs before every build of the module.
 * `-PskipModel` skips it for debug builds only: a release build always needs the model (ticket M4-06).
 */
class ModelAssetsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val verifyModel = tasks.register("verifyModel", VerifyModelTask::class.java) {
            group = "verification"
            description = "Checks the bundled embedding model against model.lock.json."
            lockFile.set(layout.projectDirectory.file("model.lock.json"))
            model.set("fr")
            modelDirectory.set(layout.projectDirectory.dir("src/main/assets/model/fr"))
        }
        val skip = providers.gradleProperty("skipModel").isPresent
        tasks.matching { (it.name == "preBuild" && !skip) || it.name == "preReleaseBuild" }
            .configureEach { dependsOn(verifyModel) }
    }
}
