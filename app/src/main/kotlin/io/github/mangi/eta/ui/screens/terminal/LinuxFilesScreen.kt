package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxFileExplorer
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaLoadingGate
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.TopBarIconButton
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Linux 环境文件浏览：目录列举、预览、导出和删除都在 Linux 会话中完成，
 * 因此能看到 /workspace 等 bind 挂载里的真实文件。
 * 查看文件时进入屏内查看态，页面返回键先退回列表再退出页面。
 *
 * Material 3 Expressive 分支按设置页信息架构重排为分段列表：
 * - 「当前位置」分组承载等宽路径行（查看态时行尾挂文件操作按钮）；
 * - 「文件浏览」分组承载 `../`、目录条目、空态与加载态；目录前进 / 后退时整组切成加载指示器，
 *   不残留上一层目录结构，条目操作走行尾「更多」菜单。
 */
@Composable
internal fun LinuxFilesScreen(
    context: Context,
    distribution: String,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val linuxDistribution = remember(distribution) {
        LinuxDistribution.entries.firstOrNull { it.wireName == distribution }
    }
    val rootfsDir = remember(linuxDistribution) {
        linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it) }
    }
    val installed = rootfsDir != null && LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)
    val scope = rememberCoroutineScope()

    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }

    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<LinuxFileExplorer.Entry>?>(null) }
    var listError by remember { mutableStateOf<Int?>(null) }
    var listRevision by remember { mutableIntStateOf(0) }
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var fileResult by remember { mutableStateOf<LinuxFileExplorer.ReadResult?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<LinuxPendingDelete?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        val dir = rootfsDir
        val dist = linuxDistribution
        if (uri == null || source == null || dir == null || dist == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val ok = withContext(Dispatchers.IO) {
                    exportLinuxFile(appContext, shellSupervisor, dir, dist, source, uri)
                }
                notice = appContext.getString(
                    if (ok) R.string.linux_files_exported else R.string.linux_files_export_failed,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = appContext.getString(R.string.linux_files_export_failed)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(currentPath, linuxDistribution, listRevision) {
        val dir = rootfsDir ?: return@LaunchedEffect
        val dist = linuxDistribution ?: return@LaunchedEffect
        if (!installed) return@LaunchedEffect
        entries = null
        listError = null
        val result = withContext(Dispatchers.IO) {
            LinuxFileExplorer.list(
                shellSupervisor,
                dir,
                currentPath,
                dist.terminalEnvironment,
                SharedFolderMounts.current(),
            )
        }
        when (result) {
            is LinuxFileExplorer.ListResult.Success -> entries = result.entries
            LinuxFileExplorer.ListResult.NotDirectory ->
                listError = R.string.linux_files_error_not_directory
            LinuxFileExplorer.ListResult.Unreadable,
            LinuxFileExplorer.ListResult.CommandFailed,
            LinuxFileExplorer.ListResult.NotInstalled ->
                listError = R.string.linux_files_error_unreadable
        }
    }

    LaunchedEffect(openFilePath) {
        val path = openFilePath ?: return@LaunchedEffect
        val dir = rootfsDir ?: return@LaunchedEffect
        val dist = linuxDistribution ?: return@LaunchedEffect
        fileResult = null
        fileResult = withContext(Dispatchers.IO) {
            LinuxFileExplorer.readText(
                shellSupervisor,
                dir,
                path,
                dist.terminalEnvironment,
                SharedFolderMounts.current(),
            )
        }
    }

    fun closeFile() {
        openFilePath = null
        fileResult = null
    }

    fun requestExport(path: String) {
        pendingExport = path
        exportLauncher.launch(path.substringAfterLast('/').ifBlank { "file" })
    }

    fun deleteItem(target: LinuxPendingDelete) {
        val dir = rootfsDir ?: return
        val dist = linuxDistribution ?: return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    LinuxFileExplorer.delete(
                        shellSupervisor,
                        dir,
                        target.path,
                        dist.terminalEnvironment,
                        SharedFolderMounts.current(),
                    )
                }
                notice = appContext.getString(
                    when (result) {
                        LinuxFileExplorer.DeleteResult.Success -> R.string.linux_files_deleted
                        LinuxFileExplorer.DeleteResult.Protected -> R.string.linux_files_delete_protected
                        else -> R.string.linux_files_delete_failed
                    },
                )
                if (result == LinuxFileExplorer.DeleteResult.Success) {
                    if (openFilePath == target.path) closeFile()
                    listRevision++
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = appContext.getString(R.string.linux_files_delete_failed)
            } finally {
                busy = false
                pendingDelete = null
            }
        }
    }

    val viewerBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = viewerBackState,
        isBackEnabled = openFilePath != null,
        onBackCompleted = { closeFile() },
    )

    val onExport: (String) -> Unit = { path -> requestExport(path) }
    val onDelete: (String, String, Boolean) -> Unit = { path, name, directory ->
        pendingDelete = LinuxPendingDelete(path = path, name = name, directory = directory)
    }
    val onOpenFile: (String) -> Unit = { path -> openFilePath = path }
    // 与路径切换同帧作废旧条目：加载期只显示指示器，不残留上一层目录结构。
    val onOpenDirectory: (String) -> Unit = { path ->
        entries = null
        listError = null
        currentPath = path
    }
    val onNavigateUp: () -> Unit = {
        entries = null
        listError = null
        currentPath = parentLinuxPath(currentPath)
    }

    EtaScaffoldPage(
        title = stringResource(R.string.linux_files_title),
        onBack = { if (openFilePath != null) closeFile() else onBack() },
        actions = {
            // 页面级操作收进顶栏：浏览目录时可直接重新列举当前目录。
            TopBarIconButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.page_refresh_38108e),
                onClick = {
                    entries = null
                    listError = null
                    listRevision++
                },
                enabled = installed && !busy && openFilePath == null,
                shapes = IconButtonDefaults.shapes(),
            )
        },
    ) {
        materialLinuxFileItems(
            context = appContext,
            distributionMissing = linuxDistribution == null,
            installed = installed,
            viewingPath = openFilePath,
            currentPath = currentPath,
            entries = entries,
            listError = listError,
            fileResult = fileResult,
            notice = notice,
            busy = busy,
            onExport = onExport,
            onDelete = onDelete,
            onOpenFile = onOpenFile,
            onOpenDirectory = onOpenDirectory,
            onNavigateUp = onNavigateUp,
        )
    }

    pendingDelete?.let { target ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.linux_files_delete_title),
            summary = stringResource(
                if (target.directory) R.string.linux_files_delete_dir_message
                else R.string.linux_files_delete_message,
                target.name,
            ),
            onDismissRequest = { if (!busy) pendingDelete = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    confirmEnabled = !busy,
                    onCancel = { if (!busy) pendingDelete = null },
                    onConfirm = { if (!busy) deleteItem(target) },
                )
            },
        ) {
        }
    }
}

