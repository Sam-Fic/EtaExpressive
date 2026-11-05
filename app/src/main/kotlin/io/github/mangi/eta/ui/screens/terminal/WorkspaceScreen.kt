package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxFileExplorer
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.rememberEtaLoadingShown
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.app.guestWorkspacePath
import io.github.mangi.eta.ui.components.ListEmptyState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「工作区与文件」页。
 *
 * Material 3 Expressive 分支按设置页的信息架构重排：
 * - 「私有工作区」分组把位置说明与当前 `/workspace` 路径合并成一行，取代原先割裂的
 *   「标题卡 + 路径卡」；
 * - 「公共目录访问」从顶栏菜单提到明面，成为带授权状态的分段行；
 * - 「工作区文件」分组承载目录内容，空态 / 加载态收进分段容器，导入动作交给右下角扩展 FAB；
 * - 文件操作走行尾「更多」按钮的 M3 Expressive 菜单（导出 / 删除）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember(appContext) { WorkspaceFileStore(context) }
    val scope = rememberCoroutineScope()
    val distribution by LinuxEnvironmentSettingsRepository.selectedFlow(appContext).collectAsState(
        initial = LinuxEnvironmentSettingsRepository.current(appContext),
    )
    val backendFlow = remember(appContext, distribution) {
        LinuxEnvironmentSettingsRepository.backendFlow(appContext, distribution)
    }
    val backend by backendFlow.collectAsState(
        initial = LinuxEnvironmentSettingsRepository.backend(appContext, distribution),
    )
    val rootfsDir = remember(distribution, backend) {
        LinuxEnvironmentPaths.rootfsDir(appContext, distribution, backend)
    }
    val linuxReady = LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)
    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)
    var path by rememberSaveable { mutableStateOf("") }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var publicAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        publicAccess = Environment.isExternalStorageManager()
        if (publicAccess) message = context.getString(R.string.capability_workspace_public_granted)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                var succeeded = 0
                uris.forEach { if (store.importFile(it)) succeeded++ }
                message = if (succeeded == uris.size) context.getString(R.string.capability_workspace_imported)
                else context.getString(R.string.capability_workspace_partial_import, succeeded)
                if (succeeded > 0) path = "imports"
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) scope.launch {
            busy = true
            try {
                val exported = if (linuxReady) {
                    withContext(Dispatchers.IO) {
                        exportWorkspaceLinuxFile(
                            context = appContext,
                            supervisor = shellSupervisor,
                            rootfsDir = rootfsDir,
                            distribution = distribution,
                            relativePath = source,
                            destination = uri,
                        )
                    }
                } else {
                    false
                }
                if (!exported) store.exportFile(source, uri)
                message = context.getString(R.string.capability_workspace_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(path, revision, linuxReady, distribution, backend) {
        listing = true
        try {
            entries = if (linuxReady) {
                val result = withContext(Dispatchers.IO) {
                    LinuxFileExplorer.list(
                        shellSupervisor,
                        rootfsDir,
                        guestWorkspacePath(path),
                        distribution.terminalEnvironment,
                        SharedFolderMounts.current(),
                    )
                }
                when (result) {
                    is LinuxFileExplorer.ListResult.Success -> result.entries.map { entry ->
                        WorkspaceEntry(
                            path = if (path.isBlank()) entry.name else path.trimEnd('/') + '/' + entry.name,
                            name = entry.name,
                            directory = entry.isDir,
                            size = entry.sizeBytes,
                        )
                    }
                    else -> store.list(path)
                }
            } else {
                store.list(path)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entries = emptyList()
            message = context.getString(R.string.capability_workspace_failed)
        } finally {
            listing = false
        }
    }

    fun requestExport(sourcePath: String, name: String) {
        pendingExport = sourcePath
        exportLauncher.launch(name)
    }

    fun requestDelete(sourcePath: String, name: String, directory: Boolean) {
        pendingDelete = WorkspaceEntry(
            path = sourcePath,
            name = name,
            directory = directory,
            size = 0L,
        )
    }

    fun requestPublicAccess() {
        try {
            accessLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        } catch (_: android.content.ActivityNotFoundException) {
            message = context.getString(R.string.capability_workspace_failed)
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.capability_workspace),
        onBack = onBack,
        listState = listState,
        // 导入是整页主操作，落到右下角扩展 FAB。
        floatingActionButton = {
            EtaExtendedFab(
                text = stringResource(R.string.capability_workspace_import),
                icon = Icons.Rounded.FileUpload,
                expanded = fabExpanded,
                onClick = { if (!busy) importLauncher.launch(arrayOf("*/*")) },
            )
        },
    ) {
        materialWorkspaceItems(
            context = context,
            path = path,
            entries = entries,
            busy = busy,
            listing = listing,
            publicAccess = publicAccess,
            message = message,
            onRequestPublicAccess = { requestPublicAccess() },
            onNavigateUp = { path = path.substringBeforeLast('/', "") },
            onOpenDirectory = { target -> path = target },
            onExport = { entry -> requestExport(entry.path, entry.name) },
            onDelete = { entry -> requestDelete(entry.path, entry.name, entry.directory) },
        )
    }

    pendingDelete?.let { target ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.capability_workspace_delete_title),
            summary = stringResource(
                if (target.directory) R.string.capability_workspace_delete_dir_message
                else R.string.capability_workspace_delete_message,
                target.name,
            ),
            onDismissRequest = { if (!busy) pendingDelete = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    confirmEnabled = !busy,
                    onCancel = { if (!busy) pendingDelete = null },
                    onConfirm = {
                        if (busy) return@EtaDialogActions
                        val deleting = target
                        pendingDelete = null
                        scope.launch {
                            busy = true
                            try {
                                val deleted = if (linuxReady) {
                                    val result = withContext(Dispatchers.IO) {
                                        LinuxFileExplorer.delete(
                                            shellSupervisor,
                                            rootfsDir,
                                            guestWorkspacePath(deleting.path),
                                            distribution.terminalEnvironment,
                                            SharedFolderMounts.current(),
                                        )
                                    }
                                    if (result == LinuxFileExplorer.DeleteResult.Success) true
                                    else {
                                        store.delete(deleting.path)
                                        true
                                    }
                                } else {
                                    store.delete(deleting.path)
                                    true
                                }
                                message = context.getString(
                                    if (deleted) R.string.capability_workspace_deleted
                                    else R.string.capability_workspace_failed,
                                )
                                revision++
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                message = context.getString(R.string.capability_workspace_failed)
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            },
        ) {
        }
    }
}

/**
 * Material 3 Expressive 内容：三段式分段列表（私有工作区 / 公共目录访问 / 工作区文件）。
 *
 * 分组统一走 [EtaSegmentedColumn]：16dp 页边距 + 13dp 组间距，标题取官方
 * `titleSmallEmphasized` primary 色，条目形状由分段列注入，与其余设置页保持同一套节奏。
 */
private fun LazyListScope.materialWorkspaceItems(
    context: Context,
    path: String,
    entries: List<WorkspaceEntry>,
    busy: Boolean,
    listing: Boolean,
    publicAccess: Boolean,
    message: String?,
    onRequestPublicAccess: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onExport: (WorkspaceEntry) -> Unit,
    onDelete: (WorkspaceEntry) -> Unit,
) {
    val groupModifier = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    // 组一：私有工作区 —— 说明与当前路径合并成一行，取代原来「标题卡 + 路径卡」的割裂结构。
    item(key = "m3-private") {
        EtaSegmentedColumn(
            modifier = groupModifier,
            title = stringResource(R.string.capability_workspace_private),
        ) {
            item(key = "m3-private-path") {
                EtaSegmentedRow(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    content = {
                        M3Text(
                            text = workspaceDisplayPath(path),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        )
                    },
                    supportingContent = {
                        M3Text(
                            text = stringResource(R.string.capability_workspace_private_summary),
                        )
                    },
                )
            }
        }
    }

    // 组二：公共目录访问 —— 授权状态摆在明面上，点击进入系统授权页。
    item(key = "m3-public") {
        EtaSegmentedColumn(modifier = groupModifier) {
            item(key = "m3-public-access") {
                EtaClickableRow(
                    title = stringResource(R.string.capability_workspace_public),
                    summary = stringResource(R.string.capability_workspace_public_summary),
                    onClick = onRequestPublicAccess,
                    showChevron = true,
                    endActions = {
                        // 授权状态以行尾文字直白呈现，不再藏进摘要文案。
                        EtaText(
                            text = stringResource(
                                if (publicAccess) R.string.status_authorized
                                else R.string.status_unauthorized,
                            ),
                            style = EtaTextStyle.Body,
                            color = if (publicAccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    },
                    leading = {
                        Icon(
                            // 状态用图标表达：已授权为解锁并取 primary，未授权为加锁取弱化色。
                            imageVector = if (publicAccess) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = if (publicAccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
        }
    }

    message?.let { text ->
        item(key = "m3-message") {
            M3Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }
    }

    // 组三：工作区文件 —— 目录内容；空态与加载态用分段容器承载。
    item(key = "m3-files") {
        EtaSegmentedColumn(
            modifier = groupModifier,
            title = stringResource(R.string.capability_workspace_files),
        ) {
            if (path.isNotBlank()) {
                item(key = "m3-parent") {
                    EtaSegmentedRow(
                        onClick = onNavigateUp,
                        enabled = !busy,
                        leadingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.DriveFileMove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        content = {
                            M3Text(
                                text = "../",
                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            )
                        },
                        supportingContent = {
                            M3Text(
                                text = stringResource(R.string.capability_workspace_parent),
                            )
                        },
                    )
                }
            }
            if (entries.isEmpty()) {
                item(key = "m3-empty") {
                    EtaSegmentedItemContainer {
                        // 本地目录首载通常几十毫秒：门控 120ms 后再显示，显示后至少保留 400ms；
                        // 收尾时先保留指示器、不立刻切到空态，避免视觉重叠。
                        val loading = listing || busy
                        val showLoading = rememberEtaLoadingShown(loading)
                        if (showLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                EtaLoadingSlot(
                                    progress = null,
                                    contained = true,
                                    size = EtaDimens.BlockLoadingSize,
                                )
                            }
                        } else if (!loading) {
                            ListEmptyState(
                                title = stringResource(R.string.capability_workspace_empty),
                                summary = stringResource(R.string.capability_workspace_empty_summary),
                            )
                        }
                    }
                }
            } else {
                entries.forEach { entry ->
                    item(key = entry.path) {
                        EtaSegmentedRow(
                            onClick = if (entry.directory) {
                                { onOpenDirectory(entry.path) }
                            } else {
                                null
                            },
                            enabled = !busy,
                            leadingContent = {
                                Icon(
                                    imageVector = if (entry.directory) {
                                        Icons.Rounded.Folder
                                    } else {
                                        Icons.AutoMirrored.Rounded.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            content = {
                                M3Text(text = entry.name)
                            },
                            supportingContent = {
                                M3Text(
                                    text = if (entry.directory) {
                                        stringResource(R.string.capability_workspace_directory)
                                    } else {
                                        Formatter.formatShortFileSize(context, entry.size)
                                    },
                                )
                            },
                            trailingContent = {
                                EtaFileActionsButton(
                                    canExport = !entry.directory,
                                    canDelete = true,
                                    enabled = !busy,
                                    onExport = { onExport(entry) },
                                    onDelete = { onDelete(entry) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 工作区路径的展示形态：始终以 `/workspace` 为根，与 Linux 环境中的挂载点一致。 */
private fun workspaceDisplayPath(path: String): String =
    if (path.isBlank()) "/workspace" else "/workspace/${path.trim('/')}"

private fun exportWorkspaceLinuxFile(
    context: android.content.Context,
    supervisor: ShellProcessSupervisor,
    rootfsDir: File,
    distribution: io.github.mangi.eta.agent.terminal.LinuxDistribution,
    relativePath: String,
    destination: Uri,
): Boolean {
    val tmp = File(context.cacheDir, "workspace-export-${System.nanoTime()}")
    return try {
        val copied = LinuxFileExplorer.copyToHostFile(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            linuxPath = guestWorkspacePath(relativePath),
            environment = distribution.terminalEnvironment,
            destination = tmp,
            sharedMounts = SharedFolderMounts.current(),
        )
        if (copied != LinuxFileExplorer.CopyResult.Success || !tmp.isFile) return false
        tmp.inputStream().use { input ->
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: return false
            output.use { input.copyTo(it) }
        }
        true
    } finally {
        tmp.delete()
    }
}
