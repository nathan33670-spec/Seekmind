package fr.corsicajack.seekmind.core.testing

import android.app.Activity
import fr.corsicajack.seekmind.core.model.RewardOutcome
import fr.corsicajack.seekmind.core.model.RewardReadiness
import fr.corsicajack.seekmind.core.model.port.RewardProvider
import kotlinx.coroutines.flow.MutableStateFlow

/** A [RewardProvider] driven by the test: what [prepare] and [show] answer, and how often they ran. */
class FakeRewardProvider(
    override val isSupported: Boolean = true,
    var readiness: RewardReadiness = RewardReadiness.READY,
    var outcome: RewardOutcome = RewardOutcome.EARNED,
) : RewardProvider {
    var prepares = 0
        private set
    var shows = 0
        private set
    var privacyForms = 0
        private set

    override val privacyOptionsRequired = MutableStateFlow(false)

    override suspend fun prepare(activity: Activity): RewardReadiness {
        prepares++
        return readiness
    }

    override suspend fun show(activity: Activity): RewardOutcome {
        shows++
        return outcome
    }

    override suspend fun showPrivacyOptions(activity: Activity) {
        privacyForms++
    }

    companion object {
        /** An Activity that is never read: on the JVM, Activity is a stub that cannot be constructed. */
        val ACTIVITY: Activity by lazy {
            Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").let { field ->
                field.isAccessible = true
                val unsafe = field.get(null)
                unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
                    .invoke(unsafe, Activity::class.java) as Activity
            }
        }
    }
}
