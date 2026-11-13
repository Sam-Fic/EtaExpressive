package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.app.AgentQuestionProjection
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi

/**
 * 危险操作确认的同意选项 id（与 `AgentDangerousConfirmationGate` 的协议一致）：
 * 这些选项会把操作真的执行下去，因此用 destructive 样式渲染。
 */
private val DANGEROUS_CONFIRM_OPTION_IDS = setOf("confirm_once", "confirm_session")

/**
 * 问答卡：与思考/工具卡片同一套 Material 3 Expressive 官方 `SegmentedListItem` 结构
 * （[AgentWorkSegmentedBubble]），标题行是父条目、表单与回执是展开后的详情子条目。
 *
 * Draft and submission state stay in the conversation store. Only whole-card disclosure
 * is local; the form itself is never folded in parts.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentQuestionCard(
    message: AgentQuestionMessageUi,
    modifier: Modifier = Modifier,
    position: SegmentedRowPosition = SegmentedRowPosition.Whole,
    /** 上/下沿与相邻卡片行按组缝相接：对应沿取内圆（外距由调用方 padding 负责）。 */
    seamAboveCard: Boolean = false,
    seamBelowCard: Boolean = false,
    onDraftChanged: (AgentQuestionAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    // A recycled chat slot must not inherit another conversation/run/call/question's controls.
    key(AgentQuestionProjection.messageId(message.request)) {
        AgentQuestionCardContent(message, modifier, position, seamAboveCard, seamBelowCard, onDraftChanged, onSubmit)
    }
}

