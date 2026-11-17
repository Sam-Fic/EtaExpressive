package io.github.mangi.eta.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mobileactions.MobileActionsAction
import io.github.mangi.eta.agent.mobileactions.MobileActionsEngine
import io.github.mangi.eta.agent.mobileactions.MobileActionsModelStore
import io.github.mangi.eta.agent.mobileactions.MobileActionsOutcome
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaDimens
import kotlinx.coroutines.launch

/**
 * 端侧设备动作试用页：输入一条指令，交给本地 FunctionGemma 选择动作，
 * 由 [io.github.mangi.eta.agent.mobileactions.MobileActionsDispatcher] 真实执行，
 * 结果与模型回复按发生顺序列在下面。
 *
 * 页面不保留会话历史给模型（每轮新建 Conversation）：该 270M 模型只做单轮函数调用，
 * 历史只会挤占 1024 上下文。
 */
@Composable
internal fun MobileActionsScreen(
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { MobileActionsModelStore.initialize(context) }
    val store by MobileActionsModelStore.state.collectAsState()
    val entries = remember { mutableStateListOf<Entry>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }

    fun submit() {
        val instruction = input.trim()
        // 记录提交瞬间的门禁值：功能「没反应」时先分清是按钮没启用还是流程内部失败。
        io.github.mangi.eta.core.AndroidAgentLogger.info(
            "mobile-actions submit: chars=${instruction.length} ready=${store.ready} running=$running"
        )
        if (instruction.isEmpty() || running) return
        input = ""
        entries += Entry.User(instruction)
        running = true
        preparing = false
        scope.launch {
            try {
                MobileActionsEngine.run(context, instruction).collect { event ->
                    when (event) {
                        MobileActionsEngine.Event.Preparing -> preparing = true
                        is MobileActionsEngine.Event.Action -> {
                            preparing = false
                            entries += Entry.Action(event.action, event.outcome)
                        }
                        is MobileActionsEngine.Event.Reply -> {
                            preparing = false
                            entries += Entry.Model(event.text)
                        }
                        is MobileActionsEngine.Event.Failure -> {
                            preparing = false
                            entries += Entry.Problem(event.message)
                        }
                    }
                }
            } finally {
                running = false
                preparing = false
            }
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.mobile_actions_screen_title),
        onBack = onBack,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EtaTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = stringResource(R.string.mobile_actions_input_hint),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    EtaTextButton(
                        text = stringResource(R.string.mobile_actions_run),
                        enabled = !running && input.isNotBlank() && store.ready,
                        onClick = { submit() },
                    )
                }
            }
        },
    ) {
        if (!store.ready) {
            item(key = "not-ready") {
                EtaText(
                    text = stringResource(R.string.mobile_actions_empty),
                    secondary = true,
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        top = 16.dp,
                        bottom = 12.dp,
                    ),
                )
            }
            item(key = "open-settings") {
                EtaClickableRow(
                    title = stringResource(R.string.mobile_actions_settings_title),
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                    ),
                )
            }
        } else {
            if (entries.isEmpty()) {
                item(key = "hint") {
                    EtaText(
                        text = stringResource(R.string.mobile_actions_try_hint),
                        secondary = true,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            top = 16.dp,
                        ),
                    )
                }
            }

            entries.forEachIndexed { index, entry ->
                item(key = "entry-$index") {
                    when (entry) {
                        is Entry.User -> EtaText(
                            text = entry.text,
                            style = EtaTextStyle.Title,
                            modifier = Modifier.padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                top = 16.dp,
                                bottom = 4.dp,
                            ),
                        )
                        is Entry.Model -> EtaText(
                            text = entry.text,
                            modifier = Modifier.padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                top = 4.dp,
                                bottom = 4.dp,
                            ),
                        )
                        is Entry.Action -> ActionRow(entry.action, entry.outcome)
                        is Entry.Problem -> EtaText(
                            text = entry.text,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                top = 4.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }
                }
            }

            if (running) {
                item(key = "running") {
                    EtaText(
                        text = stringResource(
                            if (preparing) R.string.mobile_actions_loading else R.string.mobile_actions_running,
                        ),
                        secondary = true,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            top = 8.dp,
                            bottom = 16.dp,
                        ),
                    )
                }
            }
        }
    }
}

/** 一条动作记录：图标 + 动作名 + 真实执行结果。 */
@Composable
private fun ActionRow(action: MobileActionsAction, outcome: MobileActionsOutcome) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            top = 6.dp,
            bottom = 6.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = actionIcon(action),
            contentDescription = null,
            modifier = Modifier.size(EtaDimens.DenseIconSize),
            tint = if (outcome.ok) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        EtaText(
            text = actionLabel(action),
            style = EtaTextStyle.Body,
            modifier = Modifier.weight(1f),
        )
        EtaText(
            // 成功用固定「已执行」，失败直接展示分发器给出的可读原因。
            text = if (outcome.ok) {
                stringResource(R.string.mobile_actions_result_ok)
            } else {
                outcome.message
            },
            style = EtaTextStyle.Footnote,
            color = if (outcome.ok) null else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun actionLabel(action: MobileActionsAction): String = stringResource(
    when (action) {
        MobileActionsAction.FlashlightOn -> R.string.mobile_actions_action_flashlight_on
        MobileActionsAction.FlashlightOff -> R.string.mobile_actions_action_flashlight_off
        is MobileActionsAction.CreateContact -> R.string.mobile_actions_action_contact
        is MobileActionsAction.SendEmail -> R.string.mobile_actions_action_email
        is MobileActionsAction.ShowMap -> R.string.mobile_actions_action_map
        MobileActionsAction.OpenWifiSettings -> R.string.mobile_actions_action_wifi
        is MobileActionsAction.CreateCalendarEvent -> R.string.mobile_actions_action_calendar
    },
)

private fun actionIcon(action: MobileActionsAction): ImageVector = when (action) {
    MobileActionsAction.FlashlightOn -> Icons.Rounded.FlashlightOn
    MobileActionsAction.FlashlightOff -> Icons.Rounded.FlashOff
    is MobileActionsAction.CreateContact -> Icons.Rounded.PersonAdd
    is MobileActionsAction.SendEmail -> Icons.Rounded.Email
    is MobileActionsAction.ShowMap -> Icons.Rounded.Map
    MobileActionsAction.OpenWifiSettings -> Icons.Rounded.Wifi
    is MobileActionsAction.CreateCalendarEvent -> Icons.Rounded.CalendarMonth
}

/** 页面内的对话条目；不参与持久化。 */
private sealed interface Entry {
    data class User(val text: String) : Entry
    data class Model(val text: String) : Entry
    data class Action(val action: MobileActionsAction, val outcome: MobileActionsOutcome) : Entry
    data class Problem(val text: String) : Entry
}
