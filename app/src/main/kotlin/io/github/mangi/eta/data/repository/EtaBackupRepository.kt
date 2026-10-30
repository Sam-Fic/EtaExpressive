package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootSu
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.media.index.Float16VectorCodec
import io.github.mangi.eta.agent.media.index.MediaExtraction
import io.github.mangi.eta.agent.media.index.MediaSourceScanner
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.tool.AgentToolRecipeStore
import io.github.mangi.eta.agent.tool.ToolRecipeConstants
import io.github.mangi.eta.agent.tool.ToolRecipePaths
import io.github.mangi.eta.agent.terminal.AndroidBusyBox
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.EtaSettingsBackup
import io.github.mangi.eta.data.datastore.SettingsDataStore
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationFolderEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaIndexDao
import io.github.mangi.eta.data.db.MediaIndexEntity
import io.github.mangi.eta.data.db.MediaKind
import io.github.mangi.eta.data.db.McpServerEntity
import io.github.mangi.eta.data.db.ProviderEntity
import io.github.mangi.eta.data.db.ProviderModelEntity
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.db.SkillRegistryEntity
import io.github.mangi.eta.data.model.AssistantPrompt
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/**
 * Eta用户数据备份。schema 1 只有对话、提供商和记忆；schema 2 补上助手、技能、MCP、设置、附件和可选 Linux 环境；
 * schema 5 补上可选媒体语义索引（向量行不进本清单，写在独立条目 `media-index/records.jsonl`）；
 * schema 6 补上可选已下载模型（文件写在 `models/` 下，元数据在 [models]）；
 * schema 7 补上模型自进化成果「配方」（数据本就在 `filesDir/tool_recipes`，这里纳入清单元数据）；
 * schema 8 补上 [EtaBackupDocument.appDataIncluded]：允许只打包 Linux 环境 / 媒体索引 / 模型这类大件，
 * 导入时不再用空清单覆盖本机对话与设置（旧版本读到 schema 8 会直接拒绝，不会误清空数据）；
 * 同时把配方**文件**真正放进归档（`recipes/<name>.json`）——schema 7 只记了数量，配方从未离开过设备；
 * 并补上语音配置（随应用数据）与浏览器设置（独立开关）两份 prefs 快照，见 [BackupPrefsFiles]。
 * 聊天附件只打包用户发出的图片：助手回复里的图片是正文绝对路径直接渲染，不缓存也不进备份。
 */
@Serializable
internal data class EtaBackupDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val providers: List<EtaBackupProvider> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
    val conversationState: ConversationStateEntity? = null,
    val folders: List<ConversationFolderEntity> = emptyList(),
    val memoryMd: String = "",
    val assistantMemories: Map<String, String> = emptyMap(),
    val assistants: AssistantBackupSnapshot? = null,
    val assistantAvatars: Map<String, String> = emptyMap(),
    val skillRegistry: List<SkillRegistryEntity> = emptyList(),
    val skillFiles: Map<String, String> = emptyMap(),
    val mcpServers: List<McpServerEntity> = emptyList(),
    val mcpTokens: Map<String, String> = emptyMap(),
    val settings: EtaSettingsBackup? = null,
    val mediaIndex: EtaBackupMediaIndex? = null,
    val models: EtaModelBackupDocument? = null,
    /**
     * 归档里打包的配方（模型自建工具）文件数；文件本体写在 `recipes/<name>.json` 条目下。
     *
     * 配方只能通过本归档离开设备：应用已声明 `android:allowBackup="false"`，系统备份不会带走
     * `filesDir/tool_recipes`，分享包又是单批 32 份的受限通道。schema 7 只记数量不带文件，
     * schema 8 起连同文件一起打包。
     */
    val recipeCount: Int = 0,
    /**
     * 语音配置（豆包的 ASR / 克隆参数、离线语音包选型、音色 catalog 的 key id 与 project）。
     *
     * 值是 `<prefs 文件> → key → 编码值`（编码方式见 [BackupPrefsFiles]）。
     * 空表示备份没带这一项，导入时保留本机配置。
     */
    val voicePreferences: Map<String, Map<String, String>> = emptyMap(),
    /**
     * 浏览器设置（UA / 视口 / 空闲超时）。由「浏览器数据」勾选项单独控制，不随应用数据走。
     * 登录态在 WebView 自己的存储里，不在这里。
     */
    val browserPreferences: Map<String, Map<String, String>> = emptyMap(),
    val includeLinuxEnvironment: Boolean = false,
    val linuxWorkspaceIncluded: Boolean = false,
    /**
     * 归档是否包含应用数据（对话、提供商、助手、技能、MCP、设置、附件、记忆）。
     *
     * false 表示这是一份「只带大件」的备份（Linux 环境 / 媒体索引 / 模型）：
     * 导出时应用数据整块留空，导入时也只恢复这些大件，绝不拿空清单去覆盖本机数据。
     * 旧备份没有该字段，反序列化时取默认值 true，行为与以前一致。
     */
    val appDataIncluded: Boolean = true,
    /**
     * 归档是否带上了 API Key 明文（提供商的 `apiKey`、豆包的 ASR / 克隆 Key）。
     *
     * false 表示导出时做了脱敏：提供商与语音配置都在，只有密钥留空，导入后需要用户重填。
     * 默认 true 是为了对齐旧备份——schema 8 及以前一直是明文，读老包时不该误报「需要重填」。
     */
    val apiKeysIncluded: Boolean = true,
    val attachmentCount: Int = 0,
    val importedFileCount: Int = 0,
    val agentPreferences: Map<String, String> = emptyMap(),
    val messagesExternal: Boolean = false,
    val messageCount: Int = 0,
) {
    companion object {
        const val FORMAT = "eta-backup"
        const val SCHEMA_VERSION = 8
        const val MIN_SUPPORTED_SCHEMA = 1
        const val MANIFEST_NAME = "eta-backup.json"
    }
}

@Serializable
internal data class EtaBackupProvider(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity> = emptyList(),
)

/**
 * 媒体语义索引备份元数据：清单只记切片（模型 / 截断维度）与规模，
 * 向量行写在独立归档条目 [ENTRY]，缩略图写在 [THUMBS_PREFIX]。
 */
@Serializable
internal data class EtaBackupMediaIndex(
    val modelId: String,
    val vectorDim: Int,
    val count: Int,
    val payloadEntry: String = ENTRY,
) {
    companion object {
        const val ENTRY = "media-index/records.jsonl"
        const val THUMBS_PREFIX = "media-index/thumbs/"
    }
}

/**
 * records.jsonl 单行：媒体元数据 + Base64(Float16 向量)。
 * 归档行不带 modelId/vectorDim（由清单元数据统一给出）；回滚快照行带这两个字段，
 * 以便把 media_index 整表（含其它切片）精确还原到导入前状态。
 */
@Serializable
internal data class EtaBackupMediaRecord(
    val sourceKey: String,
    val modelId: String? = null,
    val vectorDim: Int? = null,
    val kind: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val contentHash: String = "",
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameTimeMs: Long? = null,
    val thumbPath: String? = null,
    val indexedAt: Long,
    val vector: String,
)

@Serializable
internal data class EtaConversationExport(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val conversation: ConversationEntity,
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoint: ConversationContextCheckpointEntity? = null,
    val attachmentCount: Int = 0,
    val attachments: List<ConversationArchiveAttachment> = emptyList(),
    val subAgentConfigJson: String? = null,
    val subAgentConfigGeneration: String? = null,
) {
    companion object {
        const val FORMAT = "eta-conversation"
        const val SCHEMA_VERSION = 3
        const val MANIFEST_NAME = "eta-conversation.json"
    }
}

internal data class EtaBackupSummary(
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val memoryBytes: Int,
    val assistantCount: Int = 0,
    val mcpCount: Int = 0,
    val skillCount: Int = 0,
    /** 自建配方（模型给自己写的工具）数量；schema 7 起有值，旧备份为 0。 */
    val recipeCount: Int = 0,
    val attachmentCount: Int = 0,
    val includedLinuxEnvironment: Boolean = false,
    val mediaIndexCount: Int = 0,
    val mediaIndexModelId: String? = null,
    val mediaIndexDim: Int = 0,
    val downloadedModelCount: Int = 0,
    val downloadedModelBytes: Long = 0,
    /**
     * 需要用户重新填写密钥的提供商数量。
     *
     * 备份做过脱敏时（`apiKeysIncluded = false`）等于所有 API Key 模式的提供商数量；
     * 带密钥的备份与旧备份为 0。OAuth 模式的提供商不算——它们本来就走登录。
     */
    val providersNeedingKey: Int = 0,
)

internal data class EtaBackupExportOptions(
    /**
     * 是否包含应用数据（对话、提供商、助手、技能、MCP、设置、附件、记忆）。
     *
     * 关掉后就只剩 Linux 环境 / 媒体索引 / 模型这些大件，用于「只搬模型」这类场景，
     * 导入时也不会动本机对话与设置。默认开启，与旧行为一致。
     */
    val includeAppData: Boolean = true,
    /**
     * 是否包含浏览器设置（UA / 视口 / 空闲超时）。
     *
     * 独立于 [includeAppData]：它既不属于应用数据，也不属于大件，所以单列一个开关，
     * 默认关闭——浏览器偏好是可选迁移项，不该在用户没勾的时候偷偷进包。
     */
    val includeBrowserSettings: Boolean = false,
    val includeLinuxEnvironment: Boolean = false,
    /** 是否包含媒体语义索引（embedding 向量）：默认开启，与「包含 Linux 环境」并列的独立开关。 */
    val includeEmbeddings: Boolean = true,
    /**
     * 是否把 API Key 明文写进备份包。
     *
     * 默认关闭。备份包的用途就是离开设备（网盘、聊天工具、换机传输），而归档本身不加密：
     * 带上密钥等于把全部厂商的账单权限连同文件一起交出去，任何拿到包的人都能用。
     * 关掉时提供商、模型、地址、参数照常备份，只有密钥留空，导入后提示用户重填。
     */
    val includeApiKeys: Boolean = false,
    /** 是否连同已下载的语音识别 / Embedding 模型一起打包。 */
    val includeModels: Boolean = false,
)

internal class EtaBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** 备份归档的类型：决定导入走「恢复数据」还是「只恢复模型」。 */
internal enum class EtaBackupArchiveKind {
    /** 数据备份：完整备份（可能也带模型）或单个会话备份。 */
    DATA,

    /** 纯模型备份（`eta-models.json`）。 */
    MODELS,

    /** 清单缺失或根本不是 Eta 备份。 */
    UNKNOWN,
}

internal object EtaBackupRepository {
    /** 用户发出的消息类型，与 `AgentConversationStore` 落盘时的 type 取值一致。 */
    private const val MESSAGE_TYPE_USER = "user"

