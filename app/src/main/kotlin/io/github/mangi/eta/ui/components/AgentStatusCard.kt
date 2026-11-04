package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.adaptive.etaPrimary
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.model.ActiveRunSummaryUi
import io.github.mangi.eta.ui.model.RunStatusUi
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton

@Composable
fun AgentStatusCard(
    activeRun: ActiveRunSummaryUi,
    onOpenRun: () -> Unit,
    onStopRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // M3 Expressive：官方 Card(onClick) 承担容器形状、配色、海拔与按压反馈；
    // 官方 TextButton 承担按钮形状（胶囊）、容器高度与标签排版；不覆盖任何默认令牌。
    M3Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        onClick = onOpenRun,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicator(status = activeRun.status)
                Spacer(modifier = Modifier.width(10.dp))
                M3Text(
                    text = activeRun.title,
                    modifier = Modifier.weight(1f),
                    style = etaTextStyle(EtaType.Body, emphasized = true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            M3Text(
                text = activeRun.currentStep,
                style = etaTextStyle(EtaType.Secondary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                M3Text(
                    text = activeRun.elapsedLabel,
                    modifier = Modifier.weight(1f),
                    style = etaTextStyle(EtaType.Caption),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (activeRun.status == RunStatusUi.Running) {
                    M3TextButton(onClick = onStopRun) {
                        M3Text(
                            text = stringResource(R.string.ui_stop_a17f70),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusIndicator(status: RunStatusUi) {
    // M3 Expressive：运行中用官方 LoadingIndicator（morph 变形，与工具行、朗读按钮
    // 同一套 loading 语言），其余状态用官方 Icon；尺寸取官方小号图标按钮的图标令牌，
    // 颜色只用官方 primary 角色。
    val indicatorSize = IconButtonDefaults.extraSmallIconSize
    val indicatorTint = etaPrimary()
    // 进度指示 ⇄ 结果图标的切换走同一套 Expressive 淡入 + 轻微放大，不再硬换。
    val motionScheme = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.8f, animationSpec = motionScheme.defaultSpatialSpec()))
                .togetherWith(fadeOut(motionScheme.fastEffectsSpec()))
        },
        label = "runStatus",
    ) { state ->
    when (state) {
        RunStatusUi.Running -> LoadingIndicator(
            modifier = Modifier.size(indicatorSize),
            color = indicatorTint,
        )
        RunStatusUi.Success -> Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(indicatorSize),
            tint = indicatorTint,
        )
        RunStatusUi.Failed -> Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            modifier = Modifier.size(indicatorSize),
            tint = indicatorTint,
        )
        RunStatusUi.Cancelled -> Icon(
            imageVector = Icons.Rounded.MoreHoriz,
            contentDescription = null,
            modifier = Modifier.size(indicatorSize),
            tint = etaOnSurfaceVariant(),
        )
    }
    }
}
