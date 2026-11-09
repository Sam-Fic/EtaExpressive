package io.github.mangi.eta.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.tts.SpeechEngineResolver
import io.github.mangi.eta.agent.voice.tts.ReadAloudVoiceHistory
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackProfile
import io.github.mangi.eta.agent.voice.tts.SpeechVoice
import io.github.mangi.eta.agent.voice.tts.SpeechVoices
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.ReplyReadAloudPreference
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.model.AgentModelPickerProjector

@Composable
internal fun TtsSettingsScreen(onBack: () -> Unit, conversation: Boolean = false) {
    val profile = if (conversation) SpeechPlaybackProfile.CONVERSATION else SpeechPlaybackProfile.READ_ALOUD
    val previewOwner = if (conversation) "voice-conversation-preview" else "tts-preview"
    val context = LocalContext.current
    var cloud by remember(profile) { mutableStateOf(Prefs.getString(profile.modeKey) == "cloud") }
    var providerId by remember(profile) { mutableStateOf(Prefs.getString(profile.providerKey)) }
    var modelId by remember(profile) { mutableStateOf(Prefs.getString(profile.modelKey)) }
    var voice by remember(profile) { mutableStateOf(Prefs.getString(profile.voiceKey)) }
    var picker by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf(false) }
    // 自动朗读只属于「朗读」分区：语音对话（conversation）本身就在逐句朗读回复。
    var autoRead by remember(profile) { mutableStateOf(!conversation && ReplyReadAloudPreference.autoRead) }
    var streamSynth by remember(profile) { mutableStateOf(ReplyReadAloudPreference.streamSynthesis) }
    var backgroundContinue by remember(profile) { mutableStateOf(ReplyReadAloudPreference.continueInBackground) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val models = remember(providers, providerId, modelId) {
        AgentModelPickerProjector.project(providers.filter(SpeechSynthesisModels::isReadAloudProvider), providerId, modelId, includeSpeechModels = true, speechOnly = true)
    }
    val selectedProvider = remember(providers, providerId) { providers.firstOrNull { it.id == providerId } }
    val speechModelId = models.selectedModel?.modelId.orEmpty()
    val engine = remember(selectedProvider, speechModelId) { SpeechEngineResolver.resolve(selectedProvider, speechModelId) }
    val mimoVoices by io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.state.collectAsState()
    LaunchedEffect(Unit) { runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.load(context) } } }
    val personalVoices by io.github.mangi.eta.agent.voice.doubao.PersonalVoices.state.collectAsState()
    LaunchedEffect(Unit) { io.github.mangi.eta.agent.voice.doubao.PersonalVoices.load(context) }
    val catalog = remember(engine, speechModelId, personalVoices, mimoVoices, selectedProvider) {
        SpeechVoices.catalog(engine, speechModelId) + if (engine == io.github.mangi.eta.agent.voice.tts.SpeechEngine.DOUBAO && !io.github.mangi.eta.agent.voice.tts.DoubaoSpeech.usesCreate(speechModelId)) {
            personalVoices.filter { it.tts && it.accepted && it.account == io.github.mangi.eta.agent.voice.doubao.PersonalVoices.account(selectedProvider?.apiKey.orEmpty()) }
                .map { SpeechVoice(it.id, it.name, personal = true) }
        } else if (engine == io.github.mangi.eta.agent.voice.tts.SpeechEngine.MIMO) {
            mimoVoices.filter { it.providerId == selectedProvider?.id }.map { SpeechVoice(it.id, it.name, personal = true) }
        } else emptyList()
    }
    LaunchedEffect(profile) { ReadAloudVoiceHistory.rememberCurrent(context, profile) }
    val playback by SpeechPlayback.state.collectAsState()
    DisposableEffect(previewOwner) {
        onDispose { SpeechPlayback.stopOwned("preview_dispose") { it == previewOwner } }
    }
    val sample = stringResource(R.string.tts_sample)
    LaunchedEffect(cloud, providerId, selectedProvider?.id, engine, modelId, catalog, voice) {
        // A missing/expired personal voice must not silently become a public voice.
        if (voice.startsWith("etaClone") || voice.startsWith("S_") || voice.startsWith("mimo-local-")) return@LaunchedEffect
        if (!SpeechVoices.shouldReplaceStoredVoice(
                cloud = cloud,
                providerId = providerId,
                providerReady = selectedProvider != null,
                storedVoice = voice,
                catalogIds = catalog.map { it.id },
            )
        ) {
            return@LaunchedEffect
        }
        val fallback = catalog.first().id
        voice = fallback
        Prefs.putString(profile.voiceKey, fallback)
        ReadAloudVoiceHistory.remember(context, providerId, modelId, fallback, profile)
    }
    VoiceSettingsSectionPage(title = stringResource(if (conversation) R.string.voice_section_conversation else R.string.tts_title), onBack = onBack) {
        // 朗读/对话共用一套分段行：云开关、模型、音色、试听按序平铺，
        // 云相关行随开关以分段动画显隐（visible 注册），不再按条件拆多个嵌套组。
        if (conversation) item(key = "conversation_recognition") { VoiceConversationRecognitionSettings() }
        voiceGroup(key = "tts") {
            if (!conversation) item(key = "tts_auto_read") {
                EtaSwitchRow(
                    title = stringResource(R.string.tts_auto_read),
                    summary = stringResource(R.string.tts_auto_read_hint),
                    checked = autoRead,
                    onCheckedChange = {
                        autoRead = it
                        ReplyReadAloudPreference.setAutoRead(it)
                    },
                )
            }
            if (!conversation) item(key = "tts_stream_synth", visible = autoRead) {
                EtaSwitchRow(
                    title = stringResource(R.string.tts_stream_synth),
                    summary = stringResource(R.string.tts_stream_synth_hint),
                    checked = streamSynth,
                    enabled = autoRead,
                    onCheckedChange = {
                        streamSynth = it
                        ReplyReadAloudPreference.setStreamSynthesis(it)
                    },
                )
            }
            if (!conversation) item(key = "tts_auto_read_background", visible = autoRead) {
                EtaSwitchRow(
                    title = stringResource(R.string.tts_auto_read_background),
                    summary = stringResource(R.string.tts_auto_read_background_hint),
                    summaryOff = stringResource(R.string.tts_auto_read_background_hint_off),
                    checked = backgroundContinue,
                    enabled = autoRead,
                    onCheckedChange = {
                        backgroundContinue = it
                        ReplyReadAloudPreference.setContinueInBackground(it)
                    },
                )
            }
            item {
                EtaSwitchRow(
                    title = stringResource(R.string.tts_cloud),
                    summary = stringResource(R.string.tts_mode_hint),
                    summaryOff = stringResource(R.string.tts_mode_hint_off),
                    checked = cloud,
                    onCheckedChange = {
                        SpeechPlayback.stopOwned("settings") { it == previewOwner }
                        cloud = it
                        Prefs.putString(profile.modeKey, if (it) "cloud" else "system")
                    },
                )
            }
            item(visible = cloud) {
                EtaClickableRow(
                    title = stringResource(R.string.tts_model),
                    summary = models.selectedModel?.let { "${it.providerName} · ${it.displayName}" }
                    ?: stringResource(R.string.tts_select_model),
                    onClick = { picker = true },
                )
            }
            item(visible = cloud) {
                EtaClickableRow(
                    title = stringResource(R.string.tts_voice),
                    summary = catalog.firstOrNull { it.id == voice }?.name
                    ?: voice.ifBlank { stringResource(R.string.tts_select_voice) },
                    enabled = models.selectedModel != null && catalog.isNotEmpty(),
                    onClick = { voicePicker = true },
                )
            }
            item {
                EtaClickableRow(
                    title = stringResource(if (playback.owner == previewOwner) R.string.tts_stop else R.string.tts_preview),
                    summary = playback.error ?: playback.source.takeIf { playback.owner == previewOwner && it.isNotBlank() }
                    ?: if (playback.owner == previewOwner && playback.preparing) stringResource(R.string.tts_preparing) else null,
                    enabled = !playback.recording && (!cloud || (models.selectedModel != null && voice.isNotBlank())),
                    onClick = { SpeechPlayback.toggle(context, previewOwner, sample, settings = profile.snapshot()) },
                )
            }
        }
    }
    TtsModelPickerDialog(
        state = models, show = picker, onDismiss = { picker = false },
        title = stringResource(R.string.tts_model),
        onModelSelected = { provider, model ->
            SpeechPlayback.stopOwned("settings") { it == previewOwner }
            if (providerId != provider || modelId != model) {
                ReadAloudVoiceHistory.remember(context, providerId, modelId, voice, profile)
                voice = ReadAloudVoiceHistory.restore(context, provider, model, profile)
                Prefs.putString(profile.voiceKey, voice)
            }
            providerId = provider
            modelId = model
            Prefs.putString(profile.providerKey, provider)
            Prefs.putString(profile.modelKey, model)
            picker = false
        },
    )
    TtsVoicePickerDialog(
        show = voicePicker,
        voices = catalog,
        selectedId = voice,
        title = stringResource(R.string.tts_voice),
        onDismiss = { voicePicker = false },
        onSelected = { id ->
            SpeechPlayback.stopOwned("settings") { it == previewOwner }
            voice = id
            Prefs.putString(profile.voiceKey, id)
            ReadAloudVoiceHistory.remember(context, providerId, modelId, id, profile)
            voicePicker = false
        },
    )
}
