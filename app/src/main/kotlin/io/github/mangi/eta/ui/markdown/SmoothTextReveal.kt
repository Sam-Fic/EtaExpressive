package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * 一条回答只使用一个显现时钟，按源码顺序在 Markdown 块之间分配同一帧的推进量。
 *
 * 解析和文本排版仅在目标文本变化时发生；帧间推进只更新普通字段并调用
 * [invalidateDraw]。只有显现跨入新行时才额外请求一次测量以增长消息高度，
 * 全程不写 Compose State，因此字符帧不会触发重组或重新排版。
 */
@Stable
internal class SmoothTextRevealCoordinator {
    private val records = sortedMapOf<RevealBlockKey, RevealRecord>()
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)
    private val drainedState = MutableStateFlow(true)
    // 列表 marker 在绘制阶段读取它；用快照状态而非 Flow，块开始显现只让 marker 图层重绘，
    // 不会让整个列表随之重组。
    private val startedState = mutableStateOf<Set<RevealBlockKey>>(emptySet())
    private var animationsPaused = false
    private var animationsHeld = false
    private var restoredSourceLength = 0
    private var onRevealAdvanced: ((Float) -> Unit)? = null

    fun setOnRevealAdvanced(listener: ((Float) -> Unit)?) { onRevealAdvanced = listener }
    val isAnimationHeld: Boolean get() = animationsHeld
    fun restoreHistoryThrough(sourceLength: Int) {
        restoredSourceLength = sourceLength.coerceAtLeast(0)
        pauseAnimationsAndCatchUp()
    }
    fun resumeAnimationsWithoutCatchingUp() {
        animationsHeld = false
        animationsPaused = false
        updateDrainedState()
        wakeups.trySend(Unit)
    }
    fun holdAnimations() {
        animationsPaused = false
        animationsHeld = true
        wakeups.trySend(Unit)
    }
    /** All reveal-bearing leaves must have been measured before a terminal drain counts. */
    fun hasLayoutsFor(keys: Set<RevealBlockKey>): Boolean =
        keys.all { records[it]?.hasReceivedLayout == true }


    val drained: StateFlow<Boolean> = drainedState
    /** 已经开始显现的块，用于让列表 marker 与正文保持同一生命周期。 */
    val started: State<Set<RevealBlockKey>> get() = startedState

    val isAnimationPaused: Boolean
        get() = animationsPaused

    /**
     * 页面不可见时帧时钟会停，但 Runtime 仍可能继续追加文本。此时直接追平当前目标，
     * 并让后续排版结果同样立即完成，避免回到页面后补播后台积压的显现动画。
     */
    fun pauseAnimationsAndCatchUp() {
        animationsHeld = false
        animationsPaused = true
        records.values.forEach(::completeRecord)
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun resumeAnimationsAfterCatchUp() {
        animationsHeld = false
        records.values.forEach(::completeRecord)
        updateDrainedState()
        animationsPaused = false
        wakeups.trySend(Unit)
    }

    fun retainBlocks(activeBlocks: Set<RevealBlockKey>) {
        val iterator = records.iterator()
        var removedPendingBlock = false
        while (iterator.hasNext()) {
            val (_, record) = iterator.next()
            if (record.key !in activeBlocks) {
                removedPendingBlock = removedPendingBlock || record.progress < record.targetCount
                iterator.remove()
            }
        }
        val retainedStarted = startedState.value.intersect(activeBlocks)
        if (retainedStarted != startedState.value) {
            startedState.value = retainedStarted
        }
        if (removedPendingBlock || records.none { (_, record) -> record.progress < record.targetCount }) {
            updateDrainedState()
        }
        wakeups.trySend(Unit)
    }

    fun attach(
        key: RevealBlockKey,
        node: SmoothTextRevealNode,
        text: String?,
        layoutResult: TextLayoutResult?,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        record.node = node
        if (text != null && layoutResult != null) {
            updateRecord(record, text, layoutResult)
        }
        wakeups.trySend(Unit)
    }

    fun detach(key: RevealBlockKey, node: SmoothTextRevealNode) {
        val record = records[key]?.takeIf { it.node === node } ?: return
        record.node = null
        // 已离开组合的块不再消费帧时钟；保留完成进度，重挂载时只显现后续新增文本。
        completeRecord(record)
        record.layoutResult = null
        updateDrainedState()
        wakeups.trySend(Unit)
    }

    fun updateLayout(
        key: RevealBlockKey,
        node: SmoothTextRevealNode?,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        val record = records.getOrPut(key) { RevealRecord(key) }
        if (node != null) record.node = node
        updateRecord(record, text, layoutResult)
        wakeups.trySend(Unit)
    }

    fun drawSnapshot(key: RevealBlockKey): RevealDrawSnapshot? {
        val record = records[key] ?: return null
        if (record.layoutResult == null) return null
        return record.drawSnapshot
    }

    suspend fun runFrameClock() {
        while (currentCoroutineContext().isActive) {
            // 显现在推进，或已显现的字素还在淡入，都需要继续跑帧。
            val active = if (animationsHeld) null else firstPendingRecord() ?: firstAnimatingRecord()
            if (active == null) {
                updateDrainedState()
                wakeups.receive()
                continue
            }

            drainedState.value = false
            while (currentCoroutineContext().isActive) {
                if (animationsHeld || (firstPendingRecord() ?: firstAnimatingRecord()) == null) break
                val frameNanos = withFrameNanos { it }
                if (animationsHeld) break

                // 淡入窗按真实帧时刻变老：已经追平、只剩渐变的字素靠这里收尾。
                records.values.forEach { candidate ->
                    if (candidate.advanceFade(frameNanos)) candidate.node?.onRevealDataChanged()
                }

                val fadeDurationNanos = io.github.mangi.eta.ui.components.REVEAL_FADE_NANOS
                var frameAdvance = 0f
                val newlyStarted = mutableSetOf<RevealBlockKey>()
                for (record in records.values) {
                    if (record.node == null || record.layoutResult == null) continue
                    if (record.progress >= record.targetCount) continue
                    // 到达即显现：本帧到达的字素当帧跨过显现线，不再按打字机速度排队；
                    // 单个字素的渐变与上浮仍由固定时长驱动。
                    val previous = record.progress
                    record.progress = record.targetCount
                    frameAdvance += record.progress - previous
                    // 同一帧到达的字素共用同一出现时刻，各自按真实帧时刻老化。
                    record.fadeDurationNanos = fadeDurationNanos
                    record.noteRevealedGlyphs(frameNanos)
                    if (record.key !in startedState.value) newlyStarted += record.key
                    record.node?.onRevealDataChanged()
                }
                if (newlyStarted.isNotEmpty()) startedState.value = startedState.value + newlyStarted
                // One feedback callback per advancing frame, never per block or
                // for restore/detach/hold. This does not add catch-up vibration.
                if (frameAdvance > 0f) onRevealAdvanced?.invoke(frameAdvance)
                updateDrainedState()
            }
        }
    }

    private fun updateRecord(
        record: RevealRecord,
        text: String,
        layoutResult: TextLayoutResult,
    ) {
        if (record.text == text && record.layoutResult === layoutResult) return
        val restoredFirstLayout = !record.hasReceivedLayout && record.key.sourceOffset < restoredSourceLength
        if (text != record.text) {
            // 流式文本只追加不修改，但行内语法闭合（**粗体**、`code`、链接折叠等）会让
            // 渲染文本丢掉标记字符而变短或错位。此时进度只能保持单调前进：一旦回退，
            // 已显现的文字会消失并重新打字，表现为输出反复闪烁。
            val appended = text.startsWith(record.text)
            record.boundaries = updateGraphemeBoundaries(
                previousText = record.text,
                previousBoundaries = record.boundaries,
                text = text,
            )
            record.text = text
            record.targetCount = record.boundaries.lastIndex.toFloat()
            record.progress = record.progress.coerceAtMost(record.targetCount)
            // 非追加重写会让字素下标错位，旧的淡入时间戳必须作废。
            if (!appended) record.clearFade()
        }
        if (record.layoutResult !== layoutResult) {
            record.layoutResult = layoutResult
        }
        record.hasReceivedLayout = true
        if (animationsPaused || record.node == null || restoredFirstLayout) completeRecord(record)
        updateDrainedState()
        record.node?.onRevealDataChanged()
    }

    private fun completeRecord(record: RevealRecord) {
        record.progress = record.targetCount
        // 追平后不再有渐入，绘制回到纯裁剪路径，避免残留半透明或上浮的字素。
        record.clearFade()
        if (record.targetCount > 0f && record.key !in startedState.value) {
            startedState.value = startedState.value + record.key
        }
        record.node?.onRevealDataChanged()
    }

    private fun firstPendingRecord(): RevealRecord? = records.values.firstOrNull { record ->
        record.progress < record.targetCount && record.node != null && record.layoutResult != null
    }

    /**
     * 已经追平、只剩字素淡入的块：推进线不再需要它，但帧时钟必须继续跑到
     * 最后一个字素的渐变结束。
     */
    private fun firstAnimatingRecord(): RevealRecord? = records.values.firstOrNull { record ->
        record.fadeAppearNanos.isNotEmpty() && record.node != null && record.layoutResult != null
    }

    private fun updateDrainedState() {
        drainedState.value = records.values.none { record -> record.progress < record.targetCount }
    }
}

