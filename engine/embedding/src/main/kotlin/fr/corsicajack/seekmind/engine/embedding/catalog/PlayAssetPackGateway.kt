package fr.corsicajack.seekmind.engine.embedding.catalog

import android.app.Activity
import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackException
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.google.android.play.core.ktx.requestFetch
import com.google.android.play.core.ktx.requestPackStates
import com.google.android.play.core.ktx.requestProgressFlow
import com.google.android.play.core.ktx.requestRemovePack
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [AssetPackGateway] on `AssetPackManager` of Play Asset Delivery (docs/specs/embeddings.md §5.2). Only
 * for an application installed by Google Play: elsewhere, every call fails with `API_NOT_AVAILABLE`, and
 * `:app` uses [FileModelPackInstaller] instead. The library declares no network permission: the Play
 * Store downloads; its exported components are admitted by ADR-0012.
 */
class PlayAssetPackGateway(private val manager: AssetPackManager) : AssetPackGateway {
    constructor(context: Context) : this(AssetPackManagerFactory.getInstance(context.applicationContext))

    /** The current state, then every update; a failure of Play becomes a [PackStatus.FAILED] state. */
    override fun progress(pack: String): Flow<PackProgress> = flow {
        manager.requestPackStates(listOf(pack)).packStates()[pack]?.let { emit(toProgress(it)) }
        emitAll(manager.requestProgressFlow(listOf(pack)).filter { it.name() == pack }.map(::toProgress))
    }.catch { e ->
        if (e is CancellationException) throw e
        emit(PackProgress(PackStatus.FAILED, 0, 0, errorCode(e)))
    }

    override suspend fun fetch(pack: String) {
        manager.requestFetch(listOf(pack))
    }

    override suspend fun confirm(activity: Activity): Boolean = suspendCancellableCoroutine { continuation ->
        manager.showConfirmationDialog(activity)
            .addOnSuccessListener { continuation.resume(it == Activity.RESULT_OK) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    override suspend fun remove(pack: String) = manager.requestRemovePack(pack)

    override fun assetsPath(pack: String): File? = manager.getPackLocation(pack)?.assetsPath()?.let(::File)

    companion object {
        private const val PLAY_STORE = "com.android.vending"

        /**
         * True when the application was installed by Google Play ([installer] is the installing package of
         * `InstallSourceInfo`): only then does Play Asset Delivery answer.
         */
        fun answers(installer: String?): Boolean = installer == PLAY_STORE

        fun toProgress(state: AssetPackState) = PackProgress(
            status(state.status()),
            state.bytesDownloaded(),
            state.totalBytesToDownload(),
            state.errorCode(),
        )

        /** `AssetPackStatus` → [PackStatus]; an unknown status counts as not installed. */
        fun status(code: Int): PackStatus = when (code) {
            AssetPackStatus.PENDING -> PackStatus.PENDING
            AssetPackStatus.DOWNLOADING -> PackStatus.DOWNLOADING
            AssetPackStatus.TRANSFERRING -> PackStatus.TRANSFERRING
            AssetPackStatus.WAITING_FOR_WIFI -> PackStatus.WAITING_FOR_WIFI
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> PackStatus.REQUIRES_USER_CONFIRMATION
            AssetPackStatus.COMPLETED -> PackStatus.COMPLETED
            AssetPackStatus.FAILED -> PackStatus.FAILED
            AssetPackStatus.CANCELED -> PackStatus.CANCELED
            else -> PackStatus.NOT_INSTALLED
        }

        fun errorCode(e: Throwable): Int = (e as? AssetPackException)?.errorCode ?: AssetPackErrorCode.INTERNAL_ERROR
    }
}
