"""Static wiring guards only; these do not compile Kotlin or verify device rendering/audio."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
KOTLIN = ROOT / 'app/src/main/kotlin/io/github/mangi/eta'


def read(path):
    return (KOTLIN / path).read_text(encoding='utf-8')


class VoiceSettingsContractTest(unittest.TestCase):
    def test_single_settings_entry_and_serialized_legacy_routes(self):
        settings = read('ui/SettingsScreen.kt')
        self.assertEqual(1, settings.count('onNavigate(AppRoute.VoiceSettings)'))
        for old in ('SpeechSettings', 'TtsSettings', 'VoiceModeSettings'):
            self.assertNotIn(f'onNavigate(AppRoute.{old})', settings)
            self.assertIn(f'data object {old} : AppRoute', read('ui/navigation/AppRoute.kt'))
        root = read('ui/app/AgentAppRoot.kt')
        block = root[root.index('entry<AppRoute.VoiceSettings>'):root.index('entry<AppRoute.AppearanceSettings>')]
        self.assertEqual(4, block.count('VoiceSettingsScreen('))
        self.assertNotIn('pushRoute(', block)
        self.assertIn('is AppRoute.VoiceSettings ->', read('ui/app/AgentAppShell.kt'))

    def test_personal_voice_subpages_are_real_routes(self):
        # 「我的声音」二级页走真实路由，才能获得与兄弟页面一致的入栈/出栈转场。
        root = read('ui/app/AgentAppRoot.kt')
        routes = read('ui/navigation/AppRoute.kt')
        for route in ('DoubaoVoices', 'MimoVoices'):
            self.assertIn(f'data object {route} : AppRoute', routes)
            self.assertIn(f'entry<AppRoute.{route}>', root)
        self.assertIn('onOpenVoiceSubPage = ::openVoiceSubPage', root)
        screen = read('ui/VoiceSettingsScreen.kt')
        self.assertNotIn('var subPage', screen)
        self.assertNotIn('DoubaoVoiceSettings(', screen)
        self.assertNotIn('BackHandler(onBack = onBack)', read('ui/PersonalVoicesScreen.kt'))

    def test_selector_switches_in_place_with_connected_segmented_buttons(self):
        source = read('ui/VoiceSettingsScreen.kt')
        # 与外观设置页主题模式同款：官方 ButtonGroupDefaults 连体形状的 ToggleButton 组，
        # 按钮按文字自然宽度排布，超出屏宽整组横向滚动；选中即原地替换内容，
        # 不经下拉弹层中转，也不再手写 clip + background + clickable 拼装。
        self.assertIn('EtaExpressiveToggleButton(', source)
        self.assertIn('ButtonGroupDefaults.connectedLeadingButtonShapes()', source)
        self.assertIn('ButtonGroupDefaults.connectedTrailingButtonShapes()', source)
        self.assertIn('horizontalScroll(', source)
        self.assertIn('TouchHaptics.click(view)', source)
        self.assertIn('rememberSaveable', source)
        self.assertIn('key(selected)', source)
        for forbidden in ('TabRow', 'LazyRow', 'pushRoute(', 'onNavigate(', 'RoundedCornerShape', 'isMaterial3Theme()',
                          'SingleChoiceSegmentedButtonRow', 'EtaSegmentedDropdownRow', 'voice_section_choose',
                          'VoiceGroup {'):
            self.assertNotIn(forbidden, source)
        # 听写并入语音对话后不再有独立的「语音识别」分段；识别设置在语音对话段内。
        for name in ('READ_ALOUD', 'CONVERSATION', 'REALTIME', 'PERSONAL'):
            self.assertIn(f'VoiceSettingsSection.{name} ->', source)
        self.assertNotIn('RECOGNITION', source)

    def test_section_roots_do_not_nest_full_page_scaffolds(self):
        self.assertIn('LocalEmbeddedVoiceSettings provides true', read('ui/VoiceSettingsScreen.kt'))
        for file in ('TtsSettingsScreen', 'VoiceModeSettingsScreen', 'PersonalVoicesScreen'):
            source = read(f'ui/{file}.kt')
            self.assertIn('VoiceSettingsSectionPage(', source)
            # 分段根不再嵌套整页脚手架（当前 M3 脚手架为 EtaScaffoldPage）。
            self.assertNotIn('EtaScaffoldPage(', source)
        tts = read('ui/TtsSettingsScreen.kt')
        self.assertNotIn('PersonalVoicesScreen(', tts)
        self.assertIn('if (conversation) item(key = "conversation_recognition")', tts)
        self.assertNotIn('conversationEnabled', read('ui/VoiceModeSettingsScreen.kt'))

    def test_tts_profile_members_exist_and_both_read_and_write_use_them(self):
        source = read('ui/TtsSettingsScreen.kt')
        members = set(re.findall(r'profile\.(\w+)', source))
        self.assertTrue(members <= {'modeKey', 'providerKey', 'modelKey', 'voiceKey', 'snapshot'}, members)
        self.assertNotIn('Prefs.Keys.AGENT_TTS_', source)
        for member in ('modeKey', 'providerKey', 'modelKey', 'voiceKey'):
            self.assertIn(f'Prefs.getString(profile.{member})', source)
            self.assertIn(f'Prefs.putString(profile.{member},', source)
        self.assertIn('settings = profile.snapshot()', source)
        self.assertIn('ReadAloudVoiceHistory.restore(context, provider, model, profile)', source)
        self.assertIn('getSharedPreferences(profile.historyFile,', read('agent/voice/tts/ReadAloudVoiceHistory.kt'))

    def test_profile_keys_are_declared_unique_and_feature_scoped(self):
        prefs = read('config/Prefs.kt')
        declarations = dict(re.findall(r'const val (\w+) = "([^"]+)"', prefs))
        source = read('agent/voice/tts/SpeechPlaybackSettings.kt')
        keys = re.findall(r'Prefs.Keys.(\w+)', source)
        self.assertEqual(8, len(keys))
        self.assertTrue(set(keys) <= declarations.keys())
        self.assertEqual(8, len({declarations[key] for key in keys}))
        conversation = source[source.index('CONVERSATION('):source.index('fun snapshot')]
        self.assertNotIn('Prefs.Keys.AGENT_TTS_', conversation)
        self.assertIn('voice_conversation_voice_history', conversation)

    def test_call_snapshots_reach_real_asr_and_playback(self):
        controller = read('agent/voice/VoiceModeController.kt')
        block = controller[controller.index('private fun startUniversal'):controller.index('private fun startDuplex')]
        self.assertIn('SpeechPlaybackProfile.CONVERSATION.snapshot()', block)
        self.assertIn('settings = recognitionSettings', block)
        self.assertIn('SpeechPlayback.speak(app, owner, utterance, trace, settings = playbackSettings)', block)
        self.assertLess(block.index('val playbackSettings'), block.index('while (true)'))
        playback = read('agent/voice/tts/SpeechPlayback.kt')
        self.assertIn('listener = listener, settings = settings', playback)
        for field in ('cloud', 'providerId', 'modelId', 'voiceId'):
            self.assertIn(f'settings.{field}', playback)
        self.assertNotIn('Prefs.getString', playback)
        asr = read('agent/voice/SpeechInputSession.kt')
        self.assertIn('val selectedConfig = settings ?: configFor(mode)', asr)
        self.assertIn('DoubaoAsrSession.recognize(onListening, onText, selectedConfig, onLevel, stop)', asr)
        self.assertIn('VoiceEntryPolicy.enabled(it, mode)', asr)
        self.assertIn('settings == null && configFor(mode, it).cloudAsr != selected', asr)

    def test_dictation_merges_into_conversation_with_live_tail_follow(self):
        policy = read('agent/voice/VoiceEntryPolicy.kt')
        self.assertNotIn('add(VoiceEntryMode.DICTATION)', policy)
        picker = read('ui/components/AgentChatInputBar.kt')
        entries = picker[picker.index('val entries = listOf('):picker.index(').filter { it.first in modes }')]
        self.assertIn('VoiceEntryMode.UNIVERSAL', entries)
        self.assertIn('VoiceEntryMode.DOUBAO_DUPLEX', entries)
        self.assertNotIn('VoiceEntryMode.DICTATION', entries)
        controller = read('agent/voice/VoiceModeController.kt')
        block = controller[controller.index('private fun startUniversal'):controller.index('private fun startDuplex')]
        # 转写交给模型前立即清空，思考 / 播报阶段不再挂着上一轮识别文字。
        self.assertLess(block.index('transcript = ""'), block.index('submit(text)'))
        live = read('ui/components/AgentChatComposerM3.kt').split('private fun VoiceLiveTranscript(', 1)[1]
        # 单行保持不换行，滚动按最新转写持续跟随，不用瞬移。
        self.assertIn('horizontalScroll(scroll)', live)
        self.assertIn('scroll.animateScrollTo(', live)
        self.assertIn('maxLines = 1', live)
        self.assertIn('softWrap = false', live)

    def test_asr_keys_have_separate_persistence_and_settings(self):
        config = read('agent/voice/doubao/DoubaoVoiceConfig.kt')
        ui = read('ui/VoiceConversationRecognitionSettings.kt')
        router = read('agent/voice/SpeechInputSession.kt')
        load_block = config[config.index('fun load('):config.index('private fun migrateDictationIntoConversation')]
        migration = config[config.index('private fun migrateDictationIntoConversation'):config.index('fun save(')]
        save_block = config[config.index('fun save('):]
        for key in ('voice_conversation_asr_cloud', 'voice_conversation_asr_resource'):
            # 旧听写配置只迁移一次；进入语音对话后由 load/save 各自持久化。
            self.assertIn(f'"{key}"', migration)
            self.assertIn(f'"{key}"', load_block)
            self.assertIn(f'"{key}"', save_block)
        # 识别密钥不再落 prefs 明文，改由 DoubaoSecretStore 槽位加密持久化；
        # load / 一次性迁移 / save 三处同样各自经手该槽位。
        for slice_ in (load_block, migration, save_block):
            self.assertIn('DoubaoSecretStore.SLOT_CONVERSATION_ASR_KEY', slice_)
        for field in ('conversationCloudAsr', 'conversationAsrKey', 'conversationResource'):
            self.assertIn(f'config.{field}', ui)
            self.assertIn(f'config.{field}', router)
        self.assertIn('PasswordVisualTransformation()', ui)
        self.assertNotIn('AGENT_TTS_', ui)

    def test_no_old_hint_resources_or_empty_action_placeholder(self):
        removed = ('voice_mode_universal_settings_hint', 'voice_mode_doubao_settings_hint',
                   'voice_mode_universal_settings_summary', 'voice_section_choose')
        for name in ('VoiceModeSettingsScreen', 'TtsSettingsScreen', 'VoiceSettingsScreen', 'VoiceConversationRecognitionSettings'):
            source = read(f'ui/{name}.kt')
            for key in removed:
                self.assertNotIn(key, source)
            self.assertNotRegex(source, r'onClick\s*=\s*\{\s*\}')
        for directory in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            root = ET.parse(ROOT / 'app/src/main/res' / directory / 'voice_mode.xml').getroot()
            names = [element.get('name') for element in root]
            self.assertEqual(len(names), len(set(names)))
            for key in removed:
                self.assertNotIn(key, names)
            for name in ('voice_title', 'voice_section_speech', 'voice_section_tts', 'voice_section_conversation',
                         'voice_section_realtime', 'voice_section_personal',
                         'voice_conversation_cloud_asr', 'voice_conversation_asr_resource'):
                self.assertIn(name, names)


if __name__ == '__main__':
    unittest.main()
