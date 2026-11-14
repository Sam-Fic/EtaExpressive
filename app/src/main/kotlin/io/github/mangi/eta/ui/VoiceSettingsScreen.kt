package io.github.mangi.eta.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaExpressiveToggleButton
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedScope
import io.github.mangi.eta.ui.components.horizontalScrollEdgeFade
import io.github.mangi.eta.ui.haptics.TouchHaptics

internal enum class VoiceSettingsSection(val title: Int) {
    READ_ALOUD(R.string.voice_section_tts),
    CONVERSATION(R.string.voice_section_conversation),
    REALTIME(R.string.voice_section_realtime),
    PERSONAL(R.string.voice_section_personal),
}

/** 「我的声音」内的子页：作为独立顶层页呈现（自带顶栏/返回），不再嵌套进分区内。 */
internal enum class VoiceSubPage { DOUBAO_VOICES, MIMO_VOICES }

internal val LocalEmbeddedVoiceSettings = staticCompositionLocalOf { false }

/** 一组语音设置条目的统一左右边距与组间距。 */
internal val VoiceGroupInsets = PaddingValues(
    start = EtaGroupHorizontalPadding,
    end = EtaGroupHorizontalPadding,
    bottom = EtaSegmentedGroupSpacing,
)

/**
 * 一组语音设置条目（KernelSU 式分段列表）。
 *
 * 用单一入口替代各页 `item { EtaSegmentedColumn(modifier = padding) { item { … } } }`
 * 的三层包裹；条件行用 [EtaSegmentedScope.item] 的 `visible` 注册，显隐带分段动画。
 */
@Composable
internal fun VoiceGroup(content: EtaSegmentedScope.() -> Unit) {
    EtaSegmentedColumn(modifier = Modifier.padding(VoiceGroupInsets), content = content)
}

/** [VoiceGroup] 的 LazyListScope 形式。 */
internal fun LazyListScope.voiceGroup(key: Any? = null, content: EtaSegmentedScope.() -> Unit) {
    item(key = key) { VoiceGroup(content) }
}

/** Reuses section content without nesting a second page title/scaffold. */
@Composable
internal fun VoiceSettingsSectionPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    if (LocalEmbeddedVoiceSettings.current) {
        // 与独立页同一口径：列表铺到屏幕底缘，导航栏高度只补在内容末尾，
        // 滚动时条目从手势条下方透出，静止时最后一条仍完整露出。
        val navigationBarBottom =
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp + navigationBarBottom),
            content = content,
        )
    } else {
        EtaScaffoldPage(title = title, onBack = onBack, content = content)
    }
}

@Composable
internal fun VoiceSettingsScreen(
    onBack: () -> Unit,
    initialSection: VoiceSettingsSection = VoiceSettingsSection.READ_ALOUD,
    onOpenVoiceSubPage: (VoiceSubPage) -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf(initialSection) }
    EtaScaffold(title = stringResource(R.string.voice_title), onBack = onBack) { padding, scrollConnection, sidePadding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = sidePadding).nestedScroll(scrollConnection)) {
            // 分区选择器：外观设置页主题模式同款的官方连体按钮组（ButtonGroupDefaults 连体形状），
            // 每个按钮取文字自然宽度，总宽超出屏幕时整组横向滚动，不做等分屏宽；点击即切、
            // 原地替换内容（key(selected) 重建分区内容），不经下拉弹层中转。
            val view = LocalView.current
            val sections = VoiceSettingsSection.entries
            val sectionScrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = EtaSegmentedGroupSpacing)
                    // 两端截断淡出带：整组横向滚动时，被视口切一半的按钮用 DstIn 蒙版擦掉。
                    .horizontalScrollEdgeFade(sectionScrollState)
                    .horizontalScroll(sectionScrollState)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                sections.forEachIndexed { index, section ->
                    EtaExpressiveToggleButton(
                        checked = selected == section,
                        onCheckedChange = { checked ->
                            if (checked) {
                                TouchHaptics.click(view)
                                selected = section
                            }
                        },
                        modifier = Modifier.semantics { role = Role.RadioButton },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            sections.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) {
                        Text(text = stringResource(section.title))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalEmbeddedVoiceSettings provides true) {
                    key(selected) {
                        when (selected) {
                            VoiceSettingsSection.READ_ALOUD -> TtsSettingsScreen(onBack)
                            VoiceSettingsSection.CONVERSATION -> TtsSettingsScreen(onBack, conversation = true)
                            VoiceSettingsSection.REALTIME -> VoiceModeSettingsScreen(onBack)
                            VoiceSettingsSection.PERSONAL -> PersonalVoicesScreen(
                                onBack = onBack,
                                onOpenSubPage = onOpenVoiceSubPage,
                            )
                        }
                    }
                }
            }
        }
    }
}