    private val operationMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    /**
     * 脱敏时的紧凑 JSON。
     *
     * 只做结构往返，不需要可读格式；用紧凑输出避免把库里原本的紧凑串换成多行。
     */
    private val compactJson = Json { ignoreUnknownKeys = true }

    /**
     * 请求体里按名字精确判定的密钥字段（见 [redactBodies]）。
     *
     * 用「完全相等」而不是包含，是为了不误伤 `max_tokens` 这类常见参数。
     */
    private val BODY_SECRET_KEYS = setOf(
        "key", "apikey", "apikeys", "token", "accesstoken", "refreshtoken",
        "secret", "secretkey", "clientsecret", "authorization", "auth", "subscriptionkey",
    )

    /** records.jsonl 每行一条紧凑 JSON：缺省字段直接省略。 */
    private val lineJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun linuxEnvironmentBytes(context: Context): Long {
        val appContext = context.applicationContext
        val walked = LinuxDistribution.entries.sumOf { distribution ->
            directorySize(LinuxEnvironmentPaths.environmentDir(appContext, distribution))
        }
        if (walked > 0L) return walked
        if (!RootAccess.isGranted) return 0L
        return LinuxDistribution.entries.sumOf { distribution ->
            duBytes(LinuxEnvironmentPaths.environmentDir(appContext, distribution))
        }
    }

