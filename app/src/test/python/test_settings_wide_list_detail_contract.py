import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
UI = ROOT / "src/main/kotlin/io/github/mangi/eta/ui"
PROJECT = pathlib.Path(__file__).resolve().parents[4]

SCREEN = UI / "SettingsScreen.kt"
LAYOUT = UI / "SettingsWideLayout.kt"
ROOT_KT = UI / "app" / "AgentAppRoot.kt"
SHELL = UI / "app" / "AgentAppShell.kt"
FRAME = UI / "app" / "FrameDiagnosticRoute.kt"
ROUTE = UI / "navigation" / "AppRoute.kt"
NAVIGATOR = UI / "navigation" / "AgentNavigator.kt"
CATALOG = UI / "SettingsCatalog.kt"
PAGE_LAYOUT = UI / "layout" / "AdaptivePageLayout.kt"
VERSIONS = PROJECT / "gradle" / "libs.versions.toml"


class SettingsWideListDetailContractTest(unittest.TestCase):
    """宽屏设置页 list-detail 的结构契约。

    平板横屏：左栏是与手机端逐字一致的设置主页（十组全渲染、自带顶栏），右栏用独立
    返回栈承载二级/三级/四级设置页；主栈始终停在 AppRoute.Settings，左栏因此不消失、
    不重建、滚动位置不变。窄屏仍是原来的单列 + 主栈子页。

    依据 M3「Scaffold › Panes」的 co-planar 语义与 Android 官方 list-detail 指南。
    这组断言盯住：左栏不退变、entry DSL 不复制、push/pop 分流、占位路由五处登记齐全、
    默认首项、左栏选中态、左栏换页无动画、三级页动画只在直角右栏内。
    """

    def test_left_pane_is_the_unchanged_phone_settings_page(self):
        screen = SCREEN.read_text()
        # 左栏就是原封不动的设置主页，没有按分组筛选、没有切组、没有内嵌侧栏。
        for stale in (
            "selectedGroupId", "activeGroupId", "SettingsGroupSideRail",
            "groupIndex", "sideRail =", "SettingsGroupIds.",
        ):
            self.assertNotIn(stale, screen)
        self.assertEqual(len(re.findall(r"^ {12}settingsGroup\($", screen, re.M)), 10)
        # 搜索锚点、30 个子页入口、9 个帧诊断标签全部留在左栏。
        self.assertGreaterEqual(len(re.findall(r"onNavigate\(AppRoute\.\w+", screen)), 29)
        self.assertEqual(len(re.findall(r'\.settingsSectionDiagnostics\("([a-z_]+)"\)', screen)), 9)
        self.assertIn("focusKey: String? = null", screen)
        self.assertIn("EtaScaffoldPage(", screen)

    def test_left_pane_has_a_persistent_selected_row(self):
        screen = SCREEN.read_text()
        layout = LAYOUT.read_text()
        root = ROOT_KT.read_text()
        # 主页新增可选 selectedKey；手机端调用方不传时行为不变。
        self.assertIn("selectedKey: String? = null", screen)
        self.assertEqual(screen.count("selectedKey = selectedKey,"), 10)
        self.assertIn("selectedKey: String? = null", screen[screen.index("internal fun LazyListScope.settingsGroup("):])
        self.assertIn("selected = row.key != null && row.key == selectedKey", screen)
        # 选中态与搜索短暂高亮是两套独立状态，都用官方令牌。
        self.assertIn("selected -> M3MaterialTheme.colorScheme.tertiaryContainer", screen)
        self.assertIn("highlighted -> M3MaterialTheme.colorScheme.secondaryContainer", screen)
        # 路由 → 锚点键映射与目录的 Route 目标一一对应，防止新设置项漏登记。
        keys = re.findall(r"AppRoute\.(\w+)::class to SettingsKeys\.\w+", layout)
        indexed = set(re.findall(r"SettingsDestination\.Route\(AppRoute\.(\w+)", CATALOG.read_text()))
        self.assertEqual(len(keys), len(set(keys)))
        self.assertEqual(set(keys), indexed)
        self.assertIn("internal fun settingsAnchorKeyForRoute(route: AppRoute?)", layout)
        # 选中态只跟右栏栈底（二级页）走，进三级页左栏不跳。
        self.assertIn("val detailBaseRoute = settingsDetailStack.firstOrNull()", root)
        self.assertIn("settingsAnchorKeyForRoute(detailBaseRoute as? AppRoute)", root)
        self.assertIn("selectedKey = selectedKey,", root)

    def test_right_pane_opens_on_the_first_settings_subpage(self):
        layout = LAYOUT.read_text()
        root = ROOT_KT.read_text()
        screen = SCREEN.read_text()
        self.assertIn("internal val SettingsDetailFirstRoute: AppRoute = AppRoute.ModelProviders", layout)
        self.assertIn(
            "val settingsDetailStack = rememberNavBackStack<AppRoute>(SettingsDetailFirstRoute)",
            root,
        )
        # 第一项确实是设置主页第一组第一行的跳转目标。
        first_group = screen.split("settingsGroup(", 1)[1]
        self.assertIn("SettingsKeys.MODEL_PROVIDER", first_group.split("settingsGroup(", 1)[0])
        self.assertIn("onNavigate(AppRoute.ModelProviders)", first_group.split("settingsGroup(", 1)[0])
        # 每次进入宽屏设置页都回到首项，不把上一次的三级页带回来。
        self.assertIn("LaunchedEffect(currentRoute, wideScreenActive) {", root)
        self.assertIn("settingsDetailStack.add(SettingsDetailFirstRoute)", root)

    def test_settings_entry_keeps_left_pane_and_hosts_the_right_stack(self):
        root = ROOT_KT.read_text()
        block = root.split("entry<AppRoute.Settings>", 1)[1].split(
            "entry<AppRoute.SettingsDetailEmpty>", 1
        )[0]
        self.assertIn("val settingsPage: @Composable () -> Unit = {", block)
        self.assertIn("if (wideScreenActive)", block)
        self.assertIn("SettingsWideHost(", block)
        self.assertIn("left = settingsPage,", block)
        self.assertIn("backStack = settingsDetailStack,", block)
        self.assertIn("appEntries()", block)
        self.assertIn("} else {\n                    settingsPage()", block)
        # 栈底换页时重建右栏 display，避开 miuix leaving 层静止盖页的 500ms 收敛。
        self.assertIn("key(detailBaseRoute, detailInstantEpoch) {", block)
        # 重建代次只在瞬时换页时递增，三级页 push/pop 不动它（动画才不会被跳过）。
        self.assertIn("var detailInstantEpoch by remember { mutableStateOf(0) }", ROOT_KT.read_text())
        push = ROOT_KT.read_text().split("fun pushRoute(route: AppRoute) {", 1)[1].split("\n    }", 1)[0]
        self.assertNotIn("detailInstantEpoch", push)
        select = ROOT_KT.read_text().split("fun selectSettingsDetail(route: AppRoute) {", 1)[1].split("\n    }", 1)[0]
        self.assertIn("detailInstantEpoch++", select)
        self.assertIn("import androidx.compose.runtime.key", ROOT_KT.read_text())

    def test_left_selection_swaps_the_right_stack_without_animation(self):
        root = ROOT_KT.read_text()
        layout = LAYOUT.read_text()
        # 左栏与右栏用不同的导航入口：左栏整栈替换，右栏页内跳转才 push。
        self.assertIn("val openSettingsDestination: (AppRoute) -> Unit = if (wideScreenActive)", root)
        self.assertIn("{ route -> selectSettingsDetail(route) }", root)
        self.assertIn("{ route -> pushRoute(route) }", root)
        select = root.split("fun selectSettingsDetail(route: AppRoute) {", 1)[1].split("\n    }", 1)[0]
        self.assertIn("detailInstantSwap = true", select)
        self.assertIn("settingsDetailStack.clear()", select)
        self.assertIn("settingsDetailStack.add(route)", select)
        # 瞬时换页用官方 None 预设，转场标记与栈变更必须在同一个快照里。
        self.assertIn("internal val SettingsDetailInstantTransition = NavTransitions.None", layout)
        self.assertIn("transition = if (detailInstantSwap) {", root)
        self.assertIn("SettingsDetailInstantTransition", root)
        self.assertIn("Snapshot.withMutableSnapshot {", select)
        # 搜索层同样是左栏瞬时打开 / 瞬时退回，且压栈保留底部二级页与左栏选中态。
        self.assertIn("if (route == AppRoute.SettingsSearch) {", select)
        self.assertIn("detailInstantSwap = true", select)
        self.assertIn("settingsDetailStack.add(route)", select)
        pop = root.split("fun popRoute() {", 1)[1].split("\n    }", 1)[0]
        self.assertIn(
            "val dismissingSearch = settingsDetailStack.lastOrNull() == AppRoute.SettingsSearch",
            pop,
        )
        self.assertIn("if (dismissingSearch) detailInstantEpoch++", pop)

    def test_third_level_animation_stays_inside_the_square_right_pane(self):
        root = ROOT_KT.read_text()
        layout = LAYOUT.read_text()
        # 右栏独立 effects：关掉圆角裁切与整屏 scrim（直角 pane 不做圆角动画）。
        self.assertIn("internal val SettingsDetailPaneEffects: NavDisplayEffects = NavDisplayEffects.None", layout)
        self.assertIn("effects = SettingsDetailPaneEffects,", root)
        self.assertIn("effects = navEffects,", root)  # 主窗口转场不变
        # 右栏容器裁切转场的 graphicsLayer 平移，不越界盖住左栏。
        self.assertIn(".clipToBounds()", layout)
        # 右栏专用转场：入场层滑入 + 「下层压深」只取 1/4 视差，**不能有 alpha 衰减** ——
        # windowBackground 透明时下层半透明会漏出主界面内容；下层左缘越出右栏的部分
        # 由宿主 clipToBounds 裁切，不盖到左栏。
        transition = layout.split("internal val SettingsDetailPaneTransition =", 1)[1]
        transition = transition.split("/**", 1)[0]
        self.assertIn("navGraphicsTransition(opaqueDepth = 1f)", transition)
        self.assertIn("if (d <= 0f)", transition)
        self.assertIn("} else {", transition)
        self.assertIn("* width * 0.25f", transition)
        self.assertNotIn("alpha =", transition)
        self.assertIn("SettingsDetailPaneTransition", root)
        # 三级页 push/pop 立刻把 transition 从 None 切回右栏转场。
        push = root.split("fun pushRoute(route: AppRoute) {", 1)[1].split("\n    }", 1)[0]
        self.assertIn("detailInstantSwap = false", push)
        pop = root.split("fun popRoute() {", 1)[1].split("\n    }", 1)[0]
        self.assertIn("settingsDetailStack.lastOrNull() == AppRoute.SettingsSearch", pop)

    def test_push_and_pop_are_routed_to_the_right_stack(self):
        root = ROOT_KT.read_text()
        self.assertIn("val wideScreenActive = rememberIsWideScreen()", root)
        self.assertIn(
            "val settingsWideActive = wideScreenActive && currentRoute == AppRoute.Settings",
            root,
        )
        push = root.split("fun pushRoute(route: AppRoute) {", 1)[1].split("\n    }", 1)[0]
        self.assertIn("if (settingsWideActive && isSettingsDetailRoute(route)) {", push)
        self.assertIn("settingsDetailStack.add(route)", push)
        self.assertIn("navigator.push(route)", push)
        pop = root.split("fun popRoute() {", 1)[1].split("\n    }", 1)[0]
        self.assertIn("if (settingsWideActive && settingsDetailStack.size > 1) {", pop)
        self.assertIn("settingsDetailStack.removeAt(settingsDetailStack.lastIndex)", pop)
        # 主界面目的地仍走主栈，设置树的一切子页才进右栏。
        self.assertIn("fun isSettingsDetailRoute(route: AppRoute): Boolean = when (route) {", root)
        self.assertIn("is AppRoute.MessageSearch -> false", root)
        self.assertIn("AppRoute.Settings, AppRoute.SettingsDetailEmpty,", root)

    def test_one_entry_dsl_is_shared_by_both_displays(self):
        root = ROOT_KT.read_text()
        self.assertIn("fun NavEntryBuilder.appEntries() {", root)
        # 定义一处、调用两处（主 NavDisplay + Settings entry 内的右栏 NavDisplay）。
        self.assertEqual(root.count("fun NavEntryBuilder.appEntries() {"), 1)
        self.assertEqual(len(re.findall(r"^\s+appEntries\(\)$", root, re.M)), 2)
        # 每个路由只登记一次，不能为右栏复制一份 entry。
        entries = re.findall(r"entry<\s*AppRoute\.(\w+)\s*>", root)
        self.assertEqual(len(entries), len(set(entries)))
        self.assertEqual(root.count("NavDisplay(\n"), 2)
        # 方向必须在 builder 外算好，两个 NavDisplay 都不能抢在它前面。
        self.assertLess(root.index("val navSwipeDismiss = when"), root.index("NavDisplay("))

    def test_placeholder_route_is_registered_in_all_five_places(self):
        self.assertIn("data object SettingsDetailEmpty : AppRoute", ROUTE.read_text())
        self.assertIn("AppRoute.SettingsDetailEmpty::class,", NAVIGATOR.read_text())
        self.assertIn(
            "entry<AppRoute.SettingsDetailEmpty>(swipeDismiss = navSwipeDismiss) {",
            ROOT_KT.read_text(),
        )
        self.assertIn("is AppRoute.SettingsDetailEmpty ->", SHELL.read_text())
        self.assertIn(
            "AppRoute.SettingsDetailEmpty -> FrameDiagnosticPage.Settings",
            FRAME.read_text(),
        )

    def test_wide_host_is_co_planar_and_uses_single_adaptive_source(self):
        layout = LAYOUT.read_text()
        self.assertIn("internal fun SettingsWideHost(", layout)
        self.assertIn(
            "minOf(SettingsSideRailMaxWidth, maxWidth * SettingsSideRailWidthFraction)",
            layout,
        )
        # 两栏各自按被挤窄后的实际宽度发布内容限宽。
        self.assertEqual(
            layout.count("CompositionLocalProvider(LocalPageContentWidth provides maxWidth)"),
            2,
        )
        self.assertIn("internal fun SettingsDetailEmptyPane(", layout)
        self.assertNotIn("RoundedCornerShape(", layout)
        page = PAGE_LAYOUT.read_text()
        self.assertIn("val SettingsSideRailMaxWidth = 360.dp", page)
        self.assertIn("val SettingsSideRailWidthFraction = 0.46f", page)
        self.assertIn("val WideScreenMinWidth = 600.dp", page)

    def test_both_panes_lists_share_the_top_bar_horizontal_gutter(self):
        scaffold = (UI / "adaptive" / "EtaScaffold.kt").read_text()
        # 顶栏吃 systemBarsForVisualComponents（= systemBars + displayCutout）的水平 inset；
        # 列表必须补同一份，卡片左缘才与大标题/返回按钮对齐（横屏挖孔侧实测）。
        self.assertIn(
            "val topBarGutter = WindowInsets.systemBars\n"
            "        .union(WindowInsets.displayCutout)\n"
            "        .only(WindowInsetsSides.Horizontal)\n"
            "        .asPaddingValues()",
            scaffold,
        )
        self.assertIn("val gutterStart = topBarGutter.calculateStartPadding(layoutDirection)", scaffold)
        self.assertIn("val gutterEnd = topBarGutter.calculateEndPadding(layoutDirection)", scaffold)
        self.assertIn("start = sidePadding + gutterStart", scaffold)
        self.assertIn("end = sidePadding + gutterEnd", scaffold)
        self.assertIn("end = sidePadding + gutterEnd,", scaffold)
        # 效果链不变：不把 inset 挂成 modifier，只落到 contentPadding。
        lazy = scaffold.split("LazyColumn(", 1)[1]
        self.assertNotIn(".horizontalCutoutPadding()", lazy)

    def test_selected_row_uses_container_paired_content_colors(self):
        screen = SCREEN.read_text()
        segmented = (UI / "adaptive" / "EtaSegmentedList.kt").read_text()
        icon = (UI / "components" / "PreferenceIcon.kt").read_text()
        chevron = (UI / "adaptive" / "EtaChevrons.kt").read_text()
        # 亮底（tertiaryContainer）必须配官方 onTertiaryContainer，搜索高亮配 onSecondaryContainer。
        self.assertIn("LocalEtaSegmentedRowContentColor provides contentColor", screen)
        self.assertIn("selected -> M3MaterialTheme.colorScheme.onTertiaryContainer", screen)
        self.assertIn("highlighted -> M3MaterialTheme.colorScheme.onSecondaryContainer", screen)
        self.assertIn("val LocalEtaSegmentedRowContentColor = compositionLocalOf<Color?> { null }", segmented)
        self.assertIn("?: LocalEtaSegmentedRowContentColor.current", segmented)
        # 分段行官方 ListItem 的 supporting 文本仍走原来的回退，未被改动。
        self.assertIn(
            "supportingContentColor = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant",
            segmented,
        )
        # 硬编码 tint 的行首图标 / 行尾箭头也要跟着换色，否则亮底上依然看不清。
        self.assertIn("val selectedContentColor = LocalEtaSegmentedRowContentColor.current", icon)
        self.assertIn("selectedContentColor != null -> selectedContentColor", icon)
        self.assertIn("val selectedContentColor = LocalEtaSegmentedRowContentColor.current", chevron)
        self.assertIn("tint = selectedContentColor ?: tint", chevron)

    def test_detail_pane_root_has_no_back_arrow(self):
        scaffold = (UI / "adaptive" / "EtaScaffold.kt").read_text()
        root = ROOT_KT.read_text()
        # 详情栏栈深信号由宿主发布，手机端（普通二级页）读到 null。
        self.assertIn(
            "val LocalEtaDetailPaneDepth: ProvidableCompositionLocal<Int?> = compositionLocalOf { null }",
            scaffold,
        )
        # 0 = 栈底二级页不画箭头，> 0 = 三级/四级页照旧；判据只在首次组合时取一次。
        self.assertIn(
            "val showBackButton = remember { detailPaneDepth == null || detailPaneDepth > 0 }",
            scaffold,
        )
        self.assertEqual(scaffold.count("if (showBackButton) {"), 2)  # 列表骨架 + 自定义内容骨架
        self.assertEqual(scaffold.count("detailPaneDepth = LocalEtaDetailPaneDepth.current"), 2)
        # 右栏按栈深（0 = 栈底）发布；主 display 不发布，窄屏逐字不变。
        self.assertIn("LocalEtaDetailPaneDepth provides (settingsDetailStack.size - 1)", root)
        self.assertEqual(root.count("LocalEtaDetailPaneDepth provides"), 1)
        # 发布点必须落在右栏 display 外面，否则所有层级读到同一个值。
        block = root.split("entry<AppRoute.Settings>", 1)[1].split("NavDisplay(", 1)[0]
        self.assertIn("LocalEtaDetailPaneDepth provides", block)

    def test_swipe_back_drives_the_stack_it_belongs_to(self):
        root = ROOT_KT.read_text()
        # 主 display 的 onBack 只弹主栈：手势拖动的是整页，提交时若只弹右栏栈会弹回错位。
        self.assertIn("fun popRootRoute() {", root)
        self.assertIn("if (navigator.pop()) {", root)
        self.assertIn(
            "onBack = { if (settingsWideActive) popRootRoute() else popRoute() }",
            root,
        )
        # 右栏 display 仍走 popRoute，逐层退出右栏栈；两个 display 的 onBack 不能共用。
        block = root.split("entry<AppRoute.Settings>", 1)[1].split(
            "entry<AppRoute.SettingsDetailEmpty>", 1
        )[0]
        self.assertIn("onBack = { popRoute() },", block)
        self.assertEqual(root.count("fun popRootRoute() {"), 1)

    def test_no_androidx_adaptive_dependency_was_introduced(self):
        toml = VERSIONS.read_text()
        for marker in ("material3-adaptive", "adaptive-layout", "adaptive-navigation", "navigation-suite"):
            self.assertNotIn(marker, toml)
        for source in (SCREEN.read_text(), LAYOUT.read_text(), ROOT_KT.read_text(), PAGE_LAYOUT.read_text()):
            self.assertNotIn("androidx.compose.material3.adaptive", source)


if __name__ == "__main__":
    unittest.main()
