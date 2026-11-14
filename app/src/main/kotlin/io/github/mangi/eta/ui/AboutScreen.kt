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
import androidx.compose.material.icons.rounded.Description
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
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
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

/** 「参考与致谢」条目：项目名 + 一句话说明 + 外链。 */
private data class CreditEntry(
    val name: String,
    val summary: String,
    val url: String,
)

/**
 * 关于页（KernelSU `AboutMaterial` 对应物）：
 * 80dp 白底圆角图标 + 应用名 + 版本号的居中 hero，下方为分段链接列表。
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit, onOpenCredits: () -> Unit) {
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
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
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

                item {
                    EtaClickableRow(
                        title = stringResource(R.string.credits_section_title),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Description,
                            )
                        },
                        onClick = onOpenCredits,
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

/**
 * 关于页「参考与致谢」子页（独立路由，复用 miuix `NavDisplay` 的入栈/出栈转场）。
 *
 * 条目行与 About 主页同款：EtaClickableRow 直接放在 [EtaSegmentedColumn] 里，
 * `surfaceBright` 底板与首末外圆角由分段列提供；说明与链接各占一行，
 * 长说明换行时不会被压成单行省略。
 */
@Composable
internal fun AboutCreditsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = listOf(
        CreditEntry(
            name = "Mangi-11/Eta",
            summary = stringResource(R.string.credits_entry_eta_upstream),
            url = "https://github.com/Mangi-11/Eta",
        ),
        CreditEntry(
            name = "y2485871697/Eta",
            summary = stringResource(R.string.credits_entry_y248),
            url = "https://github.com/y2485871697/Eta",
        ),
        CreditEntry(
            name = "Number444/Eta",
            summary = stringResource(R.string.credits_entry_number444),
            url = "https://github.com/Number444/Eta",
        ),
        CreditEntry(
            name = "liyunkkk/Eta",
            summary = stringResource(R.string.credits_entry_liyunkkk),
            url = "https://github.com/liyunkkk/Eta",
        ),
        CreditEntry(
            name = "NatumRagnag/Eta",
            summary = stringResource(R.string.credits_entry_natumragnag),
            url = "https://github.com/NatumRagnag/Eta",
        ),
        CreditEntry(
            name = "yooma1/Eta666",
            summary = stringResource(R.string.credits_entry_yooma1),
            url = "https://github.com/yooma1/Eta666",
        ),
        CreditEntry(
            name = "earendil-works/pi",
            summary = stringResource(R.string.credits_entry_pi),
            url = "https://github.com/earendil-works/pi",
        ),
        CreditEntry(
            name = "deepseek-ai/dsh-superagent-prototype",
            summary = stringResource(R.string.credits_entry_dsh),
            url = "https://github.com/deepseek-ai/dsh-superagent-prototype",
        ),
        CreditEntry(
            name = "zhushanwen321/tai-ji",
            summary = stringResource(R.string.credits_entry_taiji),
            url = "https://github.com/zhushanwen321/tai-ji",
        ),
        CreditEntry(
            name = "omnimind-ai/OmniBot",
            summary = stringResource(R.string.credits_entry_omnibot),
            url = "https://github.com/omnimind-ai/OmniBot",
        ),
        CreditEntry(
            name = "sst/opencode",
            summary = stringResource(R.string.credits_entry_opencode),
            url = "https://github.com/sst/opencode",
        ),
        CreditEntry(
            name = "k2-fsa/sherpa-onnx",
            summary = stringResource(R.string.credits_entry_sherpa),
            url = "https://github.com/k2-fsa/sherpa-onnx",
        ),
        CreditEntry(
            name = "OpenMinis/OpenMinis",
            summary = stringResource(R.string.credits_entry_openminis),
            url = "https://github.com/OpenMinis/OpenMinis",
        ),
        CreditEntry(
            name = "lzhhhhc/imagine",
            summary = stringResource(R.string.credits_entry_imagine),
            url = "https://github.com/lzhhhhc/imagine",
        ),
        CreditEntry(
            name = "damoshen123/st-chatu8",
            summary = stringResource(R.string.credits_entry_stchatu8),
            url = "https://github.com/damoshen123/st-chatu8",
        ),
        CreditEntry(
            name = "libxposed/api",
            summary = stringResource(R.string.credits_entry_libxposed),
            url = "https://github.com/libxposed/api",
        ),
        CreditEntry(
            name = "compose-miuix-ui/miuix",
            summary = stringResource(R.string.credits_entry_miuix),
            url = "https://github.com/compose-miuix-ui/miuix",
        ),
        CreditEntry(
            name = "mikepenz/multiplatform-markdown-renderer",
            summary = stringResource(R.string.credits_entry_markdown),
            url = "https://github.com/mikepenz/multiplatform-markdown-renderer",
        ),
        CreditEntry(
            name = "niki914/zafiro",
            summary = stringResource(R.string.credits_entry_zafiro),
            url = "https://github.com/niki914/zafiro",
        ),
        CreditEntry(
            name = "m3.material.io",
            summary = stringResource(R.string.credits_entry_m3),
            url = "https://m3.material.io",
        ),
        CreditEntry(
            name = "tiann/KernelSU",
            summary = stringResource(R.string.credits_entry_kernelsu),
            url = "https://github.com/tiann/KernelSU",
        ),
    )

    EtaScaffoldPage(
        title = stringResource(R.string.credits_section_title),
        onBack = onBack,
    ) {
        item(key = "credits_entries") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
            ) {
                entries.forEach { entry ->
                    item(key = entry.name) {
                        CreditsEntryRow(
                            entry = entry,
                            onClick = { openExternalUrl(context, entry.url) },
                        )
                    }
                }
            }
        }
        // 总结性致谢不带链接，直接排在页面背景上。
        item(key = "credits_thanks") {
            Text(
                text = stringResource(R.string.credits_thanks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
            )
        }
    }
}

/** 单条致谢：项目名 + 多行说明，链接单独占最后一行，整行点击打开浏览器。 */
@Composable
private fun CreditsEntryRow(entry: CreditEntry, onClick: () -> Unit) {
    val summary = buildString {
        append(entry.summary)
        append('\n')
        append(entry.url)
    }
    EtaClickableRow(
        title = entry.name,
        summary = summary,
        onClick = onClick,
    )
}