    /** 备份体积预估：行数 × vectorDim × 2 字节（Float16），供备份界面提示。 */
    suspend fun mediaIndexEstimateBytes(context: Context): Long = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = EtaDatabase.get(appContext).mediaIndexDao()
        val (modelId, vectorDim) = mediaIndexSlice(appContext, dao)
        dao.countForModel(modelId, vectorDim).toLong() * vectorDim * 2L
    }

    suspend fun export(
        context: Context,
        output: OutputStream,
        options: EtaBackupExportOptions = EtaBackupExportOptions(),
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            AgentExecutionService.beginBackupMaintenance()
            try {
                val appContext = context.applicationContext
                val modelSources = if (options.includeModels) {
                    EtaModelBackup.downloadedSources(appContext)
                } else {
                    emptyList()
                }
                val modelDocument = modelSources.takeIf { it.isNotEmpty() }
                    ?.let(EtaModelBackup::document)
                val payloads = File(appContext.cacheDir, "daiyu-backup-conversations")
                payloads.deleteRecursively()
                BackupDurability.mkdirs(payloads)
                // 用户发出的图片随会话一起收集：只备份这些，助手回复里的图片按设计不进包。
                val userImages = linkedSetOf<String>()
                val document = try {
                    EtaDatabase.get(appContext).withTransaction {
                        val meta = snapshot(appContext, options, models = modelDocument)
                        val count = writeConversationPayloads(appContext, payloads, userImages)
                        require(count == meta.messageCount) { "会话消息在导出时发生变化" }
                        // 清单里的附件数要与真正进包的用户图片一致，不能沿用整个缓存目录的统计。
                        if (options.includeAppData) meta.copy(attachmentCount = userImages.size) else meta
                    }
                } catch (failure: Throwable) {
                    payloads.deleteRecursively()
                    throw failure
                }
                try {
                ZipOutputStream(output).use { zip ->
                    val writer = BackupZipWriter(zip)
                    zip.setLevel(if (options.includeLinuxEnvironment || modelDocument != null) 1 else 6)
                    val manifest = json.encodeToString(document)
                    require(manifest.toByteArray().size <= BackupArchiveSafety.MANIFEST_LIMIT) { "备份清单超过大小限制" }
                    writer.text(EtaBackupDocument.MANIFEST_NAME, manifest)
                    payloads.listFiles()?.sortedBy { it.name }?.forEach { file ->
                        writer.file("conversations/${file.name}", file)
                    }
                    // 聊天附件属于应用数据：只打包大件时不带。
                    if (options.includeAppData) {
                        // 只打包用户消息里引用的图片。模型回复里的图片是正文绝对路径直接渲染，
                        // 不复制、不缓存、也不进备份——路径失效时干脆不渲染。
                        val chatImageRoot = File(appContext.cacheDir, AgentChatImageCache.CACHE_DIRECTORY)
                        userImages.forEach { relative ->
                            val file = File(chatImageRoot, relative)
                            if (file.isFile) writer.file("attachments/chat-images/$relative", file)
                        }
                        // 配方是模型自建的工具：只有 ZIP 备份会带走它们（系统备份已被 allowBackup=false 关掉）。
                        ToolRecipePaths.files(appContext).forEach { recipe ->
                            writer.file(ToolRecipePaths.ENTRY_PREFIX + recipe.name, recipe)
                        }
                    }
                    // 工作区（含导入文件）同样是应用数据的产出物，跟「应用数据」一起取舍；
                    // 但打包 Linux 环境时必须连带，否则恢复出来的环境没有可用的工作文件。
                    if (options.includeAppData || options.includeLinuxEnvironment) {
                        writer.directory(
                            "attachments/imports/",
                            File(TerminalPrivateStorage.workspace(appContext.filesDir), "imports"),
                        )
                        writer.directory(
                            "linux/workspace/",
                            TerminalPrivateStorage.workspace(appContext.filesDir),
                            skipNames = setOf("imports", "mounts"),
                        )
                    }
                    if (options.includeLinuxEnvironment) {
                        exportLinuxEnvironments(appContext, writer)
                    }
                    document.mediaIndex?.let { meta -> exportMediaIndex(appContext, writer, meta) }
                    EtaModelBackup.writeFiles(writer, modelSources)
                    zip.finish()
                }
                document.toBackupSummary()
                } finally {
                    payloads.deleteRecursively()
                }
            } finally {
                AgentExecutionService.endBackupMaintenance()
            }
        }
    }

    /** 只导出已下载模型，不读取或改写对话、设置等用户数据。 */
    suspend fun exportModels(context: Context, output: OutputStream): EtaModelBackupSummary =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                EtaModelBackup.export(context.applicationContext, output)
            }
        }

    /** 只恢复模型文件；不触碰对话、设置与附件。 */
    suspend fun importModels(context: Context, input: InputStream): EtaModelBackupSummary =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                EtaModelBackup.importArchive(context.applicationContext, input)
            }
        }

    /** 当前可备份的模型数量与体积，供备份界面展示。 */
    suspend fun downloadedModels(context: Context): EtaModelBackupSummary =
        withContext(Dispatchers.IO) {
            EtaModelBackup.downloaded(context.applicationContext)
        }

    suspend fun exportConversation(
        context: Context,
        conversationId: String,
        output: OutputStream,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            AgentExecutionService.beginBackupMaintenance()
            try {
                val appContext = context.applicationContext
                val snapshot = EtaDatabase.get(appContext).withTransaction { conversationSnapshot(appContext, conversationId) }
                val prepared = ConversationArchiveMedia.prepare(appContext, snapshot)
                val document = prepared.document
                ZipOutputStream(output).use { zip ->
                    val writer = BackupZipWriter(zip)
                    writer.text(EtaConversationExport.MANIFEST_NAME, json.encodeToString(document))
                    document.attachments.forEach { attachment ->
                        val file = prepared.files.getValue(attachment.entry)
                        require(file.length() == attachment.size) { "附件在导出时发生变化" }
                        writer.file(attachment.entry, file, attachment.sha256)
                    }
                    zip.finish()
                }
                document.toConversationSummary()
            } finally {
                AgentExecutionService.endBackupMaintenance()
            }
        }
    }

    suspend fun import(context: Context, input: InputStream): EtaBackupSummary =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                AgentExecutionService.beginBackupMaintenance()
                try {
                    val appContext = context.applicationContext
                    recoverInterruptedImport(appContext, maintenanceAlreadyHeld = true)
                    val operation = File(appContext.filesDir, "backup-restore")
                    BackupDurability.mkdirs(operation)
                    var mayRetire = false
                    try {
                        val archive = File(operation, "input")
                        archive.outputStream().use {
                            BackupArchiveSafety.copyLimited(input, it, BackupArchiveSafety.TOTAL_LIMIT, operation)
                        }
                        val header = archive.inputStream().use { it.readNBytes(2) }
                        require(header.isNotEmpty()) { "备份文件为空" }
                        val files = if (header.contentEquals(byteArrayOf(0x50, 0x4b))) {
                            java.util.zip.ZipFile(archive).use { zip ->
                                require(zip.getEntry(EtaModelBackupDocument.MANIFEST_NAME) == null) {
                                    "这是模型备份，请使用「恢复模型」导入"
                                }
                            }
                            BackupArchiveSafety.stageZip(archive, File(operation, "staged"))
                        } else {
                            require(archive.length() <= BackupArchiveSafety.MANIFEST_LIMIT) { "备份清单过大" }
                            mapOf(manifestName(archive.readText()) to archive)
                        }
                        require(files.keys.none { it == EtaModelBackupDocument.MANIFEST_NAME }) {
                            "这是模型备份，请使用「恢复模型」导入"
                        }
                        val document = files[EtaBackupDocument.MANIFEST_NAME]?.let {
                            attachExternalMessages(decodeDocument(it.readText()).also(::validate), files.filterKeys(::isConversationPayload).values)
                        }
                        val conversation = files[EtaConversationExport.MANIFEST_NAME]?.let {
                            decodeConversation(it.readText())
                        }
                        if (conversation != null) {
                            // Gate generation and validate current archives before any owner, files or Room write.
                            val config = BackupSubAgentConfig.archiveForImport(conversation.schemaVersion,
                                conversation.subAgentConfigJson, ConversationSubAgentPreferences(),
                                generation = conversation.subAgentConfigGeneration)
                            val plan = ConversationArchiveImport.prepare(appContext, conversation, files)
                            val newId = plan.document.conversation.id
                            check(EtaDatabase.get(appContext).conversationDao().conversationEntity(newId) == null) { "新会话 ID 冲突，未开始导入" }
                            val prefs = requireNotNull(Prefs.localAgentPreferences()) { "Agent preferences 未初始化" }
                            check(!BackupSubAgentConfig.hasOwner(prefs, newId)) { "新会话 owner 冲突，未开始导入" }
                            withContext(NonCancellable) {
                                durableText(File(operation, "new-conversation-id"), newId)
                                val journal = BackupRestoreJournal(operation)
                                journal.begin(plan.files.keys.toList())
                                val owner = BackupConversationOwnerImport.plan(operation, newId, config)
                                try {
                                    owner.begin(appContext)
                                    EtaDatabase.get(appContext).withTransaction {
                                        plan.files.forEach { (target, source) -> journal.replace(target, source) }
                                        val imported = plan.document
                                        EtaDatabase.get(appContext).conversationDao().importAsNewConversation(
                                            imported.conversation, imported.messages, imported.contextCheckpoint,
                                        )
                                    }
                                } catch (failure: Throwable) {
                                    try {
                                        // Room has rolled back; undo only the owner installed by THIS import.
                                        BackupConversationOwnerImport.recover(appContext, operation, committed = false)
                                        journal.rollback()
                                        journal.commit()
                                        mayRetire = true
                                    } catch (rollbackFailure: Throwable) {
                                        failure.addSuppressed(rollbackFailure)
                                        throw EtaBackupException("会话导入失败且回滚未完成，日志已保留，请重启应用完成恢复", failure)
                                    }
                                    throw failure
                                }
                                // Marker failure must keep maintenance active until startup recovery.
                                journal.commit()
                                BackupConversationOwnerImport.recover(appContext, operation, committed = true)
                                mayRetire = true
                            }
                            return@withLock plan.document.toConversationSummary()
                        }
                        val planned = linkedMapOf<File, File>()
                        val linuxTars = mutableListOf<Triple<String, String, File>>()
                        val modelEntries = linkedMapOf<String, File>()
                        val recipeTargets = linkedSetOf<File>()
                        var mediaRecordsFile: File? = null
                        files.forEach { (name, source) ->
                            val target = when {
                                name == EtaBackupDocument.MANIFEST_NAME || name == EtaConversationExport.MANIFEST_NAME -> null
                                isConversationPayload(name) -> null
                                name == EtaBackupMediaIndex.ENTRY -> {
                                    require(document?.mediaIndex != null) { "备份中的媒体索引条目缺少清单元数据" }
                                    require(mediaRecordsFile == null) { "备份包含多份媒体索引数据" }
                                    mediaRecordsFile = source
                                    null
                                }
                                name.startsWith(EtaBackupMediaIndex.THUMBS_PREFIX) -> BackupArchiveSafety.target(
                                    thumbDirectory(appContext), name.removePrefix(EtaBackupMediaIndex.THUMBS_PREFIX))
                                name.startsWith(EtaModelBackupDocument.PREFIX) -> {
                                    require(document?.models != null) { "备份中的模型条目缺少清单元数据" }
                                    modelEntries[name] = source
                                    null
                                }
                                name.startsWith("attachments/chat-images/") -> BackupArchiveSafety.target(
                                    File(appContext.cacheDir, AgentChatImageCache.CACHE_DIRECTORY), name.removePrefix("attachments/chat-images/"))
                                name.startsWith("attachments/imports/") -> BackupArchiveSafety.target(
                                    File(TerminalPrivateStorage.workspace(appContext.filesDir), "imports"), name.removePrefix("attachments/imports/"))
                                name.startsWith("linux/workspace/") -> {
                                    val relative = name.removePrefix("linux/workspace/")
                                    require(relative.substringBefore('/') !in setOf("imports", "mounts")) { "工作区归档包含受保护挂载" }
                                    BackupArchiveSafety.target(TerminalPrivateStorage.workspace(appContext.filesDir), relative)
                                }
                                name.startsWith(ToolRecipePaths.ENTRY_PREFIX) -> {
                                    // 配方文件只允许出现在「带应用数据」的完整归档里，配合「整包替换」语义。
                                    require(document?.appDataIncluded == true) { "备份中的配方条目缺少应用数据" }
                                    val fileName = name.removePrefix(ToolRecipePaths.ENTRY_PREFIX)
                                    require(fileName.endsWith(".json") &&
                                        ToolRecipeConstants.TOOL_NAME_PATTERN.matches(fileName.removeSuffix(".json"))
                                    ) { "备份中的配方名无效：$fileName" }
                                    BackupArchiveSafety.target(ToolRecipePaths.directory(appContext), fileName)
                                        .also { target ->
                                            require(recipeTargets.add(target)) { "备份包含重复配方项：$fileName" }
                                            require(recipeTargets.size <= ToolRecipeConstants.MAX_STORED_RECIPES) {
                                                "备份中的配方数量超过上限"
                                            }
                                        }
                                }
                                BackupArchiveSafety.LINUX_ENVIRONMENT_TAR.matches(name) -> {
                                    val parts = name.removePrefix("linux/environments/").removeSuffix(".tar").split('/')
                                    require(parts.size == 2) { "Linux 环境归档路径无效" }
                                    linuxTars += Triple(parts[0], parts[1], source)
                                    null
                                }
                                else -> throw EtaBackupException("备份存在未知条目：$name")
                            }
                            if (target != null) {
                                require(!planned.containsKey(target)) { "多个归档条目指向同一文件" }
                                planned[target] = source
                            }
                        }
                        val modelDocument = document?.models
                        if (modelDocument != null) {
                            require(modelEntries.keys == EtaModelBackup.expectedEntries(modelDocument)) {
                                "备份中的模型条目与清单不一致"
                            }
                            EtaModelBackup.verify(modelEntries, modelDocument)
                        } else {
                            require(modelEntries.isEmpty()) { "备份中的模型条目缺少清单元数据" }
                        }
                        // schema 7 只把配方数量写进清单、不带文件；那条校验只对带文件的 schema 8 归档成立。
                        if (document != null && document.schemaVersion >= 8) {
                            require(recipeTargets.size == document.recipeCount) { "备份中的配方条目与清单不一致" }
                        }
                        val destinations = planned.keys.map { it.toPath() }.toSet()
                        require(destinations.none { path ->
                            generateSequence(path.parent) { it.parent }.any { it in destinations }
                        }) { "归档文件与目录路径冲突" }
                        val daemons = io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor.defaultRecordsFile(appContext)
                        if (daemons.exists()) {
                            val records = daemons.inputStream().use { BackupArchiveSafety.readText(it, 1024 * 1024) }
                            require(org.json.JSONArray(records).length() == 0) { "请先停止并移除后台守护任务，再导入备份" }
                        }
                        require((mediaRecordsFile != null) == (document?.mediaIndex != null)) { "备份中的媒体索引条目与清单不一致" }
                        // 向量解析与重关联都在改动任何数据前完成；行最终在 restoreMetadata 的事务里写入 media_index。
                        val mediaRows = document?.mediaIndex?.let { meta ->
                            val thumbNames = files.keys.mapNotNullTo(hashSetOf()) { key ->
                                if (key.startsWith(EtaBackupMediaIndex.THUMBS_PREFIX)) {
                                    key.removePrefix(EtaBackupMediaIndex.THUMBS_PREFIX)
                                } else {
                                    null
                                }
                            }
                            val scan = scanMediaRecords(
                                requireNotNull(mediaRecordsFile).bufferedReader(Charsets.UTF_8).lineSequence(),
                                keep = true,
                                meta = meta,
                                thumbRoot = thumbDirectory(appContext),
                                thumbNames = thumbNames,
                            )
                            requireMediaRecordsMatch(scan, meta)
                            relinkMediaRecords(appContext, scan.rows, planned)
                        }
                        // 模型是可重建缓存：在全部校验完成后、任何元数据写入前恢复；
                        // 这样数据导入失败回滚时不会留下半份模型，也无需把大文件纳入恢复日志。
                        if (modelDocument != null) {
                            EtaModelBackup.install(appContext, modelEntries, modelDocument)
                        }
                        val previousPayloads = File(operation, "previous-conversations")
                        previousPayloads.deleteRecursively()
                        BackupDurability.mkdirs(previousPayloads)
                        val old = EtaDatabase.get(appContext).withTransaction {
                            // 回滚快照是「导入失败时还原到导入前」的唯一依据，必须完整：
                            // 默认选项会脱敏密钥，拿它做快照等于回滚时把用户的 API Key 抹掉。
                            val meta = snapshot(appContext, EtaBackupExportOptions(includeApiKeys = true))
                            val count = writeConversationPayloads(appContext, previousPayloads)
                            require(count == meta.messageCount) { "回滚快照中的消息数量不一致，未开始恢复" }
                            writeMediaSnapshot(appContext, File(operation, "previous-media-index.jsonl"))
                            meta
                        }
                        val oldJson = json.encodeToString(old)
                        require(oldJson.toByteArray().size <= BackupArchiveSafety.MANIFEST_LIMIT) { "回滚快照过大，未开始恢复" }
                        durableText(File(operation, "previous.json"), oldJson)
                        val journal = BackupRestoreJournal(operation)
                        journal.begin(planned.keys.toList())
                        try {
                            EtaDatabase.get(appContext).withTransaction {
                                planned.forEach { (target, source) -> journal.replace(target, source) }
                                if (document != null) {
                                    restoreMetadata(appContext, document, mediaIndexRows = mediaRows)
                                    // 路径重写只针对会话与附件引用：只含大件的备份没有这些数据。
                                    if (document.appDataIncluded) {
                                        rewriteRestoredPaths(appContext, document)
                                    }
                                }
                            }
                            if (recipeTargets.isNotEmpty()) {
                                restoreRecipes(appContext, recipeTargets)
                            }
                            if (document?.includeLinuxEnvironment == true) {
                                linuxTars.forEach { (backend, distribution, archive) ->
                                    archive.inputStream().use { restoreLinuxTar(appContext, it, backend, distribution) }
                                }
                            }
                        } catch (failure: Throwable) {
                            withContext(NonCancellable) {
                                try {
                                    journal.rollback()
                                    restoreMetadata(appContext, attachExternalMessages(old, previousPayloads.listFiles()?.toList().orEmpty()), reconcile = false, exactPreferences = true,
                                        mediaIndexRows = readMediaSnapshot(operation))
                                } catch (rollbackFailure: Throwable) {
                                    failure.addSuppressed(rollbackFailure)
                                    throw EtaBackupException("恢复失败且回滚尚未完成，恢复日志已保留。请重启应用完成恢复", failure)
                                }
                                journal.commit()
                                mayRetire = true
                            }
                            throw failure
                        }
                        // Commit-marker failure is not an apply failure: do not start rollback after
                        // publishing a marker which startup could legitimately interpret as committed.
                        journal.commit()
                        mayRetire = true
                        if (document != null) document.toBackupSummary() else requireNotNull(conversation).toConversationSummary()
                    } finally {
                        if (mayRetire || !BackupRestoreJournal.hasJournal(operation)) {
                            BackupDurability.retire(operation).deleteRecursively()
                        }
                    }
                } finally {
                    val operation = File(context.filesDir, "backup-restore")
                    // A failed rollback keeps new execution blocked until startup recovery succeeds.
                    if (!operation.exists()) {
                        AgentExecutionService.endBackupMaintenance()
                    }
                }
            }
        }

    /** Fence actual run admission before examining any journal: logs alone do not block new history. */
    suspend fun recoverInterruptedImport(context: Context, maintenanceAlreadyHeld: Boolean = false) {
        val operation = File(context.filesDir, "backup-restore")
        if (!operation.exists()) return
        if (!maintenanceAlreadyHeld) AgentExecutionService.beginBackupMaintenance()
        // On failure the fence stays up; the caller must not admit work before a later successful recovery.
        if (BackupRestoreJournal.hasJournal(operation) && !BackupRestoreJournal.isCommitted(operation)) {
            val idFile = File(operation, "new-conversation-id")
            if (idFile.exists() || File(idFile.path + ".bak").exists()) {
                val id = android.util.AtomicFile(idFile).openRead().use { BackupArchiveSafety.readText(it, 128) }
                require(Regex("conv-[0-9a-f-]{36}").matches(id)) { "会话恢复日志 ID 无效" }
                // This ID was freshly allocated for this import only.
                EtaDatabase.get(context).conversationDao().deleteImportedConversation(id)
                BackupConversationOwnerImport.recover(context, operation, committed = false)
                BackupRestoreJournal(operation).rollback()
            } else {
                val old = android.util.AtomicFile(File(operation, "previous.json")).openRead().use {
                    decodeDocument(BackupArchiveSafety.readText(it))
                }
                val previousPayloads = File(operation, "previous-conversations").listFiles()?.toList().orEmpty()
                BackupRestoreJournal(operation).rollback()
                restoreMetadata(context, attachExternalMessages(old, previousPayloads), reconcile = false, exactPreferences = true,
                    mediaIndexRows = readMediaSnapshot(operation))
            }
        }
        if (BackupRestoreJournal.hasJournal(operation) && BackupRestoreJournal.isCommitted(operation)) {
            BackupConversationOwnerImport.recover(context, operation, committed = true)
        }
        // The atomic retirement removes the journal from the recovery namespace before releasing admission.
        BackupDurability.retire(operation).deleteRecursively()
        if (!maintenanceAlreadyHeld) AgentExecutionService.endBackupMaintenance()
    }

    suspend fun inspect(input: InputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        // Preview is bounded and streamed; import always repeats full CRC/central-directory validation.
        val body = java.io.PushbackInputStream(input, 2)
        val header = body.readNBytes(2)
        require(header.isNotEmpty()) { "备份文件为空" }
        body.unread(header)
        if (header.contentEquals(byteArrayOf(0x50, 0x4b))) {
            var summary: EtaBackupSummary? = null
            var document: EtaBackupDocument? = null
            var mediaScan: MediaRecordScan? = null
            val modelEntries = hashSetOf<String>()
            var total = 0L
            val names = hashSetOf<String>()
            ZipInputStream(body).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(names.size < BackupArchiveSafety.ENTRY_LIMIT) { "备份条目过多" }
                    val name = BackupArchiveSafety.relativePath(entry.name)
                    require(names.add(name)) { "备份存在重复条目" }
                    if (name == EtaBackupDocument.MANIFEST_NAME || name == EtaConversationExport.MANIFEST_NAME) {
                        require(summary == null) { "备份包含多个清单" }
                        val raw = BackupArchiveSafety.readText(zip)
                        total += raw.toByteArray().size
                        require(total <= BackupArchiveSafety.TOTAL_LIMIT) { "备份总量超过限制" }
                        summary = if (name == EtaBackupDocument.MANIFEST_NAME) {
                            decodeDocument(raw).also(::validate).also { document = it }.toBackupSummary()
                        } else decodeConversation(raw).toConversationSummary()
                    } else if (name == EtaBackupMediaIndex.ENTRY) {
                        // 预览不保留向量：逐行解析做一致性校验，元数据与清单的比对在读完清单后统一做。
                        require(mediaScan == null) { "备份包含多份媒体索引数据" }
                        val scan = scanMediaRecords(zip.bufferedReader(Charsets.UTF_8).lineSequence(), keep = false)
                        mediaScan = scan
                        total += scan.bytes
                        require(total <= BackupArchiveSafety.TOTAL_LIMIT) { "备份总量超过限制" }
                    } else {
                        if (name.startsWith(EtaModelBackupDocument.PREFIX)) modelEntries += name
                        total += BackupArchiveSafety.copyLimited(zip, OutputStream.nullOutputStream(), BackupArchiveSafety.TOTAL_LIMIT - total)
                    }
                }
            }
            val preview = requireNotNull(summary) { "备份文件缺少清单" }
            val meta = document?.mediaIndex
            if (meta != null) {
                requireMediaRecordsMatch(requireNotNull(mediaScan) { "备份缺少媒体语义索引数据" }, meta)
            } else {
                require(mediaScan == null) { "备份中的媒体索引条目缺少清单元数据" }
            }
            val models = document?.models
            if (models != null) {
                require(modelEntries == EtaModelBackup.expectedEntries(models)) { "备份中的模型条目与清单不一致" }
            } else {
                require(modelEntries.isEmpty()) { "备份中的模型条目缺少清单元数据" }
            }
            preview
        } else {
            val raw = BackupArchiveSafety.readText(body)
            if (manifestName(raw) == EtaConversationExport.MANIFEST_NAME) decodeConversation(raw).toConversationSummary()
            else decodeDocument(raw).also(::validate).toBackupSummary()
        }
    }

    /**
     * 导入前的轻量探测：只扫归档目录判断这是「数据备份」还是「纯模型备份」，
     * 好让确认弹窗给出正确的文案与按钮。真正的完整校验仍在 [import] / [importModels] 里重跑一遍。
     *
     * 只读条目名、不解压内容；条目数超过 [BackupArchiveSafety.ENTRY_LIMIT] 或压缩包本身损坏都判为未知。
     */
    fun archiveKind(input: InputStream): EtaBackupArchiveKind {
        val pushback = java.io.PushbackInputStream(input, 2)
        val header = pushback.readNBytes(2)
        if (header.isEmpty()) return EtaBackupArchiveKind.UNKNOWN
        pushback.unread(header)
        // 明文清单只有会话 / 数据备份会这么存；模型备份一定是 ZIP。
        if (!header.contentEquals(byteArrayOf(0x50, 0x4b))) return EtaBackupArchiveKind.DATA
        var models = false
        var data = false
        var entries = 0
        val scanned = runCatching {
            java.util.zip.ZipInputStream(pushback).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > BackupArchiveSafety.ENTRY_LIMIT) return@runCatching false
                    val name = entry.name.trim().trimStart('/')
                    when (name) {
                        EtaModelBackupDocument.MANIFEST_NAME -> models = true
                        EtaBackupDocument.MANIFEST_NAME, EtaConversationExport.MANIFEST_NAME -> data = true
                    }
                }
                true
            }
        }.getOrDefault(false)
        if (!scanned) return EtaBackupArchiveKind.UNKNOWN
        return when {
            data -> EtaBackupArchiveKind.DATA
            models -> EtaBackupArchiveKind.MODELS
            else -> EtaBackupArchiveKind.UNKNOWN
        }
    }

    /**
     * 归档带配方时执行整包替换：备份里没有的本机配方一并删掉，否则「恢复到备份那一刻」不成立
     * （本机回滚快照尤其依赖这一点）。
     *
     * 归档不带配方（老备份或只含大件的备份）时 [restored] 为空，本机配方保持原样——
     * 绝不因为「备份里没有配方」就把用户的自进化成果清空。这里的删除不进恢复日志：
     * 走到这一步时 Room 事务与所有文件替换都已成功，且配方是可由模型重造的小 JSON。
     */
    private fun restoreRecipes(context: Context, restored: Set<File>) {
        val directory = ToolRecipePaths.directory(context)
        val keep = restored.mapTo(hashSetOf()) { it.canonicalFile }
        val stale = directory.listFiles()?.filter { it.isFile && it.canonicalFile !in keep } ?: emptyList()
        stale.forEach { it.delete() }
        if (stale.isNotEmpty()) BackupDurability.syncDirectory(directory)
        AgentToolRecipeStore.invalidateCache()
    }

    private fun manifestName(raw: String): String =
        if (runCatching { org.json.JSONObject(raw).optString("format") }.getOrNull() == EtaConversationExport.FORMAT)
            EtaConversationExport.MANIFEST_NAME else EtaBackupDocument.MANIFEST_NAME

    private suspend fun writeConversationPayloads(
        context: Context,
        directory: File,
        userImages: MutableSet<String>? = null,
    ): Int {
        val dao = EtaDatabase.get(context).conversationDao()
        var count = 0
        dao.conversationEntities().forEachIndexed { index, conversation ->
            val messages = dao.messagesForConversation(conversation.id)
            if (userImages != null) userImages += userAttachedImages(context, messages)
            val exported = EtaConversationExport(
                exportedAt = System.currentTimeMillis(),
                conversation = conversation,
                messages = messages,
                contextCheckpoint = dao.contextCheckpoint(conversation.id),
            )
            val text = json.encodeToString(exported)
            require(text.toByteArray().size.toLong() <= BackupArchiveSafety.MANIFEST_LIMIT) {
                "会话「${conversation.title.ifBlank { conversation.id }.take(40)}」超过单个备份条目上限，请先减少该会话内容"
            }
            File(directory, conversationPayloadFileName(conversation.id, index)).writeText(text)
            count += exported.messages.size
        }
        return count
    }

    /**
     * 用户发出的消息所引用的本地图片，返回相对 chat-images 根目录的路径。
     *
     * 只认 [MESSAGE_TYPE_USER]：助手消息正文里的图片是绝对路径直接渲染的，按设计不缓存、不备份。
     * 命中条件同时要求「文件存在」且「位于应用私有附件目录内」，避免把任意路径带进归档。
     */
    private fun userAttachedImages(context: Context, messages: List<ConversationMessageEntity>): Set<String> {
        val root = File(context.cacheDir, AgentChatImageCache.CACHE_DIRECTORY).canonicalFile
        val found = linkedSetOf<String>()
        for (message in messages) {
            if (message.type != MESSAGE_TYPE_USER) continue
            for (raw in imageReferences(message.imagesJson)) {
                val path = raw.removePrefix("file://")
                if (path.isBlank()) continue
                val file = runCatching { File(path).canonicalFile }.getOrNull() ?: continue
                if (!file.isFile || !file.toPath().startsWith(root.toPath())) continue
                val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: continue
                if (runCatching { BackupArchiveSafety.relativePath(relative) }.isSuccess) found += relative
            }
        }
        return found
    }

    /** images_json 的元素可能是纯字符串路径，也可能是 `{preview, source, kind}` 对象。 */
    private fun imageReferences(imagesJson: String): List<String> {
        val array = runCatching { org.json.JSONArray(imagesJson) }.getOrNull() ?: return emptyList()
        val paths = mutableListOf<String>()
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is String -> paths += item
                is org.json.JSONObject -> {
                    paths += item.optString("preview")
                    paths += item.optString("source")
                }
            }
        }
        return paths.filter { it.isNotBlank() }
    }

    private fun conversationPayloadFileName(id: String, index: Int): String {        val safe = id.length <= 180 && id.matches(Regex("[A-Za-z0-9_.-]+")) && id != "." && id != ".."
        return if (safe) "$id.json" else "c$index.json"
    }

    private fun isConversationPayload(name: String): Boolean =
        name.startsWith("conversations/") && name.endsWith(".json") && name.count { it == '/' } == 1

    private fun attachExternalMessages(document: EtaBackupDocument, payloads: Collection<File>): EtaBackupDocument {
        if (!document.messagesExternal) return document
        val messages = mutableListOf<io.github.mangi.eta.data.db.ConversationMessageEntity>()
        val checkpoints = mutableListOf<io.github.mangi.eta.data.db.ConversationContextCheckpointEntity>()
        val seen = mutableSetOf<String>()
        payloads.filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
            val exported = decodeConversation(file.readText())
            require(seen.add(exported.conversation.id)) { "备份中的会话重复" }
            messages += exported.messages
            exported.contextCheckpoint?.let { checkpoints += it }
        }
        require(seen == document.conversations.map { it.id }.toSet()) { "备份中的会话消息不完整" }
        require(messages.size == document.messageCount) { "备份中的消息数量不一致" }
        return document.copy(messages = messages, contextCheckpoints = checkpoints)
    }

    private suspend fun snapshot(
        context: Context,
        options: EtaBackupExportOptions,
        models: EtaModelBackupDocument? = null,
    ): EtaBackupDocument {
        val mediaDao = EtaDatabase.get(context).mediaIndexDao()
        val (mediaModelId, mediaVectorDim) = mediaIndexSlice(context, mediaDao)
        val mediaCount = mediaDao.countForModel(mediaModelId, mediaVectorDim)
        val mediaIndex = if (options.includeEmbeddings && mediaCount > 0) {
            EtaBackupMediaIndex(modelId = mediaModelId, vectorDim = mediaVectorDim, count = mediaCount)
        } else {
            null
        }
        // 工作区跟随「应用数据」取舍，但打包 Linux 环境时必须连带（环境缺了工作区没法用）。
        val workspaceIncluded = options.includeAppData || options.includeLinuxEnvironment
        // 只打包大件：应用数据整块留空，导入时同样跳过（见 restoreMetadata）。
        if (!options.includeAppData) {
            return EtaBackupDocument(
                exportedAt = System.currentTimeMillis(),
                messagesExternal = true,
                mediaIndex = mediaIndex,
                models = models,
                // 浏览器开关独立于应用数据，只带大件时也要能带上它。
                browserPreferences = if (options.includeBrowserSettings) {
                    BackupPrefsFiles.capture(context, BackupPrefsFiles.BROWSER)
                } else {
                    emptyMap()
                },
                includeLinuxEnvironment = options.includeLinuxEnvironment,
                linuxWorkspaceIncluded = workspaceIncluded,
                appDataIncluded = false,
                // 这一支不带提供商与语音配置，包里本来就没有密钥。
                apiKeysIncluded = false,
            )
        }
        val database = EtaDatabase.get(context)
        val providers = database.providerDao().providers().map { provider ->
            EtaBackupProvider(
                provider = provider.provider.withApiKeyPolicy(options.includeApiKeys),
                models = provider.models,
            )
        }
        val conversations = database.conversationDao()
        val settings = SettingsDataStore.backupSnapshot()
        val mcpServers = database.mcpServerDao().servers()
        val chatImages = File(context.cacheDir, AgentChatImageCache.CACHE_DIRECTORY)
        val imports = File(TerminalPrivateStorage.workspace(context.filesDir), "imports")
        return EtaBackupDocument(
            exportedAt = System.currentTimeMillis(),
            providers = providers,
            selectedProviderId = settings.selectedProviderId,
            selectedModelId = settings.selectedModelId,
            conversations = conversations.conversationEntities(),
            messagesExternal = true,
            messageCount = conversations.storedMessageCount(),
            conversationState = conversations.state(),
            folders = conversations.folders(),
            memoryMd = AgentMemoryRepository.snapshot(AssistantPrompt.DEFAULT_ID).content,
            assistantMemories = AgentMemoryRepository.exportAll(),
            assistants = if (AssistantRepository.isReady()) AssistantRepository.exportSnapshot() else null,
            assistantAvatars = if (AssistantRepository.isReady()) encodeFiles(AssistantRepository.exportAvatars()) else emptyMap(),
            skillRegistry = database.skillDao().registryEntries(),
            skillFiles = encodeFiles(SkillRuntime.exportUserSkills(context)),
            mcpServers = mcpServers,
            mcpTokens = McpSecretStore(context).exportTokens(mcpServers.map { it.id }),
            settings = settings,
            mediaIndex = mediaIndex,
            models = models,
            includeLinuxEnvironment = options.includeLinuxEnvironment,
            linuxWorkspaceIncluded = workspaceIncluded,
            appDataIncluded = true,
            apiKeysIncluded = options.includeApiKeys,
            recipeCount = ToolRecipePaths.count(context),
            // 语音配置跟着应用数据走；浏览器配置单列开关，默认不进包。
            // 语音里的 ASR / 克隆 Key 是明文存储的，跟提供商密钥一起受「包含 API Key」控制。
            voicePreferences = BackupPrefsFiles.capture(
                context,
                BackupPrefsFiles.VOICE,
                if (options.includeApiKeys) emptySet() else BackupPrefsFiles.VOICE_SECRET_KEYS,
            ),
            browserPreferences = if (options.includeBrowserSettings) {
                BackupPrefsFiles.capture(context, BackupPrefsFiles.BROWSER)
            } else {
                emptyMap()
            },
            attachmentCount = countFiles(chatImages),
            importedFileCount = countFiles(imports),
            agentPreferences = Prefs.exportAgentPreferences(),
        )
    }

    private suspend fun conversationSnapshot(
        context: Context,
        conversationId: String,
    ): EtaConversationExport {
        val database = EtaDatabase.get(context)
        BackupDatabaseBudget.validate(database.openHelper.readableDatabase, conversationId)
        val dao = database.conversationDao()
        val conversation = dao.conversationEntity(conversationId)
            ?: throw EtaBackupException("会话不存在")
        val messages = dao.messagesForConversation(conversationId)
        val checkpoint = dao.contextCheckpoint(conversationId)
        return EtaConversationExport(
            exportedAt = System.currentTimeMillis(),
            conversation = conversation,
            messages = messages,
            contextCheckpoint = checkpoint,
            subAgentConfigJson = BackupSubAgentConfig.archiveForExport(conversationId, ConversationSubAgentPreferences()),
            subAgentConfigGeneration = "1",
            attachmentCount = 0,
        )
    }

    private suspend fun restoreMetadata(
        context: Context, document: EtaBackupDocument, reconcile: Boolean = true, exactPreferences: Boolean = false,
        mediaIndexRows: List<MediaIndexEntity>? = null,
    ) {
        val subAgentStore = ConversationSubAgentPreferences()
        // Prepare/validate before touching Room, settings or local preferences. The undo path must
        // remain exact: it is not a second external restore and must never run reset or migrations.
        val preferencesToRestore = when {
            exactPreferences -> document.agentPreferences
            document.schemaVersion >= 2 && document.agentPreferences.isNotEmpty() ->
                BackupSubAgentConfig.preferencesForRestore(
                    document.agentPreferences, Prefs.exportAgentPreferences(), subAgentStore,
                )
            else -> null
        }
        val database = EtaDatabase.get(context)
        val data = document.appDataIncluded
        val oauthProviders = document.providers.map { it.provider }
            .filter { ProviderAuthModes.isOAuth(it.authMode) }
        val credentialIds = document.providers.mapTo(linkedSetOf()) { it.provider.id }
        database.withTransaction {
            if (data) {
                credentialIds += database.providerDao().providers().map { it.provider.id }
                database.providerDao().replaceAll(
                    document.providers.map { provider ->
                        ProviderWithModelsSeed(
                            provider = provider.provider.awaitingCredentialCleanup(exactPreferences),
                            models = provider.models,
                        )
                    },
                )
                database.conversationDao().replaceAll(
                    conversations = document.conversations,
                    messages = document.messages,
                    contextCheckpoints = document.contextCheckpoints,
                    state = document.conversationState,
                )
                database.conversationDao().replaceFolders(document.folders)
                if (document.schemaVersion >= 2) {
                    database.mcpServerDao().replaceAll(document.mcpServers)
                    database.skillDao().replaceRegistry(document.skillRegistry)
                }
            }
            if (mediaIndexRows != null) {
                val mediaDao = database.mediaIndexDao()
                // exactPreferences 即本仓库自己的回滚快照路径：整表精确还原到导入前状态；
                // 正向导入只写入备份行（已重关联），本地既有行保留待下次索引补齐。
                if (exactPreferences) mediaDao.clearAll()
                if (mediaIndexRows.isNotEmpty()) mediaDao.upsertAll(mediaIndexRows)
            }
        }
        // 只含大件（Linux 环境 / 媒体索引 / 模型）的备份到此为止：
        // 后面全是「用清单内容覆盖本机」的动作，空清单会把对话、助手与设置清掉。
        // 浏览器配置例外：它的开关独立于应用数据，必须在下面这道闸之前恢复。
        if (document.browserPreferences.isNotEmpty()) {
            BackupPrefsFiles.restore(context, document.browserPreferences, BackupPrefsFiles.BROWSER)
        }
        if (!data) return
        if (!exactPreferences) {
            // 恢复的订阅连接不继承设备上旧授权：先清除同 ID 凭据，再按备份中的登录方式重新启用。
            withContext(NonCancellable) {
                credentialIds.forEach { ProviderCredentialRepository.forget(it) }
                database.withTransaction {
                    oauthProviders.forEach { provider ->
                        check(
                            database.providerDao().activateRestoredOAuthProvider(
                                provider.id, provider.authMode, provider.isEnabled,
                            ) == 1,
                        ) { "恢复的订阅连接状态不一致，请重新选择登录方式并登录" }
                    }
                }
            }
        }
        AgentMemoryRepository.importAll(document.assistantMemories, document.memoryMd)
        if (document.schemaVersion >= 2) {
            if (AssistantRepository.isReady()) {
                document.assistants?.let(AssistantRepository::importSnapshot)
                AssistantRepository.importAvatars(decodeFiles(document.assistantAvatars))
            }
            SkillRuntime.importUserSkills(context, decodeFiles(document.skillFiles))
            McpSecretStore(context).replaceAll(document.mcpTokens)
            // 语音配置属于应用数据；空快照表示旧备份没带，保留本机设置。
            if (document.voicePreferences.isNotEmpty()) {
                BackupPrefsFiles.restore(context, document.voicePreferences, BackupPrefsFiles.VOICE)
                // 该配置在内存里有一份 StateFlow 投影，重读磁盘才会反映到运行中的语音入口。
                DoubaoVoiceConfig.load(context)
            }
            document.settings?.let { SettingsDataStore.restoreBackup(it) }
                ?: SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
        } else {
            SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
        }
        // Old archives without this field retain current prefs. Our own undo snapshot is exact,
        // including the absence of a marker; no post-write migrations may alter rollback originals.
        preferencesToRestore?.let { values ->
            if (exactPreferences) {
                BackupSubAgentConfig.restoreExactPreferences(
                    values, requireNotNull(Prefs.localAgentPreferences()) { "Agent preferences 未初始化" },
                )
            } else {
                Prefs.restoreAgentPreferences(values)
            }
            subAgentStore.refreshAfterRestore()
        }
        LinuxEnvironmentSettingsRepository.initialize(context)
        if (reconcile) {
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.repairSelection()
        }
    }

    private suspend fun rewriteRestoredPaths(context: Context, document: EtaBackupDocument) {
        val rewrite: (String) -> String = { BackupAttachmentPaths.rewriteJson(it, context) }
        val conversations = document.conversations.map { it.copy(historyJson = rewrite(it.historyJson)) }
        val messages = document.messages.map { it.copy(imagesJson = BackupAttachmentPaths.rewriteImages(it.imagesJson, context)) }
        val checkpoints = document.contextCheckpoints.map { it.copy(historyJson = rewrite(it.historyJson)) }
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = conversations,
            messages = messages,
            contextCheckpoints = checkpoints,
            state = document.conversationState,
        )
        EtaDatabase.get(context).conversationDao().replaceFolders(document.folders)
    }

    private fun decodeDocument(raw: String): EtaBackupDocument =
        runCatching { json.decodeFromString<EtaBackupDocument>(raw) }.getOrElse { failure ->
            throw EtaBackupException("备份文件格式无效", failure)
        }.let { document ->
            if (document.schemaVersion >= 3) document else document.copy(
                providers = document.providers.map { provider -> provider.copy(
                    models = provider.models.map { model -> model.copy(visionOverride = model.visionOverride ?: model.attachment) },
                ) },
            )
        }

    private fun decodeConversation(raw: String): EtaConversationExport {
        val exported = runCatching { json.decodeFromString<EtaConversationExport>(raw) }.getOrElse { failure ->
            throw EtaBackupException("会话备份文件格式无效", failure)
        }
        if (exported.format != EtaConversationExport.FORMAT) {
            throw EtaBackupException("这不是 Eta 会话备份")
        }
        require(exported.schemaVersion in 1..EtaConversationExport.SCHEMA_VERSION) { "不支持的会话备份版本" }
        require(exported.messages.all { it.conversationId == exported.conversation.id }) { "会话消息引用无效" }
        require(exported.messages.map { it.id }.distinct().size == exported.messages.size) { "会话消息 ID 重复" }
        require(exported.contextCheckpoint == null || exported.contextCheckpoint.conversationId == exported.conversation.id) { "会话检查点引用无效" }
        require(exported.conversation.id.length <= 200 &&
            exported.conversation.id.matches(Regex("[A-Za-z0-9_.-]+")) && exported.conversation.id !in setOf(".", "..")) { "会话 ID 无效" }
        if (exported.conversation.id.isBlank()) {
            throw EtaBackupException("会话备份缺少会话 ID")
        }
        return exported
    }

    private fun validate(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) {
            throw EtaBackupException("这不是 Eta 备份文件")
        }
        if (document.schemaVersion !in EtaBackupDocument.MIN_SUPPORTED_SCHEMA..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException("不支持的 Eta 备份版本：${document.schemaVersion}")
        }
        listOf(document.skillFiles, document.assistantAvatars).forEach { files ->
            require(files.size <= 10_000) { "备份嵌入文件过多" }
            var decodedBytes = 0L
            files.forEach { (name, encoded) ->
                BackupArchiveSafety.relativePath(name)
                require(encoded.length <= 8 * 1024 * 1024) { "备份嵌入文件过大" }
                decodedBytes += Base64.decode(encoded, Base64.NO_WRAP).size
                require(decodedBytes <= BackupArchiveSafety.MANIFEST_LIMIT) { "备份嵌入文件总量过大" }
            }
        }
        require(document.assistantAvatars.keys.none { '/' in it }) { "头像文件名无效" }
        require(document.assistantMemories.values.all { it.toByteArray().size <= 1024 * 1024 }) { "助手记忆超过限制" }
        // 语音 / 浏览器配置是可选 prefs 快照：只做规模校验，真正的白名单在 BackupPrefsFiles.restore 里。
        listOf(document.voicePreferences, document.browserPreferences).forEach { snapshot ->
            require(snapshot.size <= 8) { "备份中的配置文件过多" }
            snapshot.forEach { (name, values) ->
                BackupArchiveSafety.relativePath(name)
                require(values.size <= 512) { "备份中的配置项过多" }
                require(values.all { (key, value) -> key.length <= 256 && value.length <= 8192 }) { "备份中的配置值过大" }
            }
        }
        require(document.contextCheckpoints.all { checkpoint -> document.conversations.any { it.id == checkpoint.conversationId } }) { "检查点缺少所属会话" }
        val modelIds = document.providers.flatMap { it.models }.map { it.id }
        require(modelIds.size == modelIds.toSet().size && modelIds.none(String::isBlank)) { "模型 ID 无效或重复" }
        require(document.providers.all { provider -> provider.models.all { it.providerId == provider.provider.id } }) { "模型提供商引用无效" }
        require(document.messages.map { it.id }.distinct().size == document.messages.size) { "消息 ID 重复" }
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的模型提供商存在重复或无效 ID")
        }
        val conversationIds = document.conversations.map { it.id }
        if (conversationIds.size != conversationIds.toSet().size || conversationIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的会话存在重复或无效 ID")
        }
        if (document.messages.any { it.conversationId !in conversationIds }) {
            throw EtaBackupException("备份中的消息缺少所属会话")
        }
        if (document.memoryMd.toByteArray().size > 1024 * 1024) {
            throw EtaBackupException("MEMORY.md 超过 1 MiB 限制")
        }
        document.assistants?.profiles?.let { profiles ->
            val ids = profiles.map { io.github.mangi.eta.data.model.AssistantStorage.id(it.id) }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) {
                throw EtaBackupException("备份中的助手存在重复或无效 ID")
            }
        }
        document.assistantMemories.keys.forEach { io.github.mangi.eta.data.model.AssistantStorage.id(it) }
        BackupSubAgentConfig.validateExternalPreferences(document.agentPreferences, ConversationSubAgentPreferences())
        require(document.mcpServers.size <= 10_000) { "备份中的 MCP 服务器过多" }
        val mcpIds = document.mcpServers.map { it.id }
        if (mcpIds.size != mcpIds.toSet().size || mcpIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的 MCP 服务器存在重复或无效 ID")
        }
        // 配方只是数量元数据（数据随应用数据走），但要挡住伪造的巨大数量与负数。
        require(document.recipeCount in 0..ToolRecipeConstants.MAX_STORED_RECIPES) { "备份中的配方数量无效" }
        document.mediaIndex?.let { meta ->
            if (meta.modelId.isBlank() || meta.vectorDim !in 1..4096 || meta.count !in 0..BackupArchiveSafety.ENTRY_LIMIT) {
                throw EtaBackupException("备份中的媒体语义索引元数据无效")
            }
            if (meta.payloadEntry != EtaBackupMediaIndex.ENTRY) {
                throw EtaBackupException("备份中的媒体语义索引数据条目无效")
            }
        }
        document.models?.let(EtaModelBackup::validate)
    }

    /** 备份只覆盖一个 (modelId, vectorDim) 切片：优先当前模型/维度，为空则取存量最多的切片。 */
    private suspend fun mediaIndexSlice(context: Context, dao: MediaIndexDao): Pair<String, Int> {
        val modelId = EmbeddingModelStore.state.value.model.id
        val vectorDim = EmbeddingModelStore.vectorDim(context)
        if (dao.countForModel(modelId, vectorDim) > 0) return modelId to vectorDim
        val dominant = dao.all().groupingBy { it.modelId to it.vectorDim }.eachCount().maxByOrNull { it.value }?.key
        return dominant ?: (modelId to vectorDim)
    }

    /** 缩略图缓存目录：与 MediaExtraction 的 media-thumbs 一致，恢复后按归档里的原文件名重定位。 */
    private fun thumbDirectory(context: Context): File = File(context.applicationContext.cacheDir, "media-thumbs")

    /** 向量不进 manifest（受 MANIFEST_LIMIT 约束）：写独立条目 records.jsonl，缩略图写 media-index/thumbs/。 */
    private suspend fun exportMediaIndex(context: Context, writer: BackupZipWriter, meta: EtaBackupMediaIndex) {
        val rows = EtaDatabase.get(context).mediaIndexDao().listForModel(meta.modelId, meta.vectorDim)
        require(rows.size == meta.count) { "媒体索引在导出时发生变化" }
        writer.stream(meta.payloadEntry) { output -> writeMediaRecords(output, rows, withSlice = false) }
        writer.directory(EtaBackupMediaIndex.THUMBS_PREFIX, thumbDirectory(context))
    }

    private fun writeMediaRecords(output: OutputStream, rows: List<MediaIndexEntity>, withSlice: Boolean) {
        val writer = output.bufferedWriter(Charsets.UTF_8)
        rows.forEach { row ->
            writer.append(mediaRecordJson(row, withSlice))
            writer.newLine()
        }
        writer.flush()
    }

    private fun mediaRecordJson(row: MediaIndexEntity, withSlice: Boolean): String = lineJson.encodeToString(
        EtaBackupMediaRecord(
            sourceKey = row.sourceKey,
            modelId = if (withSlice) row.modelId else null,
            vectorDim = if (withSlice) row.vectorDim else null,
            kind = row.kind,
            displayName = row.displayName,
            mime = row.mime,
            sizeBytes = row.sizeBytes,
            modifiedAt = row.modifiedAt,
            contentHash = row.contentHash,
            durationMs = row.durationMs,
            width = row.width,
            height = row.height,
            frameTimeMs = row.frameTimeMs,
            thumbPath = row.thumbPath,
            indexedAt = row.indexedAt,
            // 存量 BLOB 就是 Float16；经 FloatArray 往返一次，导出前先验证半精度可解码。
            vector = Base64.encodeToString(Float16VectorCodec.encode(Float16VectorCodec.decode(row.vector)), Base64.NO_WRAP),
        ),
    )

    /** 回滚快照：整表（所有切片）都记下来，导入失败可把 media_index 精确还原到导入前状态。 */
    private suspend fun writeMediaSnapshot(context: Context, file: File) {
        val rows = EtaDatabase.get(context).mediaIndexDao().all()
        BackupDurability.mkdirs(requireNotNull(file.parentFile))
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            rows.forEach { row ->
                writer.append(mediaRecordJson(row, withSlice = true))
                writer.newLine()
            }
            writer.flush()
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
        BackupDurability.syncDirectory(requireNotNull(file.parentFile))
    }

    /** 读回滚快照；没有快照文件返回 null（旧版恢复日志：不动 media_index）。 */
    private fun readMediaSnapshot(operation: File): List<MediaIndexEntity>? {
        val file = File(operation, "previous-media-index.jsonl")
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return android.util.AtomicFile(file).openRead().use { input ->
            scanMediaRecords(input.bufferedReader(Charsets.UTF_8).lineSequence(), keep = true).rows
        }
    }

    /** records.jsonl 解析统计：预览只留计数/维度/键，导入保留实体。 */
    private class MediaRecordScan {
        val rows = mutableListOf<MediaIndexEntity>()
        val keys = hashSetOf<Triple<String, String?, Int?>>()
        val dims = hashSetOf<Int>()
        var count = 0
        var bytes = 0L
    }

    /**
     * 逐行解析 records.jsonl 并做行级校验（向量维度、sourceKey 唯一）。
     * 归档行的切片由 [meta] 统一给出；回滚快照行自带 modelId/vectorDim（[meta] 传 null）。
     */
    private fun scanMediaRecords(
        lines: Sequence<String>,
        keep: Boolean,
        meta: EtaBackupMediaIndex? = null,
        thumbRoot: File? = null,
        thumbNames: Set<String> = emptySet(),
    ): MediaRecordScan {
        val scan = MediaRecordScan()
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            scan.bytes += line.length
            require(scan.bytes <= BackupArchiveSafety.TOTAL_LIMIT) { "媒体索引备份超过大小限制" }
            scan.count++
            require(scan.count <= BackupArchiveSafety.ENTRY_LIMIT) { "媒体索引行数过多" }
            val record = runCatching { lineJson.decodeFromString<EtaBackupMediaRecord>(line) }
                .getOrElse { failure -> throw EtaBackupException("媒体索引备份行格式无效", failure) }
            require(record.sourceKey.isNotBlank() && record.sourceKey.length <= 4096) { "媒体索引 sourceKey 无效" }
            require(MediaKind.from(record.kind) != null) { "媒体索引类型无效：${record.kind}" }
            require(record.sizeBytes >= 0 && record.modifiedAt >= 0 && record.indexedAt >= 0) { "媒体索引元数据无效" }
            val vector = runCatching { Base64.decode(record.vector, Base64.NO_WRAP) }
                .getOrElse { failure -> throw EtaBackupException("媒体向量编码无效", failure) }
            require(vector.size in 2..8192 && vector.size % 2 == 0) { "媒体向量字节数无效" }
            val vectorDim = vector.size / 2
            require(record.vectorDim == null || record.vectorDim == vectorDim) { "媒体向量维度与行声明不一致" }
            require(scan.keys.add(Triple(record.sourceKey, record.modelId, record.vectorDim))) {
                "媒体索引 sourceKey 重复：${record.sourceKey}"
            }
            scan.dims += vectorDim
            if (keep) {
                val modelId = record.modelId ?: requireNotNull(meta?.modelId) { "媒体索引行缺少模型信息" }
                val dim = record.vectorDim ?: requireNotNull(meta?.vectorDim) { "媒体索引行缺少维度信息" }
                scan.rows += MediaIndexEntity(
                    sourceKey = record.sourceKey,
                    modelId = modelId,
                    vectorDim = dim,
                    kind = record.kind,
                    displayName = record.displayName,
                    mime = record.mime,
                    sizeBytes = record.sizeBytes,
                    modifiedAt = record.modifiedAt,
                    contentHash = record.contentHash,
                    durationMs = record.durationMs,
                    width = record.width,
                    height = record.height,
                    frameTimeMs = record.frameTimeMs,
                    thumbPath = record.thumbPath?.let { path ->
                        if (thumbRoot == null) {
                            path
                        } else {
                            // 缩略图随备份恢复到本机缓存目录：按归档里的原文件名重定位绝对路径。
                            val name = File(path).name
                            if (name in thumbNames) File(thumbRoot, name).absolutePath else null
                        }
                    },
                    // 经 FloatArray 往返，保证入库的半精度向量可解码且编码归一。
                    vector = Float16VectorCodec.encode(Float16VectorCodec.decode(vector)),
                    indexedAt = record.indexedAt,
                )
            }
        }
        return scan
    }

    /** 一致性校验：records 行数 = count、向量维度 = vectorDim（sourceKey 唯一已在逐行解析时保证）。 */
    private fun requireMediaRecordsMatch(scan: MediaRecordScan, meta: EtaBackupMediaIndex) {
        require(scan.count == meta.count) { "媒体索引行数与清单不一致" }
        require(scan.dims == setOf(meta.vectorDim)) { "媒体向量维度与清单不一致" }
    }

    /**
     * 重关联：重装后 MediaStore / 路径型 sourceKey 会变，无法只靠 key 恢复。
     * 用重新扫描到的文件按 contentHash（优先）、其次 (sizeBytes, modifiedAt) 回配，
     * 能配上的复用向量（写回新 sourceKey），配不上的保留原行待下次索引补齐。
     */
    private suspend fun relinkMediaRecords(
        context: Context,
        rows: List<MediaIndexEntity>,
        restored: Map<File, File>,
    ): List<MediaIndexEntity> {
        if (rows.isEmpty()) return rows
        val app = context.applicationContext
        val thumbRoot = thumbDirectory(app).absolutePath + File.separator
        val candidates = mutableListOf<MediaSourceScanner.Candidate>()
        // 归档里即将落盘的媒体：恢复后的目标路径就是新的 sourceKey，内容哈希读暂存文件（内容与落盘一致）。
        restored.forEach { (target, source) ->
            if (target.absolutePath.startsWith(thumbRoot)) return@forEach
            val kind = MediaSourceScanner.kindForName(target.name) ?: return@forEach
            candidates += MediaSourceScanner.Candidate(
                sourceKey = target.absolutePath,
                kind = kind,
                displayName = target.name,
                mime = MediaSourceScanner.mimeForName(target.name) ?: "application/octet-stream",
                sizeBytes = source.length(),
                modifiedAt = source.lastModified(),
                filePath = source.absolutePath,
            )
        }
        // 其余可重新扫描到的媒体（MediaStore、附件缓存、工作区……）。
        val dao = EtaDatabase.get(app).mediaIndexDao()
        val sources = dao.sources().filter { it.enabled }
            .ifEmpty { MediaSourceScanner.recommendedSources(app).filter { it.enabled } }
        sources.forEach { source -> candidates += MediaSourceScanner.scan(app, source) }
        val byKey = candidates.associateBy { it.sourceKey }
        val bySize = candidates.groupBy { it.sizeBytes }
        val bySizeTime = LinkedHashMap<Pair<Long, Long>, MediaSourceScanner.Candidate>()
        candidates.forEach { candidate -> bySizeTime.putIfAbsent(candidate.sizeBytes to candidate.modifiedAt, candidate) }
        val restoredKeys = restored.keys.mapTo(hashSetOf()) { it.absolutePath }
        val hashes = hashMapOf<String, String?>()
        val claimed = hashSetOf<String>()
        return rows.map { row ->
            val unchanged = byKey[row.sourceKey] != null ||
                (!row.sourceKey.startsWith("content:") && restoredKeys.contains(row.sourceKey)) ||
                (!row.sourceKey.startsWith("content:") && File(row.sourceKey).isFile)
            val matched: String? = when {
                unchanged -> row.sourceKey
                else -> {
                    // 内容相同的文件必然同大小：只对同大小候选做哈希，兼顾「contentHash 优先」与开销。
                    val byHash = row.contentHash.takeIf { it.isNotBlank() }?.let { hash ->
                        bySize[row.sizeBytes]?.firstOrNull { candidate ->
                            candidate.sourceKey !in claimed &&
                                hashes.getOrPut(candidate.sourceKey) {
                                    MediaExtraction.contentHash(app, candidate)
                                } == hash
                        }?.sourceKey
                    }
                    byHash ?: bySizeTime[row.sizeBytes to row.modifiedAt]
                        ?.takeIf { it.sourceKey !in claimed }?.sourceKey
                }
            }
            if (matched == null) {
                row
            } else {
                claimed += matched
                row.copy(sourceKey = matched)
            }
        }
    }

    private fun EtaBackupDocument.toBackupSummary(): EtaBackupSummary = EtaBackupSummary(
        providerCount = providers.size,
        modelCount = providers.sumOf { it.models.size },
        conversationCount = conversations.size,
        messageCount = if (messagesExternal) messageCount else messages.size,
        memoryBytes = memoryMd.toByteArray().size,
        assistantCount = assistants?.profiles?.size ?: 0,
        mcpCount = mcpServers.size,
        skillCount = skillFiles.keys.map { it.substringBefore('/') }.filter { it.isNotBlank() }.toSet().size,
        recipeCount = recipeCount,
        attachmentCount = attachmentCount + importedFileCount,
        includedLinuxEnvironment = includeLinuxEnvironment,
        mediaIndexCount = mediaIndex?.count ?: 0,
        mediaIndexModelId = mediaIndex?.modelId,
        mediaIndexDim = mediaIndex?.vectorDim ?: 0,
        downloadedModelCount = models?.models?.size ?: 0,
        downloadedModelBytes = models?.models?.sumOf { model -> model.files.sumOf { it.bytes } } ?: 0L,
        providersNeedingKey = if (apiKeysIncluded) {
            0
        } else {
            providers.count { it.provider.authMode == ProviderAuthModes.API_KEY }
        },
    )

    private fun EtaConversationExport.toConversationSummary(): EtaBackupSummary = EtaBackupSummary(
        providerCount = 0,
        modelCount = 0,
        conversationCount = 1,
        messageCount = messages.size,
        memoryBytes = 0,
        attachmentCount = attachmentCount,
    )

    private fun ProviderEntity.withoutOAuthSecrets(): ProviderEntity =
        if (authMode == ProviderAuthModes.API_KEY) this else copy(apiKey = "")

    /**
     * 导出时的密钥策略。
     *
     * 带上密钥：只剥 OAuth 衍生数据，API Key 原样进清单（与 schema 8 一致）。
     * 不带密钥（默认）：所有认证模式的 `apiKey` 一律清空，提供商其余配置照常备份。
     *
     * 光清 `apiKey` 不够：中转站常在自定义请求头 / 请求体里手写密钥，那两处一并脱敏。
     */
    private fun ProviderEntity.withApiKeyPolicy(includeApiKey: Boolean): ProviderEntity =
        if (includeApiKey) withoutOAuthSecrets() else copy(
            apiKey = "",
            customHeadersJson = redactHeaders(customHeadersJson),
            customBodyJson = redactBodies(customBodyJson),
        )

    /**
     * 清空自定义请求头里像密钥的值。
     *
     * `authorization` / `x-api-key` 已在 `CustomHeaderFilter` 的禁止名单里，但
     * `api-key` / `x-goog-api-key` / `x-auth-token` 这类中转站常用写法仍然可以设置。
     * 只清值、留名字：导入后用户能看到「这里原本有个 header」，重填一次即可，不必猜配置。
     * 解析失败就写空数组——宁可丢配置，也不把可能含密钥的原文写进包。
     */
    private fun redactHeaders(raw: String): String {
        if (raw.isBlank()) return raw
        val headers = runCatching {
            compactJson.decodeFromString(ListSerializer(CustomHeader.serializer()), raw)
        }.getOrElse { return "[]" }
        return compactJson.encodeToString(
            ListSerializer(CustomHeader.serializer()),
            headers.map { header -> if (isSecretHeaderName(header.name)) header.copy(value = "") else header },
        )
    }

    /**
     * 清空自定义请求体里像密钥的字段值。
     *
     * 判定用「归一化后完全相等」而不是包含：`max_tokens` / `max_completion_tokens`
     * 归一化后是 `maxtokens` / `maxcompletiontokens`，不会误伤，否则导入后请求直接失败。
     */
    private fun redactBodies(raw: String): String {
        if (raw.isBlank()) return raw
        val bodies = runCatching {
            compactJson.decodeFromString(ListSerializer(CustomBody.serializer()), raw)
        }.getOrElse { return "[]" }
        return compactJson.encodeToString(
            ListSerializer(CustomBody.serializer()),
            bodies.map { body ->
                if (normalizeName(body.key) in BODY_SECRET_KEYS) body.copy(value = JsonNull) else body
            },
        )
    }

    private fun isSecretHeaderName(name: String): Boolean {
        val normalized = normalizeName(name)
        return normalized.contains("apikey") || normalized.contains("token") ||
            normalized.contains("secret") || normalized.contains("authorization") ||
            normalized.endsWith("auth")
    }

    private fun normalizeName(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    private fun ProviderEntity.awaitingCredentialCleanup(exactRestore: Boolean): ProviderEntity = when {
        exactRestore -> withoutOAuthSecrets()
        // 即使提交后进程中断，导入的相同 ID 也不能接上设备中尚未清除的旧授权。
        ProviderAuthModes.isOAuth(authMode) -> copy(authMode = ProviderAuthModes.API_KEY, apiKey = "", isEnabled = false)
        else -> withoutOAuthSecrets()
    }
}

