# 本地多模态语义搜索（EmbeddingGemma 2）设计方案

## 1. 背景与目标

Eta 已具备本地离线语音、终端文件管理与设备操作能力，但**没有跨模态的语义检索**：用户无法用自然语言找「去年在海边吹蜡烛的照片」「那段讲降噪算法的视频」「开头是钢琴的录音」，AI 也无法主动检索本机媒体并与文件管理能力组合。

本方案引入 **EmbeddingGemma 2**（Google DeepMind，2026-10 发布，Apache 2.0）作为端侧统一多模态 embedding 模型，把文本 / 代码 / 图像 / 视频帧 / 音频映射到同一向量空间，实现完全离线的自然语言检索。

目标：

1. **GUI**：用户能用自然语言搜索本机图片、视频、音频与文件，并可按类型过滤、预览、打开。
2. **AI 工具**：新增模型可调用的 `search_media` 工具，返回可解析的媒体引用，使模型能把它与已有的文件管理能力（`read_image`、终端文件操作、会话管理等）**链式组合**。
3. **可迁移**：数据导出功能能一并导出 embedding 生成结果，重装后无需重新嵌入即可恢复索引。

非目标（本期）：

- 不做云端向量服务，全部本地离线。
- 不替换现有 Provider 聊天模型；embedding 只服务于检索，不进入对话上下文。
- 不追求全盘扫描；只索引用户确认过的范围。

---

## 2. 技术可行性（已核实）

LiteRT-LM v0.18.0 提供 Android 的 **Kotlin API**，官方示例：

```kotlin
import com.google.ai.edge.litertlm.*

val config = EmbeddingEngineConfig(
    modelPath = modelFile.absolutePath,
    backend = Backend.GPU(),          // CPU 回退可用
    visionBackend = Backend.GPU(),
    audioBackend = Backend.GPU(),
)
EmbeddingEngine(config).use { engine ->
    engine.initialize()                       // 建议在后台线程，加载可能达数秒
    val emb = engine.computeEmbedding(
        listOf(InputData.Text("task: search query | text: 海边 吹蜡烛")),
    ).embedding                               // FloatArray
}
```

依赖（Google Maven，非 GitHub，无慢速下载问题）：

```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
```

模型文件为 `.litertlm`（如 `embeddinggemma-2-740m.litertlm`），来自 Hugging Face `litert-community`，**按需下载**，不入 APK。

### 2.1 必须遵守的 instruction 前缀

EmbeddingGemma 2 使用**非对称任务前缀**，索引侧与查询侧前缀不同，混用会显著降低召回：

| 用途 | 前缀 | 本方案对应 |
|---|---|---|
| Document Indexing | `task: search result \| text: ` | 所有入库的媒体/文本 |
| Search Query | `task: search query \| text: ` | 用户的搜索词、AI 工具的 query |
| Classification | `task: classification \| text: ` | （保留，暂不用） |
| Clustering | `task: clustering \| text: ` | （保留，暂不用） |
| Symmetric Similarity | `task: sentence similarity \| text: ` | （保留，暂不用） |

输入字符串需先去除首尾空白再拼接前缀。此规则由 `EtaEmbeddingEngine` 统一实现并加单元测试。

### 2.2 模型变体与内存

| 变体 | 模态 | 量化后近似 | 用途 |
|---|---|---|---|
| 全模态 740M | 文本+图+视频帧+音频 | 活跃 RAM 数百 MB | **默认**（用户已确认） |
| 纯文本 270M | 文本 | ~191MB 活跃 RAM | 低内存设备可选，关闭图像/音频检索 |

- 默认：**全模态 740M**；设置页提供切换，选择「纯文本」时仅用文本能力（图像/音频检索入口隐藏或降级）。
- 支持 Matryoshka 截断：默认存 **768 维**，可配置 512 / 256 维以省存储。
- 引擎**懒加载 + 空闲卸载**：仅在首次检索/索引时初始化；长时间空闲释放。每次进入检索页不重复加载。

---

## 3. 总体架构