@Composable
private fun AgentQuestionCardContent(
    message: AgentQuestionMessageUi,
    modifier: Modifier,
    position: SegmentedRowPosition,
    seamAboveCard: Boolean,
    seamBelowCard: Boolean,
    onDraftChanged: (AgentQuestionAnswer) -> Unit,
    onSubmit: () -> Unit,
) {
    val request = message.request
    val waiting = message.status == AgentQuestionStatus.Waiting
    // Submission is guarded by the store. A local latch could stay locked when a fast failure
    // returns to the original snapshot before Compose observes the intermediate submitting state.
    // Only authoritative status transitions reset disclosure. Submitting/retry keep the form.
    var expanded by remember(message.status) { mutableStateOf(waiting) }
    // 展开前解除底部锚定，标题钉在原位、内容向下生长可见（与思考/工具行同一契约；
    // 展开/收起方向本身由标准气泡统一，不再按锚定状态分方向）。
    val releaseAnchor = LocalChatAnchorRelease.current
    val toggle: () -> Unit = {
        releaseAnchor(!expanded)
        expanded = !expanded
    }
    // 展开后的详情子条目：标题、问题正文与表单/只读回执。与思考/工具行同款——
    // 先落到带类型的局部值上，详情子条目自带官方 ContentPadding（左右 16dp），
    // 内容列不再自带水平边距。
    val questionDetails: @Composable AnimatedVisibilityScope.() -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().then(retainDrawLayerWhenIdle()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(request.title, modifier = Modifier.fillMaxWidth(),
                style = chatExpandedBodyStyle())
            Text(request.question, modifier = Modifier.fillMaxWidth(),
                style = chatExpandedBodyStyle())
            if (!waiting) {
                QuestionReadOnlyDetails(message)
            } else {
                val editable = !message.submitting
                val displayed = AgentQuestionProjection.draftAnswer(message)
                val draft = AgentQuestionAnswer(message.answerKind, message.selectedOptionId, message.otherText, message.note)
                val valid = AgentQuestionCodec.validateAnswer(request, displayed).accepted
                val changeDraft: (AgentQuestionAnswer) -> Unit = { if (editable) onDraftChanged(it) }
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    request.options.forEach { option ->
                        key(option.id) {
                            val selected = displayed.kind == "option" && displayed.optionId == option.id
                            QuestionOptionRow(option.label, option.description, selected, editable,
                                option.id == request.recommendedOptionId,
                                destructive = option.id in DANGEROUS_CONFIRM_OPTION_IDS) {
                                changeDraft(draft.copy(kind = "option", optionId = option.id))
                            }
                        }
                    }
                    if (request.allowOther) {
                        QuestionOptionRow(stringResource(R.string.question_other), "",
                            displayed.kind == "other", editable, false) {
                            changeDraft(draft.copy(kind = "other", optionId = null))
                        }
                        if (displayed.kind == "other") QuestionTextField(
                            value = message.otherText,
                            onValueChange = { changeDraft(draft.copy(kind = "other", optionId = null, otherText = it.take(2000))) },
                            enabled = editable, label = stringResource(R.string.question_other_hint))
                    }
                    if (request.allowDelegation) QuestionOptionRow(stringResource(R.string.question_delegate),
                        stringResource(R.string.question_delegate_hint), displayed.kind == "delegate", editable,
                        false) { changeDraft(draft.copy(kind = "delegate", optionId = null)) }
                }
                if (request.allowNote) QuestionTextField(value = message.note,
                    onValueChange = { changeDraft(draft.copy(note = it.take(2000))) }, enabled = editable,
                    label = stringResource(R.string.question_note))
                message.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                // M3 Expressive：官方 Button 的默认形状、容器高度、内边距、
                // primary / onPrimary 与禁用态颜色全部交给官方默认值。
                Button(
                    onClick = { if (editable && valid) onSubmit() },
                    enabled = editable && valid,
                    modifier = Modifier.fillMaxWidth(),
                    // 容器型按钮直用必须传官方 shapes（AGENTS §2.5 按压 morph）。
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(if (message.submitting) R.string.question_submitting else R.string.question_submit))
                }
            }
        }
    }
    AgentWorkSegmentedBubble(
        leading = {
            // 等待作答是唯一需要被注意的状态（primary）；已回答/取消/中断是只读回执，
            // 与设置页行首图标同色不抢注意力。
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = null,
                tint = if (waiting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        title = "${questionStatusText(message.status)} · ${request.title}",
        // 回执标题单行省略：完整标题与问题正文都在展开后的详情里（与旧卡一致，
        // 收起态保持一整行）。颜色走官方默认——与思考/工具行标题同一档。
        titleMaxLines = 1,
        trailingStatus = if (message.status == AgentQuestionStatus.Answered) {
            {
                // 状态已在标题里播报，图标不重复 description；与工具行成功态同款对勾。
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        expanded = expanded,
        onToggle = toggle,
        details = questionDetails,
        modifier = modifier.testTag("question-card-surface"),
        position = position,
        seamAboveCard = seamAboveCard,
        seamBelowCard = seamBelowCard,
    )
}

@Composable
private fun questionStatusText(status: AgentQuestionStatus): String = stringResource(when (status) {
    AgentQuestionStatus.Waiting -> R.string.question_heading
    AgentQuestionStatus.Answered -> R.string.question_answered
    AgentQuestionStatus.Cancelled -> R.string.question_cancelled
    AgentQuestionStatus.Interrupted -> R.string.question_interrupted
})

@Composable
private fun QuestionOptionRow(
    label: String, description: String, selected: Boolean, enabled: Boolean, recommended: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    // M3 Expressive：官方单选 ListItem 承担整行命中区、选中容器色、涟漪与 Role.RadioButton；
    // 单选圆点用官方 RadioButton（不自绘，也不手拼 selectable + 背景）。
    // 危险操作的同意选项用 error 容器色，和其它选项在视觉上分开。
    ListItem(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            selectedContainerColor = if (destructive) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
                colors = if (destructive) {
                    RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error)
                } else {
                    RadioButtonDefaults.colors()
                },
            )
        },
        supportingContent = if (description.isNotBlank()) {
            {
                // 官方 supporting 槽位默认：bodyMedium + onSurfaceVariant。
                Text(text = description)
            }
        } else {
            null
        },
        content = { QuestionOptionLabel(label, recommended) },
    )
}