private val LINUX_TAR_SKIP = setOf("proc", "sys", "dev", "run", "tmp")

private fun exportLinuxEnvironments(context: Context, writer: BackupZipWriter) {
    LinuxDistribution.entries.forEach { distribution ->
        val envDir = LinuxEnvironmentPaths.environmentDir(context, distribution)
        if (!envDir.exists()) return@forEach
        val backend = LinuxEnvironmentPaths.backendOf(
            LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath,
        )
        val entryName = "linux/environments/${backend.wireName}/${distribution.wireName}.tar"
        val packed = writer.stream(entryName) { output ->
            if (backend == LinuxExecutionBackend.PROOT && canWalk(envDir)) {
                writeTar(envDir, output)
            } else {
                streamBusyBoxTar(envDir, output)
            }
        }
        if (packed <= 0L) {
            throw EtaBackupException("无法打包 Linux 环境：${distribution.wireName}")
        }
    }
}

private fun restoreLinuxTar(
    context: Context,
    input: java.io.InputStream,
    backendName: String,
    distributionName: String,
) {
    val backend = LinuxExecutionBackend.entries.firstOrNull { it.wireName == backendName }
        ?: throw EtaBackupException("备份中的 Linux 后端无效：$backendName")
    val distribution = LinuxDistribution.entries.firstOrNull { it.wireName == distributionName }
        ?: throw EtaBackupException("备份中的 Linux 发行版无效：$distributionName")
    val destination = LinuxEnvironmentPaths.environmentDir(context, distribution, backend)
    destination.parentFile?.mkdirs()
    if (canWrite(destination.parentFile ?: destination) && (backend == LinuxExecutionBackend.PROOT || canWalk(destination))) {
        if (destination.exists() && !destination.deleteRecursively()) {
            deleteTreeAsRoot(destination)
        }
        destination.mkdirs()
        extractTarStream(input, destination)
        return
    }
    if (!RootAccess.isGranted) {
        throw EtaBackupException("导入完整 Linux 环境需要 Root")
    }
    val tarFile = File(context.cacheDir, "eta-linux-restore-${distribution.wireName}-${System.nanoTime()}.tar")
    try {
        tarFile.outputStream().buffered().use { input.copyTo(it) }
        if (tarFile.length() <= 0L) {
            throw EtaBackupException("备份中的 Linux 环境是空的")
        }
        if (!extractTarFileAsRoot(tarFile, destination)) {
            throw EtaBackupException("无法把 Linux 环境安装到 ${destination.absolutePath}")
        }
    } finally {
        tarFile.delete()
    }
}