@JvmInline
internal value class RevealBlockKey(val sourceOffset: Int) : Comparable<RevealBlockKey> {
    override fun compareTo(other: RevealBlockKey): Int = sourceOffset.compareTo(other.sourceOffset)
}

@Stable
internal class SmoothTextRevealState(
    val key: RevealBlockKey,
    private val coordinator: SmoothTextRevealCoordinator,
) {
    private var node: SmoothTextRevealNode? = null
    private var text: String? = null
    private var layoutResult: TextLayoutResult? = null

    fun onTextLayout(text: String, layoutResult: TextLayoutResult) {
        this.text = text
        this.layoutResult = layoutResult
        coordinator.updateLayout(key, node, text, layoutResult)
    }

    internal fun attach(node: SmoothTextRevealNode) {
        this.node = node
        coordinator.attach(key, node, text, layoutResult)
    }

    internal fun detach(node: SmoothTextRevealNode) {
        if (this.node === node) this.node = null
        coordinator.detach(key, node)
    }

    internal fun drawSnapshot(): RevealDrawSnapshot? = coordinator.drawSnapshot(key)
}

@Composable
internal fun rememberSmoothTextRevealState(
    key: RevealBlockKey,
    coordinator: SmoothTextRevealCoordinator,
): SmoothTextRevealState = remember(key, coordinator) {
    SmoothTextRevealState(key, coordinator)
}

