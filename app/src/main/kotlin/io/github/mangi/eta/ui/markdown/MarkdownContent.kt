package io.github.mangi.eta.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import io.github.mangi.eta.ui.adaptive.EtaDimens

/** 代码块 / 提示块 / 表格容器的圆角：取官方 shapes 令牌。 */
@Composable
private fun markdownBlockShape(): androidx.compose.ui.graphics.Shape =
    MaterialTheme.shapes.medium

/** 强调色：取官方 primary 角色。 */
@Composable
private fun markdownAccentColor(): Color = MaterialTheme.colorScheme.primary

/** 代码块顶部语言标签与列表勾选标记的排版：取官方 labelMedium。 */
@Composable
private fun markdownLabelStyle(): TextStyle = MaterialTheme.typography.labelMedium

/** 提示块标题排版：取官方 labelLarge。 */
@Composable
private fun markdownAlertTitleStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)

/**
 * 绘制一份已解析的 [MarkdownDocument]。
 *
 * 每个块以源码偏移作为组合 key；未变化的块是同一个实例，组合期直接跳过。传入
 * [reveal] 时文本块接入逐字显现：排版仍然一次完成，帧间推进只让绘制失效。
 * 文档与块有独立 display-list 边界，尾部显现不再把已完成兄弟块一起重新录制。
 */
@Composable
internal fun MarkdownContent(
    document: MarkdownDocument,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    reveal: SmoothTextRevealCoordinator? = null,
) {
    val scope = remember(style, reveal) { MarkdownRenderScope(style, reveal) }
    MarkdownBlockColumn(blocks = document.blocks, scope = scope, compact = false,
        modifier = modifier.markdownDocumentDrawLayer())
}

@Immutable
private class MarkdownRenderScope(
    val style: MarkdownStyle,
    val reveal: SmoothTextRevealCoordinator?,
) {
    fun muted() = MarkdownRenderScope(style.muted(), reveal)
}

/** 嵌套列表的层级只影响 marker 字形，用 CompositionLocal 传递，块组件不必逐层转发。 */
private val LocalListDepth = compositionLocalOf { 0 }

