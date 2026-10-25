package io.github.mangi.eta.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.mangi.eta.data.model.AnswerFoldLevel
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.repository.ConversationUsageTotals
import io.github.mangi.eta.data.repository.ModelUsageDelta
import java.io.File
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import java.time.LocalDate
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SettingsDataStore {
    private const val STORE_NAME = "eta_settings"

    private val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
    private val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
    private val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
    private val FILE_LOGGING_ENABLED = booleanPreferencesKey("file_logging_enabled")
    private val LINUX_DISTRIBUTION = stringPreferencesKey("linux_distribution")
    private val APPEARANCE_THEME_MODE = stringPreferencesKey("appearance_theme_mode")
    private val APPEARANCE_MONET_ENABLED = booleanPreferencesKey("appearance_monet_enabled")
    private val APPEARANCE_PALETTE_STYLE = stringPreferencesKey("appearance_palette_style")
    private val APPEARANCE_ACCENT_COLOR = stringPreferencesKey("appearance_accent_color")
    private val APPEARANCE_PURE_BLACK_ENABLED = booleanPreferencesKey("appearance_pure_black_enabled")
    private val APPEARANCE_BLUR_ENABLED = booleanPreferencesKey("appearance_blur_enabled")
    private val APPEARANCE_TOP_BAR_BLUR_STYLE = stringPreferencesKey("appearance_top_bar_blur_style")
    private val APPEARANCE_PREDICTIVE_BACK_ENABLED =
        booleanPreferencesKey("appearance_predictive_back_enabled")
    private val APPEARANCE_INTERFACE_SCALE = floatPreferencesKey("appearance_interface_scale")
    private val APPEARANCE_MORPH_LOADING_INDICATOR =
        booleanPreferencesKey("appearance_morph_loading_indicator")
    private val APPEARANCE_MORPH_LOADING_BEFORE_RESPONSE =
        booleanPreferencesKey("appearance_morph_loading_before_response")
    private val APPEARANCE_MESSAGE_TIMESTAMPS_ENABLED =
        booleanPreferencesKey("appearance_message_timestamps_enabled")
    private val APPEARANCE_ANSWER_FOLD_LEVEL =
        stringPreferencesKey("appearance_answer_fold_level")
    private val APPEARANCE_ICON_LIGHT = intPreferencesKey("appearance_icon_light")
    private val APPEARANCE_ICON_DARK = intPreferencesKey("appearance_icon_dark")
    private val APP_LAUNCH_COUNT = intPreferencesKey("app_launch_count")
    private val UPDATE_DISMISSED_VERSION = stringPreferencesKey("update_dismissed_version")
    private val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
    private val RETIRED_INPUT_TOKENS = longPreferencesKey("retired_input_tokens")
    private val RETIRED_OUTPUT_TOKENS = longPreferencesKey("retired_output_tokens")
    private val RETIRED_CACHED_TOKENS = longPreferencesKey("retired_cached_tokens")
    private val RETIRED_CONVERSATIONS = intPreferencesKey("retired_conversations")
    private val RETIRED_MESSAGES = intPreferencesKey("retired_messages")
    private val RETIRED_HEATMAP_JSON = stringPreferencesKey("retired_heatmap_json")
    private val WEB_TOOLS_SETTINGS = stringPreferencesKey("web_tools_settings_v1")
    private val webToolsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val SELECTED_MODEL_BY_PROVIDER_PREFIX = "selected_model_id_by_provider."
    private const val LINUX_BACKEND_PREFIX = "linux_backend."

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = STORE_NAME,
        produceMigrations = { context -> listOf(ErrorReconnectPolicyMigration(), UsageLedgerRollbackMigration(
            File(context.filesDir, "datastore/eta_usage_ledger.json"),
        )) },
    )

    @Volatile
    private lateinit var dataStore: DataStore<Preferences>

    private lateinit var usageLedger: PreferencesUsageLedger

    @Synchronized
    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            val preferencesStore = context.applicationContext.dataStore
            usageLedger = PreferencesUsageLedger(preferencesStore) { transform ->
                diagnosticEdit("usage.editEntryWait", "usage.transform", "usage.commitTail", transform)
            }
            // The delegate's DataMigration gates every read/edit, including non-ledger settings.
            // Publication here is NOT migration completion; failures propagate from the first access.
            dataStore = preferencesStore
        }
    }

    fun settingsFlow(): Flow<Settings> {
        ensureInitialized()
        // Migration/read failures propagate: never emit a plausible default configuration.
        return dataStore.data
            .map { preferences -> preferences.toSettings() }
            .distinctUntilChanged()
    }

    suspend fun settings(): Settings = settingsFlow().first()

    fun webToolsSettingsFlow(): Flow<WebToolsSettings> {
        ensureInitialized()
        return dataStore.data
            .map { prefs ->
                prefs[WEB_TOOLS_SETTINGS]?.let { raw ->
                    runCatching { webToolsJson.decodeFromString<WebToolsSettings>(raw) }.getOrNull()
                } ?: WebToolsSettings()
            }
            .distinctUntilChanged()
    }

    suspend fun webToolsSettings(): WebToolsSettings = webToolsSettingsFlow().first()

    suspend fun setWebToolsSettings(settings: WebToolsSettings) {
        ensureInitialized()
        dataStore.edit { prefs -> prefs[WEB_TOOLS_SETTINGS] = webToolsJson.encodeToString(settings) }
    }

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        ensureInitialized()
        diagnosticEdit("settings.editEntryWait", "settings.transform", "settings.commitTail") { prefs ->
            val current = prefs.toSettings()
            val updated = transform(current)
            prefs.putOrRemove(SELECTED_PROVIDER_ID, updated.selectedProviderId)
            prefs.putOrRemove(SELECTED_MODEL_ID, updated.selectedModelId)
            prefs[MEMORY_ENABLED] = updated.memoryEnabled
            prefs[FILE_LOGGING_ENABLED] = updated.fileLoggingEnabled
            // Unrelated settings edits must not downgrade an unknown/future choice.
            if (updated.errorReconnectPolicy != current.errorReconnectPolicy) {
                prefs[ERROR_RECONNECT_POLICY] = updated.errorReconnectPolicy.persistedValue
                prefs[ERROR_RECONNECT_POLICY_VERSION] = ErrorReconnectPolicy.STORAGE_VERSION
            }
            prefs.putAppearance(updated.appearance.normalized())
        }
    }

    fun selectedProviderIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedProviderId }.distinctUntilChanged()

    fun selectedModelIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedModelId }.distinctUntilChanged()

    suspend fun selectedModelIdForProvider(providerId: String): String? {
        ensureInitialized()
        return dataStore.data
            .map { prefs -> prefs[selectedModelByProviderKey(providerId)] }
            .first()
    }

    fun memoryEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.memoryEnabled }.distinctUntilChanged()

    fun fileLoggingEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.fileLoggingEnabled }.distinctUntilChanged()

    suspend fun setFileLoggingEnabled(enabled: Boolean) {
        updateSettings { it.copy(fileLoggingEnabled = enabled) }
    }

    fun errorReconnectPolicyFlow(): Flow<ErrorReconnectPolicy> =
        settingsFlow().map { it.errorReconnectPolicy }.distinctUntilChanged()

    suspend fun setErrorReconnectPolicy(policy: ErrorReconnectPolicy) {
        ensureInitialized()
        dataStore.edit { prefs ->
            // An explicit choice, including NONE, owns its schema version.
            prefs[ERROR_RECONNECT_POLICY] = policy.persistedValue
            prefs[ERROR_RECONNECT_POLICY_VERSION] = ErrorReconnectPolicy.STORAGE_VERSION
        }
    }

    fun linuxDistributionFlow(): Flow<String?> {
        ensureInitialized()
        return dataStore.data
            .map { preferences -> preferences[LINUX_DISTRIBUTION] }
            .distinctUntilChanged()
    }

    fun linuxBackendFlow(distribution: String): Flow<String?> {
        ensureInitialized()
        return dataStore.data.map { it[stringPreferencesKey("linux_backend.$distribution")] }.distinctUntilChanged()
    }

    suspend fun setLinuxBackend(distribution: String, backend: String?) {
        ensureInitialized()
        dataStore.edit { preferences ->
            val key = stringPreferencesKey("linux_backend.$distribution")
            if (backend == null) preferences.remove(key) else preferences[key] = backend
        }
    }

    fun appearanceSettingsFlow(): Flow<AppearanceSettings> =
        settingsFlow().map { it.appearance }.distinctUntilChanged()

    suspend fun setSelectedProviderId(id: String?) {
        updateSettings { it.copy(selectedProviderId = id) }
    }

    suspend fun setSelectedModelId(id: String?) {
        updateSettings { it.copy(selectedModelId = id) }
    }

    suspend fun setSelection(providerId: String?, modelId: String?) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val previousProviderId = prefs[SELECTED_PROVIDER_ID]
            val previousModelId = prefs[SELECTED_MODEL_ID]
            if (previousProviderId != null && previousModelId != null) {
                prefs[selectedModelByProviderKey(previousProviderId)] = previousModelId
            }

            prefs.putOrRemove(SELECTED_PROVIDER_ID, providerId)
            prefs.putOrRemove(SELECTED_MODEL_ID, modelId)
            if (providerId != null && modelId != null) {
                prefs[selectedModelByProviderKey(providerId)] = modelId
            }
        }
    }

    suspend fun clearSelectedModelIdForProvider(providerId: String) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs.remove(selectedModelByProviderKey(providerId))
        }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        updateSettings { it.copy(memoryEnabled = enabled) }
    }

    suspend fun setLinuxDistribution(value: String?) {
        ensureInitialized()
        dataStore.edit { preferences -> preferences.putOrRemove(LINUX_DISTRIBUTION, value) }
    }

    suspend fun backupSnapshot(): EtaSettingsBackup {
        ensureInitialized()
        // One committed Preferences version contains BOTH settings and statistics.
        val prefs = usageLedger.preferencesSnapshot()
        val settings = prefs.toSettings()
        return EtaSettingsBackup(
            selectedProviderId = settings.selectedProviderId,
            selectedModelId = settings.selectedModelId,
            memoryEnabled = settings.memoryEnabled,
            fileLoggingEnabled = settings.fileLoggingEnabled,
            errorReconnectPolicy = prefs[ERROR_RECONNECT_POLICY],
            errorReconnectPolicyVersion = prefs[ERROR_RECONNECT_POLICY_VERSION],
            linuxDistribution = prefs[LINUX_DISTRIBUTION],
            linuxBackends = stringMap(prefs, LINUX_BACKEND_PREFIX),
            selectedModelByProvider = stringMap(prefs, SELECTED_MODEL_BY_PROVIDER_PREFIX),
            appearance = settings.appearance,
            modelUsageJson = prefs[MODEL_USAGE_JSON].orEmpty(),
            webToolsJson = prefs[WEB_TOOLS_SETTINGS].orEmpty(),
            retiredInputTokens = prefs[RETIRED_INPUT_TOKENS] ?: 0L,
            retiredOutputTokens = prefs[RETIRED_OUTPUT_TOKENS] ?: 0L,
            retiredCachedTokens = prefs[RETIRED_CACHED_TOKENS] ?: 0L,
            retiredConversations = prefs[RETIRED_CONVERSATIONS] ?: 0,
            retiredMessages = prefs[RETIRED_MESSAGES] ?: 0,
            retiredHeatmapJson = prefs[RETIRED_HEATMAP_JSON].orEmpty(),
        )
    }

    suspend fun restoreBackup(snapshot: EtaSettingsBackup) {
        require(snapshot.errorReconnectPolicyVersion == null ||
            snapshot.errorReconnectPolicyVersion in 0..ErrorReconnectPolicy.STORAGE_VERSION) {
            "Unsupported error reconnect settings version; existing settings were not changed."
        }
        ensureInitialized()
        // Keep the outer EtaBackupRepository journal intact. This function commits metadata
        // and ledger together, or neither; no file replacement can precede this Preferences edit.
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { key ->
                    key.name.startsWith(LINUX_BACKEND_PREFIX) ||
                        key.name.startsWith(SELECTED_MODEL_BY_PROVIDER_PREFIX)
                }
                .forEach { key -> prefs.remove(key) }
            prefs.putOrRemove(SELECTED_PROVIDER_ID, snapshot.selectedProviderId)
            prefs.putOrRemove(SELECTED_MODEL_ID, snapshot.selectedModelId)
            prefs[MEMORY_ENABLED] = snapshot.memoryEnabled
            prefs[FILE_LOGGING_ENABLED] = snapshot.fileLoggingEnabled
            prefs[ERROR_RECONNECT_POLICY] =
                ErrorReconnectPolicy.fromStoredSettings(
                    snapshot.errorReconnectPolicy, snapshot.errorReconnectPolicyVersion,
                ).persistedValue
            prefs[ERROR_RECONNECT_POLICY_VERSION] = ErrorReconnectPolicy.STORAGE_VERSION
            prefs.putOrRemove(LINUX_DISTRIBUTION, snapshot.linuxDistribution)
            prefs.putAppearance(snapshot.appearance.normalized())
            snapshot.linuxBackends.forEach { (distribution, backend) ->
                if (distribution.isNotBlank() && backend.isNotBlank()) {
                    prefs[stringPreferencesKey("$LINUX_BACKEND_PREFIX$distribution")] = backend
                }
            }
            snapshot.selectedModelByProvider.forEach { (providerId, modelId) ->
                if (providerId.isNotBlank() && modelId.isNotBlank()) {
                    prefs[selectedModelByProviderKey(providerId)] = modelId
                }
            }
            snapshot.modelUsageJson?.let { usageLedger.replaceIn(prefs, it) }
            snapshot.webToolsJson?.let { raw ->
                // 坏数据不落盘：解析不了就当作「备份没带这一项」，保留本机配置。
                val usable = raw.isBlank() ||
                    runCatching { webToolsJson.decodeFromString<WebToolsSettings>(raw) }.isSuccess
                if (usable) prefs.putOrRemove(WEB_TOOLS_SETTINGS, raw.takeIf { it.isNotBlank() })
            }
            snapshot.retiredHeatmapJson?.let { raw ->
                prefs.putOrRemove(RETIRED_HEATMAP_JSON, raw.takeIf { it.isNotBlank() })
            }
            snapshot.retiredInputTokens?.let { value ->
                if (value > 0L) prefs[RETIRED_INPUT_TOKENS] = value else prefs.remove(RETIRED_INPUT_TOKENS)
            }
            snapshot.retiredOutputTokens?.let { value ->
                if (value > 0L) prefs[RETIRED_OUTPUT_TOKENS] = value else prefs.remove(RETIRED_OUTPUT_TOKENS)
            }
            snapshot.retiredCachedTokens?.let { value ->
                if (value > 0L) prefs[RETIRED_CACHED_TOKENS] = value else prefs.remove(RETIRED_CACHED_TOKENS)
            }
            snapshot.retiredConversations?.let { value ->
                if (value > 0) prefs[RETIRED_CONVERSATIONS] = value else prefs.remove(RETIRED_CONVERSATIONS)
            }
            snapshot.retiredMessages?.let { value ->
                if (value > 0) prefs[RETIRED_MESSAGES] = value else prefs.remove(RETIRED_MESSAGES)
            }
        }
    }

    suspend fun setAppearanceSettings(settings: AppearanceSettings) {
        updateSettings { it.copy(appearance = settings.normalized()) }
    }

    suspend fun updateAppearanceSettings(transform: (AppearanceSettings) -> AppearanceSettings) {
        updateSettings { settings ->
            settings.copy(appearance = transform(settings.appearance).normalized())
        }
    }

    suspend fun launchCount(): Int {
        ensureInitialized()
        return dataStore.data
            .map { prefs -> prefs[APP_LAUNCH_COUNT] ?: 0 }
            .first()
    }

    suspend fun incrementLaunchCount() {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs[APP_LAUNCH_COUNT] = (prefs[APP_LAUNCH_COUNT] ?: 0) + 1
        }
    }

    suspend fun updateDismissedVersion(): String {
        ensureInitialized()
        return dataStore.data
            .map { prefs -> prefs[UPDATE_DISMISSED_VERSION].orEmpty() }
            .first()
    }

    suspend fun setUpdateDismissedVersion(version: String) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs.putOrRemove(UPDATE_DISMISSED_VERSION, version.trim().takeIf { it.isNotEmpty() })
        }
    }

    suspend fun updateLastCheckAt(): Long {
        ensureInitialized()
        return dataStore.data
            .map { prefs -> prefs[UPDATE_LAST_CHECK_AT] ?: 0L }
            .first()
    }

    suspend fun setUpdateLastCheckAt(epochMillis: Long) {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs[UPDATE_LAST_CHECK_AT] = epochMillis
        }
    }

    suspend fun retiredUsage(): RetiredUsage {
        ensureInitialized()
        // Storage/migration failure is not a successfully loaded zero-statistics snapshot.
        return dataStore.data
            .map { prefs ->
                RetiredUsage(
                    inputTokens = prefs[RETIRED_INPUT_TOKENS] ?: 0L,
                    outputTokens = prefs[RETIRED_OUTPUT_TOKENS] ?: 0L,
                    cachedTokens = prefs[RETIRED_CACHED_TOKENS] ?: 0L,
                    conversations = prefs[RETIRED_CONVERSATIONS] ?: 0,
                    messages = prefs[RETIRED_MESSAGES] ?: 0,
                    heatmap = decodeHeatmap(prefs[RETIRED_HEATMAP_JSON]),
                )
            }
            .first()
    }

    suspend fun addRetiredUsage(
        inputTokens: Long = 0L,
        outputTokens: Long = 0L,
        cachedTokens: Long = 0L,
        conversations: Int = 0,
        messages: Int = 0,
        heatmap: Map<LocalDate, Int> = emptyMap(),
    ) {
        if (
            inputTokens <= 0L &&
            outputTokens <= 0L &&
            cachedTokens <= 0L &&
            conversations <= 0 &&
            messages <= 0 &&
            heatmap.isEmpty()
        ) {
            return
        }
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs[RETIRED_INPUT_TOKENS] =
                (prefs[RETIRED_INPUT_TOKENS] ?: 0L) + inputTokens.coerceAtLeast(0L)
            prefs[RETIRED_OUTPUT_TOKENS] =
                (prefs[RETIRED_OUTPUT_TOKENS] ?: 0L) + outputTokens.coerceAtLeast(0L)
            prefs[RETIRED_CACHED_TOKENS] =
                (prefs[RETIRED_CACHED_TOKENS] ?: 0L) + cachedTokens.coerceAtLeast(0L)
            prefs[RETIRED_CONVERSATIONS] =
                (prefs[RETIRED_CONVERSATIONS] ?: 0) + conversations.coerceAtLeast(0)
            prefs[RETIRED_MESSAGES] =
                (prefs[RETIRED_MESSAGES] ?: 0) + messages.coerceAtLeast(0)
            if (heatmap.isNotEmpty()) {
                val merged = decodeHeatmap(prefs[RETIRED_HEATMAP_JSON]).toMutableMap()
                heatmap.forEach { (day, count) ->
                    if (count > 0) merged[day] = (merged[day] ?: 0) + count
                }
                prefs[RETIRED_HEATMAP_JSON] = encodeHeatmap(merged)
            }
        }
    }

    suspend fun clearRetiredUsage() {
        ensureInitialized()
        dataStore.edit { prefs ->
            prefs.remove(RETIRED_INPUT_TOKENS)
            prefs.remove(RETIRED_OUTPUT_TOKENS)
            prefs.remove(RETIRED_CACHED_TOKENS)
            prefs.remove(RETIRED_CONVERSATIONS)
            prefs.remove(RETIRED_MESSAGES)
            prefs.remove(RETIRED_HEATMAP_JSON)
        }
    }

    fun modelUsageFlow(): Flow<String> {
        ensureInitialized()
        return usageLedger.rawFlow().distinctUntilChanged()
    }

    fun conversationUsageFlow(id: String?): Flow<ConversationUsageTotals?> {
        ensureInitialized()
        return usageLedger.conversationFlow(id)
    }

    suspend fun modelUsageJson(): String {
        ensureInitialized()
        return usageLedger.snapshot()
    }

    suspend fun recordModelUsage(delta: ModelUsageDelta) {
        ensureInitialized()
        usageLedger.record(delta)
    }

    suspend fun flushModelUsage() {
        ensureInitialized()
        // Compatibility fence only: each record already returned AFTER its atomic commit.
        usageLedger.snapshot()
    }

    suspend fun addModelUsage(deltaJson: String) {
        if (deltaJson.isBlank()) return
        ensureInitialized()
        usageLedger.replace(deltaJson)
    }

    suspend fun updateModelUsage(transform: (String) -> String) {
        ensureInitialized()
        usageLedger.update(transform)
    }

    /**
     * There is no caller mutex here: entry wait is DataStore's edit-entry wall time.
     * commitTail is transform exit to edit return/throw (queueing, dispatch and persistence),
     * NOT pure disk time. The thread-local attribution surrounds synchronous slices only.
     */
    private suspend fun diagnosticEdit(
        entryStage: String,
        transformStage: String,
        tailStage: String,
        transform: (MutablePreferences) -> Unit,
    ) {
        if (!StreamPerformanceDiagnostics.enabled) {
            dataStore.edit { prefs -> transform(prefs) }
            return
        }
        val attribution = StreamPerformanceDiagnostics.captureAttribution()
        val started = System.nanoTime()
        var transformExited: Long? = null
        try {
            dataStore.edit { prefs ->
                StreamPerformanceDiagnostics.withAttribution(attribution) {
                    StreamPerformanceDiagnostics.record(entryStage, System.nanoTime() - started)
                    try {
                        StreamPerformanceDiagnostics.measure(transformStage) { transform(prefs) }
                    } finally {
                        transformExited = System.nanoTime()
                    }
                }
            }
        } finally {
            val exited = transformExited
            if (exited != null) {
                val elapsed = System.nanoTime() - exited
                StreamPerformanceDiagnostics.withAttribution(attribution) {
                    StreamPerformanceDiagnostics.record(tailStage, elapsed)
                }
            }
        }
    }

    private fun decodeHeatmap(raw: String?): Map<LocalDate, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
                    val count = json.optInt(key, 0)
                    if (count > 0) put(day, count)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeHeatmap(days: Map<LocalDate, Int>): String {
        val json = JSONObject()
        days.forEach { (day, count) ->
            if (count > 0) json.put(day.toString(), count)
        }
        return json.toString()
    }

    private fun ensureInitialized() {
        check(::dataStore.isInitialized) {
            "SettingsDataStore.init(context) must be called in Application.onCreate()"
        }
    }

    private fun selectedModelByProviderKey(providerId: String): Preferences.Key<String> =
        stringPreferencesKey("$SELECTED_MODEL_BY_PROVIDER_PREFIX$providerId")

    private fun stringMap(prefs: Preferences, prefix: String): Map<String, String> =
        prefs.asMap().mapNotNull { (key, value) ->
            val name = key.name
            if (name.startsWith(prefix) && value is String && value.isNotBlank()) {
                name.removePrefix(prefix) to value
            } else {
                null
            }
        }.toMap()

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }

    private fun Preferences.toSettings(): Settings = Settings(
        selectedProviderId = this[SELECTED_PROVIDER_ID],
        selectedModelId = this[SELECTED_MODEL_ID],
        memoryEnabled = this[MEMORY_ENABLED] ?: true,
        fileLoggingEnabled = this[FILE_LOGGING_ENABLED] ?: true,
        errorReconnectPolicy = ErrorReconnectPolicy.fromStoredSettings(
            this[ERROR_RECONNECT_POLICY], this[ERROR_RECONNECT_POLICY_VERSION],
        ),
        appearance = AppearanceSettings(
            themeMode = AppearanceThemeMode.fromPersistedValue(this[APPEARANCE_THEME_MODE]),
            monetEnabled = true,
            paletteStyle = AppearancePaletteStyle.fromPersistedValue(this[APPEARANCE_PALETTE_STYLE]),
            accentColor = AppearanceAccentColor.fromPersistedValue(this[APPEARANCE_ACCENT_COLOR]),
            pureBlackEnabled = this[APPEARANCE_PURE_BLACK_ENABLED] ?: false,
            blurEnabled = this[APPEARANCE_BLUR_ENABLED] ?: true,
            topBarBlurStyle = AppearanceTopBarBlurStyle.fromPersistedValue(
                this[APPEARANCE_TOP_BAR_BLUR_STYLE],
            ),
            predictiveBackEnabled = this[APPEARANCE_PREDICTIVE_BACK_ENABLED] ?: false,
            interfaceScale = this[APPEARANCE_INTERFACE_SCALE] ?: 1f,
            morphLoadingIndicator = this[APPEARANCE_MORPH_LOADING_INDICATOR] ?: true,
            morphLoadingBeforeResponseOnly = this[APPEARANCE_MORPH_LOADING_BEFORE_RESPONSE] ?: false,
            messageTimestampsEnabled = this[APPEARANCE_MESSAGE_TIMESTAMPS_ENABLED] ?: false,
            answerFoldLevel = AnswerFoldLevel.fromPersistedValue(this[APPEARANCE_ANSWER_FOLD_LEVEL]),
            iconLightColor = this[APPEARANCE_ICON_LIGHT] ?: AppearanceSettings.DEFAULT_ICON_LIGHT,
            iconDarkColor = this[APPEARANCE_ICON_DARK] ?: AppearanceSettings.DEFAULT_ICON_DARK,
        ).normalized(),
    )

    private fun MutablePreferences.putAppearance(settings: AppearanceSettings) {
        this[APPEARANCE_THEME_MODE] = settings.themeMode.persistedValue
        this[APPEARANCE_MONET_ENABLED] = settings.monetEnabled
        this[APPEARANCE_PALETTE_STYLE] = settings.paletteStyle.persistedValue
        this[APPEARANCE_ACCENT_COLOR] = settings.accentColor.persistedValue
        this[APPEARANCE_PURE_BLACK_ENABLED] = settings.pureBlackEnabled
        this[APPEARANCE_BLUR_ENABLED] = settings.blurEnabled
        this[APPEARANCE_TOP_BAR_BLUR_STYLE] = settings.topBarBlurStyle.persistedValue
        this[APPEARANCE_PREDICTIVE_BACK_ENABLED] = settings.predictiveBackEnabled
        this[APPEARANCE_INTERFACE_SCALE] = settings.interfaceScale
        this[APPEARANCE_MORPH_LOADING_INDICATOR] = settings.morphLoadingIndicator
        this[APPEARANCE_MORPH_LOADING_BEFORE_RESPONSE] = settings.morphLoadingBeforeResponseOnly
        this[APPEARANCE_MESSAGE_TIMESTAMPS_ENABLED] = settings.messageTimestampsEnabled
        this[APPEARANCE_ANSWER_FOLD_LEVEL] = settings.answerFoldLevel.persistedValue
        this[APPEARANCE_ICON_LIGHT] = settings.iconLightColor
        this[APPEARANCE_ICON_DARK] = settings.iconDarkColor
    }
}

