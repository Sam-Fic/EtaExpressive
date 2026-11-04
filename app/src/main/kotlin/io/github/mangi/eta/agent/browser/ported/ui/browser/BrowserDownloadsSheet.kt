// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see third_party/openminis-browser/NOTICE.md.
package io.github.mangi.eta.agent.browser.ported.ui.browser

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTooltip

/**
 * [T-android-browser-download-ux] Downloads panel — Android port of iOS
 * BrowserDownloadPanelSheet (T-browser-download-ux v2/v3): entries
 * newest-first; in-flight rows show live progress + a cancel button;
 * completed rows open the file via ACTION_VIEW (the Android "Show in Files"
 * equivalent — same FileProvider path FilePreviewScreen uses); failed rows
 * show the reason. "Clear" drops ALL finished records (completed AND failed,
 * v3 semantics); in-flight rows stay. Opening the panel marks terminal
 * entries seen, which zeroes the toolbar badge.
 *
 * M3 Expressive：官方 [ModalBottomSheet] + [BrowserSheetTopBar] 页头，
 * 列表走项目分段列表套件（[EtaSegmentedColumn] / [EtaSegmentedRow]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserDownloadsSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Expanded,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val downloads by tabPool.downloads.collectAsState()

    // Terminal entries stop counting toward the badge once the user has
    // actually looked at the panel.
    LaunchedEffect(Unit) { tabPool.markDownloadsSeen() }

    val hasFinished = downloads.any { it.state != BrowserTabPool.DownloadState.DOWNLOADING }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BrowserSheetTopBar(
                title = stringResource(R.string.om_browser_downloads_title),
                onDismiss = onDismiss,
                actions = {
                    if (hasFinished) {
                        TextButton(onClick = { tabPool.clearFinishedDownloads() }) {
                            Text(
                                text = stringResource(R.string.om_browser_downloads_clear),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )

            if (downloads.isEmpty()) {
                BrowserSheetEmptyState(
                    title = stringResource(R.string.om_browser_downloads_empty),
                )
            } else {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                ) {
                    downloads.forEach { entry ->
                        item(key = entry.id) {
                            EtaSegmentedRow(
                            modifier = Modifier.fillMaxWidth(),
                            leadingContent = {
                                val (icon, tint) = when (entry.state) {
                                    BrowserTabPool.DownloadState.DOWNLOADING ->
                                        Icons.Rounded.Download to MaterialTheme.colorScheme.primary
                                    BrowserTabPool.DownloadState.COMPLETED ->
                                        Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
                                    BrowserTabPool.DownloadState.FAILED ->
                                        Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                                )
                            },
                            content = {
                                Text(
                                    // Middle truncation keeps the extension visible (iOS v3).
                                    text = BrowserTabPool.middleTruncated(entry.filename, head = 24, tail = 12),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                DownloadStatusText(entry = entry)
                            },
                            trailingContent = {
                                when (entry.state) {
                                    BrowserTabPool.DownloadState.DOWNLOADING -> {
                                        val cancelDescription =
                                            stringResource(R.string.om_browser_downloads_cancel)
                                        EtaTooltip(text = cancelDescription) {
                                            IconButton(onClick = { tabPool.cancelDownload(entry.id) }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Cancel,
                                                    contentDescription = cancelDescription,
                                                )
                                            }
                                        }
                                    }
                                    BrowserTabPool.DownloadState.COMPLETED -> {
                                        val openDescription =
                                            stringResource(R.string.om_browser_downloads_open)
                                        EtaTooltip(text = openDescription) {
                                            IconButton(onClick = { openDownloadedFile(context, entry) }) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                                    contentDescription = openDescription,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    }
                                    BrowserTabPool.DownloadState.FAILED -> {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 下载条目的状态行：进度 + 体积，或失败原因。 */
@Composable
private fun DownloadStatusText(entry: BrowserTabPool.DownloadEntry) {
    val context = LocalContext.current
    when (entry.state) {
        BrowserTabPool.DownloadState.DOWNLOADING -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val doneText = android.text.format.Formatter
                    .formatShortFileSize(context, entry.bytesDone)
                val statusText = if (entry.totalBytes > 0) {
                    val totalText = android.text.format.Formatter
                        .formatShortFileSize(context, entry.totalBytes)
                    val pct = (entry.bytesDone * 100 / entry.totalBytes).toInt()
                    "$pct% · $doneText / $totalText"
                } else {
                    doneText
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (entry.bytesDone.toFloat() / entry.totalBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        BrowserTabPool.DownloadState.COMPLETED -> {
            Text(
                text = android.text.format.Formatter
                    .formatShortFileSize(context, entry.bytesDone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrowserTabPool.DownloadState.FAILED -> {
            Text(
                text = entry.failureReason
                    ?: stringResource(R.string.om_browser_downloads_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** ACTION_VIEW via FileProvider — same pattern as FilePreviewScreen's
 * openExternally; the `minis-sessions/` root is a declared provider path. */
private fun openDownloadedFile(context: Context, entry: BrowserTabPool.DownloadEntry) {
    val file = entry.destination ?: return
    if (!file.exists()) return
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider",
            java.io.File(java.io.File(context.cacheDir, "browser-shares").apply { mkdirs() }, file.name).also {
                require(file.length() <= 32L * 1024 * 1024) { "File exceeds sharing limit" }
                file.copyTo(it, overwrite = true)
            },
        )
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: Exception) {
        android.util.Log.w("BrowserDownloads", "open failed: ${e.message}")
    }
}