private fun skipLinuxPath(relative: String): Boolean {
    val parts = relative.split('/')
    return parts.size >= 2 && parts[0] == "rootfs" && parts[1] in LINUX_TAR_SKIP
}

private fun writeTar(source: File, output: java.io.OutputStream) {
    TarArchiveOutputStream(output).apply {
        setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
    }.let { tar ->
        source.walkTopDown().onEnter { dir ->
            !skipLinuxPath(dir.relativeTo(source).invariantSeparatorsPath)
        }.forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath.ifBlank { "." }
            if (relative == "." || skipLinuxPath(relative)) return@forEach
            val entry = TarArchiveEntry(file, relative)
            tar.putArchiveEntry(entry)
            if (file.isFile) file.inputStream().use { it.copyTo(tar) }
            tar.closeArchiveEntry()
        }
        tar.finish()
    }
}

private fun extractTarStream(input: java.io.InputStream, destination: File) {
    val root = destination.canonicalFile
    val tar = TarArchiveInputStream(input)
    while (true) {
        val entry = tar.nextEntry ?: break
        val relative = normalizeTarPath(entry.name) ?: continue
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw EtaBackupException("Linux 归档路径越界")
        }
        when {
            entry.isDirectory -> target.mkdirs()
            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                Files.deleteIfExists(target.toPath())
                runCatching {
                    Files.createSymbolicLink(target.toPath(), java.nio.file.Path.of(entry.linkName))
                }.getOrElse {
                    target.writeText("")
                }
            }
            entry.isFile -> {
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
                if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
            }
        }
    }
}