/**
 * Material 3 Expressive 内容：分段列表。
 *
 * - 「当前位置」无标题分组：等宽路径行；查看态时行尾挂 [EtaFileActionsButton]。
 * - 「文件浏览」分组：`../`、目录条目、空态 / 加载态统一收进 [EtaSegmentedItemContainer]。
 *
 * 分组统一走 [EtaSegmentedColumn]：16dp 页边距 + 13dp 组间距，与「工作区与文件」页同构。
 */
private fun LazyListScope.materialLinuxFileItems(
    context: Context,
    distributionMissing: Boolean,
    installed: Boolean,
    viewingPath: String?,
    currentPath: String,
    entries: List<LinuxFileExplorer.Entry>?,
    listError: Int?,
    fileResult: LinuxFileExplorer.ReadResult?,
    notice: String?,
    busy: Boolean,
    onExport: (String) -> Unit,
    onDelete: (String, String, Boolean) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val groupModifier = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    if (distributionMissing || !installed) {
        item(key = "m3-state") {
            EtaSegmentedColumn(modifier = groupModifier) {
                item {
                    EtaSegmentedItemContainer {
                        MaterialStateMessage(
                            message = if (distributionMissing) {
                                stringResource(R.string.linux_files_invalid_distribution)
                            } else {
                                stringResource(R.string.linux_files_not_installed)
                            },
                        )
                    }
                }
            }
        }
        return
    }

    if (viewingPath != null) {
        val viewing = viewingPath
        item(key = "m3-viewer-path") {
            EtaSegmentedColumn(modifier = groupModifier) {
                item {
                    EtaSegmentedRow(
                        leadingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        content = { PathMonoText(viewing) },
                        trailingContent = {
                            EtaFileActionsButton(
                                canExport = true,
                                canDelete = !LinuxFileExplorer.isProtectedPath(viewing),
                                enabled = !busy,
                                onExport = { onExport(viewing) },
                                onDelete = {
                                    onDelete(
                                        viewing,
                                        viewing.substringAfterLast('/').ifBlank { viewing },
                                        false,
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
        notice?.let { text ->
            item(key = "m3-viewer-notice") { MaterialGroupHint(text) }
        }
        item(key = "m3-viewer-content") {
            EtaSegmentedColumn(modifier = groupModifier) {
                item {
                    EtaSegmentedItemContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            when (val result = fileResult) {
                                is LinuxFileExplorer.ReadResult.Text -> {
                                    if (result.truncated) {
                                        EtaText(
                                            text = stringResource(R.string.linux_files_truncated_hint),
                                            style = EtaTextStyle.Footnote,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                    }
                                    SelectionContainer {
                                        TerminalMonoText(text = AnnotatedString(result.content))
                                    }
                                }
                                LinuxFileExplorer.ReadResult.Binary -> EtaText(
                                    text = stringResource(R.string.linux_files_binary_hint),
                                    style = EtaTextStyle.Body,
                                    secondary = true,
                                )
                                LinuxFileExplorer.ReadResult.NotFile -> EtaText(
                                    text = stringResource(R.string.linux_files_error_not_file),
                                    style = EtaTextStyle.Body,
                                    secondary = true,
                                )
                                LinuxFileExplorer.ReadResult.Unreadable,
                                LinuxFileExplorer.ReadResult.CommandFailed,
                                LinuxFileExplorer.ReadResult.NotInstalled -> EtaText(
                                    text = stringResource(R.string.linux_files_error_unreadable),
                                    style = EtaTextStyle.Body,
                                    secondary = true,
                                )
                                null -> Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // 文件读取通常几十毫秒：门控 120ms 后再显示，避免闪一下。
                                    EtaLoadingGate(visible = true) {
                                        EtaLoadingSlot(
                                            progress = null,
                                            contained = true,
                                            size = EtaDimens.BlockLoadingSize,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // 当前位置：等宽路径行。
    item(key = "m3-path") {
        EtaSegmentedColumn(modifier = groupModifier) {
            item {
                EtaSegmentedRow(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    content = { PathMonoText(currentPath) },
                )
            }
        }
    }
    notice?.let { text ->
        item(key = "m3-list-notice") { MaterialGroupHint(text) }
    }

    // 文件浏览：加载期整组只显示指示器；`../` + 目录条目 + 空态在列举结束后出现。
    item(key = "m3-files") {
        EtaSegmentedColumn(
            modifier = groupModifier,
            title = stringResource(R.string.linux_files_entry_title),
        ) {
            val currentEntries = entries
            val loading = listError == null && currentEntries == null
            // 加载期连 `../` 行一起收起，避免残留上一层目录的骨架。
            if (!loading && currentPath != "/") {
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
            when {
                listError != null -> item(key = "m3-list-error") {
                    EtaSegmentedItemContainer {
                        MaterialStateMessage(stringResource(listError ?: R.string.linux_files_error_unreadable))
                    }
                }
                currentEntries == null -> item(key = "m3-list-loading") {
                    EtaSegmentedItemContainer {
                        // 目录导航是用户主动操作，指示器即时出现（不走短操作 120ms 门控），
                        // 加载结束即切回内容，不给目录切换人为加 400ms 等待。
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
                    }
                }
                currentEntries.isEmpty() -> item(key = "m3-list-empty") {
                    EtaSegmentedItemContainer {
                        ListEmptyState(title = stringResource(R.string.linux_files_empty))
                    }
                }
                else -> currentEntries.forEach { entry ->
                    val target = joinLinuxPath(currentPath, entry.name)
                    item(key = "m3-entry-" + entry.name) {
                        FileRow(
                            name = entry.name,
                            isDir = entry.isDir,
                            summary = if (entry.isDir) {
                                null
                            } else {
                                Formatter.formatShortFileSize(context, entry.sizeBytes)
                            },
                            enabled = !busy,
                            onClick = {
                                if (entry.isDir) onOpenDirectory(target) else onOpenFile(target)
                            },
                            trailingActions = {
                                EtaFileActionsButton(
                                    canExport = !entry.isDir,
                                    canDelete = !LinuxFileExplorer.isProtectedPath(target),
                                    enabled = !busy,
                                    onExport = { onExport(target) },
                                    onDelete = { onDelete(target, entry.name, entry.isDir) },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class LinuxPendingDelete(
    val path: String,
    val name: String,
    val directory: Boolean,
)

private fun joinLinuxPath(parent: String, name: String): String =
    if (parent == "/") "/$name" else parent.trimEnd('/') + "/" + name

private fun parentLinuxPath(path: String): String =
    path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }

private fun exportLinuxFile(
    context: Context,
    supervisor: ShellProcessSupervisor,
    rootfsDir: File,
    distribution: LinuxDistribution,
    linuxPath: String,
    destination: Uri,
): Boolean {
    val tmp = File(context.cacheDir, "linux-export-${System.nanoTime()}")
    return try {
        val copied = LinuxFileExplorer.copyToHostFile(
            supervisor = supervisor,
            rootfsDir = rootfsDir,
            linuxPath = linuxPath,
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

/** Material 3 路径等宽文本。 */
@Composable
private fun PathMonoText(path: String) {
    M3Text(
        text = path,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 分段容器内的说明 / 状态文本（Material 3）。 */
@Composable
private fun MaterialStateMessage(message: String) {
    EtaText(
        text = message,
        style = EtaTextStyle.Body,
        secondary = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    )
}

/** 分组外提示文本（Material 3）：与分组容器同一套 16dp 边距 + 13dp 间距。 */
@Composable
private fun MaterialGroupHint(message: String) {
    EtaText(
        text = message,
        style = EtaTextStyle.Footnote,
        secondary = true,
        modifier = Modifier.padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            bottom = EtaSegmentedGroupSpacing,
        ),
    )
}

@Composable
private fun FileRow(
    name: String,
    isDir: Boolean,
    summary: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    trailingActions: (@Composable () -> Unit)? = null,
) {
    EtaFileRow(
        title = name,
        summary = summary,
        leadingIcon = if (isDir) {
            Icons.Rounded.Folder
        } else {
            Icons.AutoMirrored.Rounded.InsertDriveFile
        },
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        trailingActions = trailingActions,
    )
}

/**
 * 文件/目录列表行。
 *
 * KernelSU 式分段行：涟漪、长按、语义与内边距全部来自 [EtaSegmentedRow]；
 * 行尾可挂 [trailingActions]（如「更多」按钮），子节点的点击由自身消费，不影响整行点击。
 *
 * @param trailingActions 行尾附加内容。
 */
@Composable
internal fun EtaFileRow(
    title: String,
    summary: String?,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailingActions: (@Composable () -> Unit)? = null,
) {
    val supporting: (@Composable () -> Unit)? = summary?.let {
        {
            M3Text(text = it)
        }
    }
    val leading: @Composable () -> Unit = {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // KernelSU 式分段行：形状/容器色取自外层分段组，独立使用时退化为单条目形状。
    EtaSegmentedRow(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = leading,
        trailingContent = trailingActions,
        content = {
            M3Text(text = title)
        },
        supportingContent = supporting,
    )
}