统一原则：**`MediaSearchService` 是唯一检索入口**，GUI 搜索框与 AI 工具都调用它，保证「用户搜到的」和「AI 搜到的」结果完全一致。

```
┌─ GUI 层 ───────────────────────────────────────────────┐
│ MediaSearchScreen（自然语言搜索）/ MediaIndexSettingsScreen │
├─ 工具层 ───────────────────────────────────────────────┤
│ search_media（可链式调用 read_image / 终端文件操作 / …）      │
├─ 检索层 ───────────────────────────────────────────────┤
│ MediaSearchService：query → 向量 → 余弦 top-K（统一入口）      │
├─ 索引层 ───────────────────────────────────────────────┤
│ MediaIndexer：扫描 → 抽取 → 嵌入 → 落库（增量、可取消）        │
│ MediaSourceScanner / MediaExtraction                     │
├─ 存储层 ───────────────────────────────────────────────┤
│ Room v35：media_index / media_source + 向量 BLOB           │
├─ 运行时层 ─────────────────────────────────────────────┤
│ EtaEmbeddingEngine（封装 LiteRT-LM EmbeddingEngine）        │
│ EmbeddingModelCatalog / EmbeddingModelStore（下载+校验）     │
└────────────────────────────────────────────────────────┘
```

---

## 4. 运行时层

### 4.1 `EtaEmbeddingEngine`

`agent/media/index/EtaEmbeddingEngine.kt`，进程内单例（`Mutex` 串行 + 引用计数生命周期）。

职责：

- **模型加载/卸载**：`ensureLoaded()` / `releaseIfIdle()`；后台线程 `initialize()`；`Backend.GPU()` 为主，初始化失败自动回退 `Backend.CPU()`。
- **多模态输入构造**：
  - 文本：`InputData.Text(prefix + text)`
  - 图像：`InputData.Image(bytes)`（JPEG/PNG 原始字节，来自解码或缩略图）
  - 音频：`InputData.Audio(pcmBytes)`（16kHz 单声道 PCM，分窗）
  - 视频：按关键帧序列拆成多张 `InputData.Image`，聚合（见 4.3）
- **instruction 前缀**：`embedDocument(input)` / `embedQuery(text)` / `embedMedia(...)`，内部固定前缀，禁止调用方传裸文本。
- **维度**：`truncateDim`（默认 768，可选 512/256）+ **L2 归一化**（归一化后余弦=点积，简化检索）。
- **编码**：存储用 Float16（半精度）BLOB，读取时转 Float；省一半存储。
- **并发**：索引串行；检索可与加载互斥但整体串行，避免多线程竞争同一引擎。

### 4.2 `EmbeddingModelCatalog` + `EmbeddingModelStore`

仿 `SpeechModelCatalog` / `OfflineSpeechPack`：

- Catalog 声明每个变体：`id`、`revision`、`.litertlm` 文件字节数、SHA-256、多个下载源（Hugging Face + 镜像）。
- Store 按需下载、校验、落盘到 `filesDir/embedding/<modelId>/<revision>/`，暴露下载状态给设置页。
- 默认模型：全模态 740M；可选：纯文本 270M。
- 复用现有下载工具与「SHA-256 全量校验」约定，禁止引入未校验的第三方二进制。

ProGuard：保留 `com.google.ai.edge.litertlm.**`。

### 4.3 视频/音频抽取约定

- **视频**：`MediaMetadataRetriever` 按时间均匀抽取关键帧（默认每 N 秒 1 帧，上限如 8 帧），逐帧嵌入后**平均 + 归一化**为视频向量；同时保留帧时间戳用于「时刻检索」。低内存设备或纯文本变体下跳过视频。
- **音频**：解码为 16kHz 单声道 PCM，按固定窗口（如 30s，上限如 5 分钟）切片嵌入后聚合。
- 所有抽取有**硬预算**（帧数、窗口数、并发），避免大文件 OOM。

---

## 5. 存储层（Room v35）

修改 `EtaDatabase.kt`：`version 34 → 35`，注册新实体与迁移。

### 5.1 `media_index`

