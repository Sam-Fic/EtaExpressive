package io.github.mangi.eta.ui.components

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppUpdateOffer
import io.github.mangi.eta.data.repository.AppUpdateRepository
import io.github.mangi.eta.data.repository.AppUpdateService
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.EtaProgressBar
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppUpdateDialog(
    offer: AppUpdateOffer?,
    currentVersion: String,
    onDismiss: () -> Unit,
) {
    if (offer == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(offer.versionName) { mutableStateOf(false) }
    // 前台下载的真实进度：有总字节时走确定型，无总字节（服务端未给 Content-Length）退回不定型。
    var downloadProgress by remember(offer.versionName) { mutableStateOf<Float?>(null) }
    var downloadJob by remember(offer.versionName) { mutableStateOf<Job?>(null) }
    var backgroundStarted by remember(offer.versionName) { mutableStateOf(false) }
    val busy = downloading || backgroundStarted
    // 有 `.part` 说明上次取消/中断过：按钮文案改成「继续下载」，进度自然从已下载字节起算。
    var resumedBytes by remember(offer.versionName) { mutableStateOf(0L) }
    LaunchedEffect(offer.versionName, downloading) {
        resumedBytes = withContext(Dispatchers.IO) {
            AppUpdateRepository.partialDownloadBytes(context, offer)
        }
    }
    val summary = stringResource(
        R.string.update_available_summary,
        offer.versionName,
        currentVersion.ifBlank { stringResource(R.string.update_unknown_version) },
    )
    EtaDialog(
        show = true,
        title = stringResource(R.string.update_available_title),
        summary = summary,
        onDismissRequest = {
            if (!busy) onDismiss()
        },
        actions = {
            // 未同意下载时三个按钮、下载中两个按钮：大字号 + 360dp 下仍可能放不进一行，交给 FlowRow 换行。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 「稍后/忽略此版本」只在用户表达下载意愿前给出；一旦进入下载流程整体消失而不是禁用。
                if (!busy) {
                    TextButton(
                        onClick = {
                            // 「稍后」只关掉本次提醒：下次冷启动仍会问。
                            // 想彻底安静就用「忽略此版本」。
                            onDismiss()
                        },
                    ) {
                        Text(text = stringResource(R.string.update_later))
                    }
                    TextButton(
                        onClick = {
                            // 忽略此版本：持久化，后续自动检查不再弹出该版本及更低版本。
                            val version = offer.versionName
                            scope.launch {
                                AppUpdateRepository.dismissVersion(version)
                                onDismiss()
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.update_ignore_version))
                    }
                }
                TextButton(
                    onClick = {
                        // 下载中：这一个按钮变为「停止下载」，与后台服务的通知动作保持一致。
                        if (downloading) {
                            downloadJob?.cancel()
                            return@TextButton
                        }
                        if (busy) return@TextButton
                        if (offer.apkUrl.isNullOrBlank()) {
                            AppUpdateRepository.openReleasePage(context, offer)
                            onDismiss()
                            return@TextButton
                        }
                        if (!AppUpdateRepository.canInstallPackages(context)) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.update_install_permission),
                                Toast.LENGTH_LONG,
                            ).show()
                            AppUpdateRepository.requestInstallPermission(context)
                            return@TextButton
                        }
                        downloading = true
                        downloadProgress = null
                        downloadJob = scope.launch {
                            try {
                                var lastEmitAt = 0L
                                val file = AppUpdateRepository.downloadApk(
                                    context = context,
                                    offer = offer,
                                    onProgress = { bytesRead, totalBytes ->
                                        // onProgress 在 IO 线程回调：按 100ms 节流后再切回主线程写状态。
                                        val now = SystemClock.uptimeMillis()
                                        if (now - lastEmitAt >= 100L) {
                                            lastEmitAt = now
                                            val fraction = if (totalBytes > 0L) {
                                                (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                            } else {
                                                null
                                            }
                                            scope.launch { downloadProgress = fraction }
                                        }
                                    },
                                )
                                withContext(Dispatchers.Main) {
                                    AppUpdateRepository.installApk(context, file)
                                }
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                Toast.makeText(
                                    context,
                                    failure.message?.takeIf { it.isNotBlank() }
                                        ?: context.getString(R.string.update_download_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                downloading = false
                                downloadProgress = null
                                downloadJob = null
                            }
                        }
                    },
                    enabled = downloading || !busy,
                ) {
                    Text(
                        text = when {
                            downloading -> stringResource(R.string.update_stop_download)
                            resumedBytes > 0L -> stringResource(R.string.update_continue_download)
                            else -> stringResource(R.string.update_now)
                        },
                    )
                }
                // 「后台下载」在用户同意下载（进入下载流程）后才出现：先同意，才有转后台的选项。
                if (busy) {
                    TextButton(
                        onClick = {
                            if (backgroundStarted) return@TextButton
                            // 正常不会走到 apkUrl 为空：能看到本按钮说明已在前台下载中，空值早被「立即更新」入口拦下。
                            if (offer.apkUrl.isNullOrBlank()) {
                                AppUpdateRepository.openReleasePage(context, offer)
                            } else {
                                AppUpdateService.startDownload(context, offer)
                                // 服务端幂等，这里仍然锁住按钮，避免重复点出多份启动 intent。
                                backgroundStarted = true
                            }
                            onDismiss()
                        },
                        enabled = !backgroundStarted,
                    ) {
                        Text(text = stringResource(R.string.update_background_download))
                    }
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 前台下载：有总字节走 wavy 确定型，无总字节走 LoadingIndicator 不定型。
            if (downloading) {
                val fraction = downloadProgress
                if (fraction != null) {
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        EtaProgressBar(progress = fraction)
                        EtaText(
                            text = stringResource(
                                R.string.update_downloading_percent,
                                (fraction * 100f).roundToInt(),
                            ),
                            style = EtaTextStyle.Secondary,
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        EtaLoadingSlot(progress = null, size = EtaDimens.DenseIconSize)
                        Spacer(modifier = Modifier.width(EtaDimens.TrailingElementGap))
                        EtaText(
                            text = stringResource(R.string.update_downloading),
                            style = EtaTextStyle.Secondary,
                        )
                    }
                }
            }
            if (offer.notes.isNotBlank()) {
                EtaText(
                    text = offer.notes,
                    style = EtaTextStyle.Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .padding(bottom = 12.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
