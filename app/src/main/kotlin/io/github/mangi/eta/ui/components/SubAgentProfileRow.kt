package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.agent.model.ImageResolutionTier
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.TtsModelPickerDialog
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaTrailingChevron
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** Pickers capture both editor identity and profile binding; a changed owner/run dismisses them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentProfileRow(profile: SubAgentProfile, providers: List<ProviderSetting>, enabled: Boolean = true,
    settings: Boolean = false, header: (@Composable () -> Unit)? = null,
    draftAllowed: Boolean = enabled, draftRetry: (() -> Unit)? = null) {
    val editor = LocalConversationSubAgentEditor.current
    val usable = enabled && editor?.enabled == true
    val view = LocalView.current
    val currentUsable by rememberUpdatedState(usable)
    var modelPicker by remember(editor, profile.id) { mutableStateOf(false) }
    var thinkingPicker by remember(editor, profile.id) { mutableStateOf(false) }
    var profileDraft by remember(editor, profile.id) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
    LaunchedEffect(usable, editor) {
        if (!usable) { modelPicker = false; thinkingPicker = false }
    }
    LaunchedEffect(profile.role) { thinkingPicker = false; modelPicker = false }
    val config = remember(profile.providerId, profile.modelId, profile.role, providers) {
        resolveSubAgentProfileConfig(profile, providers)
    }
    val thinking = resolveSubAgentProfileThinking(profile, config)
    val canThink = thinking.canThink
    val efforts = thinking.efforts
    val effective = thinking.effective
    val thinkingLabel = thinkingStateLabel(config, profile, thinking, stringResource(R.string.sap_select_model))
    LaunchedEffect(profile.providerId, profile.modelId) { thinkingPicker = false }
    Column(verticalArrangement = Arrangement.spacedBy(if (settings) 0.dp else 12.dp)) {
        if (settings) {
            // 设置块与标题行同处一个 KernelSU 式分段列：只有组首（标题行）与组末（并行上限）
            // 接管 16dp 外圆角，中间行是 4dp 内圆角 + 2dp 缝隙。此前标题行和设置块各自
            // 起一个 EtaSegmentedColumn，导致同一个子代理被切成两张断开的卡片。
            EtaSegmentedColumn(Modifier.fillMaxWidth()) {
                header?.let { headerContent -> item(key = "header") { headerContent() } }
                item(key = "model") {
                    SubAgentSettingsRow(
                        stringResource(R.string.sap_model), config?.let { it.modelDisplayName.ifBlank { it.model } }
                            ?: if (profile.modelId.isBlank()) stringResource(R.string.sap_select_model) else stringResource(R.string.sap_model_unavailable),
                        Icons.Rounded.ViewInAr, stringResource(R.string.spr_model_for, profile.name), enabled = usable, badge = config?.providerName,
                        onClick = { if (currentUsable) modelPicker = true },
                        onLongClick = { if (currentUsable && canThink) thinkingPicker = true },
                        trailing = { SubAgentGptSpeedButton(profile, providers, usable) })
                }
                item(key = "role") {
                    // 与配置弹窗、上下文压缩页「请求接口」行同款：原位弹出锚定菜单，点选即写入。
                    SubAgentProfileRoleRow(
                        currentRole = profile.role,
                        description = stringResource(R.string.spr_select_role_for, profile.name),
                        enabled = usable,
                        onSelected = { role ->
                            if (currentUsable) editor?.updateProfile(profile.id) { it.withRole(role) }
                        },
                    )
                }
                if (profile.supportsTaskTier) item(key = "tier") {
                    SubAgentProfileTierRow(
                        currentTier = profile.tier,
                        description = stringResource(R.string.spr_set_tier_for, profile.name),
                        enabled = usable,
                        onSelected = { tier ->
                            if (currentUsable) editor?.updateProfile(profile.id) { latest ->
                                if (latest.supportsTaskTier) latest.copy(tier = tier) else latest
                            }
                        },
                    )
                }
                if (profile.role == "image_generation") item(key = "resolution") {
                    // 与配置弹窗同款：原位弹出锚定菜单；Grok 端点只留低/高，当前值始终保留以免显示错位。
                    SubAgentProfileResolutionRow(
                        currentResolution = profile.imageResolution,
                        verifiedGrok = grokImageBinding(config),
                        icon = Icons.Rounded.ViewInAr,
                        description = stringResource(R.string.spr_set_resolution_for, profile.name),
                        enabled = usable && config != null,
                        onSelected = { tier ->
                            if (currentUsable) editor?.updateProfile(profile.id) { latest ->
                                if (latest.providerId == profile.providerId && latest.modelId == profile.modelId && latest.role == "image_generation") latest.copy(imageResolution = tier) else latest
                            }
                        },
                    )
                }
                item(key = "thinking") {
                    // 当前值不在可选档位里时用行上状态文本占位，占位项不可写入。
                    SubAgentProfileThinkingRow(
                        state = thinking,
                        placeholderLabel = thinkingLabel,
                        description = stringResource(R.string.spr_adjust_thinking_for, profile.name),
                        enabled = usable && canThink,
                        onSelected = { next ->
                            if (currentUsable) editor?.updateProfile(profile.id) { latest ->
                                if (latest.role == profile.role && latest.providerId == profile.providerId && latest.modelId == profile.modelId)
                                    latest.copy(reasoning = next) else latest
                            }
                        },
                    )
                }
                addParallelLimitItem(profile, config, usable)
            }
        } else {
            // 与技能、预设详情页的兄弟行同一套写法：外层 EtaSegmentedRow 只负责分段卡片
            // 形状/底色与官方行内边距（choiceRow 保持官方 ContentPadding），行本身不挂 onClick，
            // 语义不合并；正文块单独可点，GPT 速度 Chip 与「配置」IconButton 保持 Row 的
            // 兄弟节点，各自独立寻址、互不吞进同一次点按。
            EtaSegmentedRow(
                modifier = Modifier.fillMaxWidth(),
                enabled = usable,
                choiceRow = true,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .combinedClickable(
                                enabled = usable,
                                onLongClickLabel = stringResource(R.string.spr_adjust_thinking_for, profile.name),
                                onLongClick = { if (currentUsable && canThink) { thinkingPicker = true } },
                                onClick = { if (currentUsable) { TouchHaptics.click(view); modelPicker = true } },
                            ),
                    ) {
                        Text(profile.name,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(config?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (profile.modelId.isBlank()) stringResource(R.string.sap_none) else stringResource(R.string.sap_model_unavailable),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (config != null) Text(config.providerName + stringResource(R.string.spr_thinking_suffix, thinkingLabel),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SubAgentGptSpeedButton(profile, providers, usable)
                    // 官方 IconButton：容器尺寸取 IconButtonDefaults 默认值（内部已 minimumInteractiveComponentSize()），
                    // 图标尺寸取 Icon 默认值，不再手写 48.dp / 20.dp。
                    IconButton(enabled = usable, onClick = {
                        if (currentUsable && editor != null) {
                            SubAgentProfileDraftSession.open(editor, profile)?.let {
                                TouchHaptics.click(view)
                                profileDraft = it
                            }
                        }
                    }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.MoreVert, stringResource(R.string.spr_config_for, profile.name))
                    }
                }
            }
        }
    }
    profileDraft?.let { session ->
        SubAgentProfileConfigDialog(session, editor, providers, enabled = draftAllowed, onDismiss = {
            session.dismiss()
            if (profileDraft === session) profileDraft = null
        }, onRetry = { if (draftRetry != null) draftRetry() else session.editor.retry() })
    }
    if (usable && modelPicker) {
        val models = profileCompatibleModels(providers, profile)
        TtsModelPickerDialog(models, true, { modelPicker = false }, { provider, model ->
            if (currentUsable) editor?.saveModel(profile.id, ModelFeatureSelection(true, provider, model), expected = profile)
            modelPicker = false
        }, stringResource(R.string.sap_select_model_for, profile.name), onClearSelection = {
            if (currentUsable) editor?.saveModel(profile.id, ModelFeatureSelection(true, "", ""), expected = profile)
            modelPicker = false
        }, highlightSelection = true)
    }
    if (usable && thinkingPicker && config != null && canThink) {
        val effort = requireNotNull(effective)
        val options = efforts
        ThinkingEffortPickerDialog(true, effort, options.ifEmpty { listOf(effort) }, { thinkingPicker = false }, { next ->
            if (currentUsable && next in options) editor?.updateProfile(profile.id) { latest ->
                if (latest.role == profile.role && latest.providerId == profile.providerId && latest.modelId == profile.modelId)
                    latest.copy(reasoning = next) else latest
            }
        }, description = stringResource(R.string.spr_thinking_desc, profile.name) +
            if (profile.isMedia) stringResource(R.string.spr_thinking_endpoint) else "")
    }
}

/**
 * 设置态分段行：标题为主行（content），取值为次要行（supportingContent），
 * 与子代理配置弹窗、MCP / 技能等兄弟页面的 [EtaSegmentedRow] 形态一致。
 * 窄屏下取值不再被固定宽度的标签挤成逐字折行。
 *
 * 尾部可挂额外控件（如 GPT 速度 Chip）、provider badge 与箭头。图标、取值与箭头都
 * 不写显式 tint/color，交给官方行在禁用态统一压暗——旧实现显式取 onSurface，
 * 会让「推理强度」「并行上限」在禁用时仍是黑图标黑箭头。
 *
 * 轻触反馈由 [EtaSegmentedRow] 自己触发，调用方**不要**在 onClick 里再写 TouchHaptics。
 */
@Composable
internal fun SubAgentSettingsRow(
    label: String,
    value: String,
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    badge: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        leadingContent = {
            Icon(icon, contentDescription = null)
        },
        supportingContent = {
            Text(
                text = value,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EtaDimens.TrailingElementGap),
            ) {
                if (!badge.isNullOrBlank()) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(
                            text = badge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                trailing?.invoke()
                EtaTrailingChevron()
            }
        },
    ) {
        Text(
            text = label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * ===== 共享值行卡（设置块与配置弹窗两处共用）=====
 *
 * 以下行组件只负责渲染与档位/过滤逻辑，写入目标由 [SubAgentProfileRoleRow.onSelected] 等
 * 闭包决定：页面侧即时写 editor，弹窗侧写草稿 session（各自的 canCommit/currentUsable 守卫
 * 留在调用方）；容器包装（页面直排 item vs 弹窗 cardItem + EtaSegmentedItemContainer）同样
 * 留在调用方，行本体不感知所在窗口——「逐像素同款」由共用本体保证，不再靠注释维护。
 */

/** 职责行：四角色原位下拉（与上下文压缩页「请求接口」行同款），点选回调选中的角色值。 */
@Composable
internal fun SubAgentProfileRoleRow(
    currentRole: String,
    description: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    val roles = listOf(
        "implementation" to stringResource(R.string.sap_role_impl),
        "review" to stringResource(R.string.sap_role_review),
        "image_generation" to stringResource(R.string.sap_role_image),
        "video_generation" to stringResource(R.string.sap_role_video),
    )
    EtaSegmentedDropdownRow(
        title = stringResource(R.string.sap_role),
        items = roles.map { it.second },
        selectedIndex = roles.indexOfFirst { it.first == currentRole },
        onItemSelected = { index ->
            roles.getOrNull(index)?.first?.let(onSelected)
        },
        icon = Icons.AutoMirrored.Rounded.Assignment,
        enabled = enabled,
        description = description,
    )
}

/** 任务分工行：null（跟随端点）+ 全部档位；[summary] 仅配置弹窗需要（端点注记）。 */
@Composable
internal fun SubAgentProfileTierRow(
    currentTier: SubAgentTaskTier?,
    description: String,
    enabled: Boolean,
    onSelected: (SubAgentTaskTier?) -> Unit,
    summary: String? = null,
) {
    val tiers = listOf<SubAgentTaskTier?>(null) + SubAgentTaskTier.entries
    EtaSegmentedDropdownRow(
        title = stringResource(R.string.sap_task_tier),
        summary = summary,
        items = tiers.map { tier ->
            tier?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_tier_unset)
        },
        selectedIndex = tiers.indexOf(currentTier),
        onItemSelected = { index ->
            if (index in tiers.indices) onSelected(tiers[index])
        },
        icon = Icons.Rounded.AccountTree,
        enabled = enabled,
        description = description,
    )
}

/**
 * 默认分辨率行：Grok 端点只留低/高，当前值始终保留以免取值显示错位。
 * 图标两处刻意不同（页面 ViewInAr / 弹窗 Image），经 [icon] 传入；[summary] 仅弹窗需要。
 */
@Composable
internal fun SubAgentProfileResolutionRow(
    currentResolution: String?,
    verifiedGrok: Boolean,
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onSelected: (String?) -> Unit,
    summary: String? = null,
) {
    val tiers = listOf<String?>(null) + ImageResolutionTier.values.filter {
        !verifiedGrok || it in setOf("low", "high") || it == currentResolution
    }
    EtaSegmentedDropdownRow(
        title = stringResource(R.string.sap_default_resolution),
        summary = summary,
        items = tiers.map { tier ->
            tier?.let(ImageResolutionTier::labelRes)?.let { stringResource(it) }
                ?: stringResource(R.string.sap_follow_endpoint)
        },
        selectedIndex = tiers.indexOf(currentResolution),
        onItemSelected = { index ->
            if (index in tiers.indices) onSelected(tiers[index])
        },
        icon = icon,
        enabled = enabled,
        description = description,
    )
}

/** 思维档行：当前值不在可选档位里时用 [placeholderLabel] 占位（无模型/端点不支持/未启用/需重选），占位项不可写入。 */
@Composable
internal fun SubAgentProfileThinkingRow(
    state: SubAgentProfileThinkingState,
    placeholderLabel: String,
    description: String,
    enabled: Boolean,
    onSelected: (ReasoningEffort) -> Unit,
) {
    val matchedIndex = if (state.canThink) state.efforts.indexOf(state.effective) else -1
    val effortLabels = state.efforts.map { effort -> stringResource(effort.labelRes) }
    val items = if (matchedIndex >= 0) effortLabels else listOf(placeholderLabel) + effortLabels
    EtaSegmentedDropdownRow(
        title = stringResource(R.string.sap_thinking),
        items = items,
        selectedIndex = if (matchedIndex >= 0) matchedIndex else 0,
        onItemSelected = { index ->
            state.efforts.getOrNull(if (matchedIndex >= 0) index else index - 1)
                ?.let(onSelected)
        },
        icon = Icons.Rounded.AutoAwesome,
        enabled = enabled,
        description = description,
    )
}

/**
 * profile 标题行：页面 [SubAgentProfileHeaderRow] 与配置弹窗 header 卡的共享本体。
 *
 * 角色图标 + 只读名称正文 + 行尾 Expressive 开关；[menuContent] 渲染在开关之前
 * （页面溢出菜单槽，与技能页同序：先菜单再开关）；[onRowClick] 非空时整行可点
 * （弹窗改名入口）。[name] 供开关语义（sas_enable_for），[nameText] 供正文展示
 * （弹窗空名时显示占位文案，语义仍用实名）。
 */
@Composable
internal fun SubAgentProfileHeaderCard(
    role: String,
    name: String,
    enabled: Boolean,
    switchEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    nameText: String = name,
    onRowClick: (() -> Unit)? = null,
    menuContent: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    EtaSegmentedRow(
        modifier = modifier,
        enabled = switchEnabled,
        choiceRow = true,
        onClick = onRowClick,
        leadingContent = {
            // 不显式 tint：让官方行内容色在禁用态把图标一并压暗。
            Icon(
                imageVector = when (role) {
                    "review" -> Icons.AutoMirrored.Rounded.FactCheck
                    "image_generation" -> Icons.Rounded.Image
                    "video_generation" -> Icons.Rounded.Videocam
                    else -> Icons.Rounded.AccountTree
                },
                contentDescription = null,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                menuContent?.invoke()
                // 开关尺寸交给官方 EtaSwitch；可访问名必须落在真正可切换的节点上
                //（ListItem 命中测试不透传外层包裹盒的点击）。
                EtaSwitch(
                    checked = enabled,
                    enabled = switchEnabled,
                    modifier = Modifier.semantics {
                        contentDescription = context.getString(R.string.sas_enable_for, name)
                    },
                    onCheckedChange = onEnabledChange,
                )
            }
        },
    ) {
        Text(
            text = nameText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
