package fr.corsicajack.seekmind.core.database.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import fr.corsicajack.seekmind.core.model.AppFamily
import fr.corsicajack.seekmind.core.model.config.EmbeddingConfig
import fr.corsicajack.seekmind.core.model.config.PrivacyConfig
import fr.corsicajack.seekmind.core.model.config.StorageConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Acceptance of the disclosure (docs/specs/ui-ux.md §3.1): its version and the time of the tap. */
data class Consent(val version: Int, val acceptedAt: Long)

/**
 * The non-sensitive settings (architecture §6, docs/07-securite-vie-privee.md §5): switches, durations,
 * choices. **No captured data ever goes here** (INV-06): neither text, nor title, nor URL, nor query.
 */
data class Settings(
    /** null: not accepted, or withdrawn. */
    val consent: Consent?,
    /** Families of applications to remember (docs/specs/confidentialite.md §3.4). */
    val enabledFamilies: Set<AppFamily>,
    /** null: active; Long.MAX_VALUE: paused without limit (docs/specs/capture.md §7). */
    val pausedUntil: Long?,
    /** null: unlimited (docs/specs/stockage.md §6). */
    val retentionDays: Int?,
    val storageBudgetBytes: Long,
    /** App lock, on by default (EF-ONB-06); the interface disables it without a secure screen lock. */
    val lockEnabled: Boolean,
    /** Delay after going to the background before the lock comes back (docs/specs/ui-ux.md §3.6). */
    val lockGraceMs: Long,
    /** docs/specs/embeddings.md §5.1. */
    val activeModelId: String,
    /** Search history, on by default and deletable (EF-SRCH-09). */
    val historyEnabled: Boolean,
)

/** Settings in DataStore Preferences, exposed as typed flows (ticket M1-05). */
class SettingsRepository(private val store: DataStore<Preferences>, private val defaults: Settings = DEFAULTS) {
    val settings: Flow<Settings> = store.data.map(::read).distinctUntilChanged()

    val consent: Flow<Consent?> = settings.map { it.consent }.distinctUntilChanged()
    val enabledFamilies: Flow<Set<AppFamily>> = settings.map { it.enabledFamilies }.distinctUntilChanged()
    val pausedUntil: Flow<Long?> = settings.map { it.pausedUntil }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    suspend fun acceptConsent(consent: Consent) = store.edit {
        it[CONSENT_VERSION] = consent.version
        it[CONSENT_AT] = consent.acceptedAt
    }

    suspend fun withdrawConsent() = store.edit {
        it.remove(CONSENT_VERSION)
        it.remove(CONSENT_AT)
    }

    suspend fun setEnabledFamilies(families: Set<AppFamily>) = store.edit { prefs ->
        prefs[ENABLED_FAMILIES] = families.mapTo(mutableSetOf()) { it.name }
    }

    suspend fun setPausedUntil(until: Long?) = store.edit { prefs ->
        if (until == null) prefs.remove(PAUSED_UNTIL) else prefs[PAUSED_UNTIL] = until
    }

    suspend fun setRetentionDays(days: Int?) = store.edit { prefs ->
        require(days == null || days > 0) { "retention of $days days" }
        prefs[RETENTION_DAYS] = days ?: UNLIMITED_RETENTION
    }

    suspend fun setStorageBudgetBytes(bytes: Long) = store.edit { prefs ->
        require(bytes > 0) { "budget of $bytes bytes" }
        prefs[STORAGE_BUDGET] = bytes
    }

    suspend fun setLockEnabled(enabled: Boolean) = store.edit { it[LOCK_ENABLED] = enabled }

    suspend fun setLockGraceMs(ms: Long) = store.edit { prefs ->
        require(ms >= 0) { "grace of $ms ms" }
        prefs[LOCK_GRACE_MS] = ms
    }

    suspend fun setActiveModelId(id: String) = store.edit { it[ACTIVE_MODEL] = id }

    suspend fun setHistoryEnabled(enabled: Boolean) = store.edit { it[HISTORY_ENABLED] = enabled }

    /** Back to the defaults, consent withdrawn: part of "erase everything". */
    suspend fun reset() = store.edit { it.clear() }

    private fun read(prefs: Preferences): Settings {
        val version = prefs[CONSENT_VERSION]
        val at = prefs[CONSENT_AT]
        return Settings(
            consent = if (version != null && at != null) Consent(version, at) else null,
            enabledFamilies = prefs[ENABLED_FAMILIES]
                ?.mapNotNullTo(mutableSetOf()) { name -> AppFamily.entries.firstOrNull { it.name == name } }
                ?: defaults.enabledFamilies,
            pausedUntil = prefs[PAUSED_UNTIL],
            retentionDays = when (val days = prefs[RETENTION_DAYS]) {
                null -> defaults.retentionDays
                UNLIMITED_RETENTION -> null
                else -> days
            },
            storageBudgetBytes = prefs[STORAGE_BUDGET] ?: defaults.storageBudgetBytes,
            lockEnabled = prefs[LOCK_ENABLED] ?: defaults.lockEnabled,
            lockGraceMs = prefs[LOCK_GRACE_MS] ?: defaults.lockGraceMs,
            activeModelId = prefs[ACTIVE_MODEL] ?: defaults.activeModelId,
            historyEnabled = prefs[HISTORY_ENABLED] ?: defaults.historyEnabled,
        )
    }

    companion object {
        /** Name of the preferences file in the application's files. */
        const val FILE_NAME = "seekmind_settings"

        /** Defaults of the specs, taken from the *Config objects rather than copied. */
        val DEFAULTS = Settings(
            consent = null,
            enabledFamilies = PrivacyConfig().defaultEnabledFamilies,
            pausedUntil = null,
            retentionDays = StorageConfig().retentionDays,
            storageBudgetBytes = StorageConfig().storageBudgetBytes,
            lockEnabled = true,
            lockGraceMs = 5 * 60_000L,
            activeModelId = EmbeddingConfig().defaultModel,
            historyEnabled = true,
        )

        /** Every key the repository writes; nothing else may appear in the file. */
        val KEYS: Set<String> get() = ALL_KEYS.mapTo(mutableSetOf()) { it.name }

        /** The single DataStore of the process for [FILE_NAME]. */
        fun create(context: Context, fileName: String = FILE_NAME): SettingsRepository =
            SettingsRepository(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(fileName) })

        private const val UNLIMITED_RETENTION = 0
        private val CONSENT_VERSION = intPreferencesKey("consent_version")
        private val CONSENT_AT = longPreferencesKey("consent_accepted_at")
        private val ENABLED_FAMILIES = stringSetPreferencesKey("enabled_families")
        private val PAUSED_UNTIL = longPreferencesKey("paused_until")
        private val RETENTION_DAYS = intPreferencesKey("retention_days")
        private val STORAGE_BUDGET = longPreferencesKey("storage_budget_bytes")
        private val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        private val LOCK_GRACE_MS = longPreferencesKey("lock_grace_ms")
        private val ACTIVE_MODEL = stringPreferencesKey("active_model_id")
        private val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        private val ALL_KEYS = listOf(
            CONSENT_VERSION, CONSENT_AT, ENABLED_FAMILIES, PAUSED_UNTIL, RETENTION_DAYS, STORAGE_BUDGET,
            LOCK_ENABLED, LOCK_GRACE_MS, ACTIVE_MODEL, HISTORY_ENABLED,
        )
    }
}
