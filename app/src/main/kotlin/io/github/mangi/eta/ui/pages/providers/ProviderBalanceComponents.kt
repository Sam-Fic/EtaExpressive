package io.github.mangi.eta.ui.pages.providers

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderBalanceFetcher
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.data.repository.formatBalanceDisplay
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedScope
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaDimens
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text

/**
 * 从 UI 触发的余额刷新需要一个长生命周期协程作用域：composition 随页面销毁会取消在途请求。
 * 这里沿 Context 包裹链向上寻找宿主 Activity 的 LifecycleOwner，拿它的 lifecycleScope。
 */
internal fun Context.activityLifecycleOwnerOrNull(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    return current as? LifecycleOwner
}

/** 下拉框顺序与预设一一对应。 */
private val BalancePresets = listOf(
    BalanceOption.PRESET_CUSTOM,
    BalanceOption.PRESET_NEW_API,
    BalanceOption.PRESET_SUB2API,
    BalanceOption.PRESET_DEEPSEEK,
)

/**
 * 余额信息组：展开行独立成段（首段），开关收在行尾（KernelSU `SegmentedDropdownItem`
 * 同款位置），展开后的预设下拉与表单体作为隐藏段注册（[EtaSegmentedScope.item] 的
 * `visible`），折叠时以 spring 动画收起并把末段圆角让给展开行。
 */
internal fun EtaSegmentedScope.ProviderBalanceOptionFields(
    balanceOption: BalanceOption,
    onBalanceOptionChange: (BalanceOption) -> Unit,
    provider: ProviderSetting? = null,
) {
    item {
        ProviderBalanceOptionHeader(
            balanceOption = balanceOption,
            onBalanceOptionChange = onBalanceOptionChange,
        )
    }
    item(visible = balanceOption.enabled) {
        ProviderBalanceOptionBody(
            balanceOption = balanceOption,
            onBalanceOptionChange = onBalanceOptionChange,
            provider = provider,
        )
    }
}

@Composable
private fun ProviderBalanceOptionHeader(
    balanceOption: BalanceOption,
    onBalanceOptionChange: (BalanceOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(balanceOption.enabled) }
    val context = LocalContext.current
    ProviderExpandableRow(
        title = stringResource(R.string.ui_balance_info_title),
        summary = stringResource(R.string.ui_balance_info_summary),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        endActions = {
            EtaSwitch(
                checked = balanceOption.enabled,
                onCheckedChange = { value ->
                    onBalanceOptionChange(balanceOption.copy(enabled = value))
                    if (value) expanded = true
                },
            )
        },
    )
}

