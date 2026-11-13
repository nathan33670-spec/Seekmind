package fr.corsicajack.seekmind.engine.privacy

import fr.corsicajack.seekmind.core.database.store.AppRule
import fr.corsicajack.seekmind.core.model.AppFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The user's latest privacy choices, kept in memory for the gate, which decides without suspending.
 * [current] is null until both sources have been read once, and again if one of them fails: the gate then
 * rejects everything.
 */
class UserChoicesCache(scope: CoroutineScope, rules: Flow<List<AppRule>>, enabledFamilies: Flow<Set<AppFamily>>) {
    @Volatile
    var current: UserPrivacyChoices? = null
        private set

    init {
        scope.launch {
            combine(rules, enabledFamilies) { list, families ->
                UserPrivacyChoices(list.associate { it.packageName to it.mode }, families)
            }
                // A failed source leaves no stale choices behind: in doubt, the gate rejects everything.
                .catch { current = null }
                .collect { current = it }
        }
    }
}