| 字段 | 说明 |
|---|---|
| `sourceKey`（PK 部分） | 稳定标识：MediaStore 用 `content://` id；SAF 用 tree uri + document id；附件用绝对路径 |
| `kind` | `image` / `video` / `audio` / `document` |
| `displayName` / `mime` | 展示与过滤 |
| `sizeBytes` / `modifiedAt` | 增量判断 |
| `contentHash` | 强去重与「重装重关联」依据（见 §9） |
| `durationMs` / `width` / `height` | 元数据 |
| `frameTimeMs` | 视频时刻检索的帧时间戳（可空） |
| `thumbPath` | 缩略图缓存路径 |
| `vectorDim` / `vector`（BLOB, Float16） | 归一化向量 |
| `modelId` | 生成该向量的模型变体；换模型后据此判定需重嵌入 |
| `indexedAt` | 时间戳 |

主键建议 `(sourceKey, modelId, vectorDim)`；`kind`、`modifiedAt` 建索引。

### 5.2 `media_source`

用户确认过的扫描源：类型（mediastore / saf-tree / shared-folder / attachment-cache）、uri/路径、启用状态、上次扫描时间、统计（条目数）。

### 5.3 迁移与测试

- 新增 `Migration(34, 35)`，仅建表（不影响既有数据）。
- 更新 `EtaDatabaseMigrationTest`。

---

## 6. 索引层

- `MediaSourceScanner.kt`：按 `media_source` 逐源产出候选项。
  - MediaStore：`Images`/`Video`/`Audio` 查询。
  - SAF tree：`DocumentsContract` 遍历。
  - 共享文件夹：`SharedFolderMounts`。
  - 附件缓存：`AgentChatImageCache` + `workspace/imports`。
- `MediaExtraction.kt`：图片降采样、视频抽帧、音频切窗、文档文本抽取（本期：txt/md 直读；PDF/DOCX 放后续阶段）。
- `MediaIndexer.kt`：批处理 + **增量**（`(size, mtime, contentHash)` 未变则跳过）+ 变更项更新、删除项清理（向量 + 缩略图）+ 进度回调 + 可取消。
- 调度：**手动「立即索引」** + 可选周期任务。宿主复用现有前台任务机制（`AgentExecutionService` 的 `specialUse`），避免引入新服务；如后续需要系统级调度再评估 WorkManager。

---

## 7. 扫描范围推荐与用户确认

### 7.1 推荐项（面向用户展示，含预估条目数与权限提示）

| 范围 | 推荐 | 权限/前置 | 默认 |
|---|---|---|---|
| A. 系统媒体库（图片/视频/音频，MediaStore） | 推荐 | `READ_MEDIA_IMAGES/VIDEO/AUDIO`（Android 14 可部分授权） | **开启** |
| B. 会话附件缓存（chat-images / imports） | 推荐 | 无需额外权限（App 私有） | **开启** |
| C. Linux 共享文件夹 | 可选 | 复用已配置的 `SharedFolderMounts` | 关闭 |
| D. 用户自选文件夹（SAF 目录） | 可选 | 逐目录授权，持久化 URI | 关闭 |
| E. 终端工作区文件 | 可选 | App 私有，无限权 | 关闭 |

### 7.2 确认流程（必须先确认）

1. 首次启用检索（或在设置页首次配置）时弹出确认弹窗，列出推荐范围、**预估条目数/体积**、需要的权限。
2. 弹窗内可逐项开关；`D` 项提供「添加文件夹」走 SAF 选择器；`A` 项触发运行时权限申请。
3. **只有用户确认后才开始首次索引**；未确认前检索页显示引导态。
4. 用户可随时在设置页**增删范围**、重扫或清空索引。

统一使用现成自适应组件（`EtaDialog` / `EtaSegmentedColumn` / `EtaSegmentedRow` 等），与设置页风格一致，带轻触触觉反馈。

附加过滤（默认内置，避免噪声）：跳过隐藏文件、极小缩略图、`.nomedia` 目录、系统缓存；可设最小尺寸阈值。

---

## 8. 检索层

`MediaSearchService.kt`（唯一入口）：

