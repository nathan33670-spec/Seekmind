package fr.corsicajack.seekmind.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.corsicajack.seekmind.engine.source.SourceReturn
import javax.inject.Singleton

/** The return to the source (docs/specs/retour-source.md): its rules are read once, on first use. */
@Module
@InstallIn(SingletonComponent::class)
object SourceModule {
    @Provides
    @Singleton
    fun sourceReturn(@ApplicationContext context: Context): SourceReturn = SourceReturn.create(context)
}
