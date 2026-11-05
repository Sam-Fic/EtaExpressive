package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaStatusDot
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextStyle

/**
 * 环境状态卡（页面首屏 hero）：发行版 + 版本为标题、运行方式为副标题，
 * 下方是状态说明与唯一的主操作按钮。
 *
 * 这里不再自绘组标题——状态文案本身就是这一组的语义，标题交给页面分组承担，
 * 避免同一个字符串在组标题与卡内标题各渲染一次。
 */
@Composable
internal fun LinuxEnvironmentStatusCard(
    distribution: String,
    version: String?,
    ready: Boolean,
    backend: String,
    summary: String,
    busy: Boolean,
    progress: Float?,
    message: String?,
    actionText: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    // hero 是分段列表里的一个条目容器（surfaceBright + 分段圆角），
    // 与下方各分组同一套容器语言，仅少了组标题。
    EtaSegmentedColumn(
        modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, top = 12.dp, bottom = EtaSegmentedGroupSpacing),
    ) {
        item {
            EtaSegmentedItemContainer {
                LinuxEnvironmentStatusBody(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    distribution = distribution,
                    version = version,
                    ready = ready,
                    backend = backend,
                    summary = summary,
                    busy = busy,
                    progress = progress,
                    message = message,
                    actionText = actionText,
                    actionEnabled = actionEnabled,
                    onAction = onAction,
                )
            }
        }
    }
}

/** 状态卡内容：状态说明与主操作按钮。 */
@Composable
private fun LinuxEnvironmentStatusBody(
    distribution: String,
    version: String?,
    ready: Boolean,
    backend: String,
    summary: String,
    busy: Boolean,
    progress: Float?,
    message: String?,
    actionText: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EtaStatusDot(active = ready)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                EtaText(
                    text = listOfNotNull(distribution, version?.takeIf { it.isNotBlank() })
                        .joinToString(" "),
                    style = EtaTextStyle.Title,
                )
                EtaText(text = backend, style = EtaTextStyle.Footnote, secondary = true)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                EtaLoadingSlot(
                    progress = progress,
                    size = EtaDimens.DenseIconSize,
                )
            }
            EtaText(
                text = summary,
                style = EtaTextStyle.Body,
                secondary = true,
                modifier = Modifier.weight(1f),
            )
        }
        message?.let {
            EtaText(text = it, style = EtaTextStyle.Footnote, secondary = true)
        }
        actionText?.let {
            EtaTextButton(
                text = it,
                enabled = actionEnabled,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 环境配置分组：发行版与运行方式。
 *
 * M3 下与其余分组统一为分段列表（组标题 + 分段行），行首图标按语义给出；
 * 无 root 时运行方式不可切换，退化为只读行。
 */
@Composable
internal fun LinuxEnvironmentConfiguration(
    title: String,
    distribution: LinuxDistribution,
    backend: LinuxExecutionBackend,
    rootGranted: Boolean,
    enabled: Boolean,
    onDistributionSelected: (LinuxDistribution) -> Unit,
    onBackendSelected: (LinuxExecutionBackend) -> Unit,
) {
    val distributions = LinuxDistribution.entries
    val distributionSummaries = distributions.map {
        stringResource(
            when (it) {
                LinuxDistribution.ALPINE -> R.string.linux_distribution_alpine_summary
                LinuxDistribution.DEBIAN -> R.string.linux_distribution_debian_summary
            },
        )
    }
    val backends = listOf(LinuxExecutionBackend.PROOT, LinuxExecutionBackend.CHROOT)
    val backendSummaries = backends.map {
        stringResource(
            when (it) {
                LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot_summary
                LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot_summary
            },
        )
    }
    val backendLabels = backends.map { it.displayName() }
    val backendIndex = backends.indexOf(backend).coerceAtLeast(0)
    val canSelectBackend = rootGranted || backend == LinuxExecutionBackend.CHROOT

    EtaSegmentedColumn(
        modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
        title = title,
    ) {
        item {
            // 与外观/语音等兄弟页同构：KernelSU 式分段下拉行——行尾以 primary 色给出当前发行版，
            // 点击后从按下位置弹出带分段形状与勾选图标的表达性菜单。
            EtaSegmentedDropdownRow(
                title = stringResource(R.string.linux_distribution_title),
                // 取值由行尾以 primary 色呈现，副标题补发行版之间的定位差异。
                summary = distributionSummaries[distributions.indexOf(distribution)],
                items = distributions.map { it.displayName() },
                selectedIndex = distributions.indexOf(distribution),
                onItemSelected = { onDistributionSelected(distributions[it]) },
                icon = Icons.Rounded.Layers,
                enabled = enabled,
            )
        }
        item {
            if (canSelectBackend) {
                EtaSegmentedDropdownRow(
                    title = stringResource(R.string.capability_linux_backend),
                    // 取值由行尾以 primary 色呈现，副标题只补运行方式的含义。
                    summary = backendSummaries[backendIndex],
                    items = backendLabels,
                    selectedIndex = backends.indexOf(backend),
                    onItemSelected = { onBackendSelected(backends[it]) },
                    icon = Icons.Rounded.Security,
                    enabled = enabled,
                )
            } else {
                // 不可切换时与可切换分支同构：副标题只讲运行方式的含义，当前值同样摆在行尾。
                EtaClickableRow(
                    title = stringResource(R.string.capability_linux_backend),
                    summary = backendSummaries[backendIndex],
                    onClick = null,
                    showChevron = false,
                    leading = { Icon(Icons.Rounded.Security, null) },
                    endActions = {
                        EtaText(
                            text = backendLabels[backendIndex],
                            style = EtaTextStyle.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun LinuxDistribution.displayName(): String = stringResource(
    when (this) {
        LinuxDistribution.ALPINE -> R.string.linux_distribution_alpine
        LinuxDistribution.DEBIAN -> R.string.linux_distribution_debian
    },
)

@Composable
internal fun LinuxExecutionBackend.displayName(): String = stringResource(
    when (this) {
        LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot
        LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot
    },
)
