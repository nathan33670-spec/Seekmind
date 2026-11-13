package fr.corsicajack.seekmind.engine.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import dagger.hilt.android.AndroidEntryPoint
import fr.corsicajack.seekmind.engine.capture.pause.PauseController
import fr.corsicajack.seekmind.engine.capture.pause.TileModel
import fr.corsicajack.seekmind.engine.capture.status.CaptureStatusRepository
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick settings tile (docs/specs/capture.md §7): a tap toggles between active and paused without
 * limit, the subtitle tells the state, a long press opens the application (its `QS_TILE_PREFERENCES`
 * activity). While the panel shows it, the tile follows [CaptureStatusRepository], so a change made in
 * the application shows at once. The system calls [onStartListening] whenever the panel opens: an
 * active tile, refreshed on request only, is not needed.
 */
@AndroidEntryPoint
class PauseTileService : TileService() {
    @Inject
    lateinit var status: CaptureStatusRepository

    @Inject
    lateinit var pause: PauseController

    private lateinit var scope: CoroutineScope
    private var listening: Job? = null
    private var model: TileModel? = null

    override fun onCreate() {
        super.onCreate()
        // Tile updates and taps on the main thread.
        scope = CoroutineScope(SupervisorJob() + mainExecutor.asCoroutineDispatcher())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch { status.status.collect { render(TileModel.of(it)) } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (model?.onTap) {
            TileModel.Action.TOGGLE_PAUSE -> {
                val toggle = { scope.launch { pause.toggle() } }
                // Resuming from the lock screen asks for the unlock; pausing never does.
                if (model?.needsUnlock(isLocked) == true) unlockAndRun { toggle() } else toggle()
            }

            TileModel.Action.OPEN_APP, null -> openApp()
        }
    }

    private fun render(next: TileModel) {
        model = next
        val tile = qsTile ?: return
        tile.state = if (next.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle = when (val s = next.subtitle) {
            TileModel.Subtitle.Active -> getString(R.string.tile_subtitle_active)

            TileModel.Subtitle.Paused -> getString(R.string.tile_subtitle_paused)

            is TileModel.Subtitle.PausedUntil ->
                getString(R.string.tile_subtitle_paused_until, DateFormat.getTimeFormat(this).format(Date(s.at)))

            TileModel.Subtitle.NotSetUp -> getString(R.string.tile_subtitle_not_set_up)
        }
        tile.subtitle = subtitle
        tile.stateDescription = subtitle
        tile.updateTile()
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
