package io.github.mangi.eta.ui.adaptive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.TopBarIconButton

/**
 * 多选批量操作共享套件：顶栏/段标题入口原位切换、底部浮动操作栏、进退场装配与底部占位。
 *
 * 收敛自全仓 10 个手写多选页（聊天管理/回收站/分类、MCP、工具配方、远程智能体、模型管理、
 * 媒体搜索、豆包音色、供应商列表）的逐字级同构装配：
 * - 药丸外壳 = 官方 M3E [HorizontalFloatingToolbar]（expanded + vibrant 变体容器
 *   primaryContainer/onPrimaryContainer——standard 默认 surfaceContainer 与页面背景同色融合，
 *   官方 vibrant 令牌见 srczip FloatingToolbar.kt:1119-1128；端盖同心 padding）；
 * - 全选钮 = SelectAll/Deselect 图标随 [EtaSelectionBar.allSelected] 原位自适应；
 * - 动作钮 = [EtaSelectionAction] 列表参数化，破坏性动作（destructive）走官方裸
 *   IconButton + error 色图标（判据=会不会毁数据），普通动作走 [EtaIconButton] Standard 档；
 * - 进退场 = 位移走 M3E Spatial（轻微过冲）、透明度走 Effects（无过冲）。
 *
 * 选择态骨架（`selectionMode/selectedIds` 两行 remember + `NavigationBackHandler` 先退多选）
 * **不在此收敛**：provider 作用域、home 域、勾选剪枝等语义差异是页面真实状态，留页面自治。
 */

/** 多选操作栏的中间动作钮：图标、无障碍文案、点击与启用门控，破坏性动作用 error 色区分。 */
data class EtaSelectionAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    /** 追加门控（busy 锁、删除资格等）；最终启用还要叠加「已选 > 0」。 */
    val enabled: Boolean = true,
    /** 破坏性动作：图标取 error 色，走官方裸 IconButton + [EtaTooltip]。 */
    val destructive: Boolean = false,
)

/**
 * 底部居中的多选操作药丸：全选 + [actions] 动作组。
 *
 * @param allSelected 勾选集是否已覆盖全集（决定全选钮的 SelectAll/Deselect 自适应）。
 * @param selectedCount 已选数量；除全选外的动作钮统一要求 > 0 才可用。
 * @param enabled 整栏门控（抓取中/变更中等页面级 busy），禁用时含全选钮整体变灰。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EtaSelectionBar(
    allSelected: Boolean,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    actions: List<EtaSelectionAction>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            // vibrant 变体默认容器 primaryContainer/onPrimaryContainer——standard 默认 surfaceContainer
            // 与页面背景同色融合（深色下不可辨）；官方 vibrant 令牌见 srczip FloatingToolbar.kt:1119-1128。
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            leadingContent = {},
            // 端盖同心：首末两枚 40dp 圆钮与药丸端头弧心对齐（环隙与上下等宽）。
            contentPadding = EtaFloatingToolbarConcentricPadding(
                IconButtonDefaults.smallContainerSize().width,
            ),
        ) {
            // 动作组：固定 8dp 间距的一行图标按钮（EtaIconButton 的 `Standard` 档裸图标）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtaIconButton(
                    icon = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                    contentDescription = stringResource(
                        if (allSelected) R.string.media_action_deselect_all else R.string.media_action_select_all,
                    ),
                    onClick = onSelectAll,
                    enabled = enabled,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                actions.forEach { action ->
                    if (action.destructive) {
                        // 破坏性操作：图标用 error 色区分（官方裸 IconButton，容器/图标尺寸
                        // 与 EtaIconButton 的 `Standard` + S 档一致），执行前的确认弹窗由页面负责。
                        EtaTooltip(text = action.contentDescription) {
                            androidx.compose.material3.IconButton(
                                onClick = action.onClick,
                                enabled = enabled && selectedCount > 0 && action.enabled,
                                modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = action.contentDescription,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                                )
                            }
                        }
                    } else {
                        EtaIconButton(
                            icon = action.icon,
                            contentDescription = action.contentDescription,
                            onClick = action.onClick,
                            enabled = enabled && selectedCount > 0 && action.enabled,
                            size = EtaIconSize.S,
                            style = EtaButtonStyle.Standard,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶栏多选入口原位切换：Checklist→Close，退出并清空勾选。「取消」不落底栏，
 * 由入口图标原位切换承载（与各页既有形态一致）。触觉由 [onToggle] 调用方负责。
 */
@Composable
fun RowScope.EtaSelectionTopBarAction(
    selectionMode: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    TopBarIconButton(
        icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
        contentDescription = stringResource(
            if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,
        ),
        onClick = onToggle,
        enabled = enabled,
    )
}

/**
 * 段标题/计数行落点的多选入口（纯图标）：多选态原位变「取消」，退出并清空勾选。
 * 触觉由 [onToggle] 调用方负责。
 */
@Composable
fun EtaSelectionEntryAction(
    selectionMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    EtaIconButton(
        icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
        contentDescription = stringResource(
            if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,
        ),
        onClick = onToggle,
        enabled = enabled,
        modifier = modifier,
        size = EtaIconSize.S,
        style = EtaButtonStyle.Standard,
    )
}

/**
 * 多选操作栏的进退场装配：底部居中悬浮，位移走 M3E Spatial（轻微过冲）、
 * 透明度走 Effects（无过冲）。宿主是页面内容外的 `Box(Modifier.fillMaxSize())`。
 */
@Composable
fun BoxScope.EtaSelectionBarOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        ) { it } + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = slideOutVertically(
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        ) { it } + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        content()
    }
}

/**
 * 多选态的列表底部占位：给悬浮药丸让位（88dp 药丸区 + 24dp 常态余量），随导航条避让。
 */
@Composable
fun EtaSelectionBottomSpacer(isSelecting: Boolean, modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(if (isSelecting) 88.dp else 24.dp)
            .navigationBarsPadding(),
    )
}
