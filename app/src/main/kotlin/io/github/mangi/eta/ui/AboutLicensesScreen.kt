package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.haptics.HapticSelectionContainer

/**
 * 随 APK 分发的第三方组件许可证登记条目。
 *
 * [id] 是稳定路由键（[io.github.mangi.eta.ui.navigation.AppRoute.AboutLicenseDetail]），
 * 不随展示名变化；[assetName] 指向 `assets/licenses/` 下的许可证全文文件。
 */
private data class LicenseEntry(
    val id: String,
    val title: String,
    val summary: String,
    val assetName: String,
)

/**
 * 许可证登记表：只登记真正随包分发的第三方组件，与 `assets/licenses/` 下的文件一一对应。
 *
 * 展示名与说明走字符串资源，故必须在组合期读取。
 */
@Composable
private fun licenseEntries(): List<LicenseEntry> = listOf(
    LicenseEntry(
        id = "proot",
        title = "PRoot 5.1.107.92",
        summary = stringResource(R.string.licenses_entry_proot_summary),
        assetName = "licenses/proot-5.1.107.92.txt",
    ),
    LicenseEntry(
        id = "talloc",
        title = "talloc 2.4.3",
        summary = stringResource(R.string.licenses_entry_talloc_summary),
        assetName = "licenses/talloc-2.4.3.txt",
    ),
    LicenseEntry(
        id = "libandroid-shmem",
        title = "libandroid-shmem 0.7",
        summary = stringResource(R.string.licenses_entry_libandroid_shmem_summary),
        assetName = "licenses/libandroid-shmem-0.7.txt",
    ),
    LicenseEntry(
        id = "nimbus-jose-jwt",
        title = "Nimbus JOSE+JWT",
        summary = stringResource(R.string.licenses_entry_nimbus_summary),
        assetName = "licenses/nimbus-jose-jwt-Apache-2.0.txt",
    ),
    LicenseEntry(
        id = "gpl-3.0",
        title = "GNU GPL v3.0",
        summary = stringResource(R.string.licenses_entry_gpl3_summary),
        assetName = "licenses/GPL-3.0.txt",
    ),
    LicenseEntry(
        id = "agpl-3.0",
        title = "GNU AGPL v3.0",
        summary = stringResource(R.string.licenses_entry_agpl3_summary),
        assetName = "licenses/AGPL-3.0.txt",
    ),
)

/**
 * 关于页「开源许可」子页：列出随 APK 分发的第三方组件，点击进入许可证全文。
 *
 * 行样式与 About / Credits 一致：EtaClickableRow 直接放在 [EtaSegmentedColumn] 里，
 * 分组卡片与首末外圆角由分段列提供。
 */
@Composable
internal fun AboutLicensesScreen(onBack: () -> Unit, onOpenDetail: (String) -> Unit) {
    // EtaSegmentedColumn 的 DSL 闭包不是 @Composable，条目列表必须先在这里读好。
    val entries = licenseEntries()
    EtaScaffoldPage(
        title = stringResource(R.string.licenses_title),
        onBack = onBack,
    ) {
        item(key = "licenses_entries") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
            ) {
                entries.forEach { entry ->
                    item(key = entry.id) {
                        EtaClickableRow(
                            title = entry.title,
                            summary = entry.summary,
                            onClick = { onOpenDetail(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单条许可证全文页：从 assets 读取原文，等宽字体展示并支持长按选区复制。
 *
 * 文件缺失或读取失败时只给一行说明，不把异常抛到 UI 层。
 */
@Composable
internal fun AboutLicenseDetailScreen(licenseId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val entry = licenseEntries().firstOrNull { it.id == licenseId }
    val assetPath = entry?.assetName ?: "licenses/$licenseId.txt"
    val licenseText = remember(context, assetPath) {
        runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    EtaScaffoldPage(
        title = entry?.title ?: stringResource(R.string.licenses_title),
        onBack = onBack,
    ) {
        item(key = "license_text") {
            if (licenseText.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.licenses_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                )
            } else {
                HapticSelectionContainer {
                    Text(
                        text = licenseText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                    )
                }
            }
        }
    }
}
