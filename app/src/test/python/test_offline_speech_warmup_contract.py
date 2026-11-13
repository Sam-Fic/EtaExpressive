"""Static wiring guards for offline speech warmup; these do not compile Kotlin or run the engine."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
KOTLIN = ROOT / 'app/src/main/kotlin/io/github/mangi/eta'
OFFLINE = KOTLIN / 'agent/voice/offline'


def read(path):
    path = Path(path)
    return (path if path.is_absolute() else KOTLIN / path).read_text(encoding='utf-8')


class OfflineSpeechWarmupContractTest(unittest.TestCase):
    def test_runtime_settings_share_the_pack_preferences_file(self):
        source = read(OFFLINE / 'OfflineSpeechRuntimeSettings.kt')
        self.assertIn('"offline_speech"', source)
        self.assertIn('KEY_PREWARM_ON_START = "prewarm_on_start"', source)
        self.assertIn('KEY_IDLE_RELEASE = "idle_release_minutes"', source)
        # 默认首次使用后常驻 10 分钟；只接受固定档位，损坏值回退默认。
        self.assertIn('DefaultIdleReleaseMinutes = 10', source)
        self.assertIn('listOf(5, 10, 30, 0)', source)
        self.assertIn('takeIf { it in idleReleaseOptions } ?: DefaultIdleReleaseMinutes', source)

    def test_settings_ui_exposes_prewarm_and_idle_release(self):
        source = read('ui/OfflineSpeechPackColumn.kt')
        self.assertIn('OfflineSpeechRuntimeSettings.state.collectAsState()', source)
        self.assertIn('LaunchedEffect(Unit) { OfflineSpeechRuntimeSettings.load(context) }', source)
        self.assertIn('EtaSwitchRow(', source)
        self.assertIn('R.string.speech_prewarm', source)
        self.assertIn('R.string.speech_idle_release', source)
        self.assertIn('EtaSegmentedDropdownRow(', source)
        self.assertIn('OfflineSpeechRuntimeSettings.idleReleaseOptions', source)
        # 语音包未校验通过时不展示可切换的预加载状态。
        self.assertIn('enabled = state.ready', source)

    def test_recording_starts_before_engine_acquire(self):
        source = read(OFFLINE / 'OfflineSpeechSession.kt')
        # 开麦与装载并行化后仍保序：开麦任务先于装载任务派发，UI 进入 Listening 后才 await 引擎槽位，
        # 冷启动不再把麦克风压在装载之后。
        self.assertLess(source.index('opened.startRecording()'), source.index('OfflineSpeechEngineCache.acquire('))
        self.assertLess(
            source.index('withContext(Dispatchers.Main) { onListening() }'),
            source.index('slot.await()'),
        )
        self.assertIn('Channel<FloatArray>(Channel.UNLIMITED)', source)
        self.assertIn('launch(Dispatchers.IO)', source)
        self.assertIn('tryReceive()', source)
        # 诊断字段用于真机确认第二轮是否命中常驻模型、积压了多少预热期音频。
        self.assertIn('recognition.engine', source)
        self.assertIn('recognition.audio.buffer', source)

    def test_engine_cache_keys_by_model_revision_and_engine(self):
        cache = read(OFFLINE / 'OfflineSpeechEngineCache.kt')
        self.assertIn('data class SpeechEngineKey(', cache)
        for field in ('modelId', 'revision', 'engine'):
            self.assertIn(f'val {field}:', cache)
        # 所有 native 调用共用一条单线程 dispatcher。
        self.assertIn('Executors.newSingleThreadExecutor', cache)
        self.assertIn('"eta-speech-native"', cache)
        # 识别中失效只挂起，lease 释放后再关闭，避免 use-after-free。
        self.assertIn('fun invalidate(', cache)
        self.assertIn('pendingInvalidate', cache)
        self.assertIn('fun releaseIdleIfUnused(', cache)
        # 每轮复用前的干净化：ncnn 重建流、SenseVoice 清 VAD。
        self.assertIn('reset(recreate = finished)', cache)
        self.assertIn('vad.clear()', cache)

    def test_invalidation_follows_pack_voice_and_memory_pressure(self):
        prewarm = read(OFFLINE / 'OfflineSpeechPrewarm.kt')
        self.assertIn('OfflineSpeechPack.state', prewarm)
        self.assertIn('DoubaoVoiceConfig.state', prewarm)
        self.assertIn('OfflineSpeechRuntimeSettings.state', prewarm)
        self.assertIn('conversationCloudAsr', prewarm)
        # 资格只看校验后的 ready；旧听写开关 enabled 不参与判定。
        self.assertNotIn('pack.enabled', prewarm)
        # 备份恢复经 OfflineSpeechPack.refresh 把 ready 置 false，必须走同一路失效。
        self.assertIn('fun refresh(', read(OFFLINE / 'OfflineSpeechPack.kt'))
        self.assertIn('registerComponentCallbacks(this)', prewarm)
        self.assertIn('onTrimMemory', prewarm)
        for level in ('TRIM_MEMORY_RUNNING_LOW', 'TRIM_MEMORY_RUNNING_CRITICAL', 'TRIM_MEMORY_COMPLETE'):
            self.assertIn(level, prewarm)
        self.assertIn('onLowMemory', prewarm)

    def test_prewarm_installs_after_the_full_runtime_gate(self):
        app = read('EtaApp.kt')
        gate = app.index('shouldInitializeFullRuntime(Application.getProcessName(), packageName)')
        install = app.index('OfflineSpeechPrewarm.install(this)')
        self.assertLess(gate, install)
        controller = read('agent/voice/VoiceModeController.kt')
        # 浮窗 / 系统助理入口补一次幂等安装，并在每轮识别传入诊断。
        self.assertIn('OfflineSpeechPrewarm.install(app)', controller)
        self.assertIn('diagnostic = trace', controller)
        router = read('agent/voice/SpeechInputSession.kt')
        self.assertIn('diagnostic: VoiceDiagnostics? = null', router)
        self.assertIn(
            'OfflineSpeechSession.recognize(context, onListening, onText, mode, diagnostic, onLevel, stop)',
            router,
        )

    def test_timeouts_use_recording_and_ready_clocks(self):
        policy = read(OFFLINE / 'SpeechInputPolicy.kt')
        self.assertIn(
            'fun timedOut(totalElapsedMs: Long, sinceReadyMs: Long, hasText: Boolean)',
            policy,
        )
        self.assertIn('totalElapsedMs >= 60_000', policy)
        self.assertIn('sinceReadyMs >= 8_000', policy)
        session = read(OFFLINE / 'OfflineSpeechSession.kt')
        self.assertIn('recordStart', session)
        self.assertIn('listenStart', session)
        self.assertIn(
            'SpeechInputPolicy.timedOut(now - recordStart, now - listenStart',
            session,
        )

    def test_warmup_strings_exist_in_every_locale(self):
        keys = (
            'speech_prewarm',
            'speech_prewarm_summary',
            'speech_idle_release',
            'speech_idle_release_summary',
            'speech_idle_release_5',
            'speech_idle_release_10',
            'speech_idle_release_30',
            'speech_idle_release_never',
        )
        for directory in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            root = ET.parse(ROOT / 'app/src/main/res' / directory / 'speech.xml').getroot()
            names = [element.get('name') for element in root]
            self.assertEqual(len(names), len(set(names)))
            for key in keys:
                self.assertIn(key, names)


if __name__ == '__main__':
    unittest.main()
