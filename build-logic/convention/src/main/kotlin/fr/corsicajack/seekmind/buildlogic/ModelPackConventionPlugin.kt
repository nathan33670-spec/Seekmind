package fr.corsicajack.seekmind.buildlogic

import fr.corsicajack.seekmind.buildlogic.model.VerifyModelTask
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The on-demand asset pack of the `multi` model (docs/specs/embeddings.md §5.2, ticket M4-06):
 * `verifyModel` checks the set in src/main/assets/model/multi against the lock of :engine:embedding. The
 * release bundles of :app depend on it, so that a bundle never ships a missing or altered pack.
 */
class ModelPackConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        tasks.register("verifyModel", VerifyModelTask::class.java) {
            group = "verification"
            description = "Checks the multi embedding model of the asset pack against model.lock.json."
            lockFile.set(rootProject.layout.projectDirectory.file("engine/embedding/model.lock.json"))
            model.set("multi")
            modelDirectory.set(layout.projectDirectory.dir("src/main/assets/model/multi"))
        }
    }
}
