"""The document renderer must retain display-list boundaries, not change reveal/UI policy."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / "src/main/kotlin/io/github/mangi/eta/ui/markdown"


class MarkdownDrawLayersContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.content = (ROOT / "MarkdownContent.kt").read_text()
        cls.layers = (ROOT / "MarkdownDrawLayers.kt").read_text()
        cls.cull = (ROOT / "MarkdownViewportDrawCull.kt").read_text()

    def test_document_and_compound_blocks_are_independent_recordings(self):
        self.assertIn("modifier = modifier.markdownDocumentDrawLayer()", self.content)
        self.assertIn("Box(Modifier.markdownBlockDrawLayer(scope.style.tone == MarkdownTone.Answer),", self.content)
        self.assertIn("propagateMinConstraints = true", self.content)
        self.assertIn("Row(Modifier.fillMaxWidth().markdownBlockDrawLayer(scope.style.tone == MarkdownTone.Answer))", self.content)
        self.assertIn("internal fun Modifier.markdownDocumentDrawLayer(): Modifier = graphicsLayer()", self.layers)

    def test_cull_is_inside_the_recording_and_reveal_clip_has_no_child_layer(self):
        self.assertIn("graphicsLayer().markdownViewportDrawCull(cull)", self.layers)
        self.assertIn("val drawModifier = modifier.markdownBlockDrawLayer", self.content)
        self.assertIn("modifier = drawModifier.smoothTextReveal(revealState),", self.content)
        self.assertNotIn("smoothTextReveal(revealState).graphicsLayer", self.content)
        self.assertIn("if (isAttached) invalidateDraw()", self.cull)

    def test_layer_cache_does_not_change_composition_placement_or_semantics(self):
        code = "\n".join(line for line in self.layers.splitlines()
                         if not line.lstrip().startswith(("*", "/**")))
        for forbidden in ("CompositingStrategy.Offscreen", "clip =", "alpha =", "semantics {",
                          "clearAndSetSemantics", "layout {", "LaunchedEffect", "mutableStateOf"):
            self.assertNotIn(forbidden, code)
        self.assertIn("rememberSmoothTextRevealState(block.revealKey, reveal)", self.content)
        self.assertIn("onTextLayout = { layout -> revealState.onTextLayout(block.text.text, layout) }", self.content)


if __name__ == "__main__":
    unittest.main()