private fun streamBusyBoxTar(source: File, output: java.io.OutputStream) {
    if (!RootAccess.isGranted) {
        throw EtaBackupException("打包完整 Linux 环境需要 Root")
    }
    val script = AndroidBusyBox.discoveryScript() +
        "; [ -n \"${'$'}eta_busybox\" ] || exit 127; " +
        "\"${'$'}eta_busybox\" tar -C " + shellQuote(source.absolutePath) +
        " --exclude=rootfs/proc --exclude=rootfs/sys --exclude=rootfs/dev --exclude=rootfs/run --exclude=rootfs/tmp" +
        " -cf - ."
    val process = RootSu.process(script).redirectErrorStream(false).start()
    val stderr = StringBuilder()
    val reader = Thread {
        runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
    }.apply { isDaemon = true; start() }
    try {
        process.inputStream.copyTo(output)
        val finished = process.waitFor(20, TimeUnit.MINUTES)
        reader.join(5_000)
        val code = if (finished) process.exitValue() else -1
        if (!finished || code != 0) {
            AndroidAgentLogger.warn(
                "Backup linux tar failed: source=${source.absolutePath} exit=$code stderr=${stderr.toString().take(500)}",
            )
            throw EtaBackupException(
                "无法打包 Linux 环境（退出码 $code）${stderr.toString().trim().take(180).let { if (it.isBlank()) "" else "：$it" }}",
            )
        }
    } finally {
        runCatching { process.destroyForcibly() }
    }
}

