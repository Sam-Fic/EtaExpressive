from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[3]
BASE = APP / "src/main/kotlin/io/github/mangi/eta"

class SelfAppSurfaceContractTest(unittest.TestCase):
    def test_real_target_is_pinned_before_ask_and_surface_routing(self):
        s = (BASE / "agent/tool/AgentLocalTools.kt").read_text()
        execute = s.split("override fun execute(", 1)[1].split("private fun resolveAskedSurface", 1)[0]
        self.assertLess(execute.index("prepareLaunchApp(args)"), execute.index("resolveAskedSurface("))
        self.assertIn('prepared?.intent?.component?.packageName == it', execute)
        self.assertIn('route == AgentSelfAppSurface.Route.BASE &&', execute)
        self.assertIn('toolCall.copy(argumentsJson', execute)
        self.assertIn('"task_surface_scope", "self_app"', execute)
        self.assertIn('"base_task_surface", runSurface.wire', execute)
        self.assertNotRegex(execute, r'runSurface\s*=(?!=)')

    def test_lifecycle_remains_on_base_and_gui_validates_after_entry_gate(self):
        s = (BASE / "agent/tool/AgentLocalTools.kt").read_text()
        self.assertIn('name in virtualLifecycle || (route == AgentSelfAppSurface.Route.BASE', s)
        internal = s.split("private fun executeInternal(", 1)[1].split("private fun deviceToolPermissionError", 1)[0]
        self.assertLess(internal.index('beforeToolExecution(toolCall.name)'), internal.index('"SELF_APP_TARGET_LOST"'))
        self.assertIn('ForegroundExclusiveGate.acquire(browserRunId)', s)
        self.assertIn('if (!backgroundSurface) return null', s)
        self.assertIn('surfaceRouteLock.lockInterruptibly()', s)
        self.assertIn('surfaceRouteLock.unlock()', s)

    def test_focus_and_launch_are_default_display_only(self):
        s = (BASE / "agent/accessibility/AgentAccessibilityService.kt").read_text()
        probe = s.split('internal fun currentMainDisplayPackageName()', 1)[1].split('fun displaySize()', 1)[0]
        self.assertIn('windowsOnAllDisplays.get(android.view.Display.DEFAULT_DISPLAY)', probe)
        self.assertIn('it.isFocused', probe)
        self.assertIn('focused.size != 1', probe)
        self.assertIn('root.window?.displayId != android.view.Display.DEFAULT_DISPLAY', probe)
        self.assertIn('root.windowId != focused.single().id', probe)
        self.assertIn('root.packageName?.toString() == it', probe)
        launch = (BASE / 'agent/tool/AgentLocalTools.kt').read_text()
        self.assertIn('ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)', launch)

    def test_focus_loss_latches_rejection_for_queued_gui_until_explicit_target(self):
        s = (BASE / 'agent/tool/AgentSelfAppSurface.kt').read_text()
        self.assertIn('if (!active && !lost) return Route.BASE', s)
        validate = s.split('fun validateForeground(', 1)[1].split('companion object', 1)[0]
        self.assertIn('lost = true', validate)
        self.assertNotIn('leave()', validate)

    def test_prompt_and_launch_schema_explain_scope_not_global_override(self):
        s = (BASE / 'agent/device/AgentTaskSurface.kt').read_text()
        # 自身例外条款已从常量 SELF_APP_CLAUSE 改为函数 selfAppClause(named)（按工具目录裁剪示例）。
        self.assertIn('selfAppClause(named) + when (stored)', s)
        self.assertIn('io.github.mangi.eta', s)
        self.assertIn('不把整轮改成前台', s)
        self.assertIn('task_surface_scope=self_app', s)
        self.assertIn('task_surface_scope=ordinary_run', s)
        self.assertNotIn('表示之后都在主屏直接操作', s)
        schema = (BASE / 'agent/model/AgentContextAppToolCatalog.kt').read_text()
        # launch_app 的 schema 描述已译成英文，语义不变：自身目标不触发 ASK，
        # 也不改变其它应用的 ASK/后台偏好。
        self.assertIn('without triggering ASK', schema)
        self.assertIn('do not change the ASK/background preference of other apps', schema)

    def test_all_preference_translations_describe_self_only_exception(self):
        for folder, token in [('values', 'Eta itself'), ('values-b+zh+Hans', 'Eta自身'), ('values-b+zh+Hant', 'Eta自身')]:
            texts = {e.attrib['name']: e.text for e in ET.parse(APP / 'src/main/res' / folder / 'agent_task_preference_ui.xml').getroot()}
            for key in ['agent_task_preference_mode_hint_background', 'agent_task_surface_prompt_message', 'agent_task_preference_mode_hint_ask']:
                self.assertIn(token, texts[key])
            language = {
                'values': ('Other apps run on the background', 'runs in the foreground', 'without changing this preference', 'foreground without prompting'),
                'values-b+zh+Hans': ('其它应用在后台', '自身直接前台', '不改变此偏好', '不询问'),
                'values-b+zh+Hant': ('其他應用在後台', '自身直接前台', '不改變此偏好', '不詢問'),
            }[folder]
            background = texts['agent_task_preference_mode_hint_background']
            for part in language[:3]:
                self.assertIn(part, background)
            for key in ['agent_task_surface_prompt_message', 'agent_task_preference_mode_hint_ask']:
                self.assertIn(language[3], texts[key])
