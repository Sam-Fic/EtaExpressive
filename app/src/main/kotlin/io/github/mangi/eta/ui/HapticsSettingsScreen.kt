package io.github.mangi.eta.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 「震动」页里的全部开关。
 *
 * 三层结构：总开关（触控反馈 / 消息生成触控反馈）→ 语义细分项 → 后台补充项。
 * 细分项只在所属总开关打开时生效，判定集中在 `TouchHaptics.is*Enabled()`，
 * 本页只负责读写这些布尔值。
 */
private val HapticSwitchKeys: List<String> = listOf(
    Prefs.Keys.HAPTIC_TOUCH_FEEDBACK,
    Prefs.Keys.HAPTIC_TAP,
    Prefs.Keys.HAPTIC_LONG_PRESS,
    Prefs.Keys.HAPTIC_KEYBOARD,
    Prefs.Keys.HAPTIC_MESSAGE_GENERATION,
    Prefs.Keys.HAPTIC_TYPING,
    Prefs.Keys.HAPTIC_TOOL_STEPS,
    Prefs.Keys.HAPTIC_REASONING_DONE,
    Prefs.Keys.HAPTIC_BACKGROUND,
    Prefs.Keys.HAPTIC_BACKGROUND_REASONING,
)

private fun hapticSwitchSnapshot(): Map<String, Boolean> =
    HapticSwitchKeys.associateWith { Prefs.isEnabled(it) }

