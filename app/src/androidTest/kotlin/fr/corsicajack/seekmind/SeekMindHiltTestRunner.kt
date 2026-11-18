package fr.corsicajack.seekmind

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.CustomTestApplication

/** The Hilt test application: [SeekMindBaseApplication], with a graph per test that may replace bindings. */
@CustomTestApplication(SeekMindBaseApplication::class)
interface SeekMindHiltTestApp

/**
 * Runs the `@HiltAndroidTest` tests, which replace a binding (E2E-15), with [SeekMindHiltTestApp]. The
 * application class is chosen once per process: the build uses this runner only with
 * `-Pseekmind.hiltTests=true`, and device-tests.yml runs these tests apart from the others.
 */
class SeekMindHiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(classLoader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(classLoader, SeekMindHiltTestApp_Application::class.java.name, context)
}
