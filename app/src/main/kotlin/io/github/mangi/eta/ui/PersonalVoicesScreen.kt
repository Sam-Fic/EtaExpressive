package io.github.mangi.eta.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices
import io.github.mangi.eta.agent.voice.tts.ReadAloudVoiceHistory
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaMenuEntry
import io.github.mangi.eta.ui.adaptive.EtaTextMenu
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PersonalVoicesScreen(
    onBack: () -> Unit,
    onOpenSubPage: (VoiceSubPage) -> Unit = {},
) {
    val view = LocalView.current
    // 分区根不再拦截返回：让 NavHost 统一处理，保留预测性返回预览。
    VoiceSettingsSectionPage(title = stringResource(R.string.voice_section_personal), onBack = onBack) {
        voiceGroup(key = "personal") {
            item {
                EtaClickableRow(title = stringResource(R.string.pv_doubao_voice),
                    onClick = { onOpenSubPage(VoiceSubPage.DOUBAO_VOICES) })
            }
            item {
                EtaClickableRow(title = stringResource(R.string.pv_mimo_voice),
                    onClick = { onOpenSubPage(VoiceSubPage.MIMO_VOICES) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MimoVoicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val available = providers.filter(MimoPersonalVoices::supports)
    var selectedProvider by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID)) }
    val provider = available.firstOrNull { it.id == selectedProvider } ?: available.singleOrNull()
    var providerMenu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<MimoPersonalVoices.Voice?>(null) }
    var sample by remember { mutableStateOf(context.getString(R.string.pv_sample_default)) }
    val voices by MimoPersonalVoices.state.collectAsState()
    val playback by SpeechPlayback.state.collectAsState()
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { MimoPersonalVoices.load(context) } }
            .onSuccess { ready = true }.onFailure { notice = context.getString(R.string.pv_list_load_failed) }
    }
    DisposableEffect(Unit) { onDispose { if (SpeechPlayback.state.value.owner?.startsWith("mimo-preview:") == true) SpeechPlayback.stop("preview_dispose") } }
    // 仅在上传/删除期间吞掉返回；空闲时交给 NavHost，保留路由级预测性返回。
    BackHandler(enabled = busy) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result -> uri = result }

    fun importVoice() {
        val source = uri ?: return
        val target = provider ?: return
        val title = name
        busy = true; notice = ""
        scope.launch {
            try {
                MimoPersonalVoices.import(context, source, title, target.id)
                name = ""; uri = null; notice = context.getString(R.string.pv_saved_toast)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notice = if (e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty() else context.getString(R.string.pv_import_failed)
            } finally { busy = false }
        }
    }

    // 与兄弟语音页同一套分段列表：说明文字排在页面背景上，输入 / 动作收进分段组。
    VoiceSettingsSectionPage(title = stringResource(R.string.pv_mimo_voice), onBack = { TouchHaptics.click(view); onBack() }) {
        item(key = "mimo-hint") {
            VoiceNote(stringResource(R.string.pv_save_hint))
        }
        if (available.isEmpty()) {
            item(key = "mimo-no-provider") {
                VoiceNote(stringResource(R.string.pv_need_provider))
            }
        }
        voiceGroup(key = "mimo-form") {
            item {
                Box {
                    EtaClickableRow(
                        title = provider?.name ?: stringResource(R.string.pv_select_provider),
                        enabled = !busy && available.isNotEmpty(),
                        onClick = { providerMenu = true },
                    )
                    EtaTextMenu(
                        expanded = providerMenu,
                        onDismissRequest = { providerMenu = false },
                        entries = available.map { item ->
                            EtaMenuEntry(text = item.name, icon = Icons.Rounded.Cloud) {
                                TouchHaptics.click(view); selectedProvider = item.id; providerMenu = false
                            }
                        },
                    )
                }
            }
            item {
                EtaSegmentedTextField(
                    label = stringResource(R.string.dv_voice_name_label),
                    value = name,
                    onValueChange = { name = it.take(80) },
                    enabled = !busy,
                    singleLine = true,
                )
            }
            item {
                EtaClickableRow(
                    title = stringResource(if (uri == null) R.string.pv_select_audio else R.string.pv_reselect_audio),
                    enabled = !busy,
                    onClick = { picker.launch(arrayOf("audio/mpeg", "audio/wav", "audio/x-wav")) },
                )
            }
            item {
                EtaClickableRow(
                    title = stringResource(R.string.pv_save_reference),
                    summary = if (busy) stringResource(R.string.pv_saving) else null,
                    enabled = ready && !busy && uri != null && name.isNotBlank() && provider != null,
                    onClick = { importVoice() },
                )
            }
        }
        item(key = "mimo-import-note") {
            VoiceNote(stringResource(R.string.pv_import_note))
        }
        voiceGroup(key = "mimo-sample") {
            item {
                EtaSegmentedTextField(
                    label = stringResource(R.string.pv_sample_hint),
                    value = sample,
                    onValueChange = { sample = it.take(300) },
                    singleLine = false,
                    minLines = 2,
                )
            }
        }
        val message = notice.ifBlank { playback.error.orEmpty() }
        if (message.isNotBlank()) {
            item(key = "mimo-notice") {
                VoiceNote(message)
            }
        }
        if (voices.isNotEmpty()) {
            voiceGroup(key = "mimo-voices") {
                voices.forEach { voice ->
                    val bound = available.firstOrNull { it.id == voice.providerId }
                    val owner = "mimo-preview:${voice.id}"
                    val playing = playback.owner == owner
                    item(key = voice.id) {
                        EtaClickableRow(
                            title = voice.name,
                            summary = buildString {
                                append(
                                    context.getString(
                                        R.string.pv_voice_meta,
                                        bound?.name ?: context.getString(R.string.pv_provider_unavailable),
                                        voice.durationMs / 1000.0,
                                    ),
                                )
                                if (playing) {
                                    append(" · ")
                                    append(
                                        context.getString(
                                            if (playback.preparing) R.string.pv_generating_preview
                                            else R.string.dv_previewing,
                                        ),
                                    )
                                }
                            },
                            onClick = null,
                            showChevron = false,
                            leading = { Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null) },
                            bottomAction = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        enabled = playing || (bound != null && sample.isNotBlank() && !playback.recording),
                                        onClick = { TouchHaptics.click(view); SpeechPlayback.previewMimo(context, voice, sample) },
                                    ) { Text(if (playing) stringResource(R.string.action_stop) else stringResource(R.string.dv_preview)) }
                                    TextButton(
                                        enabled = bound != null,
                                        onClick = {
                                            TouchHaptics.click(view)
                                            val model = bound?.let { SpeechSynthesisModels.mergeCatalog(it) }?.firstOrNull { it.modelId == "mimo-v2.5-tts" && it.isEnabled }
                                            if (model == null) notice = context.getString(R.string.pv_enable_model_first) else {
                                                SpeechPlayback.stop("settings")
                                                ReadAloudVoiceHistory.rememberCurrent(context)
                                                Prefs.putString(Prefs.Keys.AGENT_TTS_MODE, "cloud")
                                                Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID, voice.providerId)
                                                Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_ID, model.id)
                                                Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, voice.id)
                                                ReadAloudVoiceHistory.remember(context, voice.providerId, model.id, voice.id)
                                                notice = context.getString(R.string.pv_set_as_read_aloud, voice.name)
                                            }
                                        },
                                    ) { Text(stringResource(R.string.pv_use_for_read_aloud)) }
                                    TextButton(
                                        enabled = !busy,
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                            disabledContentColor =
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                                        ),
                                        onClick = { TouchHaptics.click(view); deleting = voice },
                                    ) { Text(stringResource(R.string.action_delete)) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    deleting?.let { voice ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.pv_delete_title, voice.name)) },
            text = { Text(stringResource(R.string.pv_delete_note)) },
            confirmButton = { TextButton(onClick = {
                TouchHaptics.click(view); SpeechPlayback.stop("settings"); deleting = null; busy = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { MimoPersonalVoices.remove(voice.id) }; notice = context.getString(R.string.pv_deleted) }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = context.getString(R.string.pv_delete_failed) }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { TouchHaptics.click(view); deleting = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}

/** 语音子页的纯说明文字：与分组容器同一套 16dp 边距 + 13dp 间距。 */
@Composable
private fun VoiceNote(message: String) {
    EtaText(
        text = message,
        style = EtaTextStyle.Secondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
    )
}