@Composable
internal fun HapticsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember { Prefs.localAgentPreferences() }
    var flags by remember { mutableStateOf(hapticSwitchSnapshot()) }

    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            // listener 的 key 是 String?（平台 API 的可空签名），而 HapticSwitchKeys 与
            // BOOLEAN_DEFAULTS 都以 String 为键；先做非空判断再按 String 使用。
            if (key != null && key in HapticSwitchKeys) {
                flags = flags + (key to changed.getBoolean(
                    key,
                    Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key),
                ))
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun on(key: String): Boolean = flags[key] == true

    fun write(key: String, value: Boolean): Boolean {
        val target = prefs ?: return false
        return runCatching { target.edit().putBoolean(key, value).commit() }.getOrDefault(false)
    }

    fun onToggle(key: String, value: Boolean): Boolean {
        if (!write(key, value)) {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.settings_write_failed),
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        flags = flags + (key to value)
        // 开关行的震动由 EtaSwitchRow 自己发，调用方不再补。唯一的例外是「触控反馈」总开关
        // 从关到开：那一刻它还是关的，TouchHaptics.toggle 会被直接拦掉，用户按下去毫无手感，
        // 所以写完之后再补一下，让「已启用」这件事被摸到。
        if (value && key == Prefs.Keys.HAPTIC_TOUCH_FEEDBACK) {
            TouchHaptics.toggle(view, on = true)
        }
        return true
    }

    val touchOn = on(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK)
    val generationOn = touchOn && on(Prefs.Keys.HAPTIC_MESSAGE_GENERATION)
    val backgroundOn = generationOn && on(Prefs.Keys.HAPTIC_BACKGROUND)
    val groupPadding = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    EtaScaffoldPage(
        title = stringResource(R.string.haptics_title),
        onBack = onBack,
    ) {
        item(key = "haptics_touch_group") {
            // 与兄弟设置页一致：全部走自适应共享行组件（EtaSwitchRow）。总开关与它的细分项
            // 同组，总开关关掉时细分项置灰但仍然可见，用户能看全自己能配的东西。
            EtaSegmentedColumn(
                modifier = groupPadding,
                title = stringResource(R.string.haptics_group_interface),
            ) {
                item(key = "touch_feedback") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_touch_feedback),
                        summary = stringResource(R.string.haptics_touch_feedback_summary),
                        checked = touchOn,
                        onCheckedChange = { value -> onToggle(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, value) },
                    )
                }
                item(key = "haptic_tap") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_tap),
                        summary = stringResource(R.string.haptics_tap_summary),
                        checked = on(Prefs.Keys.HAPTIC_TAP),
                        enabled = touchOn,
                        onCheckedChange = { value -> onToggle(Prefs.Keys.HAPTIC_TAP, value) },
                    )
                }
                item(key = "haptic_long_press") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_long_press),
                        summary = stringResource(R.string.haptics_long_press_summary),
                        checked = on(Prefs.Keys.HAPTIC_LONG_PRESS),
                        enabled = touchOn,
                        onCheckedChange = { value -> onToggle(Prefs.Keys.HAPTIC_LONG_PRESS, value) },
                    )
                }
                item(key = "haptic_keyboard") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_keyboard),
                        summary = stringResource(R.string.haptics_keyboard_summary),
                        checked = on(Prefs.Keys.HAPTIC_KEYBOARD),
                        enabled = touchOn,
                        onCheckedChange = { value -> onToggle(Prefs.Keys.HAPTIC_KEYBOARD, value) },
                    )
                }
            }
        }
        item(key = "haptics_generation_group") {
            EtaSegmentedColumn(
                modifier = groupPadding,
                title = stringResource(R.string.haptics_group_generation),
            ) {
                item(key = "message_generation") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_message_generation),
                        summary = stringResource(R.string.haptics_message_generation_summary),
                        checked = generationOn,
                        enabled = touchOn,
                        onCheckedChange = { value ->
                            onToggle(Prefs.Keys.HAPTIC_MESSAGE_GENERATION, value)
                        },
                    )
                }
                item(key = "haptic_typing") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_typing),
                        summary = stringResource(R.string.haptics_typing_summary),
                        checked = on(Prefs.Keys.HAPTIC_TYPING),
                        enabled = generationOn,
                        onCheckedChange = { value -> onToggle(Prefs.Keys.HAPTIC_TYPING, value) },
                    )
                }
                item(key = "haptic_tool_steps") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_tool_steps),
                        summary = stringResource(R.string.haptics_tool_steps_summary),
                        checked = on(Prefs.Keys.HAPTIC_TOOL_STEPS),
                        enabled = generationOn,
                        onCheckedChange = { value -> onToggle(Prefs.Keys.HAPTIC_TOOL_STEPS, value) },
                    )
                }
                item(key = "haptic_reasoning_done") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_reasoning_done),
                        summary = stringResource(R.string.haptics_reasoning_done_summary),
                        checked = on(Prefs.Keys.HAPTIC_REASONING_DONE),
                        enabled = generationOn,
                        onCheckedChange = { value -> onToggle(Prefs.Keys.HAPTIC_REASONING_DONE, value) },
                    )
                }
            }
        }
        item(key = "haptics_background_group") {
            // 组内首行标题与分组同名（后台震动），再设组标题会连读两遍，这里不设组标题。
            EtaSegmentedColumn(modifier = groupPadding) {
                item(key = "haptics_background") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_background),
                        summary = stringResource(
                            when {
                                !backgroundOn -> R.string.haptics_background_summary_off
                                on(Prefs.Keys.HAPTIC_BACKGROUND_REASONING) ->
                                    R.string.haptics_background_summary_reasoning
                                else -> R.string.haptics_background_summary_tools
                            },
                        ),
                        checked = backgroundOn,
                        enabled = generationOn,
                        onCheckedChange = { value -> onToggle(Prefs.Keys.HAPTIC_BACKGROUND, value) },
                    )
                }
                item(key = "haptics_background_reasoning") {
                    EtaSwitchRow(
                        title = stringResource(R.string.haptics_background_reasoning),
                        summary = stringResource(R.string.haptics_background_reasoning_summary),
                        checked = on(Prefs.Keys.HAPTIC_BACKGROUND_REASONING),
                        enabled = backgroundOn,
                        onCheckedChange = { value ->
                            onToggle(Prefs.Keys.HAPTIC_BACKGROUND_REASONING, value)
                        },
                    )
                }
            }
        }
    }
}
