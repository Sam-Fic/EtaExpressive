import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'src/main/kotlin/io/github/mangi/eta'

class WebViewRendererExitContractTest(unittest.TestCase):
    def test_all_installed_clients_handle_renderer_exit(self):
        sources = [p for p in SOURCE.rglob('*.kt') if 'object : WebViewClient()' in p.read_text()]
        self.assertEqual(2, len(sources))
        for path in sources:
            self.assertIn('override fun onRenderProcessGone', path.read_text(), str(path))

    def test_both_pool_creation_paths_wire_retirement(self):
        source = (SOURCE / 'agent/browser/ported/browser/BrowserTabPool.kt').read_text()
        self.assertEqual(2, source.count('manager.onRendererGone = { handleRendererGone(manager) }'))
        retirement = source.split('private fun handleRendererGone(')[1].split('private fun handleCloseWindow(')[0]
        self.assertIn('it.manager === manager', retirement)
        self.assertIn('savedURLs.remove', retirement)
        self.assertNotIn('loadURL(', retirement)
        self.assertNotIn('createTab(', retirement)

    def test_dead_renderer_disposal_does_not_send_page_commands(self):
        source = (SOURCE / 'agent/browser/ported/browser/BrowserUseManager.kt').read_text()
        disposal = source.split('private fun dispose(')[1].split('fun loadURL(')[0]
        for call in ['webView.stopLoading(', 'webView.evaluateJavascript(', 'webView.reload(', 'webView.loadUrl(']:
            self.assertNotIn(call, disposal)
        self.assertIn('if (destroyed) return', disposal)
        self.assertIn('pendingEvaluations.clear()', disposal)
        self.assertIn('webView.destroy()', disposal)
