package fr.corsicajack.seekmind.core.testing

import fr.corsicajack.seekmind.core.common.DispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher

/** Every dispatcher is the same test dispatcher: code under test runs in the virtual time of runTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatchers(val dispatcher: TestDispatcher = StandardTestDispatcher()) : DispatcherProvider {
    override val main: TestDispatcher get() = dispatcher
    override val io: TestDispatcher get() = dispatcher
    override val default: TestDispatcher get() = dispatcher
}