- `search(query, kinds, sources, limit)`：`embedQuery` → 与库中向量做**归一化点积**（暴力余弦，万级条目在手机可接受；后续可切 ANN/sqlite-vec）→ 按 `kind`/来源过滤 → 返回 top-K + 分数。
- `findSimilar(ref, limit)`：以某媒体自身向量找相近项（「找相似」）。
- `searchVideoMoments(query, limit)`：视频按帧检索，返回视频 + 命中时间戳。
- 过滤维度必须与库中向量维度一致（`vectorDim` 过滤），避免跨模型/跨维度误比。

规模路径：万级以内暴力检索足够；超过阈值再引入 ANN（HNSW/sqlite-vec），接口不变。

---

## 9. 备份 / 导出集成（含 embedding 结果）

用户要求：**导出能包含 embedding 生成结果，重装后可恢复**。

### 9.1 导出

- `EtaBackupDocument.SCHEMA_VERSION`：`4 → 5`；新增可选字段 `mediaIndex: EtaBackupMediaIndex?`（仅元数据：`modelId`、`vectorDim`、`count`、`payloadEntry`）。
- 新增导出选项 `EtaBackupExportOptions.includeEmbeddings: Boolean`（默认 **开启**，与「包含 Linux 环境」并列的独立类别），`DataBackupScreen` 的「备份内容」分组里对应一个 `EtaSegmentedCheckboxRow` + 体积预估。
- 向量不塞进 manifest（避免超出 `BackupArchiveSafety.MANIFEST_LIMIT`），而是写成独立归档条目 `media-index/records.jsonl`（每行一条：`sourceKey/kind/contentHash/元数据/Base64(Float16 向量)`），沿用 `BackupZipWriter` 的既有写法。
- 缩略图可选择性一并导出到 `media-index/thumbs/`（复用 `writer.directory`）。
- `validate()` 增加：行数 = `count`、维度 = `vectorDim`、`sourceKey` 唯一等一致性校验；`inspect()` 预览也读元数据。

### 9.2 导入 / 重装恢复

- 导入时把 `media-index/records.jsonl` 解析并**事务内**写入 `media_index` 表。
- **重关联**：重装后 MediaStore/SAF 的 `sourceKey` 变化，无法只靠 key 恢复 → 以 **`contentHash`（优先）+ `(size, mtime)`** 回配到重新扫描到的文件；能配上的直接复用向量（跳过嵌入），配不上的在下次索引补齐。
- 需要重新授权 SAF 目录的部分，恢复后由用户在引导页重新授权，再触发一次轻量重关联扫描。
- 回滚路径（`previous.json` / journal）同样纳入 `mediaIndex`，保持导入失败可完整回退。

---

## 10. AI 工具层（与文件管理能力打通）

工具名：**`search_media`**，归入新的「设备媒体工具」组，受新开关 `AGENT_MEDIA_SEARCH_TOOLS` 约束（默认关闭）。

接入点（对齐现有实现风格）：

- `agent/model/AgentMediaSearchToolCatalog.kt`：用 `AgentToolSchema.function` 声明 schema；在 `AgentToolCatalog` 注册使其对模型可见。
- `agent/tool/AgentMediaSearchTools.kt`：执行器，持有 `Context` 与 `MediaSearchService`。
- `AgentLocalTools`：实例化 + 在 `execute(toolCall)` 的 `when` 分支路由（参考 `eta_manage_models` 等分支）。
- `AgentToolRequirements`：登记为 `RootRequirement.NONE`，并声明所需普通系统授权（媒体读权限）；未登记不能进入模型目录。
- `Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS` + 字符串资源。

动作：

| action | 说明 |
|---|---|
| `search` | query（必填）、`kinds[]`、`sources[]`、`limit`（默认 ~10） |
| `find_similar` | 以某 `path/uri` 为参考找相近媒体 |
| `list_sources` | 返回已配置扫描源及统计 |
| `index_status` | 返回索引规模、模型、维度、上次索引时间、是否就绪 |
| `reindex` | 触发（增量）重建索引，可带范围参数 |

