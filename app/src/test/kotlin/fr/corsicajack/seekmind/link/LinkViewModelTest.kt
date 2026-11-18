package fr.corsicajack.seekmind.link

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.engine.monetization.license.ActivationRefusal
import fr.corsicajack.seekmind.engine.monetization.license.ActivationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The activation link (docs/specs/ui-ux.md §3.7, ticket M8-05). */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkViewModelTest {
    private val activated = mutableListOf<String>()
    private var answer: ActivationResult = ActivationResult.Activated("SM-1", "lifetime")

    private fun model(link: String?) = LinkViewModel(link) { code ->
        activated += code
        answer
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is activated without the confirmation`() {
        val model = model("seekmind://activate?t=a.b.c")
        assertThat(model.step.value).isEqualTo(LinkStep.Confirm)
        assertThat(activated).isEmpty()
        model.confirm()
        assertThat(activated).containsExactly("a.b.c")
        assertThat(model.step.value).isEqualTo(LinkStep.Done(answer))
    }

    @Test
    fun `a declined link does nothing, even confirmed afterwards`() {
        val model = model("seekmind://activate?t=a.b.c")
        model.decline()
        model.confirm()
        assertThat(model.step.value).isEqualTo(LinkStep.Declined)
        assertThat(activated).isEmpty()
    }

    @Test
    fun `a malformed or oversized link is neutral and cannot be confirmed`() {
        listOf(
            null,
            "seekmind://activate?t=a b",
            "seekmind://evil?t=a.b.c",
            "seekmind://activate?t=" + "a".repeat(5_000),
        )
            .forEach { link ->
                val model = model(link)
                assertThat(model.step.value).isEqualTo(LinkStep.Invalid)
                model.confirm()
                assertThat(activated).isEmpty()
            }
    }

    @Test
    fun `a refusal is shown and a second confirmation does nothing`() {
        answer = ActivationResult.Refused(ActivationRefusal.REVOKED)
        val model = model("seekmind://activate?t=a.b.c")
        model.confirm()
        model.confirm()
        assertThat(activated).hasSize(1)
        assertThat(model.step.value).isEqualTo(LinkStep.Done(answer))
    }
}
