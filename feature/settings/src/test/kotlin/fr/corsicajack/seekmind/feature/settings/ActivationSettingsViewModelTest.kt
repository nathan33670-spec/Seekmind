package fr.corsicajack.seekmind.feature.settings

import com.google.common.truth.Truth.assertThat
import fr.corsicajack.seekmind.core.model.Entitlement
import fr.corsicajack.seekmind.engine.monetization.license.ActivationRefusal
import fr.corsicajack.seekmind.engine.monetization.license.ActivationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The « Activation » section (docs/specs/ui-ux.md §3.5, ticket M8-05). */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivationSettingsViewModelTest {
    private val entitlement = MutableStateFlow<Entitlement>(Entitlement.Free(7))
    private val activated = mutableListOf<String>()
    private var answer: ActivationResult = ActivationResult.Activated("SM-1", "lifetime")
    private var removals = 0

    private lateinit var model: ActivationSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        model = ActivationSettingsViewModel(
            entitlement = entitlement,
            activate = { code ->
                activated += code
                answer.also {
                    if (it is ActivationResult.Activated) {
                        entitlement.value =
                            Entitlement.Activated(it.licenseId, it.plan)
                    }
                }
            },
            deactivate = {
                removals++
                entitlement.value = Entitlement.Free(7)
            },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the code of a pasted link is activated, and the field is emptied on success`() {
        model.setInput("  seekmind://activate?t=a.b.c \n")
        model.activate()
        assertThat(activated).containsExactly("a.b.c")
        assertThat(model.state.value.input).isEmpty()
        assertThat(model.state.value.message).isEqualTo(ActivationMessage.Activated)
    }

    @Test
    fun `a refusal keeps the field and says why`() {
        answer = ActivationResult.Refused(ActivationRefusal.EXPIRED)
        model.setInput("a.b.c")
        model.activate()
        assertThat(model.state.value.input).isEqualTo("a.b.c")
        assertThat(model.state.value.message).isEqualTo(ActivationMessage.Refused(ActivationRefusal.EXPIRED))
        assertThat(model.state.value.entitlement).isEqualTo(Entitlement.Free(7))
    }

    @Test
    fun `an empty field is unreadable and asks nothing`() {
        model.setInput("   ")
        model.activate()
        assertThat(activated).isEmpty()
        assertThat(model.state.value.message).isEqualTo(ActivationMessage.Refused(ActivationRefusal.UNREADABLE))
    }

    @Test
    fun `typing clears the last message`() {
        model.setInput("x")
        model.activate()
        model.setInput("y")
        assertThat(model.state.value.message).isNull()
    }

    @Test
    fun `removing the activation goes back to the free plan`() {
        model.setInput("a.b.c")
        model.activate()
        model.deactivate()
        assertThat(removals).isEqualTo(1)
        assertThat(model.state.value.entitlement).isEqualTo(Entitlement.Free(7))
        assertThat(model.state.value.message).isEqualTo(ActivationMessage.Removed)
    }
}