**返回结构**（关键：给出可直接被现有工具消费的引用）：

```json
{
  "results": [
    {
      "kind": "video",
      "display_name": "beach.mp4",
      "path": "/storage/emulated/0/DCIM/beach.mp4",
      "content_uri": "content://media/external/video/media/1234",
      "score": 0.83,
      "modified_at": 1700000000,
      "duration_ms": 45000,
      "frame_time_ms": 12000
    }
  ],
  "model": "embeddinggemma-2-740m",
  "dim": 768
}
```

**与现有文件管理能力组合**：工具只负责「找到并返回引用」，后续动作交给已有能力，无需新增文件工具：

- 看图/看视频帧 → `read_image`
- 复制 / 移动 / 重命名 / 打开 → `terminal`（Android 或 Linux 环境）
- 关联到会话、整理记录 → `eta_manage_history`
- 敏感媒体（含音频/视频）按需走敏感读工具确认开关。

工具描述中明确：结果里的 `path`/`content_uri` 可被其他工具直接使用，鼓励链式调用；未开启索引时返回明确错误码 `MEDIA_INDEX_NOT_READY`。

---

## 11. GUI 设计

### 11.1 检索页 `MediaSearchScreen`

- 顶部 `ProviderSearchField`（复用现成搜索组件）。
- 类型过滤 chips：全部 / 图片 / 视频 / 音频 / 文件。
- 结果网格/列表：缩略图、时长或尺寸、命中分数；视频「时刻」结果标注时间戳，可跳帧预览。
- 点击：预览或打开（复用现有图像/视频预览与打开链路）。
- 空态：未配置扫描源时引导到设置页；未建索引时提示「去建立索引」。

### 11.2 设置页 `MediaIndexSettingsScreen`

- 模型：当前变体（全模态/纯文本）、下载/校验状态、切换、存储占用。
- 扫描源：推荐项展示 + 开关 + 自选文件夹（SAF）；预估条目数/体积。
- 动作：立即索引、重建、清空、后台索引开关。
- 全部走自适应组件与现有设置页写法。

### 11.3 导航与权限

- `AppRoute` 新增可序列化的 `MediaSearch`、`MediaIndexSettings`；在 `AgentNavigator` 的路由注册处接入 composable（沿用现有 `AppRoute.*::class` 类型安全导航）；在 `SettingsScreen` 增加入口。
- Manifest：`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`，Android 14+ 追加 `READ_MEDIA_VISUAL_USER_SELECTED`（部分授权）。
- 采用**按需申请**：进入检索/配置时才请求，拒绝后仍可用「仅附件缓存」等无限权范围。

---

## 12. 权限与隐私

- 全流程离线，不上传任何媒体或向量。
- 默认只覆盖 MediaStore + 用户显式授权的目录，不申请全盘访问；Root 不作为前提。
- 索引数据仅存 App 私有目录与 Room；导出时由用户显式选择是否包含向量。
- 结果仅供本地 GUI 与本地 Agent 使用；向量不进入模型对话上下文。

---

## 13. 性能与内存预算

- 引擎懒加载 + 空闲卸载；索引串行；检索与索引互斥。
- 抽取有硬预算（视频帧数、音频窗口数、单文件大小上限）。
- 存储：Float16 + 可截断维度；默认 768，允许 512/256。
- 低内存设备可切纯文本变体并关闭视频/音频索引。
- 批量落库分页提交，避免长事务。

---

## 14. 许可证与第三方声明

- EmbeddingGemma 2：Apache 2.0。
- LiteRT-LM Android 运行时：按其许可。
- 需更新 `docs/THIRD_PARTY_NOTICES.md`，补充上述条目与模型来源/哈希。

---

## 15. 测试计划

- `EtaEmbeddingEngineTest`：instruction 前缀正确性、归一化、维度截断、CPU 回退。
- `MediaSearchRankingTest`：给定构造向量的排名与过滤（含跨 kind 过滤、维度一致性）。
- `MediaIndexIncrementalTest`：size/mtime/contentHash 未变跳过、变更更新、删除清理。
- `EtaDatabaseMigrationTest`：34→35 迁移。
- `EtaBackupMediaIndexTest`：导出→导入往返、向量精度、`contentHash` 重关联、回滚路径。
- `AgentMediaSearchToolTest`：schema 校验、动作路由、索引未就绪错误码、权限未授予时的拒绝。

