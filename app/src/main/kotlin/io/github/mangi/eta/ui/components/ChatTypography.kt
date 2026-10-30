package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.adaptive.etaTextStyle

/**
 * 聊天界面排版访问器：把 L1 字阶（[EtaType]）固定成聊天语境的命名档位，
 * 全部转包给 [etaTextStyle]，不自定义任何字号/行高/字重。
 *
 * 此前三个访问器私藏在 `ChatMessageItem.kt`，跨文件使用靠同包可见性「顺手」引用；
 * 现在独立成文件作为唯一出处，新代码不再往单个文件里加私有排版 helper。
 */

/** 正文档位（官方 bodyLarge）。 */
@Composable
internal fun chatBodyStyle(): TextStyle = etaTextStyle(EtaType.Body)

/** 次级/标题正文档（官方 bodyMedium）。 */
@Composable
internal fun chatSecondaryStyle(): TextStyle = etaTextStyle(EtaType.Secondary)

/** 说明档（官方 bodySmall）。 */
@Composable
internal fun chatTertiaryStyle(): TextStyle = etaTextStyle(EtaType.Caption)

/** 极小注档（官方 labelSmall）。 */
@Composable
internal fun chatQuaternaryStyle(): TextStyle = etaTextStyle(EtaType.Micro)

/**
 * 展开内容正文档位：思考块展开内容与各工具详情文字的唯一共用出处
 * （14sp / 行高 22sp / onSurfaceVariant）。命令与参数块用 [monospace]
 * 叠加等宽字体——按约定只换字体，字号与颜色档位保持一致。
 */
@Composable
internal fun chatExpandedBodyStyle(monospace: Boolean = false): TextStyle =
    chatSecondaryStyle().copy(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = etaOnSurfaceVariant(),
    ).let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it }
