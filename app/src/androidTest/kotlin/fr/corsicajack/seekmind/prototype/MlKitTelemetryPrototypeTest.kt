package fr.corsicajack.seekmind.prototype

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.TrafficStats
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.engine.ocr.MlKitOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prototype S2 (ticket M0-07): 100 recognitions with the bundled ML Kit model, then what its
 * telemetry left behind: scheduled jobs, errors in the process log, bytes sent by the app.
 * Measurements are logged under the `SeekMindProto` tag (docs/prototypes/S2-mlkit-sans-telemetrie.md).
 */
@RunWith(AndroidJUnit4::class)
class MlKitTelemetryPrototypeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val packageName = instrumentation.targetContext.packageName

    /** Variant `ads` holds INTERNET; variant `offline` does not (ADR-0009). */
    private val holdsInternet = instrumentation.targetContext
        .checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
    private val flavor = if (holdsInternet) "ads" else "offline"

    @Test
    fun recognisesOneHundredTimesWithoutTelemetry() {
        val uid = Process.myUid()
        val txBefore = TrafficStats.getUidTxBytes(uid)
        val bitmap = drawReferenceText()
        val engine = MlKitOcrEngine()
        val durations = LongArray(RECOGNITIONS)
        var correct = 0
        try {
            runBlocking {
                repeat(RECOGNITIONS) { index ->
                    val outcome = engine.recognize(bitmap)
                    durations[index] = outcome.durationMs
                    if (outcome.lines.joinToString(" ") { it.text }.contains(EXPECTED)) correct++
                }
            }
        } finally {
            engine.close()
            bitmap.recycle()
        }
        durations.sort()
        measure("ocr_first_ms", durations.last().toDouble())
        measure("ocr_median_ms", durations[RECOGNITIONS / 2].toDouble())
        measure("ocr_correct", correct.toDouble())

        // Telemetry is batched: give the scheduler time, then force any job the app owns to run now.
        SystemClock.sleep(SCHEDULE_WAIT_MS)
        val jobs = scheduledJobIds()
        measure("scheduled_jobs", jobs.size.toDouble())
        jobs.forEach { shell("cmd jobscheduler run -f $packageName $it") }
        SystemClock.sleep(UPLOAD_WAIT_MS)

        val txBytes = TrafficStats.getUidTxBytes(uid).let { if (txBefore < 0 || it < 0) -1 else it - txBefore }
        measure("tx_bytes", txBytes.toDouble())
        val errors = processErrors()
        measure("log_errors", errors.size.toDouble())
        errors.distinct().forEach { Log.i(TAG, "api=${Build.VERSION.SDK_INT} log_error_tag=$it") }
        val crashes = shell("logcat -d -b crash").lines().count { it.contains(packageName) }
        measure("crash_lines", crashes.toDouble())

        assertThat(correct).isEqualTo(RECOGNITIONS)
        assertThat(crashes).isEqualTo(0)
        assertThat(txBytes).isAtLeast(0L) // -1: the platform does not count this uid's traffic
        if (holdsInternet) assertThat(txBytes).isEqualTo(0L)
    }

    /** Synthetic reference text, drawn black on white, as a screen would show it. */
    private fun drawReferenceText(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 400, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
        }
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(EXPECTED, 40f, 160f, paint)
            drawText("Recette des pates a la truffe", 40f, 280f, paint)
        }
        return bitmap
    }

    /** Ids of the jobs this package has scheduled, from `dumpsys jobscheduler`. */
    private fun scheduledJobIds(): List<String> {
        val prefix = Regex("""JOB #u\d+a?\d*/(\d+): \S+ $packageName/(\S+)""")
        return shell("dumpsys jobscheduler $packageName").lines().mapNotNull { line ->
            prefix.find(line)?.let { match ->
                Log.i(TAG, "api=${Build.VERSION.SDK_INT} job_service=${match.groupValues[2]}")
                match.groupValues[1]
            }
        }.distinct()
    }

    /** Tags of the warning and error lines this process wrote; tags carry no user content. */
    private fun processErrors(): List<String> {
        val line = Regex("""^\S+ \S+\s+(\d+)\s+\d+ ([EF]) (.+?)\s*:""")
        val pid = Process.myPid().toString()
        return shell("logcat -d *:E").lines().mapNotNull { text ->
            line.find(text)?.takeIf { it.groupValues[1] == pid }?.let { "${it.groupValues[2]}/${it.groupValues[3]}" }
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
        }

    private fun measure(name: String, value: Double) {
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} flavor=$flavor $name=$value")
    }

    private companion object {
        const val TAG = "SeekMindProto"
        const val EXPECTED = "SEEKMIND OCR 2026"
        const val RECOGNITIONS = 100
        const val SCHEDULE_WAIT_MS = 10_000L
        const val UPLOAD_WAIT_MS = 15_000L
    }
}
