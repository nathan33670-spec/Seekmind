package fr.corsicajack.seekmind.engine.embedding.catalog

import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayAssetPackGatewayTest {
    @Test
    fun everyPlayStatusHasItsPackStatus() {
        val expected = mapOf(
            AssetPackStatus.UNKNOWN to PackStatus.NOT_INSTALLED,
            AssetPackStatus.NOT_INSTALLED to PackStatus.NOT_INSTALLED,
            AssetPackStatus.PENDING to PackStatus.PENDING,
            AssetPackStatus.DOWNLOADING to PackStatus.DOWNLOADING,
            AssetPackStatus.TRANSFERRING to PackStatus.TRANSFERRING,
            AssetPackStatus.WAITING_FOR_WIFI to PackStatus.WAITING_FOR_WIFI,
            AssetPackStatus.REQUIRES_USER_CONFIRMATION to PackStatus.REQUIRES_USER_CONFIRMATION,
            AssetPackStatus.COMPLETED to PackStatus.COMPLETED,
            AssetPackStatus.FAILED to PackStatus.FAILED,
            AssetPackStatus.CANCELED to PackStatus.CANCELED,
        )
        for ((code, status) in expected) assertThat(PlayAssetPackGateway.status(code)).isEqualTo(status)
        assertThat(PlayAssetPackGateway.status(42)).isEqualTo(PackStatus.NOT_INSTALLED)
    }

    @Test
    fun onlyAnInstallationByPlayUsesPlay() {
        assertThat(PlayAssetPackGateway.answers("com.android.vending")).isTrue()
        assertThat(PlayAssetPackGateway.answers(null)).isFalse() // adb, development builds
        assertThat(PlayAssetPackGateway.answers("com.google.android.packageinstaller")).isFalse()
        assertThat(PlayAssetPackGateway.answers("org.fdroid.fdroid")).isFalse()
    }

    @Test
    fun aFailureOtherThanPlaysIsAnInternalError() {
        assertThat(PlayAssetPackGateway.errorCode(IllegalStateException())).isEqualTo(AssetPackErrorCode.INTERNAL_ERROR)
    }
}