internal fun Modifier.smoothTextReveal(state: SmoothTextRevealState): Modifier =
    this then SmoothTextRevealElement(state)

private data class SmoothTextRevealElement(
    val state: SmoothTextRevealState,
) : ModifierNodeElement<SmoothTextRevealNode>() {
    override fun create(): SmoothTextRevealNode = SmoothTextRevealNode(state)

    override fun update(node: SmoothTextRevealNode) {
        node.updateState(state)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "smoothTextReveal"
    }
}

internal class SmoothTextRevealNode(
    private var state: SmoothTextRevealState,
) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {
    private val alphaPaint = Paint()
    private var cachedLayoutResult: TextLayoutResult? = null
    private var cachedFullCount = -1
    private var cachedFullPath: Path? = null
    private var cachedNextPath: Path? = null
    private var cachedVisibleHeight = -1

    override fun onAttach() {
        state.attach(this)
        onRevealDataChanged()
    }

    override fun onDetach() {
        state.detach(this)
        clearPathCache()
        cachedVisibleHeight = -1
    }

    fun updateState(next: SmoothTextRevealState) {
        if (state === next) return
        if (isAttached) state.detach(this)
        state = next
        clearPathCache()
        cachedVisibleHeight = -1
        if (isAttached) { state.attach(this); onRevealDataChanged() }
    }

    fun onRevealDataChanged() {
        val visibleHeight = state.visibleHeightPx()
        if (visibleHeight != cachedVisibleHeight) {
            cachedVisibleHeight = visibleHeight
            if (isAttached) invalidateMeasurement()
        }
        if (isAttached) invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val visibleHeight = if (state.drawSnapshot() == null) placeable.height
            else state.visibleHeightPx().coerceAtMost(placeable.height)
        cachedVisibleHeight = visibleHeight
        val measuredHeight = visibleHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(placeable.width, measuredHeight) {
            placeable.place(0, 0)
        }
    }

    override fun ContentDrawScope.draw() {
        val snapshot = state.drawSnapshot() ?: run { drawContent(); return }
        val contentScope = this
        val targetCount = snapshot.boundaries.lastIndex
        if (targetCount <= 0 || (snapshot.progress >= targetCount && !snapshot.liveFade)) {
            drawContent()
            return
        }

        // 有字素在淡入窗内时，整段不透明前缀只画到窗口起点，
        // 窗内的字素逐个按出现时刻给出不透明度与上浮。
        // 长块不参与逐字淡入：淡入窗内每个字素都要「裁剪 + 重绘整块内容」一次，
        // 上千字素的块单帧就能压住主线程数秒（实测 22k 消息会话里触发 5s 输入 ANR，
        // 主线程栈停在 drawFadingGlyphs -> clipPath -> drawContent 的循环里）。
        // 超过预算直接整块绘制：视觉上只是少了逐字淡入，不会丢内容。
        val fadeLive = snapshot.liveFade && targetCount <= MAX_FADE_GLYPHS
        val fullCount = if (fadeLive) {
            snapshot.fadeGlyphIndex.coerceIn(0, targetCount)
        } else {
            floor(snapshot.progress).toInt().coerceIn(0, targetCount)
        }
        ensurePaths(snapshot, fullCount)
        cachedFullPath?.let { path ->
            clipPath(path) { contentScope.drawContent() }
        }
        if (fadeLive) {
            drawFadingGlyphs(snapshot)
            return
        }

        val partialAlpha = (snapshot.progress - fullCount).coerceIn(0f, 1f)
        if (partialAlpha > 0f) {
            cachedNextPath?.let { path ->
                clipPath(path) {
                    alphaPaint.alpha = partialAlpha
                    drawContext.canvas.saveLayer(
                        Rect(Offset.Zero, size),
                        alphaPaint,
                    )
                    try {
                        contentScope.drawContent()
                    } finally {
                        drawContext.canvas.restore()
                    }
                }
            }
        }
    }

    /**
     * 窗口内的字素：越新出现的越透明、越靠下，随年龄一起淡入并升到最终位置。
     *
     * 同一帧出现的字素不透明度与上浮量完全相同，合进一个图层一起画。淡入时长固定后，
     * 文字生成再快，窗内也只是同时排下更多字素；每帧新建的图层数只跟窗内出现的帧数
     * 有关，不会随生成速度线性增长。
     */
    private fun ContentDrawScope.drawFadingGlyphs(snapshot: RevealDrawSnapshot) {
        val layout = snapshot.layoutResult
        val textLength = layout.layoutInput.text.length
        val boundaries = snapshot.boundaries
        val startIndex = snapshot.fadeGlyphIndex.coerceIn(0, boundaries.lastIndex)
        val endIndex = (startIndex + snapshot.fadeSize).coerceIn(startIndex, boundaries.lastIndex)
        val contentScope = this
        var index = startIndex
        while (index < endIndex) {
            val alpha = fadeAlphaAt(snapshot, index)
            if (alpha <= MIN_FADE_ALPHA) {
                index += 1
                continue
            }
            val paths = ArrayList<Path>(4)
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            var rise = 0f
            var runEnd = index
            while (runEnd < endIndex && fadeAlphaAt(snapshot, runEnd) == alpha) {
                val start = boundaries[runEnd].coerceIn(0, textLength)
                val end = boundaries[runEnd + 1].coerceIn(start, textLength)
                runEnd += 1
                if (end <= start) continue
                val path = layout.getPathForRange(start, end)
                val bounds = path.getBounds()
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                paths += path
                left = min(left, bounds.left)
                top = min(top, bounds.top)
                right = max(right, bounds.right)
                bottom = max(bottom, bounds.bottom)
                val line = layout.getLineForOffset(start.coerceAtMost((textLength - 1).coerceAtLeast(0)))
                rise = max(
                    rise,
                    (1f - alpha) * FADE_RISE_LINE_FRACTION *
                        (layout.getLineBottom(line) - layout.getLineTop(line)).coerceAtLeast(0f),
                )
            }
            index = runEnd
            if (paths.isEmpty()) continue
            // 上浮会把字形探出原行；这个节点没有外层裁剪，图层范围必须自己收在
            // 测量到的节点内，否则带下坠的模糊边缘会浮出本块之外。
            val layerTop = (top - rise).coerceAtLeast(0f)
            val layerBottom = (bottom + rise).coerceAtMost(size.height)
            if (layerBottom <= layerTop) continue
            alphaPaint.alpha = alpha
            drawContext.canvas.saveLayer(
                Rect(
                    left = left.coerceAtLeast(0f),
                    top = layerTop,
                    right = right.coerceAtMost(size.width),
                    bottom = layerBottom,
                ),
                alphaPaint,
            )
            try {
                translate(top = rise) {
                    // 同一不透明度档位的字形裁剪区互不重叠，合并成一条路径后再裁剪仍是
                    // 它们的并集（NonZero 填充不会抵消），但 drawContent 从「每个字素一次」
                    // 降到「每档一次」，这是本次卡顿的直接来源。
                    if (paths.size == 1) {
                        clipPath(paths[0]) { contentScope.drawContent() }
                    } else {
                        val union = Path()
                        paths.forEach { path -> union.addPath(path) }
                        clipPath(union) { contentScope.drawContent() }
                    }
                }
            } finally {
                drawContext.canvas.restore()
            }
        }
    }

    /** 淡入窗内某字素此刻的不透明度，含正在打的那一个字素只显现了一部分的比例。 */
    private fun fadeAlphaAt(snapshot: RevealDrawSnapshot, index: Int): Float =
        snapshot.fadeAlpha(index) * (snapshot.progress - index).coerceIn(0f, 1f)

    private fun ensurePaths(snapshot: RevealDrawSnapshot, fullCount: Int) {
        val layout = snapshot.layoutResult
        if (cachedLayoutResult === layout && cachedFullCount == fullCount) return

        // Selection paths are not additive across wrapped lines or Bidi runs:
        // a former end line can become an intermediate line with different bounds.
        // Keep fractional-frame hits, but rebuild every changed integer prefix.
        val textLength = layout.layoutInput.text.length
        val fullEnd = snapshot.boundaries[fullCount].coerceIn(0, textLength)
        cachedFullPath = if (fullEnd > 0) layout.getPathForRange(0, fullEnd) else null
        cachedNextPath = nextGraphemePath(snapshot, fullCount)
        cachedLayoutResult = layout
        cachedFullCount = fullCount
    }

    private fun nextGraphemePath(
        snapshot: RevealDrawSnapshot,
        fullCount: Int,
    ): Path? {
        val textLength = snapshot.layoutResult.layoutInput.text.length
        val start = snapshot.boundaries.getOrNull(fullCount)?.coerceIn(0, textLength)
            ?: return null
        val end = snapshot.boundaries.getOrNull(fullCount + 1)?.coerceIn(start, textLength)
            ?: return null
        return if (end > start) {
            snapshot.layoutResult.getPathForRange(start, end)
        } else {
            null
        }
    }

    private fun clearPathCache() {
        cachedLayoutResult = null
        cachedFullCount = -1
        cachedFullPath = null
        cachedNextPath = null
    }
}