最终验证命令（沿用项目统一命令）：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

---

## 16. 分阶段实施

| 阶段 | 内容 |
|---|---|
| P0 运行时打通 | 依赖 + 模型下载校验 + 文本嵌入冒烟（GPU/CPU、内存、前缀） |
| P1 MVP | MediaStore 图片/视频索引 + 文本→多媒体语义搜索 GUI + `search_media` 工具（跑通「AI 搜索 → 链式文件操作」） |
| P2 | 音频索引 + 视频「时刻检索」（帧时间戳） |
| P3 | SAF 自选文件夹 + 共享文件夹 + 文档文本抽取（PDF/DOCX） |
| P4 增强 | ANN 向量检索、NPU 后端、后台调度与预算、部分媒体权限适配、纯文本变体打磨 |

---

## 17. 风险与开放问题

1. **模型文件体积**：全模态 `.litertlm` 较大，下载耗时；需清晰的下载进度与断点/重试与磁盘不足提示。
2. **内存压力**：740M 在多模态索引时峰值较高；依赖懒加载/卸载与预算，低端机降级纯文本。
3. **NPU 后端**：`Backend.NPU(nativeLibraryDir)` 需按设备适配，P4 再做，默认 GPU + CPU 回退。
4. **备份体积**：嵌入结果会让备份显著变大；用独立开关 + 体积预估提示，且不塞进 manifest。
5. **重装重关联**：依赖 `contentHash` 匹配；无法匹配的媒体在下次索引补齐，需在 UI 明确「部分已恢复、部分待重建」。
6. **暴力检索上限**：条目规模很小时够用，需预留 ANN 切换点与阈值配置。
7. **媒体权限**：Android 14 部分授权下，媒体集合可能不完整，需在 UI 说明并支持补充授权。

---

## 18. 文件清单

**新增**

- `agent/media/index/EtaEmbeddingEngine.kt`
- `agent/media/index/EmbeddingModelCatalog.kt`
- `agent/media/index/EmbeddingModelStore.kt`
- `agent/media/index/MediaSourceScanner.kt`
- `agent/media/index/MediaExtraction.kt`
- `agent/media/index/MediaIndexer.kt`
- `agent/media/index/MediaSearchService.kt`
- `agent/model/AgentMediaSearchToolCatalog.kt`
- `agent/tool/AgentMediaSearchTools.kt`
- `data/db/MediaEntities.kt`（`media_index` / `media_source` 实体，对齐现有 `ConversationEntities.kt` 等按域分文件的组织方式）
- `data/db/MediaIndexDao.kt`
- `ui/screens/search/MediaSearchScreen.kt`
- `ui/screens/settings/MediaIndexSettingsScreen.kt`
- 对应测试文件（见 §15）

**修改**

- `app/build.gradle.kts`（litertlm 依赖 + ProGuard）
- `data/db/EtaDatabase.kt`（version 35 + 实体 + 迁移）
- `agent/tool/AgentLocalTools.kt`（实例化 + 路由）
- `agent/tool/AgentToolRequirements.kt`（登记）
- `agent/model/AgentToolCatalog.kt`（appendTo）
- `config/Prefs.kt`（`AGENT_MEDIA_SEARCH_TOOLS`）
- `data/repository/EtaBackupRepository.kt`（schema 5 + `includeEmbeddings` + 归档条目 + 导入恢复）
- `ui/screens/backup/DataBackupScreen.kt`（导出类别勾选）
- `ui/navigation/AppRoute.kt`、`ui/navigation/AgentNavigator.kt`、`ui/SettingsScreen.kt`（导航入口）
- `AndroidManifest.xml`（媒体读权限）
- 字符串资源、`docs/THIRD_PARTY_NOTICES.md`
- `EtaDatabaseMigrationTest` 等既有测试