private fun extractTarFileAsRoot(archive: File, destination: File): Boolean {
    destination.parentFile?.mkdirs()
    val script = AndroidBusyBox.discoveryScript() +
        "; [ -n \"${'$'}eta_busybox\" ] || exit 127; " +
        "\"${'$'}eta_busybox\" mkdir -p -- " + shellQuote(destination.parentFile?.absolutePath.orEmpty()) + "; " +
        "\"${'$'}eta_busybox\" rm -rf -- " + shellQuote(destination.absolutePath) + "; " +
        "\"${'$'}eta_busybox\" mkdir -p -- " + shellQuote(destination.absolutePath) + "; " +
        "\"${'$'}eta_busybox\" tar -C " + shellQuote(destination.absolutePath) + " -xf " + shellQuote(archive.absolutePath)
    return runRoot(script, timeoutMinutes = 20)
}

private fun normalizeTarPath(raw: String): String? {
    val relative = raw.trim().trimStart('/').replace('\\', '/')
    if (relative.isBlank() || relative == ".") return null
    val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.any { it == ".." }) throw EtaBackupException("Linux 归档路径越界")
    return segments.joinToString("/")
}

private fun deleteTreeAsRoot(target: File): Boolean {
    if (!target.exists()) return true
    return runRoot("rm -rf -- ${shellQuote(target.absolutePath)}", timeoutMinutes = 5)
}

