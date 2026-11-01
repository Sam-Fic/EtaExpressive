package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.SessionGatewayRule
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text

/** 分段列调用方的统一外边距：取组边距 token（水平 16dp、组间 13dp），与 KernelSU 分段列表一致。 */
internal val EditorSectionPadding = Modifier.padding(
    start = EtaGroupHorizontalPadding,
    end = EtaGroupHorizontalPadding,
    bottom = EtaSegmentedGroupSpacing,
)

internal fun LazyListScope.providerSessionGateway(
    modelPattern: String,
    pathPattern: String,
    retention: String,
    keySource: String,
    keyField: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onChange: (model: String, path: String, retention: String, source: String, field: String) -> Unit,
) {
    item(key = "session_gateway") {
        // KernelSU 式分段列：展开行独立成段（首段），展开后的表单体作为隐藏段
        // 注册（visible = expanded，折叠时以 spring 动画收起并把末段圆角让给展开行）。
        EtaSegmentedColumn(
            modifier = EditorSectionPadding,
            title = stringResource(R.string.phe_session_id),
        ) {
            item {
                ProviderExpandableRow(
                    title = stringResource(R.string.phe_gateway),
                    summary = "${modelPattern.ifBlank { SessionGatewayRule.DEFAULT_MODEL }} · ${keyField.ifBlank { SessionGatewayRule.DEFAULT_FIELD }}",
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                )
            }
            item(visible = expanded) {
                EtaSegmentedItemContainer {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProviderField(
                            value = modelPattern,
                            onValueChange = { onChange(it, pathPattern, retention, keySource, keyField) },
                            label = stringResource(R.string.phe_model_regex),
                        )
                        ProviderField(
                            value = pathPattern,
                            onValueChange = { onChange(modelPattern, it, retention, keySource, keyField) },
                            label = stringResource(R.string.phe_path_regex),
                        )
                        ProviderField(
                            value = retention,
                            onValueChange = { onChange(modelPattern, pathPattern, it, keySource, keyField) },
                            label = stringResource(R.string.phe_session_keep),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderField(
                                value = keySource,
                                onValueChange = { onChange(modelPattern, pathPattern, retention, it, keyField) },
                                label = stringResource(R.string.phe_key_source),
                                modifier = Modifier.weight(1f),
                            )
                            ProviderField(
                                value = keyField,
                                onValueChange = { onChange(modelPattern, pathPattern, retention, keySource, it) },
                                label = stringResource(R.string.phe_field),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun LazyListScope.providerHeadersEditor(
    headers: List<ProviderHeaderDraft>,
    onHeadersChange: (List<ProviderHeaderDraft>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    item(key = "custom_headers") {
        // KernelSU 式分段列：展开行独立成段（首段），请求头编辑体与「添加」行落在
        // 同一个隐藏段里（visible = expanded，折叠时以 spring 动画收起）。
        EtaSegmentedColumn(
            modifier = EditorSectionPadding,
            title = stringResource(R.string.phe_custom_headers),
        ) {
            item {
                ProviderExpandableRow(
                    title = if (headers.isEmpty()) stringResource(R.string.prov_not_set) else stringResource(R.string.prov_set_count, headers.size),
                    summary = stringResource(R.string.phe_headers_summary),
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                )
            }
            item(visible = expanded) {
                EtaSegmentedItemContainer {
                    Column {
                        headers.forEach { row ->
                            key(row.id) {
                                ProviderHeaderRow(
                                    row = row,
                                    onNameChange = { value ->
                                        onHeadersChange(headers.map {
                                            if (it.id == row.id) it.copy(header = it.header.copy(name = value)) else it
                                        })
                                    },
                                    onValueChange = { value ->
                                        onHeadersChange(headers.map {
                                            if (it.id == row.id) it.copy(header = it.header.copy(value = value)) else it
                                        })
                                    },
                                    onRemove = { onHeadersChange(headers.filterNot { it.id == row.id }) },
                                )
                            }
                        }
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            M3Text(
                                text = stringResource(R.string.phe_hint_example),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ProviderDivider()
                        ProviderAddRow(text = stringResource(R.string.phe_add_header)) {
                            onHeadersChange(headers + ProviderHeaderDraft())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderHeaderRow(
    row: ProviderHeaderDraft,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var visible by remember(row.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderField(
                value = row.header.name,
                onValueChange = onNameChange,
                label = stringResource(R.string.phe_name),
            )
            ProviderField(
                value = row.header.value,
                onValueChange = onValueChange,
                label = stringResource(R.string.phe_value),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    ProviderIconAction(
                        icon = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = if (visible) {
                            context.getString(R.string.page_hide_bb0e7e)
                        } else {
                            context.getString(R.string.page_show_71b677)
                        },
                        onClick = { visible = !visible },
                    )
                },
            )
        }
        ProviderIconAction(
            icon = Icons.Rounded.Delete,
            contentDescription = context.getString(R.string.ui_delete_3755f5),
            onClick = {
                TouchHaptics.click(view)
                onRemove()
            },
        )
    }
}