@kotlinx.serialization.Serializable
internal data class EtaSettingsBackup(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val fileLoggingEnabled: Boolean = true,
    val linuxDistribution: String? = null,
    val linuxBackends: Map<String, String> = emptyMap(),
    val selectedModelByProvider: Map<String, String> = emptyMap(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    // Missing in an older/partial backup means preserve; explicit "" still means reset.
    val modelUsageJson: String? = null,
    /**
     * 网页工具配置（搜索 / 抓取后端、自定义 endpoint），原始 JSON。
     *
     * 只搬配置，不搬凭据：API Key 存在独立的 `eta_web_tools_secrets` 且由 KeyStore 加密，
     * 密钥不可跨设备迁移，换机后需要用户重填（UI 已有 `web_tools_credentials_lost` 提示）。
     * null 表示旧备份没带这一项，保留本机配置。
     */
    val webToolsJson: String? = null,
    // Missing retired fields also preserve; explicit zero/empty is still an exact restore.
    val retiredInputTokens: Long? = null,
    val retiredOutputTokens: Long? = null,
    val retiredCachedTokens: Long? = null,
    val retiredConversations: Int? = null,
    val retiredMessages: Int? = null,
    val retiredHeatmapJson: String? = null,
    // An old backup has no choice version; only its legacy off/missing default is upgraded.
    val errorReconnectPolicy: String? = null,
    val errorReconnectPolicyVersion: Int? = null,
)

internal data class RetiredUsage(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val conversations: Int = 0,
    val messages: Int = 0,
    val heatmap: Map<LocalDate, Int> = emptyMap(),
)
