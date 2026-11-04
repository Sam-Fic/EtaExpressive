package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.SubAgentPresetDetailScreen
import io.github.mangi.eta.ui.SubAgentSettingsScreen
import io.github.mangi.eta.ui.app.AgentAppTheme
import org.robolectric.RuntimeEnvironment
import java.util.UUID

internal class SubAgentUiFixture(
    profiles: List<SubAgentProfile> = listOf(
        SubAgentProfile("legacy-0", "执行代理 1"),
        SubAgentProfile("legacy-2", "执行代理 2"),
        SubAgentProfile("legacy-3", "执行代理 3"),
        SubAgentProfile("legacy-1", "审查／总结代理", role = "review"),
    ),
    enabled: Boolean = true,
    canEdit: () -> Boolean = { true },
    providers: List<ProviderSetting>? = null,
    providerLookup: (suspend (String) -> ProviderSetting?)? = null,
    preferenceTransform: (SharedPreferences) -> SharedPreferences = { it },
) {
    val repository = ConversationSubAgentPreferences(
        preferenceTransform(RuntimeEnvironment.getApplication().getSharedPreferences("sub-agent-ui-${UUID.randomUUID()}", Context.MODE_PRIVATE)))
    val owner = SubAgentConfigKey.Conversation("ui-${UUID.randomUUID()}")
    init {
        check(repository.update(owner) { ConversationSubAgentConfig(profiles = profiles, enabled = enabled) }
            is ConversationSubAgentPreferences.WriteResult.Saved)
    }
    val editor = if (providers == null && providerLookup == null) {
        ConversationSubAgentEditor(owner, repository, canEdit)
    } else {
        ConversationSubAgentEditor(owner, repository, { id ->
            if (providerLookup != null) providerLookup(id)
            else providers?.singleOrNull { it.id == id }
        }, canEdit)
    }
    fun snapshot(): ConversationSubAgentConfig = repository.snapshot(owner)
    /** Tests opt in to a catalog entry; the product's missing catalog stays empty. */
    fun createPreset(name: String = "测试子代理组"): io.github.mangi.eta.agent.delegation.SubAgentPreset {
        val preset = repository.addPreset(name)
        check(repository.update(SubAgentConfigKey.Preset(preset.id)) { snapshot().detached() }
            is ConversationSubAgentPreferences.WriteResult.Saved)
        return repository.presets().single { it.id == preset.id }
    }
}

internal fun ComposeContentTestRule.setSubAgentContent(
    fixture: SubAgentUiFixture = SubAgentUiFixture(),
    content: @Composable () -> Unit,
) {
    setContent {
        // 使用生产环境的官方 Material 3 主题（外观与主题驱动的配色），缩放取默认 1x。
        // Intentionally no Scaffold: window dialogs must work standalone and when opened
        // from the collaboration Dialog, not silently depend on an Activity's overlay host.
        AgentAppTheme(
            appearance = AppearanceSettings(accentColor = AppearanceAccentColor.BLUE),
            applyInterfaceScale = true,
        ) {
            CompositionLocalProvider(LocalConversationSubAgentEditor provides fixture.editor) {
                fixture.editor.observe()
                content()
            }
        }
    }
}

/**
 * 预设列表 → 详情的测试宿主，对应生产环境的两条独立路由。
 *
 * 生产用 miuix `NavDisplay` 完成入栈/出栈；测试不引入整套导航容器，但仍分别组合两个 screen，
 * 并由 Activity 的 BackHandler 模拟系统返回，保证「详情有自己的屏幕与生命周期」这一
 * 契约不会退回到列表页内部的状态切换。
 */
@Composable
internal fun SubAgentPresetRouteHost(
    fixture: SubAgentUiFixture,
    onExit: () -> Unit = {},
    isDetailCurrent: Boolean = true,
) {
    var presetId by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = presetId != null) { presetId = null }
    val openId = presetId
    if (openId == null) {
        SubAgentSettingsScreen(
            onBack = onExit,
            onOpenPreset = { presetId = it },
            repositoryFactory = { fixture.repository },
        )
    } else {
        SubAgentPresetDetailScreen(
            presetId = openId,
            onBack = { presetId = null },
            repositoryFactory = { fixture.repository },
            isCurrentRoute = isDetailCurrent,
        )
    }
}