@Composable
private fun QuestionOptionLabel(label: String, recommended: Boolean) {
    // M3 Expressive：「推荐」标签用官方 Badge（官方容器色与内边距），不自绘底色圆角块。
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 官方 content 槽位默认：bodyLarge + contentColor，不显式传样式。
        Text(label, Modifier.weight(1f))
        if (recommended) {
            Badge(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Text(stringResource(R.string.question_recommended), style = etaTextStyle(EtaType.Micro))
            }
        }
    }
}

@Composable
private fun QuestionTextField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, label: String) {
    EtaFormTextField(value = value, onValueChange = onValueChange, enabled = enabled, hint = label,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), minLines = 1, maxLines = Int.MAX_VALUE)
}

@Composable
private fun questionSummary(message: AgentQuestionMessageUi): String? {
    if (message.status != AgentQuestionStatus.Answered) return null
    // Never turn an uncommitted draft into a historical answer. The header already shows status.
    val answer = message.answer ?: return null
    val choice = when (answer.kind) {
        "option" -> message.request.options.firstOrNull { it.id == answer.optionId }?.label ?: return null
        "other" -> stringResource(R.string.question_other) + " " + answer.otherText
        "delegate" -> stringResource(R.string.question_delegate)
        else -> return null
    }
    return stringResource(R.string.question_selected, choice)
}

/** Historical details are text, not a disabled form; they can never submit or mutate a draft. */
@Composable
private fun QuestionReadOnlyDetails(message: AgentQuestionMessageUi) {
    val request = message.request
    // Only the submitted answer is authority. Never read selectedOptionId, otherText or note drafts here.
    val answer = message.answer.takeIf { message.status == AgentQuestionStatus.Answered }
    // 档位与思考/工具详情同源：正文 chatExpandedBodyStyle（14sp/onSurfaceVariant），
    // 小标签 chatQuaternaryStyle（labelSmall）+ onSurfaceVariant——不另设字号与颜色。
    val bodyStyle = chatExpandedBodyStyle()
    val labelStyle = chatQuaternaryStyle()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        questionSummary(message)?.let { summary ->
            // This is the only summary. Custom answers remain complete, even when very long.
            Text(summary, modifier = Modifier.fillMaxWidth(), style = bodyStyle)
        }
        if (answer == null) {
            // Unanswered history may show the original options, never an uncommitted draft.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                request.options.forEach { option ->
                    QuestionHistoryOption(option.label, option.description, selected = false,
                        recommended = option.id == request.recommendedOptionId)
                }
            }
        } else {
            when (answer.kind) {
                "option" -> request.options.firstOrNull { it.id == answer.optionId }?.description
                    ?.takeIf { it.isNotBlank() }?.let {
                        Text(it, modifier = Modifier.fillMaxWidth(), style = bodyStyle)
                    }
                "delegate" -> Text(stringResource(R.string.question_delegate_hint), modifier = Modifier.fillMaxWidth(),
                    style = bodyStyle)
                else -> Unit
            }
        }
        answer?.note?.takeIf { it.isNotBlank() }?.let { note ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.question_note), style = labelStyle, color = labelColor)
                Text(note, modifier = Modifier.fillMaxWidth(), style = bodyStyle)
            }
        }
    }
}

@Composable
private fun QuestionHistoryOption(label: String, description: String, selected: Boolean, recommended: Boolean = false) {
    // M3 Expressive：历史项是纯展示的官方 ListItem；未选中的圆点用官方禁用 RadioButton，
    // 不手绘圆形描边。
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        // 官方展示型 ListItem 已自行 mergeDescendants，不必重复声明。
        // 但它的 applySemantics 只在 !enabled 时给 disabled()，不设 selected，
        // 所以 selected 由本层补上（与单选重载不同：那个重载官方会自己设）。
        modifier = Modifier.semantics { this.selected = selected },
        trailingContent = {
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                RadioButton(selected = false, onClick = null, enabled = false)
            }
        },
        supportingContent = if (description.isNotBlank()) {
            {
                // 官方 supporting 槽位默认：bodyMedium + onSurfaceVariant。
                Text(text = description)
            }
        } else {
            null
        },
        content = { QuestionOptionLabel(label, recommended) },
    )
}
