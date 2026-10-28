package io.github.mangi.eta.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppUpdateOffer
import io.github.mangi.eta.data.repository.AppUpdateRepository
import io.github.mangi.eta.data.repository.AppUpdateService
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            if (!downloading) onDismiss()
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                TextButton(
                    onClick = {
                        if (downloading) return@TextButton
                        onDismiss()
                    },
                    enabled = !downloading,
                ) {
                    Text(text = stringResource(R.string.update_later))
                }
                TextButton(
                    onClick = {
                        if (downloading) return@TextButton
                        // 忽略此版本：持久化，后续冷启动不再弹出该版本的更新提醒。
                        val version = offer.versionName
                        scope.launch {
                            AppUpdateRepository.dismissVersion(version)
                            onDismiss()
                        }
                    },
                    enabled = !downloading,
                ) {
                    Text(text = stringResource(R.string.update_ignore_version))
                }
                TextButton(
                    onClick = {
                        if (downloading) return@TextButton
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
                        scope.launch {
                            val result = runCatching {
                                val file = AppUpdateRepository.downloadApk(context, offer)
                                withContext(Dispatchers.Main) {
                                    AppUpdateRepository.installApk(context, file)
                                }
                            }
                            downloading = false
                            result.exceptionOrNull()?.let { failure ->
                                if (failure is CancellationException) throw failure
                                Toast.makeText(
                                    context,
                                    failure.message?.takeIf { it.isNotBlank() }
                                        ?: context.getString(R.string.update_download_failed),
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@launch
                            }
                            onDismiss()
                        }
                    },
                    enabled = !downloading,
                ) {
                    Text(
                        text = if (downloading) {
                            stringResource(R.string.update_downloading)
                        } else {
                            stringResource(R.string.update_now)
                        },
                    )
                }
                TextButton(
                    onClick = {
                        if (offer.apkUrl.isNullOrBlank()) {
                            AppUpdateRepository.openReleasePage(context, offer)
                        } else {
                            AppUpdateService.startDownload(context, offer, currentVersion)
                        }
                        onDismiss()
                    },
                    enabled = !downloading,
                ) {
                    Text(text = stringResource(R.string.update_background_download))
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
