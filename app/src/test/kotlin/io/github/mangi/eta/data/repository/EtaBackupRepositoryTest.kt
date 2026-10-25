package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentModelDefaults
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentPresetCatalog
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.withApiKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class EtaBackupRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    private inline fun <T> withRemotePreferences(block: (SharedPreferences) -> T): T {
        val field = Prefs::class.java.getDeclaredField("remote").apply { isAccessible = true }
        val previous = field.get(Prefs)
        val remote = context.getSharedPreferences("backup-remote-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        Prefs.attachRemote(remote)
        try {
            return block(remote)
        } finally {
            field.set(Prefs, previous)
        }
    }

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        java.io.File(context.filesDir, "backup-restore").deleteRecursively()
        io.github.mangi.eta.agent.runtime.AgentExecutionService.endBackupMaintenance()
        io.github.mangi.eta.config.Prefs.initLocal(context)
        val prefs = requireNotNull(io.github.mangi.eta.config.Prefs.localAgentPreferences())
        check(prefs.edit().clear().commit())
        check(io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences(prefs).resetLegacyConfigurationOnce())
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        AssistantRepository.init(context)
        McpServerRepository.init(context)
    }

    @Test
    fun failureAfterSettingsCommitRollsBackRichUsageImmediatelyAndThroughStartupJournal() = runBlocking {
        val preferences = io.github.mangi.eta.data.datastore.FaultPreferencesStore()
        io.github.mangi.eta.data.datastore.withSettingsStore(preferences) {
            for (failureCount in listOf(1, 2)) {
                val incomingRaw = applyModelUsageDelta(null, ModelUsageDelta(
                    "ledger-provider", "Ledger", "model", "Model", 19, 3, 7, 2,
                    conversationId = "ledger-owner", requestId = "incoming", atMillis = 1000,
                ))
                SettingsDataStore.restoreBackup(io.github.mangi.eta.data.datastore.EtaSettingsBackup(
                    memoryEnabled = false, modelUsageJson = incomingRaw,
                    retiredInputTokens = 21, retiredOutputTokens = 4, retiredCachedTokens = 8,
                    retiredConversations = 2, retiredMessages = 3, retiredHeatmapJson = "{\"2026-01-01\":2}",
                ))
                val incoming = SettingsDataStore.backupSnapshot()
                val archive = ByteArrayOutputStream()
                EtaBackupRepository.export(context, archive)
                val oldRaw = applyModelUsageDelta(null, ModelUsageDelta(
                    "ledger-provider", "Ledger", "model", "Model", 9007199254740993L, 13, 17, 5,
                    conversationId = "old-owner", requestId = "old", atMillis = 2000,
                ))
                SettingsDataStore.restoreBackup(incoming.copy(memoryEnabled = true, modelUsageJson = oldRaw,
                    retiredInputTokens = 123, retiredOutputTokens = 456, retiredCachedTokens = 78,
                    retiredConversations = 5, retiredMessages = 9, retiredHeatmapJson = "{\"2026-02-02\":5}"))
                val old = SettingsDataStore.backupSnapshot()
                val receipt = preferences.committed.value[io.github.mangi.eta.data.datastore.USAGE_ROLLBACK_RECEIPT]
                val field = Prefs::class.java.getDeclaredField("localAgent").apply { isAccessible = true }
                val real = requireNotNull(Prefs.localAgentPreferences())
                var remainingFailures = failureCount
                var commits = 0
                val wrapped = object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = real.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun clear(): SharedPreferences.Editor { delegate.clear(); return this }
                            override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                delegate.putString(key, value); return this
                            }
                            override fun commit(): Boolean {
                                commits++
                                // This failure is AFTER the actual Settings API committed, not in
                                // its transform. Both import and exact undo go through the real API.
                                val observed = runBlocking { SettingsDataStore.backupSnapshot() }
                                assertEquals(if (commits == 1) incoming else old, observed)
                                delegate.commit()
                                if (commits == 1 && failureCount == 2) preferences.failures = 1
                                if (remainingFailures > 0) { remainingFailures--; return false }
                                return true
                            }
                        }
                    }
                }
                field.set(Prefs, wrapped)
                try {
                    assertTrue(runCatching { EtaBackupRepository.import(context,
                        ByteArrayInputStream(archive.toByteArray())) }.isFailure)
                    assertEquals(if (failureCount == 2) incoming else old, SettingsDataStore.backupSnapshot())
                    assertEquals(receipt, preferences.committed.value[io.github.mangi.eta.data.datastore.USAGE_ROLLBACK_RECEIPT])
                    val operation = File(context.filesDir, "backup-restore")
                    if (failureCount == 2) {
                        // Injected undo Preferences commit failure leaves incoming statistics, a
                        // durable rich undo snapshot and admission fence for startup recovery.
                        assertTrue(operation.exists())
                        assertTrue(io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance)
                        remainingFailures = 0
                        EtaBackupRepository.recoverInterruptedImport(context, maintenanceAlreadyHeld = true)
                        io.github.mangi.eta.agent.runtime.AgentExecutionService.endBackupMaintenance()
                        assertEquals(old, SettingsDataStore.backupSnapshot())
                    }
                    assertEquals(9007199254740993L, SettingsDataStore.conversationUsageFlow("old-owner").first()!!.input)
                    assertFalse(operation.exists())
                    assertFalse(io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance)
                } finally {
                    field.set(Prefs, real)
                    File(context.filesDir, "backup-restore").deleteRecursively()
                    io.github.mangi.eta.agent.runtime.AgentExecutionService.endBackupMaintenance()
                }
            }
        }
    }

    @Test
    fun exportAndImportRestoresProvidersConversationsAndMemory() = runBlocking {
        val provider = ProviderRepository.addProvider(
            io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting(
                id = "backup-provider",
                name = "Backup",
                baseUrl = "https://api.example.com/v1",
                models = listOf(
                    io.github.mangi.eta.data.model.Model(
                        id = "backup-model",
                        modelId = "model-1",
                        displayName = "Model 1",
                        source = ModelSource.CATALOG,
                    ),
                ),
            ),
        ).withApiKey("sk-backup-test")
        ProviderRepository.updateProvider(provider)
        SettingsDataStore.setSelection(provider.id, provider.models.first().id)
        AgentMemoryRepository.replaceAll("# 核心记忆\n喜欢 Kotlin")

        val conversation = ConversationEntity(
            id = "conversation-backup",
            title = "备份会话",
            thinkingEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
            providerId = provider.id,
            modelId = provider.models.first().id,
        )
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(conversation),
            messages = listOf(
                ConversationMessageEntity(
                    id = "message-backup",
                    conversationId = conversation.id,
                    sortIndex = 0,
                    type = "user",
                    content = "保留这条消息",
                ),
            ),
            contextCheckpoints = listOf(
                ConversationContextCheckpointEntity(
                    conversationId = conversation.id,
                    historyJson = "[]",
                ),
            ),
            state = ConversationStateEntity(selectedConversationId = conversation.id),
        )

        val output = ByteArrayOutputStream()
        // 这条用例断言的是「带密钥的完整备份能原样还原」，所以显式要求把 API Key 写进包：
        // 默认导出会脱敏（见 exportOmitsApiKeysByDefault）。
        val exported = EtaBackupRepository.export(
            context, output, EtaBackupExportOptions(includeApiKeys = true),
        )
        assertEquals(1, exported.conversationCount)
        assertTrue(exported.providerCount > 0)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)

        ProviderRepository.updateProvider(provider.withApiKey("changed"))
        AgentMemoryRepository.replaceAll("changed")
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = emptyList(),
            messages = emptyList(),
            contextCheckpoints = emptyList(),
            state = null,
        )

        val imported = EtaBackupRepository.import(
            context,
            ByteArrayInputStream(output.toByteArray()),
        )
        assertEquals(1, imported.conversationCount)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)
        assertEquals(
            "保留这条消息",
            EtaDatabase.get(context).conversationDao().messages().single().content,
        )
        val restoredSettings = SettingsDataStore.settings()
        assertEquals(provider.id, restoredSettings.selectedProviderId)
        assertEquals(provider.models.first().id, restoredSettings.selectedModelId)
        val restoredConversation = EtaDatabase.get(context).conversationDao().conversationEntities().single()
        assertEquals(provider.id, restoredConversation.providerId)
        assertEquals(provider.models.first().id, restoredConversation.modelId)
        assertEquals("sk-backup-test", ProviderRepository.providerById(provider.id)?.apiKey)
        assertEquals(ModelSource.CATALOG, ProviderRepository.providerById(provider.id)?.models?.first()?.source)
    }

    /**
     * 默认导出必须脱敏：归档本身不加密，包一离开设备就等于交出全部厂商的账单权限。
     *
     * 除了 `apiKey` 字段，中转站常把密钥写在自定义请求头 / 请求体里，这两处也要清。
     * 同时反向确认 `max_tokens` 这类常规参数没被误伤——误伤会让导入后请求直接失败。
     */
    @Test
    fun exportOmitsApiKeysByDefault() = runBlocking {
        val provider = ProviderRepository.addProvider(
            io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting(
                id = "redacted-provider",
                name = "中转站",
                baseUrl = "https://api.example.com/v1",
                customHeaders = listOf(
                    io.github.mangi.eta.data.model.CustomHeader("api-key", "sk-in-header"),
                    io.github.mangi.eta.data.model.CustomHeader("x-trace-id", "keep-me"),
                ),
                customBody = listOf(
                    io.github.mangi.eta.data.model.CustomBody("api_key", kotlinx.serialization.json.JsonPrimitive("sk-in-body")),
                    io.github.mangi.eta.data.model.CustomBody("max_tokens", kotlinx.serialization.json.JsonPrimitive(4096)),
                ),
            ),
        ).withApiKey("sk-plain-key")

        val output = ByteArrayOutputStream()
        val exported = EtaBackupRepository.export(context, output)
        assertEquals(1, exported.providerCount)

        val imported = EtaBackupRepository.import(
            context,
            ByteArrayInputStream(output.toByteArray()),
        )
        // 用户需要知道少了什么，否则会以为导入不完整。
        assertEquals(1, imported.providersNeedingKey)

        val restored = requireNotNull(ProviderRepository.providerById(provider.id))
        assertTrue(restored.apiKey.isEmpty())
        assertEquals("", restored.customHeaders.single { it.name == "api-key" }.value)
        assertEquals("keep-me", restored.customHeaders.single { it.name == "x-trace-id" }.value)
        assertEquals(
            kotlinx.serialization.json.JsonNull,
            restored.customBody.single { it.key == "api_key" }.value,
        )
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive(4096),
            restored.customBody.single { it.key == "max_tokens" }.value,
        )
    }

    @Test
    fun fullBackupRestoresPresetDirectoryPayloadAndRefreshesObservedRevisions() = runBlocking {
        io.github.mangi.eta.config.Prefs.initLocal(context)
        val prefs = requireNotNull(io.github.mangi.eta.config.Prefs.localAgentPreferences())
        check(prefs.edit().clear().commit())
        val store = io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences(prefs)
        check(store.resetLegacyConfigurationOnce())
        val preset = store.addPreset("备份预设")
        val presetOwner = io.github.mangi.eta.agent.delegation.SubAgentConfigKey.Preset(preset.id)
        val owner = io.github.mangi.eta.agent.delegation.SubAgentConfigKey.Conversation("backup-owner")
        store.update(presetOwner) { it.copy(enabled = false, profiles = listOf(
            io.github.mangi.eta.agent.delegation.SubAgentProfile(
                id = "remembered-model", name = "模型记忆", providerId = "provider", modelId = "selection",
                reasoningByModel = mapOf("provider\u0000selection" to io.github.mangi.eta.data.model.ReasoningEffort.HIGH),
            ),
        )) }
        store.applyPreset(owner, preset.id)
        val original = store.snapshot(owner)
        val directory = store.presets()
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        store.removePreset(preset.id)
        store.update(owner) { it.copy(profiles = emptyList(), enabled = true) }
        val ownerRevision = store.revision(owner).value
        val presetRevision = store.revision(presetOwner).value
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertEquals(original, store.snapshot(owner))
        assertEquals(directory, store.presets())
        assertTrue(store.revision(owner).value > ownerRevision)
        assertTrue(store.revision(presetOwner).value > presetRevision)
        store.validateRestoredPreferences()
    }

    @Test
    fun catalogOnlyCorruptBackupIsRejectedBeforeClearingCurrentPreferences() = runBlocking {
        io.github.mangi.eta.config.Prefs.initLocal(context)
        val prefs = requireNotNull(io.github.mangi.eta.config.Prefs.localAgentPreferences())
        check(prefs.edit().clear().putString("unrelated-current", "keep").commit())
        val store = io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences(prefs)
        val preset = store.addPreset("保留")
        val before = prefs.all.toMap()
        val revision = store.revision.value
        val raw = org.json.JSONObject().put("format", EtaBackupDocument.FORMAT).put("schemaVersion", 4)
            .put("exportedAt", 0).put("agentPreferences", org.json.JSONObject()
                .put(io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences.RESET_MARKER_KEY, "s:1")
                .put(io.github.mangi.eta.agent.delegation.SubAgentPresetCatalog.KEY, "s:{invalid")).toString()
        assertTrue(runCatching { EtaBackupRepository.import(context, ByteArrayInputStream(raw.toByteArray())) }.isFailure)
        assertEquals(before, prefs.all)
        assertEquals(revision, store.revision.value)
        assertTrue(store.presetExists(preset.id))
    }

    @Test
    fun oldFullBackupRestoresChatWhileKeepingCurrentGenerationAndMarker() = runBlocking {
        val prefs = requireNotNull(io.github.mangi.eta.config.Prefs.localAgentPreferences())
        val store = io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences(prefs)
        val preset = store.addPreset("升级后预设")
        val owner = io.github.mangi.eta.agent.delegation.SubAgentConfigKey.Conversation("new-owner")
        store.applyPreset(owner, preset.id)
        val config = store.snapshot(owner)
        val directory = store.presets()
        val draft = store.createDraft(owner)
        store.bindDraft(draft, SubAgentConfigKey.Conversation("bound-current"))
        val profile = SubAgentProfile(id = "confirmed-current", name = "当前模型默认值",
            providerId = "current-provider", modelId = "current-selection")
        val model = SubAgentParallelModel(profile.providerId, "current-api-model")
        assertTrue(store.updateConfirmedProfile(draft, profile.id, model) {
            it.copy(profiles = listOf(profile), parallelLimits = mapOf(model to 3))
        } is ConversationSubAgentPreferences.WriteResult.Saved)
        prefs.edit().putString(ConversationSubAgentPreferences.UI_DRAFT_KEY, draft.value).commit()
        val current = Prefs.exportAgentPreferences()
        val pointer = prefs.getString(ConversationSubAgentPreferences.UI_DRAFT_KEY, null)
        val draftConfig = store.snapshot(draft)
        val defaults = prefs.getString(SubAgentModelDefaults.KEY, null)
        val revision = store.revision(owner).value
        val document = EtaBackupDocument(
            exportedAt = 0,
            providers = listOf(EtaBackupProvider(io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting(
                id = "old-backup-provider", name = "旧备份供应商", baseUrl = "https://api.example.com/v1",
            ).withApiKey("restored-backup-key").toEntity())),
            conversations = listOf(ConversationEntity(id = "old-chat", title = "旧聊天", thinkingEnabled = false, createdAt = 1, updatedAt = 2)),
            messages = listOf(ConversationMessageEntity(
                id = "old-message", conversationId = "old-chat", sortIndex = 0, type = "user", content = "保留聊天",
            )),
            agentPreferences = mapOf(
                "unrelated-setting" to "s:restored",
                io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences.SEED_KEY to "s:{obsolete",
                io.github.mangi.eta.agent.delegation.SubAgentPresetCatalog.KEY to "s:{obsolete",
                io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences.UI_DRAFT_KEY to "s:obsolete-draft",
                "agent_child_profiles_v1" to "s:{obsolete",
                "agent_child_0_image_resolution" to "s:obsolete",
                "agent_child_0_enabled" to "b:true",
            ),
        )
        val raw = kotlinx.serialization.json.Json.encodeToString(EtaBackupDocument.serializer(), document)
        withRemotePreferences { remote ->
            val remoteBefore = mapOf(
                SubAgentPreferences.PROFILES_KEY to "obsolete-remote-profiles",
                ConversationSubAgentPreferences.UI_DRAFT_KEY to "obsolete-remote-draft",
                ConversationSubAgentPreferences.SEED_KEY to "obsolete-remote-seed",
            )
            val edit = remote.edit()
            remoteBefore.forEach { (key, value) -> edit.putString(key, value) }
            check(edit.commit())
            EtaBackupRepository.import(context, ByteArrayInputStream(raw.toByteArray()))
            current.forEach { (key, value) -> assertEquals("Current preference lost: $key", value, Prefs.exportAgentPreferences()[key]) }
            assertEquals(current.getValue(SubAgentPreferences.PROFILES_KEY).substring(2), Prefs.getString(SubAgentPreferences.PROFILES_KEY))
            assertEquals(pointer, Prefs.getString(ConversationSubAgentPreferences.UI_DRAFT_KEY))
            assertEquals(current.getValue(ConversationSubAgentPreferences.SEED_KEY).substring(2), Prefs.getString(ConversationSubAgentPreferences.SEED_KEY))
            assertEquals(remoteBefore, remote.all)
        }
        assertEquals("保留聊天", EtaDatabase.get(context).conversationDao().messages().single().content)
        assertEquals("restored-backup-key", ProviderRepository.providerById("old-backup-provider")?.apiKey)
        assertEquals("旧备份供应商", ProviderRepository.providerById("old-backup-provider")?.name)
        assertEquals(draftConfig, store.snapshot(draft))
        assertEquals(defaults, prefs.getString(SubAgentModelDefaults.KEY, null))
        assertFalse(prefs.contains("agent_child_0_image_resolution"))
        assertFalse(prefs.contains("agent_child_0_enabled"))
        assertEquals("restored", prefs.getString("unrelated-setting", null))
        assertEquals(config, store.snapshot(owner))
        assertEquals(directory, store.presets())
        assertEquals(pointer, prefs.getString(io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences.UI_DRAFT_KEY, null))
        assertTrue(store.isConfigurationResetComplete())
        assertTrue(store.revision(owner).value > revision)
        val after = prefs.all.toMap()
        check(store.resetLegacyConfigurationOnce())
        assertEquals(after, prefs.all)
    }

    @Test
    fun markedBackupWithoutPointerBlocksRemoteProfilesAndDraftWithoutOverlayingLiveConfig() = runBlocking {
        val prefs = requireNotNull(Prefs.localAgentPreferences())
        val store = ConversationSubAgentPreferences(prefs)
        val livePreset = store.addPreset("不应覆叠的本地预设")
        val liveDraft = store.createDraft()
        val live = SubAgentConfigKey.Conversation("live-before-restore")
        store.applyPreset(live, livePreset.id)
        prefs.edit().putString(ConversationSubAgentPreferences.UI_DRAFT_KEY, liveDraft.value).commit()
        withRemotePreferences { remote ->
            val obsoleteProfiles = org.json.JSONObject().put("version", 1).put("agents", org.json.JSONArray()
                .put(SubAgentProfile(id = "remote-old", name = "旧远端配置").toJson())).toString()
            check(remote.edit().putString(SubAgentPreferences.PROFILES_KEY, obsoleteProfiles)
                .putString(ConversationSubAgentPreferences.UI_DRAFT_KEY, "obsolete-remote-draft").commit())
            val remoteBefore = remote.all.toMap()
            val document = EtaBackupDocument(exportedAt = 0, agentPreferences = mapOf(
                ConversationSubAgentPreferences.RESET_MARKER_KEY to "s:1",
                SubAgentPreferences.PROFILES_KEY to "s:{obsolete legacy payload",
                "agent_child_0_enabled" to "b:true",
                "other-restored" to "i:2",
            ))
            val raw = kotlinx.serialization.json.Json.encodeToString(EtaBackupDocument.serializer(), document)
            EtaBackupRepository.import(context, ByteArrayInputStream(raw.toByteArray()))
            assertEquals(2, prefs.getInt("other-restored", 0))
            assertTrue(prefs.contains(SubAgentPreferences.PROFILES_KEY))
            assertEquals(0, org.json.JSONObject(Prefs.getString(SubAgentPreferences.PROFILES_KEY)).getJSONArray("agents").length())
            assertTrue(prefs.contains(ConversationSubAgentPreferences.UI_DRAFT_KEY))
            assertEquals("", Prefs.getString(ConversationSubAgentPreferences.UI_DRAFT_KEY))
            assertTrue(SubAgentPreferences.profiles().isEmpty())
            assertTrue(store.isConfigurationResetComplete())
            assertFalse(prefs.contains(SubAgentModelDefaults.KEY))
            assertFalse(prefs.contains(ConversationSubAgentPreferences.SEED_KEY))
            assertFalse(prefs.contains(SubAgentPresetCatalog.KEY))
            assertFalse(prefs.contains("agent_child_0_enabled"))
            assertFalse(BackupSubAgentConfig.hasOwner(prefs, live.value))
            assertNull(store.existingDraftOrNull(liveDraft))
            assertTrue(store.presets().isEmpty())
            assertFalse(store.snapshot(live).enabled)
            assertTrue(store.snapshot(live).profiles.isEmpty())
            assertEquals(remoteBefore, remote.all)
        }
    }

    @Test
    fun malformedMarkedModelDefaultsAreRejectedBeforeAnyWrite() = runBlocking {
        val prefs = requireNotNull(io.github.mangi.eta.config.Prefs.localAgentPreferences())
        val before = prefs.all.toMap()
        val document = EtaBackupDocument(exportedAt = 0, agentPreferences = mapOf(
            io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences.RESET_MARKER_KEY to "s:1",
            io.github.mangi.eta.agent.delegation.SubAgentModelDefaults.KEY to "s:{invalid",
        ))
        val raw = kotlinx.serialization.json.Json.encodeToString(EtaBackupDocument.serializer(), document)
        assertTrue(runCatching { EtaBackupRepository.import(context, ByteArrayInputStream(raw.toByteArray())) }.isFailure)
        assertEquals(before, prefs.all)
    }

    private val conversationJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    private fun conversationDocument(archive: String? = null, generation: String? = null) = EtaConversationExport(
        exportedAt = 1,
        conversation = ConversationEntity(id = "generation-source", title = "generation chat", thinkingEnabled = false, createdAt = 1, updatedAt = 2),
        messages = listOf(ConversationMessageEntity(id = "generation-message", conversationId = "generation-source",
            sortIndex = 0, type = "user", content = "chat still imports")),
        subAgentConfigJson = archive,
        subAgentConfigGeneration = generation,
    )

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun generationIsOptionalWhenDecodingEverySupportedConversationSchema() {
        assertEquals(3, EtaConversationExport.SCHEMA_VERSION)
        val descriptor = EtaConversationExport.serializer().descriptor
        val generationIndex = descriptor.getElementIndex("subAgentConfigGeneration")
        assertTrue(descriptor.isElementOptional(generationIndex))
        assertTrue(descriptor.getElementDescriptor(generationIndex).isNullable)
        for (schema in 1..3) {
            val document = conversationDocument().copy(schemaVersion = schema)
            val raw = org.json.JSONObject(conversationJson.encodeToString(EtaConversationExport.serializer(), document))
                .apply { remove("subAgentConfigGeneration") }.toString()
            val decoded = conversationJson.decodeFromString(EtaConversationExport.serializer(), raw)
            assertEquals(schema, decoded.schemaVersion)
            assertNull(decoded.subAgentConfigGeneration)
            assertEquals(document.conversation, decoded.conversation)
            assertEquals(document.messages, decoded.messages)
        }
    }

    @Test
    fun unmarkedSchemaThreeValidAndCorruptConfigsNeverReviveButChatImports() = runBlocking {
        val prefs = requireNotNull(io.github.mangi.eta.config.Prefs.localAgentPreferences())
        val store = ConversationSubAgentPreferences(prefs)
        val source = SubAgentConfigKey.Conversation("old-source-config")
        store.update(source) { it.copy(enabled = true,
            profiles = listOf(SubAgentProfile(id = "old-agent", name = "old", providerId = "provider", modelId = "model"))) }
        val original = store.snapshot(source)
        val valid = store.export(source)
        val dao = EtaDatabase.get(context).conversationDao()
        listOf(valid, "{invalid", org.json.JSONObject(valid).put("version", 987).toString()).forEach { archive ->
            val beforeIds = dao.conversationEntities().map { it.id }.toSet()
            val beforePrefs = prefs.all.toMap()
            val raw = org.json.JSONObject(conversationJson.encodeToString(EtaConversationExport.serializer(),
                conversationDocument(archive))).apply { remove("subAgentConfigGeneration") }.toString()
            val summary = EtaBackupRepository.import(context, ByteArrayInputStream(raw.toByteArray()))
            assertEquals(1, summary.conversationCount)
            val imported = dao.conversationEntities().single { it.id !in beforeIds }
            assertEquals("chat still imports", dao.messagesForConversation(imported.id).single().content)
            assertTrue(BackupSubAgentConfig.hasOwner(prefs, imported.id))
            val config = store.snapshot(SubAgentConfigKey.Conversation(imported.id))
            assertFalse(config.enabled)
            assertTrue(config.profiles.isEmpty())
            assertTrue(config.parallelLimits.isEmpty())
            assertTrue(config.legacyParallelLimits.isEmpty())
            assertNull(config.appliedPresetId)
            beforePrefs.forEach { (key, value) -> assertEquals(value, prefs.all[key]) }
            assertEquals(original, store.snapshot(source))
            assertTrue(store.isConfigurationResetComplete())
        }
    }

    @Test
    fun newConversationExportMarksGenerationAndImportPreservesConfiguration() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val sourceDocument = conversationDocument()
        dao.importAsNewConversation(sourceDocument.conversation, sourceDocument.messages, null)
        val store = ConversationSubAgentPreferences()
        val source = SubAgentConfigKey.Conversation(sourceDocument.conversation.id)
        store.update(source) { it.copy(enabled = true,
            profiles = listOf(SubAgentProfile(id = "new-agent", name = "new", providerId = "provider", modelId = "model"))) }
        val original = store.snapshot(source)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.exportConversation(context, source.value, output)
        val exported = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            assertEquals(EtaConversationExport.MANIFEST_NAME, zip.nextEntry.name)
            conversationJson.decodeFromString(EtaConversationExport.serializer(), zip.readBytes().toString(Charsets.UTF_8))
        }
        assertEquals("1", exported.subAgentConfigGeneration)
        assertEquals(3, exported.schemaVersion)
        assertEquals(store.export(source), exported.subAgentConfigJson)
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        val imported = dao.conversationEntities().single { it.id != source.value }
        assertEquals("chat still imports", dao.messagesForConversation(imported.id).single().content)
        assertEquals(original, store.snapshot(SubAgentConfigKey.Conversation(imported.id)))
        assertEquals(original, store.snapshot(source))
    }

    @Test
    fun invalidGenerationAndMarkedCorruptArchiveRejectBeforeChatFilesOrOwnerWrites() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val live = conversationDocument()
        dao.importAsNewConversation(live.conversation, live.messages, null)
        val prefs = requireNotNull(io.github.mangi.eta.config.Prefs.localAgentPreferences())
        val store = ConversationSubAgentPreferences(prefs)
        val fallback = BackupSubAgentConfig.archiveForImport(3, null, store)
        val imports = File(TerminalPrivateStorage.workspace(context.filesDir), "imports")
        val image = File(imports, "generation-${UUID.randomUUID()}/image.jpg")
            .apply { parentFile!!.mkdirs(); writeText("attachment must not be installed") }
        try {
            val withImage = conversationDocument().copy(messages = live.messages.map {
                it.copy(imagesJson = org.json.JSONArray().put(image.absolutePath).toString())
            })
            val prepared = ConversationArchiveMedia.prepare(context, withImage)
            val beforeConversations = dao.conversationEntities()
            val beforeMessages = dao.messages()
            val beforePrefs = prefs.all.toMap()
            val revision = store.revision.value
            fun attachmentFiles() = imports.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(imports).path to BackupDurability.digest(it) }
            val beforeFiles = attachmentFiles()
            val invalid = listOf(
                "" to null, "0" to "{invalid", "2" to fallback, "s:1" to fallback,
                "1" to "{invalid", "1" to org.json.JSONObject(fallback).put("version", 987).toString(),
            )
            invalid.forEach { (generation, archive) ->
                val document = prepared.document.copy(subAgentConfigGeneration = generation, subAgentConfigJson = archive)
                val output = ByteArrayOutputStream()
                ZipOutputStream(output).use { zip ->
                    val writer = BackupZipWriter(zip)
                    writer.text(EtaConversationExport.MANIFEST_NAME,
                        conversationJson.encodeToString(EtaConversationExport.serializer(), document))
                    document.attachments.forEach { attachment ->
                        writer.file(attachment.entry, prepared.files.getValue(attachment.entry), attachment.sha256)
                    }
                }
                val failure = runCatching {
                    EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
                }.exceptionOrNull()
                assertTrue(failure != null)
                if (generation != "1") assertEquals("不支持的会话子代理配置代际", failure?.message)
                assertEquals(beforeConversations, dao.conversationEntities())
                assertEquals(beforeMessages, dao.messages())
                assertEquals(beforePrefs, prefs.all)
                assertEquals(revision, store.revision.value)
                assertEquals(beforeFiles, attachmentFiles())
            }
        } finally {
            image.parentFile!!.deleteRecursively()
        }
    }

    @Test(expected = EtaBackupException::class)
    fun rejectsUnknownBackupFormatBeforeChangingData(): Unit = runBlocking {
        EtaBackupRepository.inspect(
            ByteArrayInputStream("{\"format\":\"other\",\"schemaVersion\":1,\"exportedAt\":0}".toByteArray())
        )
    }
}
