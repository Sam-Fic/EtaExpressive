package io.github.mangi.eta.ui

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import io.github.mangi.eta.agent.voice.DoubaoRealtimeVoices
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.agent.voice.VoiceModeController
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository

@Composable
internal fun VoiceModeSettingsScreen(onBack: () -> Unit) {
    val config by DoubaoVoiceConfig.state.collectAsState()
    var providerPicker by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var catalogStatus by remember { mutableStateOf(0) }
    var voices by remember { mutableStateOf(DoubaoRealtimeVoices.catalog) }
    val scope = rememberCoroutineScope()
    var providerId by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_PROVIDER_ID),
        )
    }
    var voice by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_VOICE).let {
                if (it.startsWith("etaClone") || it.startsWith("S_")) it else DoubaoRealtimeVoices.selectedId(it)
            },
        )
    }
    var instructions by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS)
                .ifBlank { VoiceModeController.DEFAULT_DUPLEX_INSTRUCTIONS },
        )
    }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val speechProviders = remember(providers) { providers.filter(SpeechSynthesisModels::isRealtimeVoiceProvider) }
    val provider = speechProviders.firstOrNull { it.id == providerId }

    val context = androidx.compose.ui.platform.LocalContext.current
    val personal by io.github.mangi.eta.agent.voice.doubao.PersonalVoices.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { DoubaoVoiceConfig.load(context); io.github.mangi.eta.agent.voice.doubao.PersonalVoices.load(context) }
    val availableVoices = voices + personal.filter { it.tts && it.accepted && it.account ==
        io.github.mangi.eta.agent.voice.doubao.PersonalVoices.account(provider?.apiKey.orEmpty()) }
        .map { io.github.mangi.eta.agent.voice.tts.SpeechVoice(it.id, it.name, personal = true) }

    VoiceSettingsSectionPage(title = stringResource(R.string.voice_section_realtime), onBack = onBack) {
        voiceGroup(key = "doubao") {
            item {
                EtaSwitchRow(
                    title = stringResource(R.string.voice_mode_doubao_enable),
                    checked = config.duplexEnabled,
                    onCheckedChange = { DoubaoVoiceConfig.save(context, config.copy(duplexEnabled = it)) },
                )
            }
            item {
                EtaClickableRow(
                    title = stringResource(R.string.voice_mode_doubao_provider),
                    summary = provider?.name ?: stringResource(R.string.voice_mode_doubao_provider_missing),
                    onClick = { providerPicker = true },
                )
            }
            item {
                EtaClickableRow(
                    title = stringResource(R.string.realtime_choose_voice),
                    summary = availableVoices.firstOrNull { it.id == voice }?.name
                    ?: stringResource(R.string.dv_not_selected),
                    onClick = { voicePicker = true },
                )
            }
            item {
                EtaClickableRow(
                    title = stringResource(if (refreshing) R.string.realtime_refreshing else R.string.realtime_refresh_voices),
                    summary = stringResource(when (catalogStatus) {
                            1 -> R.string.realtime_catalog_updated
                            2 -> R.string.realtime_catalog_failed
                            else -> R.string.realtime_catalog_builtin
                    }),
                    enabled = !refreshing,
                    onClick = {
                        if (!refreshing) scope.launch {
                            refreshing = true
                            try { voices = DoubaoRealtimeVoices.refresh(); catalogStatus = 1 }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { catalogStatus = 2 }
                            finally { refreshing = false }
                        }
                    },
                )
            }
            item {
                // 与相邻分段行同容器：无边框输入框嵌进行内，不再是一个描边盒子套在容器里。
                EtaSegmentedTextField(
                    label = stringResource(R.string.voice_mode_doubao_instructions),
                    value = instructions,
                    onValueChange = {
                        instructions = it
                        Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS, it)
                    },
                    minLines = 2,
                    maxLines = 5,
                )
            }
        }
    }

    TtsVoicePickerDialog(
        show = voicePicker,
        voices = availableVoices,
        selectedId = voice,
        title = stringResource(R.string.realtime_choose_voice),
        onDismiss = { voicePicker = false },
        onSelected = { value ->
            voice = value
            Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_VOICE, value)
            voicePicker = false
        },
    )
    SpeechRadioPickerDialog(
        show = providerPicker,
        title = stringResource(R.string.voice_mode_doubao_provider),
        rows = speechProviders.map { it.id to it.name },
        selectedId = providerId,
        emptyText = stringResource(R.string.voice_mode_doubao_provider_missing),
        onDismiss = { providerPicker = false },
        onSelected = { value ->
            providerId = value
            Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_PROVIDER_ID, value)
            providerPicker = false
        },
    )
}