@Composable
private fun MarkdownBlockColumn(
    blocks: List<MarkdownBlock>,
    scope: MarkdownRenderScope,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            key(block.offset) {
                val previous = blocks.getOrNull(index - 1)
                val gap = if (compact) {
                    markdownCompactGap(previous, scope.style.tone)
                } else {
                    markdownBlockGap(previous, block, scope.style.tone)
                }
                if (gap.value > 0f) Spacer(Modifier.height(with(density) { gap.toDp() }))
                // Preserve a separate recording for decorations/compound blocks too. Nested
                // columns and list items have their own boundaries, so a long list/quote is
                // not flattened back into a single recording when its tail changes.
                Box(Modifier.markdownBlockDrawLayer(scope.style.tone == MarkdownTone.Answer),
                    propagateMinConstraints = true) {
                    MarkdownBlockView(block, scope)
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, scope: MarkdownRenderScope) {
    when (block) {
        is MarkdownParagraph -> MarkdownText(block, scope.style.body, scope)
        is MarkdownHeading -> MarkdownText(
            block = block,
            style = scope.style.heading(block.level),
            scope = scope,
            modifier = Modifier.semantics { heading() },
        )
        is MarkdownCode -> MarkdownCodeBlock(block, scope)
        is MarkdownQuote -> MarkdownQuoteBlock(block, scope)
        is MarkdownAlert -> MarkdownAlertBlock(block, scope)
        is MarkdownList -> MarkdownListBlock(block, scope)
        is MarkdownTable -> MarkdownTableBlock(block, scope)
        is MarkdownTableCell -> MarkdownText(block, scope.style.table, scope)
        is MarkdownRule ->
            HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
}

@Composable
private fun MarkdownText(
    block: MarkdownTextBlock,
    style: TextStyle,
    scope: MarkdownRenderScope,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    val reveal = scope.reveal
    val images = remember(block.text) {
        block.text.getStringAnnotations(DOCUMENT_IMAGE_TAG, 0, block.text.length)
    }
    if (images.isNotEmpty()) {
        val ranges = remember(block.text) {
            io.github.mangi.eta.ui.components.chatImageParagraphRanges(
                block.text.text, images.map {
                    io.github.mangi.eta.ui.components.ChatImageParagraphRange(it.start, it.end, it.item)
                },
            )
        }
        Column(modifier) {
            ranges.forEach { part ->
                if (part.source != null) {
                    io.github.mangi.eta.ui.components.ChatDocumentImage(part.source)
                } else {
                    // Preserve the prior image-paragraph exception: captions remain selectable
                    // and do not create artificial reveal identities from rendered offsets.
                    MarkdownText(MarkdownParagraph(block.offset + part.start,
                        block.text.subSequence(part.start, part.end)), style,
                        remember(scope) { MarkdownRenderScope(scope.style, null) }, softWrap = softWrap)
                }
            }
        }
        return
    }
    // Text leaves need boundaries even inside code/table containers. The reveal clip is
    // inside this layer; fractional progress invalidates only this leaf's recording.
    val drawModifier = modifier.markdownBlockDrawLayer(scope.style.tone == MarkdownTone.Answer)
    // 空单元格没有可显现的文字，也不占用显现协调器的 key。
    if (reveal == null || block.text.isEmpty()) {
        DocumentText(text = block.text, style = style, softWrap = softWrap, modifier = drawModifier)
        return
    }
    val revealState = rememberSmoothTextRevealState(block.revealKey, reveal)
    // 显现裁剪按字素边界逐帧变化，关闭像素对齐可避免裁剪边缘抖动。
    val animatedStyle = remember(style) { style.copy(textMotion = TextMotion.Animated) }
    DocumentText(
        text = block.text,
        style = animatedStyle,
        softWrap = softWrap,
        // Keep the reveal clip and its text in one recording. A separate child layer
        // can escape the canvas clip on Android; the retained boundary belongs OUTSIDE
        // smoothTextReveal, so it cannot expose the still-hidden target text.
        modifier = drawModifier.smoothTextReveal(revealState),
        onTextLayout = { layout -> revealState.onTextLayout(block.text.text, layout) },
    )
}

/** Preserve selection dragging over links without clickable child overlays. */
@Composable
private fun DocumentText(
    text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier,
    softWrap: Boolean = true, onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {},
) {
    val hasLinks = remember(text) { text.getLinkAnnotations(0, text.length).isNotEmpty() }
    if (hasLinks) ChatSelectableText(text, style, modifier, onTextLayout, softWrap)
    else Text(text = text, style = style, modifier = modifier, softWrap = softWrap, onTextLayout = onTextLayout)
}

// ── 代码块 ──────────────────────────────────────────────────────────────

@Composable
private fun MarkdownCodeBlock(block: MarkdownCode, scope: MarkdownRenderScope) {
    val style = scope.style
    val codeBody: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language ?: stringResource(R.string.markdown_code_block),
                style = markdownLabelStyle(),
                color = style.secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyButton(text = block.text.text, tint = style.secondaryColor)
        }
        // 代码不折行，长行横向滚动，保留缩进与对齐。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            MarkdownText(block, style.code, scope, softWrap = false)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = markdownBlockShape(),
        color = style.codeBackground,
    ) {
        Column(content = { codeBody() })
    }
}

@Composable
private fun CopyButton(text: String, tint: Color) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    val copyAction: () -> Unit = {
        @Suppress("DEPRECATION")
        clipboard.setText(AnnotatedString(text))
        copied = true
    }
    val iconTint = if (copied) markdownAccentColor() else tint
    val copyIcon: @Composable () -> Unit = {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_code),
            modifier = Modifier.size(EtaDimens.StatusIconSize),
            tint = iconTint,
        )
    }
    // 复制动作走官方 IconButton（官方涟漪、Role 与图标槽位）；
    // 尺寸取官方 extraSmall 图标按钮令牌（容器 32dp / 图标 20dp）。
    M3IconButton(
        onClick = copyAction,
        modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
        content = copyIcon,
    )
}

