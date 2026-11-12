package io.github.mangi.eta.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaExpandChevron
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTrailingChevron
import io.github.mangi.eta.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text

/** 分段列调用方的统一外边距：取组边距 token（水平 16dp、组间 13dp），与 KernelSU 分段列表调用方一致。 */
internal val ProviderSectionGroupPadding = Modifier.padding(
    start = EtaGroupHorizontalPadding,
    end = EtaGroupHorizontalPadding,
    bottom = EtaSegmentedGroupSpacing,
)

/**
 * 可展开编辑器的展开体容器：M3 用官方分段容器（与分段行拼成连续表面，
 * 对应参考实现的 SegmentedItemContainer）。
 */
@Composable
internal fun ProviderSegmentBody(
    content: @Composable ColumnScope.() -> Unit,
) {
    EtaSegmentedItemContainer {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** 厂商品牌原色图标；资源已经包含适合圆形裁剪的背景与安全区。 */
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        // 官方图标槽宽度 24dp；间距由官方 ItemBetweenSpace 12dp 施加。
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape),
    )
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    sharedProviderBrandLogoRes(provider)

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    sharedProviderBrandLogoRes(sourceType)

/** 已知厂商使用品牌图标，未知来源继续按协议类型使用通用图标。 */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }

    val fallbackIcon = if (provider is CustomProviderSetting) Icons.Rounded.Dns else Icons.Rounded.Language
    // 官方默认几何（24dp + ItemBetweenSpace 12dp）；着色统一设置页 onSurfaceVariant。
    Icon(
        imageVector = fallbackIcon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

internal enum class TagChipTone { Normal, Emphasized }

/**
 * 小胶囊标签，用于能力标签与状态标记。
 *
 * Material 3 下改为官方 [Surface] + 官方形状令牌（`shapes.extraSmall`）与 `labelSmall` 字阶，
 * 不再手写圆角与容器色。
 */
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    val background: Color
    val foreground: Color
    when (tone) {
        TagChipTone.Normal -> {
            background = MaterialTheme.colorScheme.secondaryContainer
            foreground = MaterialTheme.colorScheme.onSecondaryContainer
        }
        TagChipTone.Emphasized -> {
            background = MaterialTheme.colorScheme.primaryContainer
            foreground = MaterialTheme.colorScheme.onPrimaryContainer
        }
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = background,
        contentColor = foreground,
    ) {
        M3Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 供应商页共用的自适应小组件（仅本包内使用）
// ---------------------------------------------------------------------------

/** 顶栏/行尾图标按钮：用官方 [M3IconButton]。 */
@Composable
internal fun ProviderIconAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    M3IconButton(onClick = onClick, enabled = enabled, shapes = IconButtonDefaults.shapes(), ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/** 主操作按钮：官方 [Button]，传官方 `shapes`（按压 morph，AGENTS §2.5）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProviderPrimaryAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shapes = M3ButtonDefaults.shapes(),
    ) {
        M3Text(text = text)
    }
}

/** 破坏性操作按钮：官方 [Button] + 官方 error 角色色，`shapes` 同主操作（AGENTS §2.5）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProviderDestructiveAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = M3ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        shapes = M3ButtonDefaults.shapes(),
    ) {
        M3Text(text = text)
    }
}

/**
 * 可点击设置行：M3 用官方 [ListItem]（onClick 重载自带涟漪与语义）。
 *
 * @param showChevron 是否在尾部显示右侧箭头（导航型条目默认显示；动作型条目传 false）。
 */
@Composable
internal fun ProviderClickableRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val supportingText = summary
    val supporting: (@Composable () -> Unit)? = if (supportingText != null) {
        {
            M3Text(text = supportingText)
        }
    } else {
        null
    }
    val trailing: (@Composable () -> Unit)? = if (showChevron) {
        { EtaTrailingChevron() }
    } else {
        null
    }
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = leading,
        content = { M3Text(text = title) },
        supportingContent = supporting,
        trailingContent = trailing,
    )
}

/** 纯展示设置行：M3 用官方 [ListItem]。 */
@Composable
internal fun ProviderNoticeRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val supportingText = summary
    val supporting: (@Composable () -> Unit)? = if (supportingText != null) {
        {
            M3Text(text = supportingText)
        }
    } else {
        null
    }
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = leading,
        content = { M3Text(text = title) },
        supportingContent = supporting,
    )
}

/** 展开/收起行：M3 用官方 [ListItem] + 旋转箭头（动画曲线取官方 motionScheme）。 */
@Composable
internal fun ProviderExpandableRow(
    title: String,
    summary: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showChevron: Boolean = true,
    endActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val supportingText = summary
    val supporting: (@Composable () -> Unit)? = if (supportingText != null) {
        {
            M3Text(text = supportingText)
        }
    } else {
        null
    }
    EtaSegmentedRow(
        onClick = { onExpandedChange(!expanded) },
        modifier = Modifier.fillMaxWidth(),
        content = { M3Text(text = title) },
        supportingContent = supporting,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 尾槽顺序统一：开关永远最右，展开箭头在其左侧。
                if (showChevron) {
                    EtaExpandChevron(expanded = expanded)
                    // 与 EtaClickableRow 同一口径：尾槽内部元素间距统一 12dp。
                    if (endActions != null) {
                        Spacer(modifier = Modifier.width(EtaDimens.TrailingElementGap))
                    }
                }
                endActions?.invoke(this)
            }
        },
    )
}

/** 强调色的「新增」行：M3 用官方 [ListItem]（主色标题 + 加号图标）。 */
@Composable
internal fun ProviderAddRow(
    text: String,
    onClick: () -> Unit,
) {
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        content = {
            M3Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

/**
 * 下拉选择行：走与外观/语音等兄弟页一致的 [EtaSegmentedDropdownRow]
 * （行尾以 primary 色给出当前取值、从按下位置弹出带分段形状与勾选图标的表达性菜单）。
 */
@Composable
internal fun ProviderDropdownRow(
    title: String,
    summary: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    EtaSegmentedDropdownRow(
        title = title,
        items = items,
        selectedIndex = selectedIndex,
        onItemSelected = onSelectedIndexChange,
        summary = summary,
        enabled = enabled,
    )
}

/** 单行文本输入：用官方 [OutlinedTextField]。 */
@Composable
internal fun ProviderField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { M3Text(text = label) },
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
    )
}

/** 分隔线：用官方 [HorizontalDivider]。 */
@Composable
internal fun ProviderDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(modifier = modifier)
}

/** 搜索框：用官方 [OutlinedTextField] + 搜索图标。 */@Composable
internal fun ProviderSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { M3Text(text = placeholder) },
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
    )
}