internal class RevealDrawSnapshot(
    private val record: RevealRecord,
) {
    val layoutResult: TextLayoutResult
        get() = checkNotNull(record.layoutResult)
    val boundaries: IntArray
        get() = record.boundaries
    val progress: Float
        get() = record.progress
    /** 还有字素处在淡入窗内；为假时绘制完全走原来的纯裁剪路径。 */
    val liveFade: Boolean
        get() = record.fadeAppearNanos.isNotEmpty()
    val fadeGlyphIndex: Int
        get() = record.fadeGlyphIndex
    val fadeSize: Int
        get() = record.fadeAppearNanos.size

    /**
     * 淡入窗内第 [glyphIndex] 个字素的不透明度：从头索引开始按真实帧时刻老化到 1，
     * 再用 smoothstep 收出平缓的头尾。窗外的字素一律视为已经完全显现。
     */
    fun fadeAlpha(glyphIndex: Int): Float {
        val duration = record.fadeDurationNanos
        val queueIndex = glyphIndex - record.fadeGlyphIndex
        if (duration <= 0L || queueIndex < 0 || queueIndex >= record.fadeAppearNanos.size) return 1f
        val ageNanos = record.lastFrameNanos - record.fadeAppearNanos[queueIndex]
        if (ageNanos <= 0L) return 0f
        val fraction = (ageNanos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }
}

internal class RevealRecord(
    val key: RevealBlockKey,
) {
    val drawSnapshot = RevealDrawSnapshot(this)
    var node: SmoothTextRevealNode? = null
    var hasReceivedLayout = false
    var text: String = ""
    var layoutResult: TextLayoutResult? = null
    var boundaries: IntArray = intArrayOf(0)
    var progress: Float = 0f
    var targetCount: Float = 0f
    /** 淡入窗的起写字素下标；窗外（更早显现）的字素一律不透明。 */
    var fadeGlyphIndex: Int = 0
    /** 单次淡入的时长，随当前显现速度推得；0 表示不做淡入。 */
    var fadeDurationNanos: Long = 0L
    /** 最近一帧的时刻，绘制阶段用它给窗口内的字素算年龄。 */
    var lastFrameNanos: Long = 0L
    /** 淡入窗内每个字素的出现时刻，与 [fadeGlyphIndex] 连续对应。 */
    val fadeAppearNanos = ArrayDeque<Long>()

    /** 丢弃淡入窗：追平目标、文本重写或进度被压短时，旧时刻已经不再对应任何字素。 */
    fun clearFade() {
        fadeAppearNanos.clear()
        fadeGlyphIndex = floor(progress).toInt().coerceAtLeast(0)
    }

    /**
     * 为本帧新显现的字素登记出现时刻。同一帧显现的字素共用同一时刻；
     * 帧间只更新普通字段，不写 Compose State。
     */
    fun noteRevealedGlyphs(now: Long) {
        lastFrameNanos = now
        val highest = floor(progress).toInt().coerceAtMost(targetCount.toInt() - 1)
        var index = fadeGlyphIndex + fadeAppearNanos.size
        while (index <= highest) {
            fadeAppearNanos.addLast(now)
            index += 1
        }
    }

    /**
     * 按真实帧时刻让淡入窗变老：到时的字素出队并把窗口前移。
     * 窗口非空即返回 true，让帧时钟一直跑到最后一个字素淡完。
     */
    fun advanceFade(now: Long): Boolean {
        if (fadeAppearNanos.isEmpty()) return false
        lastFrameNanos = now
        val duration = fadeDurationNanos
        if (duration <= 0L) {
            // 速度为 0 之类的退化情形必须一次清空，否则窗口永远变不老。
            fadeGlyphIndex += fadeAppearNanos.size
            fadeAppearNanos.clear()
            return true
        }
        while (fadeAppearNanos.isNotEmpty() && now - fadeAppearNanos.first() >= duration) {
            fadeAppearNanos.removeFirst()
            fadeGlyphIndex += 1
        }
        return true
    }
}

private fun SmoothTextRevealState.visibleHeightPx(): Int {
    val snapshot = drawSnapshot() ?: return 0
    val layoutResult = snapshot.layoutResult
    val targetCount = snapshot.boundaries.lastIndex
    if (targetCount <= 0 || snapshot.progress >= targetCount) {
        return layoutResult.size.height
    }

    val visibleCount = ceil(snapshot.progress).toInt().coerceIn(0, targetCount)
    if (visibleCount == 0) return 0
    val textLength = layoutResult.layoutInput.text.length
    val visibleEnd = snapshot.boundaries[visibleCount].coerceIn(0, textLength)
    if (visibleEnd == 0 || layoutResult.lineCount == 0) return 0
    val line = layoutResult.getLineForOffset((visibleEnd - 1).coerceAtMost(textLength - 1))
    return ceil(layoutResult.getLineBottom(line)).toInt()
        .coerceIn(0, layoutResult.size.height)
}

internal fun graphemeBoundaries(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        result += boundary
        boundary = iterator.next()
    }
    if (result.lastOrNull() != text.length) result += text.length
    return result.toIntArray()
}