@Composable
private fun ProviderBalanceOptionBody(
    balanceOption: BalanceOption,
    onBalanceOptionChange: (BalanceOption) -> Unit,
    provider: ProviderSetting?,
) {
    val context = LocalContext.current
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var accessTokenVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resolved = balanceOption.resolved()
    val isNewApi = resolved.preset == BalanceOption.PRESET_NEW_API
    val switchOn = balanceOption.enabled

    ProviderSegmentBody {
        val presetIndex = BalancePresets.indexOf(resolved.preset).coerceAtLeast(0)
        ProviderDropdownRow(
            title = stringResource(R.string.ui_balance_preset),
            summary = stringResource(
                when (BalancePresets[presetIndex]) {
                    BalanceOption.PRESET_NEW_API -> R.string.ui_balance_preset_new_api_summary
                    BalanceOption.PRESET_SUB2API -> R.string.ui_balance_preset_sub2api_summary
                    BalanceOption.PRESET_DEEPSEEK -> R.string.ui_balance_preset_deepseek_summary
                    else -> R.string.ui_balance_preset_custom_summary
                },
            ),
            items = listOf(
                stringResource(R.string.ui_balance_preset_custom),
                stringResource(R.string.ui_balance_preset_new_api),
                stringResource(R.string.ui_balance_preset_sub2api),
                stringResource(R.string.ui_balance_preset_deepseek),
            ),
            selectedIndex = presetIndex,
            onSelectedIndexChange = { selectedIndex ->
                onBalanceOptionChange(
                    BalanceOption.applyPreset(
                        BalancePresets.getOrElse(selectedIndex) { BalanceOption.PRESET_CUSTOM },
                        balanceOption,
                    ),
                )
            },
        )
        Column(modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = 16.dp)) {
            if (isNewApi) {
                ProviderField(
                    value = balanceOption.accessToken,
                    onValueChange = {
                        onBalanceOptionChange(balanceOption.copy(accessToken = it))
                    },
                    label = stringResource(R.string.ui_balance_access_token),
                    visualTransformation = if (accessTokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        ProviderIconAction(
                            icon = if (accessTokenVisible) {
                                Icons.Rounded.Visibility
                            } else {
                                Icons.Rounded.VisibilityOff
                            },
                            contentDescription = if (accessTokenVisible) {
                                context.getString(R.string.page_hide_bb0e7e)
                            } else {
                                context.getString(R.string.page_show_71b677)
                            },
                            onClick = { accessTokenVisible = !accessTokenVisible },
                        )
                    },
                )
                BalanceHint(
                    text = stringResource(R.string.ui_balance_access_token_summary),
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            } else {
                ProviderField(
                    value = balanceOption.apiPath,
                    onValueChange = {
                        onBalanceOptionChange(balanceOption.copy(apiPath = it))
                    },
                    label = stringResource(R.string.ui_balance_api_path),
                )
                ProviderField(
                    value = balanceOption.resultPath,
                    onValueChange = {
                        onBalanceOptionChange(balanceOption.copy(resultPath = it))
                    },
                    label = stringResource(R.string.ui_balance_result_path),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            val onTestBalance: () -> Unit = {
                val currentProvider = provider
                if (currentProvider != null) scope.launch {
                    isTesting = true
                    testResult = null
                    val option = balanceOption.copy(enabled = true).resolved()
                    testResult = if (option.apiPath.isBlank() || option.resultPath.isBlank()) {
                        context.getString(R.string.ui_balance_test_missing_fields)
                    } else {
                        ProviderBalanceFetcher.fetch(currentProvider, option)
                            .fold(
                                onSuccess = { formatBalanceDisplay(it) },
                                onFailure = { it.message ?: it.toString() },
                            )
                    }
                    isTesting = false
                }
            }
            ProviderPrimaryAction(
                text = if (isTesting) {
                    context.getString(R.string.page_testing_f43705)
                } else {
                    stringResource(R.string.ui_test_balance)
                },
                onClick = onTestBalance,
                enabled = switchOn && !isTesting && provider != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
            testResult?.let {
                ProviderBalanceAmount(
                    amount = it,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BalanceHint(text: String, modifier: Modifier = Modifier) {
    M3Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun ProviderBalanceAmount(
    amount: String,
    modifier: Modifier = Modifier,
) {
    val contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Rounded.Savings,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(EtaDimens.MicroIconSize),
            tint = contentColor,
        )
        M3Text(
            text = amount,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 只有拿到过成功金额才占用空间。
 *
 * 余额刷新在后台静默进行：既没有“刷新中”，也不显示“已刷新”之类的成功提示，
 * 无缓存金额的失败同样不再插入提示文本，避免刷新过程在顶部栏与模型选择器里闪烁。
 */
internal fun hasBalanceIndicatorContent(state: ProviderBalanceState?): Boolean =
    state?.amount != null

/**
 * 顶部栏与模型选择器共用的余额只读指示器。
 *
 * - 只展示最后一次成功金额；刷新继续在后台更新数字，但不再显示刷新中/已刷新等提示。
 * - 金额与图标默认使用主题中性文字色，缓存过期或刷新失败不改变颜色。
 * - 只读：不响应点击，也不弹出余额详情。
 * - [animateChanges] 为 true 时（仅顶部栏），余额变化会播放一次动画。
 */
@Composable
internal fun ProviderBalanceIndicator(
    state: ProviderBalanceState?,
    modifier: Modifier = Modifier,
    animateChanges: Boolean = false,
) {
    if (state == null) return
    val amount = state.amount ?: return
    if (animateChanges) {
        AnimatedProviderBalanceIndicator(state = state, amount = amount, modifier = modifier)
        return
    }
    val amountColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Rounded.Savings,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(EtaDimens.MicroIconSize),
            tint = amountColor,
        )
        M3Text(
            text = amount,
            style = MaterialTheme.typography.labelSmall,
            color = amountColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