// ── 引用与提示 ──────────────────────────────────────────────────────────

@Composable
private fun MarkdownQuoteBlock(block: MarkdownQuote, scope: MarkdownRenderScope) {
    val barColor = scope.style.dividerColor
    val mutedScope = remember(scope) { scope.muted() }
    MarkdownBlockColumn(
        blocks = block.blocks,
        scope = mutedScope,
        compact = false,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val width = 3.dp.toPx()
                drawLine(
                    color = barColor,
                    start = Offset(width / 2, width / 2),
                    end = Offset(width / 2, size.height - width / 2),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
            .padding(start = 12.dp),
    )
}

@Composable
private fun MarkdownAlertBlock(block: MarkdownAlert, scope: MarkdownRenderScope) {
    val accent = block.kind.accentColor()
    val alertBody: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = block.kind.icon,
                contentDescription = null,
                modifier = Modifier.size(EtaDimens.StatusIconSize),
                tint = accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(block.kind.label),
                style = markdownAlertTitleStyle(),
                color = accent,
            )
        }
        if (block.blocks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            MarkdownBlockColumn(blocks = block.blocks, scope = scope, compact = false)
        }
    }
    // 官方 Surface 承担提示底板；颜色仍是语义强调色的 8% 淡色。
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = markdownBlockShape(),
        color = accent.copy(alpha = 0.08f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            content = { alertBody() },
        )
    }
}

private val MarkdownAlertKind.icon: ImageVector
    get() = when (this) {
        MarkdownAlertKind.Note -> Icons.Rounded.Info
        MarkdownAlertKind.Tip -> Icons.Rounded.Lightbulb
        MarkdownAlertKind.Important -> Icons.Rounded.Feedback
        MarkdownAlertKind.Warning -> Icons.Rounded.Warning
        MarkdownAlertKind.Caution -> Icons.Rounded.Report
    }

private val MarkdownAlertKind.label: Int
    get() = when (this) {
        MarkdownAlertKind.Note -> R.string.markdown_alert_note
        MarkdownAlertKind.Tip -> R.string.markdown_alert_tip
        MarkdownAlertKind.Important -> R.string.markdown_alert_important
        MarkdownAlertKind.Warning -> R.string.markdown_alert_warning
        MarkdownAlertKind.Caution -> R.string.markdown_alert_caution
    }

/**
 * 提示框强调色。
 *
 * Material 3 Expressive 的 `colorScheme` 没有 success / warning / important 语义角色，
 * 一律映射到官方角色（`primary` / `tertiary` / `error`）。
 */
@Composable
private fun MarkdownAlertKind.accentColor(): Color = when (this) {
    MarkdownAlertKind.Note -> MaterialTheme.colorScheme.primary
    MarkdownAlertKind.Tip -> MaterialTheme.colorScheme.tertiary
    MarkdownAlertKind.Important -> MaterialTheme.colorScheme.tertiary
    MarkdownAlertKind.Warning -> MaterialTheme.colorScheme.tertiary
    MarkdownAlertKind.Caution -> MaterialTheme.colorScheme.error
}

// ── 列表 ────────────────────────────────────────────────────────────────