private fun runRoot(command: String, timeoutMinutes: Long): Boolean {
    if (!RootAccess.isGranted) return false
    val process = runCatching { RootSu.process(command).redirectErrorStream(true).start() }.getOrNull() ?: return false
    return try {
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } finally {
        runCatching { process.destroy() }
    }
}

private fun canWalk(root: File): Boolean = root.canRead() && (root.listFiles()?.firstOrNull()?.canRead() != false)

private fun canWrite(root: File): Boolean = root.parentFile?.canWrite() == true && (!root.exists() || root.canWrite())

private fun encodeFiles(files: Map<String, ByteArray>): Map<String, String> =
    files.mapValues { (_, bytes) -> Base64.encodeToString(bytes, Base64.NO_WRAP) }

private fun decodeFiles(files: Map<String, String>): Map<String, ByteArray> =
    files.mapValues { (_, encoded) -> Base64.decode(encoded, Base64.NO_WRAP) }

private fun duBytes(root: File): Long {
    if (!root.exists() || !RootAccess.isGranted) return 0L
    val process = runCatching {
        RootSu.process("du -sb -- ${shellQuote(root.absolutePath)}").redirectErrorStream(true).start()
    }.getOrNull() ?: return 0L
    return try {
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            0L
        } else {
            process.inputStream.bufferedReader().readText().trim().substringBefore('\t').toLongOrNull() ?: 0L
        }
    } finally {
        runCatching { process.destroy() }
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun directorySize(root: File): Long {
    if (!root.exists() || !root.canRead()) return 0L
    return runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
}

private fun countFiles(root: File): Int {
    if (!root.exists()) return 0
    return runCatching { root.walkTopDown().count { it.isFile } }.getOrDefault(0)
}
