"""No-SDK wiring/parameter and strict divider contracts for the restored menu.

Actual attached-node touches, persistence and live setting changes are exercised by
TopBarOverflowMenuInteractiveModeTest; these checks do not substitute for Compose.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"


def read(path):
    return (SRC / path).read_text(encoding="utf-8")


def balanced(source, start):
    """Extract one balanced Kotlin argument list or block, ignoring string literals."""
    opening = source[start]
    closing = {"(": ")", "{": "}"}[opening]
    depth = 0
    quoted = False
    escaped = False
    for index in range(start, len(source)):
        char = source[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError("Unclosed Kotlin block or call")


def arguments(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*\(", source)
    if not match:
        raise AssertionError(f"Missing {name} call")
    return balanced(source, match.end() - 1)


def callback(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*\{", source)
    if not match:
        raise AssertionError(f"Missing {name} callback")
    return balanced(source, match.end() - 1)


def entry_call(source, start):
    """Extract one composable call together with its optional trailing lambda."""
    paren = balanced(source, source.index("(", start))
    after = source.index(paren, start) + len(paren)
    if source[after:].lstrip().startswith("{"):
        return paren + balanced(source, source.index("{", after))
    return paren


def compact(source):
    return re.sub(r"\s+", "", source)


class TopBarInteractiveModeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.menu = read("ui/app/TopBarOverflowMenu.kt")
        start = cls.menu.index("internal fun TopBarMenuItems(")
        # 弹层容器已统一为官方的 EtaMenuPopup，菜单条目由 TopBarMenuItems 组装；
        # 切片从 TopBarMenuItems 的定义开始，到行/段封装函数之前结束。
        cls.content = cls.menu[start:cls.menu.index("private fun ColumnScope.TopBarMenuSection(", start)]
        cls.entries = []
        cls.sequence = []
        # 段落头有两种写法：带参数 `TopBarMenuSection(first = true)` 与裸 `TopBarMenuSection {`，
        # 所以只匹配名字本身，再向后取到 `{` 之前的签名来判断是否首段。
        for match in re.finditer(r"\bTopBarMenuSection\b|\bTopBarMenuItem\s*\(", cls.content):
            if match.group(0).startswith("TopBarMenuSection"):
                header = cls.content[match.start():cls.content.index("{", match.start())]
                # 首段之前不加分割线；其余每段由 TopBarMenuSection 自行补一条分割线。
                if "first = true" not in header:
                    cls.sequence.append("divider")
            else:
                entry = entry_call(cls.content, match.start())
                cls.entries.append(entry)
                # 行文案直接传 stringResource，或复用 kimiWebLabel 变量。
                # 标签只认资源名或 kimiWebLabel，文案改动不影响契约。
                label = re.search(r"text\s*=\s*stringResource\(R\.string\.(\w+)\)", entry)
                if label:
                    cls.sequence.append(label.group(1))
                elif "text = kimiWebLabel" in entry:
                    cls.sequence.append("kimiWebLabel")
                else:
                    raise AssertionError("Unexpected menu row label")
        interactive = [entry for entry in cls.entries if "R.string.action_interactive_mode" in entry]
        if len(interactive) != 1:
            raise AssertionError("Expected exactly one interactive-mode menu row")
        cls.interactive = interactive[0]

    def test_exact_menu_order_keeps_semantic_sections_and_conditional_rows(self):
        # M3 壳层把「新建对话」独立成顶栏按钮（AgentAppShell 里的 AddComment IconButton），
        # 不再占溢出菜单一行；「移动到回收站」只在非调度台出现，「停止 Kimi Web」只在可停止时出现。
        self.assertEqual([
            "action_search_history",
            "action_voice_settings",
            "divider", "action_interactive_mode", "divider",
            "action_open_terminal", "action_open_browser", "action_browse_workspace_files",
            "action_sub_agent_status", "kimiWebLabel", "divider", "action_token_usage",
            "action_compress_conversation", "action_auto_compress_context",
            "divider", "conversation_move_to_trash",
            "divider", "capability_kimi_stop",
        ], self.sequence)
        # 计数行 `val sectionSixthCount = if (canStopKimiWeb) 1 else 0` 也含该条件，
        # 所以取最后一次出现，即真正的收尾「停止 Kimi Web」条件块。
        stop_at = self.content.rindex("if (canStopKimiWeb)")
        stop = balanced(self.content, self.content.index("{", stop_at))
        self.assertEqual(1, stop.count("TopBarMenuSection {"))
        self.assertIn("R.string.capability_kimi_stop", stop)
        self.assertEqual(4, self.content[:stop_at].count("TopBarMenuSection {"))
        self.assertEqual(5, self.content.count("TopBarMenuSection {"))
        self.assertEqual(1, self.content.count("TopBarMenuSection(first = true)"))

    def test_dividers_come_from_the_section_helper_and_the_official_divider(self):
        helper_at = self.menu.index("private fun ColumnScope.TopBarMenuSection(")
        helper = balanced(self.menu, self.menu.index("{", helper_at))
        # 段落只负责「非首段才补线」，线本身取官方 EtaMenuDivider。
        self.assertEqual(
            compact("{ if (!first) EtaMenuDivider() content() }"),
            compact(re.sub(r"//[^\n]*", "", helper)),
        )
        self.assertIn("import io.github.mangi.eta.ui.adaptive.EtaMenuDivider", self.menu)
        self.assertNotIn("HorizontalDivider(", self.menu)
        self.assertEqual(1, self.menu.count("EtaMenuDivider()"))
        self.assertNotIn("Divider(", self.content)  # 分割线只在 helper 里出现
        self.assertNotIn("MiuixTheme", helper)
        self.assertNotIn("thickness", helper)  # 官方默认厚度
        self.assertNotIn("color =", helper)  # 官方默认 outlineVariant，不再自绘透明度
        self.assertNotIn("MiuixTheme", self.content)

    def test_menu_observes_the_real_preference_without_initialization_writes(self):
        self.assertIn("val interactiveModePreference = remember { InteractiveModePreference() }", self.menu)
        self.assertIn("val interactiveModeEnabled by rememberInteractiveModeEnabled(interactiveModePreference)", self.menu)
        self.assertEqual(2, self.menu.count("interactiveModePreference.setEnabled("))
        self.assertEqual(2, self.interactive.count("interactiveModePreference.setEnabled("))
        for forbidden in ("Prefs.putBoolean", "AgentTaskSurface.save", "ask_user", "LaunchedEffect", "SideEffect"):
            self.assertNotIn(forbidden, self.menu)

    def test_row_and_switch_are_both_clickable_without_dismissing_the_popup(self):
        self.assertIn('modifier = Modifier.testTag("top-bar-interactive-mode-row")', self.interactive)
        # 图标改为位置参数写法（无需命名 imageVector）。
        self.assertIn("Icon(Icons.Rounded.TouchApp,", self.interactive)
        switch = arguments(self.interactive, "EtaSwitch")
        self.assertIn("checked = interactiveModeEnabled", switch)
        self.assertIn("interactiveModePreference.setEnabled(enabled)", callback(switch, "onCheckedChange"))
        self.assertIn("onCheckedChange = { enabled ->", switch)
        self.assertIn('Modifier.testTag("top-bar-interactive-mode-switch")', switch)
        click = callback(self.interactive, "onClick")
        self.assertIn("interactiveModePreference.setEnabled(!interactiveModeEnabled)", click)
        self.assertNotIn("menuState.dismiss", self.interactive)
        # 行点击的触感由 TopBarMenuItem 统一处理，开关自己再补一次。
        self.assertEqual(1, self.interactive.count("TouchHaptics.click(view)"))
        self.assertIn("shape = EtaMenuItemShape(index = index, count = count)", self.menu)
        row = balanced(self.menu, self.menu.index("{", self.menu.index("private fun TopBarMenuItem(")))
        self.assertIn("TouchHaptics.click(view)", row)

    def test_preference_cold_read_and_observer_use_only_the_independent_local_boolean(self):
        preference = read("config/InteractiveModePreference.kt")
        state = read("ui/components/InteractiveModePreferenceState.kt")
        self.assertIn("private val preferences: SharedPreferences? = Prefs.localAgentPreferences()", preference)
        self.assertIn('const val PREF_KEY = "interactive_mode"', preference)
        self.assertIn("val enabled: Boolean get() = read(preferences)", preference)
        self.assertIn("fun read(preferences: SharedPreferences? = Prefs.localAgentPreferences()): Boolean", preference)
        self.assertIn("runCatching { preferences?.getBoolean(PREF_KEY, false) ?: false }.getOrDefault(false)", preference)
        self.assertEqual(1, preference.count("getBoolean("))
        self.assertEqual(1, preference.count("putBoolean("))
        setter_at = preference.index("fun setEnabled(")
        setter = balanced(preference, preference.index("{", setter_at))
        self.assertIn("preferences?.edit()?.putBoolean(PREF_KEY, enabled)?.apply()", setter)
        # All write access is confined to an explicit user-requested toggle, not
        # construction, cold reads, migration, or observer registration/refresh.
        self.assertEqual(1, preference.count(".edit()"))
        self.assertNotIn(".edit()", preference[:setter_at])
        for source in (self.menu, preference, state):
            # Comments may describe the intentional absence of migration.
            code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
            for forbidden in ("AgentTaskSurface", "agent_task_surface", "migrate", "putString", "Prefs.putBoolean"):
                self.assertNotIn(forbidden, code)
        self.assertIn("key == null || key == PREF_KEY", preference)
        self.assertIn("preferences?.registerOnSharedPreferenceChangeListener(listener)", preference)
        self.assertIn("preferences?.unregisterOnSharedPreferenceChangeListener(listener)", preference)
        self.assertIn("closed.compareAndSet(false, true)", preference)
        self.assertIn("if (!closed.get()) onChanged(enabled)", preference)
        self.assertIn("main.removeCallbacks(refresh)", preference)
        self.assertIn("remember(preference) { mutableStateOf(preference.enabled) }", state)
        self.assertIn("DisposableEffect(preference)", state)
        self.assertIn("preference.observe { enabled.value = it }", state)
        self.assertIn("onDispose { observation.close() }", state)

    def test_behavior_tests_cover_location_isolation_real_touches_and_observer_disposal(self):
        tests = ROOT / "app/src/test/kotlin/io/github/mangi/eta"
        preference_test = (tests / "config/InteractiveModePreferenceTest.kt").read_text(encoding="utf-8")
        menu_test = (tests / "ui/app/TopBarOverflowMenuInteractiveModeTest.kt").read_text(encoding="utf-8")
        observer_test = (tests / "ui/components/InteractiveModePreferenceStateTest.kt").read_text(encoding="utf-8")
        for case in (
            "absentIndependentKeyDefaultsFalseForEveryLocationWithoutWritingOrMigrating",
            "existingIndependentBooleanWinsForAllLocationsAndColdReadsAgreeWithObservers",
            "togglingOnAndOffNeverChangesForegroundBackgroundAskOrAMissingLocation",
            "settingsLocationWritesDoNotChangeTheSwitchOrNotifyItsObservers",
            "externalIndependentWritesRemovalAndClearRefreshTheSameColdRead",
            "corruptBooleanAndUnavailableStorageFailClosedWithoutRepairWrites",
            "backgroundNotificationsUseMainAndReadLatestStorageRatherThanCapturedValues",
            "registrationRereadsAndCloseUnregistersOnceSuppressingQueuedAndLateCallbacks",
        ):
            self.assertIn(f"@Test fun {case}()", preference_test)
        for mode in ("FOREGROUND", "BACKGROUND", "ASK"):
            self.assertIn(f"exerciseRealTouches(AgentTaskSurfaceMode.{mode})", menu_test)
        self.assertIn("row().performTouchInput { click(Offset(width * 0.35f, center.y)) }", menu_test)
        self.assertIn("switch().performTouchInput { click() }", menu_test)
        self.assertIn("compose.mainClock.advanceTimeBy(500)", menu_test)
        self.assertIn("assertStored(mode)", menu_test)
        self.assertIn("assertEnabled(true)", menu_test)
        self.assertIn("assertEnabled(false)", menu_test)
        self.assertIn("settingsLocationChangesNeverChangeTheSwitchButIndependentWritesRefreshIt", menu_test)
        self.assertIn("persistedIndependentTrueIsUsedOnFirstCompositionWithoutChangingForeground", menu_test)
        self.assertIn("askLocationDoesNotEnableOrInitializeTheIndependentSwitch", menu_test)
        self.assertIn("rememberInteractiveModeEnabled(selected.value)", observer_test)
        self.assertIn("assertEquals(0, first.listeners.size)", observer_test)
        self.assertIn("assertEquals(0, second.listeners.size)", observer_test)
        self.assertIn("assertEquals(2, second.unregisterCount)", observer_test)

    def test_menu_call_parameters_keep_existing_actions_and_avoid_lifted_toggle_state(self):
        declaration = arguments(self.menu, "TopBarOverflowMenu")
        caller = arguments(read("ui/app/AgentAppShell.kt"), "TopBarOverflowMenu")
        for name in ("onOpenTerminal", "onLaunchKimiWeb", "kimiWebLabel",
                     "canStopKimiWeb", "onStopKimiWeb", "onRefreshKimiWeb", "onOpenBrowser",
                     "onOpenWorkspace", "onOpenVoiceSettings",
                     "autoCompressEnabled", "isCompressingContext",
                     "onToggleAutoCompress", "onCompressConversation", "onOpenSearchHistory",
                     "onOpenTokenUsage", "onOpenSubAgentStatus",
                     "canDeleteCurrentConversation", "onDeleteCurrentConversation"):
            with self.subTest(parameter=name):
                self.assertRegex(declaration, r"\b" + name + r"\s*:")
                self.assertIn(f"{name} = {name}", caller)
        self.assertNotIn("interactiveMode", declaration)
        self.assertNotIn("interactiveMode", caller)
        auto = next(entry for entry in self.entries if "R.string.action_auto_compress_context" in entry)
        self.assertIn("checked = autoCompressEnabled", arguments(auto, "EtaExpressiveSwitch"))
        self.assertIn("onToggleAutoCompress(!autoCompressEnabled)", callback(auto, "onClick"))


if __name__ == "__main__":
    unittest.main()