@Composable
private fun MarkdownListBlock(block: MarkdownList, scope: MarkdownRenderScope) {
    val depth = LocalListDepth.current
    val answer = scope.style.tone == MarkdownTone.Answer
    val itemGap = when {
        block.loose -> if (answer) 10.dp else 8.dp
        else -> if (answer) 4.dp else 2.dp
    }
    // 序号按本列表最长的数字预留宽度，各项正文左边缘对齐。
    val markerWidth = if (block.ordered) {
        val digits = (block.items.lastOrNull()?.number ?: 1).toString().length
        (digits * 9 + 8).dp
    } else {
        14.dp
    }
    Column(Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { index, item ->
            key(item.offset) {
                if (index > 0) Spacer(Modifier.height(itemGap))
                MarkdownListItemRow(item, block.ordered, !block.loose, depth, markerWidth, scope)
            }
        }
    }
}

@Composable
private fun MarkdownListItemRow(
    item: MarkdownListItem,
    ordered: Boolean,
    compact: Boolean,
    depth: Int,
    markerWidth: Dp,
    scope: MarkdownRenderScope,
) {
    Row(Modifier.fillMaxWidth().markdownBlockDrawLayer(scope.style.tone == MarkdownTone.Answer)) {
        ListMarker(item = item, ordered = ordered, depth = depth, width = markerWidth, scope = scope)
        CompositionLocalProvider(LocalListDepth provides depth + 1) {
            MarkdownBlockColumn(
                blocks = item.blocks,
                scope = scope,
                compact = compact,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
        }
    }
}

@Composable
private fun RowScope.ListMarker(
    item: MarkdownListItem,
    ordered: Boolean,
    depth: Int,
    width: Dp,
    scope: MarkdownRenderScope,
) {
    val style = scope.style
    val reveal = scope.reveal
    // 在 graphicsLayer 内读取显现进度：块开始显现只让 marker 图层更新，不触发重组。
    val visibility = Modifier.graphicsLayer {
        alpha = if (listMarkerVisible(reveal != null, item.markerRevealKey, reveal?.started?.value, item.containsImage)) 1f else 0f
    }
    val markerModifier = visibility
        .padding(end = 8.dp)
        .widthIn(min = width)
    when {
        item.checked != null -> {
            val lineHeight = with(LocalDensity.current) { style.body.lineHeight.toDp() }
            Box(markerModifier.height(lineHeight), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    tint = if (item.checked) markdownAccentColor() else style.markerColor,
                )
            }
        }
        ordered -> Text(
            text = "${item.number}.",
            style = style.body,
            color = style.markerColor,
            textAlign = TextAlign.End,
            modifier = markerModifier.alignByBaseline(),
        )
        else -> Text(
            text = BULLETS[depth % BULLETS.size],
            style = style.body,
            color = style.markerColor,
            textAlign = TextAlign.Center,
            modifier = markerModifier.alignByBaseline(),
        )
    }
}

/**
 * 流式正文里 marker 与该项第一段文字同时出现；没有文字的空项在显现期间保持隐藏，
 * 避免先冒出孤立的圆点。
 */
internal fun listMarkerVisible(
    revealActive: Boolean,
    markerKey: RevealBlockKey?,
    started: Set<RevealBlockKey>?,
    containsImage: Boolean = false,
): Boolean = !revealActive || (markerKey != null && started?.contains(markerKey) == true) ||
    (markerKey == null && containsImage)

private val BULLETS = listOf("•", "◦", "▪")

// ── 表格 ────────────────────────────────────────────────────────────────

/**
 * 列宽按内容自适应：每列取最宽单元格的自然宽度并限制在合理区间；总宽不足时按比例
 * 撑满可用宽度，超出时整体横向滚动。网格线与表头底色在绘制阶段按测量结果画出。
 */
