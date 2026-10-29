"""Static wiring checks only; no device rendering, vibration or Kotlin compilation."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
KOTLIN = ROOT / 'app/src/main/kotlin/io/github/mangi/eta'


def read(path):
    return (KOTLIN / path).read_text(encoding='utf-8')


class VoiceFormStyleHapticsContractTest(unittest.TestCase):
    def test_shared_form_has_rounded_filled_outline_and_floating_label(self):
        field = read('ui/components/EtaFormTextField.kt')
        # M3-only：圆角/填充色/浮动标签委托官方 OutlinedTextField 默认令牌，
        # 不再自绘 RoundedCornerShape、lerp 容器色与描边色。
        self.assertIn('OutlinedTextField(', field)
        self.assertIn('import androidx.compose.material3.OutlinedTextField', field)
        self.assertIn('internal val EtaInputCornerRadius = 12.dp', field)
        self.assertIn('internal fun etaInputContainerColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh', field)
        self.assertIn('label = { Text(text = hint) }', field)
        self.assertIn('import androidx.compose.material3.Text', field)  # inherit animated label size/color
        self.assertNotIn('useLabelAsPlaceholder', field)
        self.assertIn('.fillMaxWidth()', field)
        self.assertIn('contentDescription = hint', field)
        for gone in ('RoundedCornerShape', 'lerp(', 'unfocusedBorderColor', 'focusedContainerColor'):
            self.assertNotIn(gone, field)
        self.assertNotRegex(field, r'Color\(0x')

    def test_chat_shell_matches_form_style_without_replacing_stateful_editor(self):
        chat = read('ui/components/AgentChatInputBar.kt')
        composer = read('ui/components/AgentChatComposerM3.kt')
        # M3 输入区：官方 TextField(state = textFieldState) 是唯一 stateful 编辑器，
        # 容器形态全部取官方 Shape/颜色槽，不再有 miuix 时代的自绘胶囊（clip/描边/0.5dp 线）。
        shell = composer.split('private fun M3ComposerTextField(', 1)[1].split('private fun M3VoiceBar(', 1)[0]
        self.assertIn('TextField(', shell)
        self.assertIn('state = textFieldState', shell)
        self.assertIn('TextFieldLineLimits.MultiLine(', shell)
        self.assertIn('maxHeightInLines = 6', shell)
        self.assertIn('shape = MaterialTheme.shapes.extraLarge', shell)
        self.assertIn('focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer', shell)
        self.assertNotIn('BasicTextField(', shell)
        self.assertNotIn('.dropShadow(', shell)
        for gone in ('InputContainerShape', 'etaInputContainerColor', 'width = 0.5.dp', 'outline.copy(alpha = 0.30f)'):
            self.assertNotIn(gone, composer)
        voice = composer.split('private fun M3VoiceBar(', 1)[1]
        self.assertIn('.clip(MaterialTheme.shapes.extraLarge)', voice)
        self.assertIn('.background(MaterialTheme.colorScheme.surfaceContainer)', voice)
        self.assertIn('M3VoiceBar(', composer)
        self.assertIn('onTranscriptTap =', composer)
        self.assertIn('Material3ComposerBody(', chat)

    def test_shared_field_keeps_line_limits_password_and_disabled_state(self):
        field = read('ui/components/EtaFormTextField.kt')
        for name in ('value', 'onValueChange', 'enabled', 'singleLine', 'visualTransformation', 'keyboardOptions'):
            self.assertIn(f'{name} = {name}', field)
        self.assertIn('minLines = if (singleLine) 1 else minLines', field)
        self.assertIn('maxLines = if (singleLine) 1 else maxLines', field)
        self.assertIn('maxLines: Int = Int.MAX_VALUE', field)
        self.assertIn('visualTransformation: VisualTransformation = VisualTransformation.None', field)
        # 涟漪/focus 指示交给官方 OutlinedTextField 默认值，不再包一层压制按压反馈的 M3 包装。
        self.assertNotIn('LocalRippleConfiguration', field)
        self.assertNotIn('WithoutPressRipple', field)
        self.assertNotIn('mutableStateOf', field)  # draft ownership stays at callers

    def test_screens_and_question_use_same_shared_field(self):
        # M3E 分段化后分成两个家族，各自只有一份共享实现、都不允许裸写 OutlinedTextField：
        # 设置页用 EtaSegmentedTextField（分段输入行），提问卡片用 EtaFormTextField
        # （带浮动Label的表单输入框）。
        screens = ('ui/PersonalVoicesScreen.kt', 'ui/VoiceModeSettingsScreen.kt',
                   'ui/VoiceConversationRecognitionSettings.kt', 'ui/DoubaoVoiceSettings.kt')
        for path in screens:
            with self.subTest(path=path):
                source = read(path)
                self.assertIn('EtaSegmentedTextField(', source)
                self.assertNotIn('OutlinedTextField(', source)
        question = read('ui/components/AgentQuestionCard.kt')
        self.assertIn('EtaFormTextField(', question)
        self.assertNotIn('OutlinedTextField(', question)
        mimo = read('ui/PersonalVoicesScreen.kt')
        self.assertIn('name = it.take(80)', mimo)
        self.assertIn('enabled = !busy', mimo)
        self.assertIn('singleLine = true', mimo)
        self.assertIn('sample = it.take(300)', mimo)
        realtime = read('ui/VoiceModeSettingsScreen.kt')
        self.assertIn('minLines = 2', realtime)
        self.assertIn('maxLines = 5', realtime)
        self.assertIn('Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS, it)', realtime)
        question = read('ui/components/AgentQuestionCard.kt')
        self.assertIn('hint = label', question)
        self.assertIn('minLines = 1, maxLines = Int.MAX_VALUE', question)
        self.assertIn('otherText = it.take(2000)', question)
        self.assertIn('note = it.take(2000)', question)
        self.assertIn('val editable = !message.submitting', question)
        cloud = read('ui/VoiceConversationRecognitionSettings.kt')
        self.assertIn('visualTransformation = PasswordVisualTransformation()', cloud)
        doubao = read('ui/DoubaoVoiceSettings.kt')
        self.assertIn('visualTransformation = visualTransformation', doubao)

    def test_missing_voice_controls_use_app_haptic_wrappers_once(self):
        for path in ('ui/VoiceModeSettingsScreen.kt', 'ui/VoiceConversationRecognitionSettings.kt'):
            source = read(path)
            self.assertIn('EtaSwitchRow(', source)
            self.assertIn('EtaClickableRow(', source)
            self.assertNotIn('TouchHaptics.click', source)  # wrappers own haptic, dialogs own their own
        wrappers = read('ui/components/HapticPreferences.kt')
        self.assertIn('TouchHaptics.click(view)', wrappers)
        self.assertIn('enabled = enabled', wrappers)
        touch = read('ui/haptics/TouchHaptics.kt')
        self.assertIn('if (!ignoreAppSwitch && !isTouchEnabled()) return false', touch)

    def test_refresh_is_disabled_while_running_and_dialogs_keep_owned_haptics(self):
        realtime = read('ui/VoiceModeSettingsScreen.kt')
        self.assertIn('enabled = !refreshing', realtime)
        self.assertIn('if (!refreshing) scope.launch', realtime)
        picker = read('ui/TtsModelPickerDialog.kt')
        self.assertIn('TouchHaptics.click', picker)
        actions = read('ui/adaptive/EtaDialog.kt')
        self.assertEqual(2, actions.count('TouchHaptics.click(view)'))
        for name in ('onSelected', 'onDismiss'):
            # New screen wiring does not add feedback in already-haptic dialog callbacks.
            for callback in re.finditer(name + r'\s*=\s*\{([^}]+)\}', realtime):
                self.assertNotIn('TouchHaptics', callback.group(1))


if __name__ == '__main__':
    unittest.main()
