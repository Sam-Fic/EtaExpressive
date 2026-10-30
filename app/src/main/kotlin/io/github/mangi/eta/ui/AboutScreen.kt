package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppUpdateOffer
import io.github.mangi.eta.data.repository.AppUpdateRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.components.AppUpdateDialog
import io.github.mangi.eta.ui.components.PreferenceIcon
import kotlinx.coroutines.launch

/** 本项目源码仓库（origin）。 */
private const val SOURCE_CODE_URL = "https://github.com/Sam-Fic/EtaExpressive"

/** 作者在 GitHub 上的仓库列表页。 */
private const val AUTHOR_REPOSITORIES_URL = "https://github.com/Sam-Fic?tab=repositories"

/** 作者的 GitHub 账号名。 */
private const val AUTHOR_GITHUB_HANDLE = "Sam-Fic"

/** 用系统浏览器打开外部链接。 */
private fun openExternalUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

/**
 * 关于页（KernelSU `AboutMaterial` 对应物）：
 * 80dp 白底圆角图标 + 应用名 + 版本号的居中 hero，下方为分段链接列表。
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentVersion = remember { AppUpdateRepository.currentVersionName(context) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateOffer by remember { mutableStateOf<AppUpdateOffer?>(null) }

    EtaScaffoldPage(
        title = stringResource(R.string.ui_about_bed172),
        onBack = onBack,
    ) {
        item(key = "about_hero") {
            AboutHero(versionName = currentVersion)
        }
        item(key = "about_links") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.update_check_title),
                        summary = if (checkingUpdate) {
                            stringResource(R.string.update_checking)
                        } else {
                            stringResource(
                                R.string.update_current_version,
                                currentVersion.ifBlank { stringResource(R.string.update_unknown_version) },
                            )
                        },
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.CloudDownload,
                            )
                        },
                        onClick = {
                            if (checkingUpdate) return@EtaClickableRow
                            checkingUpdate = true
                            coroutineScope.launch {
                                // 手动检查不遵循「忽略此版本」，否则用户会得到“已是最新版本”的错误结论。
                                val result = AppUpdateRepository.checkForUpdate(context, respectIgnored = false)
                                checkingUpdate = false
                                result.fold(
                                    onSuccess = { offer ->
                                        if (offer == null) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.update_latest),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            updateOffer = offer
                                        }
                                    },
                                    onFailure = { failure ->
                                        Toast.makeText(
                                            context,
                                            failure.message?.takeIf { it.isNotBlank() }
                                                ?: context.getString(R.string.update_check_failed),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        },
                    )
                }

                item {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_source_code_740296),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Code,
                            )
                        },
                        endActions = {
                            SettingsStatusText(text = "GitHub")
                        },
                        onClick = { openExternalUrl(context, SOURCE_CODE_URL) },
                    )
                }

                item {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_author_homepage_6b41d2),
                        summary = stringResource(R.string.ui_author_homepage_summary_8f0c73),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Person,
                            )
                        },
                        endActions = {
                            SettingsStatusText(text = AUTHOR_GITHUB_HANDLE)
                        },
                        onClick = { openExternalUrl(context, AUTHOR_REPOSITORIES_URL) },
                    )
                }
            }
        }
    }

    AppUpdateDialog(
        offer = updateOffer,
        currentVersion = currentVersion,
        onDismiss = { updateOffer = null },
    )
}

/**
 * 关于页 hero：80dp 白底 16dp 圆角图标 + 应用名 + 版本号（KernelSU `AboutMaterial` 布局）。
 */
@Composable
private fun AboutHero(versionName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(MaterialTheme.shapes.large)
                .background(Color.White),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 直接用官方字阶令牌（含字重/行高/字距），不再只拷 fontSize 或额外覆盖字重。
        Text(
            modifier = Modifier.padding(top = 12.dp),
            text = stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = versionName,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
