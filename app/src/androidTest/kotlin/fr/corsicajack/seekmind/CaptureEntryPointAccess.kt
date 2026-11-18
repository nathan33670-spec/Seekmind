package fr.corsicajack.seekmind

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.DatabaseState
import fr.corsicajack.seekmind.di.CaptureEntryPoint

/** The open database of the application under test, through [CaptureEntryPoint]. */
object CaptureEntryPointAccess {
    fun database(context: Context): DatabaseHandle =
        (EntryPointAccessors.fromApplication(context, CaptureEntryPoint::class.java).database() as DatabaseState.Ready)
            .database
}
