package fr.corsicajack.seekmind

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.work.SeekMindWorkerFactory

/**
 * What every application class of SeekMind does, the real one ([SeekMindApp]) and the Hilt test one of
 * the end-to-end tests that replace a binding (docs/06-qualite-tests-ci.md §4): WorkManager is
 * initialised on demand with the worker factory of the graph (its default initializer is removed from
 * the manifest). The factory is read from the graph when WorkManager first asks for it, not injected:
 * a Hilt test application creates its graph only when the test starts.
 */
open class SeekMindBaseApplication :
    Application(),
    Configuration.Provider {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun workerFactory(): SeekMindWorkerFactory
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(EntryPointAccessors.fromApplication(this, WorkerEntryPoint::class.java).workerFactory())
            .build()
}
