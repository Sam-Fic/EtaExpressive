package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import io.github.mangi.eta.ui.adaptive.EtaDecorativeShape
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.etaRandomDecorativeShape

/**
 * 整页状态页（status page）：官方 Empty state 观感——装饰形状垫底的大图标 + 主标语 +
 * （可选）次级说明与动作槽。用于「没有任何条目」的整页零态；列表内嵌的轻量空态走
 * [ListEmptyState]，两者是并存的两个档位，不互相替代。
 *
 * 取证（AGENTS.md 强制规则：先确认官方有没有现成组件）：material3 1.5.0-alpha29 没有
 * 空态/状态页组件——AAR（~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3/
 * material3-android/1.5.0-alpha29/473974471875087c1b82fd3112636010add0e7f4/material3.aar）
 * 的 classes.jar 全类清单按 Info/Platter/Status/Empty/Placeholder/Banner/Notice/Splash
 * 筛选为空集，官方 release notes（1.5.0-alpha01–alpha29、1.4.0 stable 新组件总表）亦无
 * 对应条目。故本组件是官方积木的组合而非自造 M3 组件：图标坐在 [EtaDecorativeShape]
 * （形状轮廓取官方 `androidx.compose.material3.MaterialShapes`，填充为官方色角色降 alpha）
 * 正中；尺寸取 [EtaDimens.EmptyStateShapeSize]（128dp）/ [EtaDimens.EmptyStateGraphicIconSize]
 * （72dp）既有空态令牌；主标语 = bodyLarge + onSurfaceVariant（EtaText Body 档显式弱化色，
 * 与官方 Text(bodyLarge, onSurfaceVariant) 取值一致）。
 *
 * 版式单源自 [io.github.mangi.eta.ui.adaptive.EtaSearchPage] 的原内联空态——早年
 * EtaSearchPage 与媒体搜索页各持一份近似复制，现收敛到本组件。
 *
 * @param icon 图标（与所在页面条目的行首图标同款语义，如 MCP 服务器页的 Dns）。
 * @param title 状态主标语。
 * @param summary 可选次级说明（Secondary 档正文）。
 * @param action 可选动作槽（页内补充按钮）；整页零态的主动线一般仍交给页面 FAB，避免双入口。
 * @param shape 装饰形状轮廓；缺省每次进页面随机换一枚官方轮廓、同一次停留内保持稳定
 *   （见 [etaRandomDecorativeShape]）。
 */
@Composable
internal fun EtaStatusPage(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    action: (@Composable () -> Unit)? = null,
    shape: RoundedPolygon? = null,
) {
    val resolvedShape = shape ?: remember { etaRandomDecorativeShape() }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                modifier = Modifier.size(EtaDimens.EmptyStateShapeSize),
                contentAlignment = Alignment.Center,
            ) {
                // 图标坐在一块 M3E 装饰形状正中：形状垫在图标下方四周均匀露出，
                // 纯装饰不承载信息（轮廓与弱化色的官方取值见 [EtaDecorativeShape] KDoc）。
                EtaDecorativeShape(
                    modifier = Modifier.size(EtaDimens.EmptyStateShapeSize),
                    shape = resolvedShape,
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(EtaDimens.EmptyStateGraphicIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 标语与次级说明的关系对齐设置行的 headline/supporting 节奏（4dp），
            // 与上方图标组之间保持 16dp 的呼吸感。
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EtaText(
                    text = title,
                    style = EtaTextStyle.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                summary?.let {
                    EtaText(
                        text = it,
                        style = EtaTextStyle.Secondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            action?.invoke()
        }
    }
}
