package io.github.mangi.eta.ui.components

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.runtime.localizedText
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.ui.adaptive.EtaAlphas
import io.github.mangi.eta.ui.adaptive.etaGptSpeedColor
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 速度按钮的无障碍描述：说清当前档位与“点击切换”，刻意不引入长按语义。走资源，纯 JVM 单测回退中文。 */
internal fun subAgentGptSpeedDescription(name: String, mode: GptSpeedMode): String =
    localizedText(R.string.subagent_speed_content_description, "%1\$s GPT 速度：%2\$s，点击切换速度",
        name, gptSpeedDisplayName(mode))

/**
 * 子代理 GPT 速度按钮：仅 GPT 模型显示，单击在原位循环速度档位。
 *
 * 动画与主会话 [ThinkingEffortChip] 完全一致，直接复用 ThinkingSpeedAnimation.kt 的策略：
 * 450ms、旋转一整圈、缩放 1→1.12→1、[GptSpeedAnimationGate] 闸门；档位颜色走
 * [etaGptSpeedColor] 的官方角色映射（快速=tertiary、极速=error）。
 * 与主会话不同的是入口为“单击”而非长按：单击只切速度，绝不打开模型选择或思考菜单。
 *
 * 只有写回 [ConversationSubAgentPreferences.WriteResult.Saved] 才播放动画并弹 Toast；
 * 禁用 / 运行中 / 过期 / 被拒绝时不写、不动画、不弹 Toast，避免假成功反馈。
 */
@Composable
internal fun SubAgentGptSpeedButton(
    profile: SubAgentProfile,
    providers: List<ProviderSetting>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val editor = LocalConversationSubAgentEditor.current
    val usable = enabled && editor?.enabled == true
    val eligible = remember(profile.providerId, profile.modelId, providers) {
        val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
        supportsGptSpeedBinding(provider, model)
    }
    // 非 GPT 或不可用的模型入口都不渲染速度按钮。
    if (!eligible) return

    // Bind the scope too: owner/model changes cancel pending lookups and old feedback/animation.
    key(editor, profile.id, profile.providerId, profile.modelId) {
        BoundSubAgentGptSpeedButton(profile, editor, usable, modifier)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoundSubAgentGptSpeedButton(
    profile: SubAgentProfile,
    editor: ConversationSubAgentEditor?,
    usable: Boolean,
    modifier: Modifier,
) {
    val mode = profile.gptSpeedForModel(providerId = profile.providerId, modelId = profile.modelId)
    val gate = remember { GptSpeedAnimationGate() }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current

    val normalColor = MaterialTheme.colorScheme.onSurface
    // 颜色由 owner 传入的 mode 决定，非 GPT 不会走到这里；颜色插值属 Effects，走官方
    // motionScheme 的 effects 档（临界阻尼、无过冲）。
    val contentColor by animateColorAsState(
        targetValue = etaGptSpeedColor(mode, normalColor),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Color>(),
        label = "sub_agent_gpt_speed_color",
    )
    val displayedColor = if (usable) contentColor else contentColor.copy(alpha = EtaAlphas.DisabledContent)
    val semanticsModifier = modifier.semantics(mergeDescendants = true) {
        contentDescription = subAgentGptSpeedDescription(profile.name, mode)
    }

    // 动画规格必须在协程外取好：MaterialTheme.motionScheme 是 @Composable 读取，不能在协程里调用。
    val progressSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.defaultSpatialSpec()

    // 单击在原位循环速度档位。
    val cycleSpeed: () -> Unit = {
        // 动画中拒绝新的单击：不排队、不累计，避免截断当前一圈。
        val token = gate.request()
        if (token != 0) {
            scope.launch {
                try {
                    val result = editor?.cycleGptSpeed(
                        profile.id, profile.providerId, profile.modelId, expected = profile,
                    )
                    coroutineContext.ensureActive()
                    if (result !is ConversationSubAgentPreferences.WriteResult.Saved) return@launch
                    // Only the actual saved binding may produce success feedback.
                    val savedProfile = result.config.profiles.singleOrNull {
                        it.id == profile.id && it.providerId == profile.providerId && it.modelId == profile.modelId
                    } ?: return@launch
                    val savedMode = savedProfile.gptSpeedForModel(profile.providerId, profile.modelId)
                    TouchHaptics.click(view)
                    Toast.makeText(context, gptSpeedToastMessage(savedMode), Toast.LENGTH_SHORT).show()
                    progress.snapTo(0f)
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = progressSpec,
                    )
                } finally {
                    // Query, write, feedback and animation share one cancellation-safe gate.
                    gate.finish(token)
                }
            }
        }
    }

    // 官方 AssistChip 承担容器高度、形状、描边、涟漪、标签排版与禁用态；
    // 前置图标尺寸取官方 AssistChipDefaults.IconSize，只有品牌渐变色通过官方
    // AssistChipDefaults.assistChipColors(labelColor = …) 注入，不自定义任何形状/间距。
    AssistChip(
        onClick = cycleSpeed,
        // 触摸下限交给官方 Modifier.minimumInteractiveComponentSize()（读 LocalMinimumInteractiveComponentSize，默认 48.dp）。
        modifier = semanticsModifier.minimumInteractiveComponentSize(),
        enabled = usable,
        colors = AssistChipDefaults.assistChipColors(labelColor = displayedColor),
        leadingIcon = {
            Icon(
                // 官方图标替代自绘 ic_atom：AutoAwesome＝AI 思考/生成中。
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier
                    .size(AssistChipDefaults.IconSize)
                    .graphicsLayer {
                        rotationZ = gptSpeedRotationDegrees(progress.value)
                        val scale = gptSpeedScale(progress.value)
                        scaleX = scale
                        scaleY = scale
                    },
                tint = displayedColor,
            )
        },
        label = {
            Text(
                text = gptSpeedDisplayName(mode),
                style = MaterialTheme.typography.labelLarge,
                color = displayedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
