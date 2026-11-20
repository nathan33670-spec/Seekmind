package fr.corsicajack.seekmind.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.core.database.settings.SettingsRepository
import fr.corsicajack.seekmind.engine.capture.pipeline.CapturePipeline
import fr.corsicajack.seekmind.engine.capture.routing.CaptureLoop
import fr.corsicajack.seekmind.engine.capture.routing.EventRouter

/**
 * The singletons the end-to-end tests of the capture observe (docs/06-qualite-tests-ci.md §4): they
 * synchronise on [CapturePipeline.events], which carry identifiers and reasons only. The application
 * itself does not use it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptureEntryPoint {
    fun pipeline(): CapturePipeline

    fun settings(): SettingsRepository

    fun database(): DatabaseState

    /** For the diagnostics of a test whose expected event does not come. */
    fun router(): EventRouter

    fun loop(): CaptureLoop
}