/**
 * 为只追加文本增量维护字素边界。
 *
 * 新内容可能把旧文本的最后一个字素继续延长，例如组合音标、ZWJ emoji、旗帜和 CRLF。
 * 因此保留倒数第二个边界之前的结果，只重算最后一个旧字素和新增后缀，避免每个流式
 * 分片都从头扫描整条回答。
 */
internal fun updateGraphemeBoundaries(
    previousText: String,
    previousBoundaries: IntArray,
    text: String,
): IntArray {
    if (
        previousText.isEmpty() ||
        !text.startsWith(previousText) ||
        previousBoundaries.isEmpty() ||
        previousBoundaries.first() != 0 ||
        previousBoundaries.last() != previousText.length
    ) {
        return graphemeBoundaries(text)
    }
    if (text == previousText) return previousBoundaries

    val restartBoundaryIndex = (previousBoundaries.lastIndex - 1).coerceAtLeast(0)
    val restartOffset = previousBoundaries[restartBoundaryIndex]
    val suffixBoundaries = graphemeBoundaries(text.substring(restartOffset))
    return IntArray(restartBoundaryIndex + suffixBoundaries.size).also { merged ->
        for (index in 0 until restartBoundaryIndex) {
            merged[index] = previousBoundaries[index]
        }
        suffixBoundaries.forEachIndexed { index, boundary ->
            merged[restartBoundaryIndex + index] = restartOffset + boundary
        }
    }
}

/** 字素刚出现时相对最终位置的下沉比例（按行高衡量）。 */
private const val FADE_RISE_LINE_FRACTION = 0.12f

/**
 * 逐字淡入的字素预算。
 *
 * 淡入窗内每个字素都要单独裁剪并重绘整块内容，代价与字素数线性相关。长回答在
 * 数万条消息的会话里会直接把主线程压到超过 5 秒（输入事件 ANR）。超过预算的块
 * 退化为整块绘制，只牺牲逐字淡入的观感。
 */
private const val MAX_FADE_GLYPHS = 400
/** 低于这个不透明度的字素不再单独建层，直接跳过。 */
private const val MIN_FADE_ALPHA = 0.001f
