package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * A display-list boundary, NOT an offscreen texture. Auto compositing has no retained bitmap
 * height limit and does not clip tall documents. Keep the boundary attached while reveal runs:
 * invalidating a descendant layer then updates its RenderNode, not all of its completed siblings.
 * The document boundary also stops an unrelated row draw from walking every Markdown block.
 * Fractional reveal frames only record the active text leaf. Content/layout changes (including
 * crossing a revealed line) may still rebuild ancestor lists of layer references, not their text.
 */
internal fun Modifier.markdownDocumentDrawLayer(): Modifier = graphicsLayer()

/**
 * Cull INSIDE the layer. Its recording can be empty outside the viewport and refresh() invalidates
 * that same recording on reentry. Culling outside a cached layer would not guard its independent
 * recording when a reveal/selection descendant invalidates it.
 *
 * This changes drawing only: no conditional composition/placement, merged or cleared semantics,
 * selection gate, alpha, clipping, or Offscreen texture. Actual content/style/layout/selection
 * changes still invalidate the normal Compose layer; no manual "completed" cache can go stale.
 */
@Composable
internal fun Modifier.markdownBlockDrawLayer(cull: Boolean): Modifier =
    graphicsLayer().markdownViewportDrawCull(cull)