@Composable
private fun MarkdownTableBlock(block: MarkdownTable, scope: MarkdownRenderScope) {
    val style = scope.style
    val bodyStyles = remember(style, block.alignments) {
        block.alignments.map { align -> style.table.copy(textAlign = align.textAlign) }
    }
    val headerStyles = remember(bodyStyles) {
        bodyStyles.map { it.copy(fontWeight = FontWeight.SemiBold) }
    }
    var grid by remember { mutableStateOf(TableGrid.Empty) }
    val lineColor = style.dividerColor
    val headerColor = style.tableHeaderBackground
    val columns = block.header.size
    val rows = remember(block) { listOf(block.header) + block.rows }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = constraints.maxWidth
        val tableGrid: @Composable (Modifier) -> Unit = { layoutModifier ->
            Layout(
                modifier = layoutModifier.drawBehind {
                    val current = grid
                    val headerHeight = current.rowHeights.firstOrNull() ?: return@drawBehind
                    drawRect(headerColor, size = Size(size.width, headerHeight.toFloat()))
                    val stroke = 0.5.dp.toPx()
                    var y = 0f
                    current.rowHeights.dropLast(1).forEach { height ->
                        y += height
                        drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                    }
                    var x = 0f
                    current.columnWidths.dropLast(1).forEach { width ->
                        x += width
                        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                    }
                },
                content = {
                    rows.forEachIndexed { rowIndex, row ->
                        row.forEachIndexed { column, cell ->
                            MarkdownText(
                                block = cell,
                                style = if (rowIndex == 0) headerStyles[column] else bodyStyles[column],
                                scope = scope,
                                // 单元格以固定宽度测量，不需要 fill；fill 会干扰列宽的固有尺寸查询。
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                },
            ) { measurables, _ ->
                val rowCount = measurables.size / columns
                val minWidth = TableMinColumnWidth.roundToPx()
                val maxWidth = TableMaxColumnWidth.roundToPx()
                val widths = IntArray(columns) { column ->
                    (0 until rowCount).maxOf { row ->
                        val cell = rows[row][column]
                        if (cell.text.getStringAnnotations(DOCUMENT_IMAGE_TAG, 0, cell.text.length).isNotEmpty())
                            maxWidth // Image host may subcompose: never ask it for intrinsic dimensions.
                        else measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity)
                    }.coerceIn(minWidth, maxWidth)
                }
                val natural = widths.sum()
                if (natural in 1 until availableWidth) {
                    val extra = availableWidth - natural
                    var assigned = 0
                    for (column in 0 until columns) {
                        val share = if (column == columns - 1) {
                            extra - assigned
                        } else {
                            (extra.toLong() * widths[column] / natural).toInt()
                        }
                        widths[column] += share
                        assigned += share
                    }
                }
                val placeables = measurables.mapIndexed { index, measurable ->
                    measurable.measure(Constraints.fixedWidth(widths[index % columns]))
                }
                val heights = IntArray(rowCount) { row ->
                    (0 until columns).maxOf { column -> placeables[row * columns + column].height }
                }
                val next = TableGrid(widths.toList(), heights.toList())
                if (next != grid) grid = next
                layout(widths.sum(), heights.sum()) {
                    var y = 0
                    for (row in 0 until rowCount) {
                        var x = 0
                        for (column in 0 until columns) {
                            placeables[row * columns + column].place(x, y)
                            x += widths[column]
                        }
                        y += heights[row]
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            // 官方 Surface 承担表格外框、圆角与描边（outlineVariant 角色）。
            Surface(
                shape = markdownBlockShape(),
                color = Color.Transparent,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                tableGrid(Modifier)
            }
        }
    }
}

/** 测量结果供绘制阶段画网格；只在绘制中读取，写入不会引起重组。 */
@Immutable
private data class TableGrid(val columnWidths: List<Int>, val rowHeights: List<Int>) {
    companion object {
        val Empty = TableGrid(emptyList(), emptyList())
    }
}

private val MarkdownColumnAlign.textAlign: TextAlign
    get() = when (this) {
        MarkdownColumnAlign.Start -> TextAlign.Start
        MarkdownColumnAlign.Center -> TextAlign.Center
        MarkdownColumnAlign.End -> TextAlign.End
    }

private val TableMinColumnWidth = 72.dp
private val TableMaxColumnWidth = 260.dp
