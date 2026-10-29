package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.doubao.PersonalVoicePreview
import io.github.mangi.eta.agent.voice.doubao.PersonalVoices
import io.github.mangi.eta.agent.voice.doubao.VoiceCatalogPreferences
import io.github.mangi.eta.agent.voice.doubao.VoiceCatalogSecretStore
import io.github.mangi.eta.agent.voice.doubao.VoiceProjectCatalog
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaPickerSheet
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.etaPrimary
import io.github.mangi.eta.ui.adaptive.EtaMenuEntry
import io.github.mangi.eta.ui.adaptive.EtaTextMenu
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DoubaoVoiceSettings(onBack: () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteVoice by remember { mutableStateOf<PersonalVoices.Voice?>(null) }
    var showSync by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf<android.net.Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var showAccount by remember { mutableStateOf(false) }
    var settingsMenu by remember { mutableStateOf(false) }
    val back: () -> Unit = {
        when (voiceSettingsBackTarget(showSync, showAccount, mode, step)) {
            VoiceSettingsBackTarget.SYNC_PARENT -> showSync = false
            VoiceSettingsBackTarget.ACCOUNT_PARENT -> showAccount = false
            VoiceSettingsBackTarget.PREVIOUS_STEP -> step--
            VoiceSettingsBackTarget.HOME -> mode = null
            VoiceSettingsBackTarget.EXIT -> onBack()
        }
    }
    val config by DoubaoVoiceConfig.state.collectAsState()
    val voices by PersonalVoices.state.collectAsState()
    var cloneKey by remember { mutableStateOf("") }
    var postpaid by remember { mutableStateOf(false) }
    var slotId by remember { mutableStateOf("") }
    var importBusy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val home = mode == null && !showAccount && !showSync
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // 内部页快照驱动 AnimatedContent：退场页只读快照，不会跟着外部状态提前变成下一页。
    val page = DoubaoVoicePage(mode = mode, step = step, account = showAccount, sync = showSync)
    // 页间侧滑与 NavHost 同一套 M3E 规格：位移走 Spatial（轻微过冲），淡化走 Effects。
    // 规格须在组合期读好，transitionSpec 里不能读 CompositionLocal。
    val pageSlideSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val pageFadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // 向导内层/忙时自己消费返回；回到本页首页后交给 NavHost，保留路由级预测性返回。
    androidx.activity.compose.BackHandler(enabled = busy || importBusy || !home) {
        if (!busy && !importBusy && !home) back()
    }
    val preview = remember { PersonalVoicePreview() }
    val previewState by preview.state.collectAsState()
    LaunchedEffect(previewState.error) {
        previewState.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) {
        DoubaoVoiceConfig.load(context); PersonalVoices.load(context)
        cloneKey = DoubaoVoiceConfig.state.value.cloneKey
    }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audio = uri; fileName = context.getString(R.string.dv_recording_selected); consent = false
            scope.launch {
                val display = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                }
                if (audio == uri) fileName = display ?: context.getString(R.string.dv_recording_selected)
            }
        }
    }
    deleteVoice?.let { voice ->
        AlertDialog(onDismissRequest = { deleteVoice = null }, title = { Text(stringResource(R.string.dv_remove_title, voice.name)) },
            text = { Text(stringResource(R.string.dv_delete_local_note)) },
            confirmButton = { TextButton(onClick = {
                TouchHaptics.click(view)
                try { PersonalVoices.removeLocal(voice); if (previewState.matches(voice.account, voice.id)) preview.stop(5); notice = context.getString(R.string.dv_local_removed) }
                catch (_: Exception) { notice = context.getString(R.string.dv_local_remove_failed) }
                deleteVoice = null
            }) { Text(stringResource(R.string.dv_remove_record), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { TouchHaptics.click(view); deleteVoice = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    fun beginMode(next: String) {
        mode = next; name = ""; step = 0; slotId = ""; manualId = false
        showSync = false; postpaid = false; advanced = false; audio = null
        fileName = ""; consent = false; notice = ""
    }
    LaunchedEffect(home) { if (!home) preview.stop() }
    // 列表铺到屏幕底缘，导航栏高度只补在滚动内容末尾（与抽屉/兄弟页同口径）。
    val navigationBarBottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    EtaScaffold(
        title = when {
            showSync -> stringResource(R.string.dv_sync_title)
            showAccount -> stringResource(R.string.dv_account_title)
            mode == "create" -> stringResource(R.string.dv_mode_create)
            mode == "import" -> stringResource(R.string.dv_mode_import)
            else -> stringResource(R.string.dv_my_voices)
        },
        onBack = { TouchHaptics.click(view); back() },
        actions = {
            if (home) Box {
                IconButton(onClick = { TouchHaptics.click(view); settingsMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.dv_settings_cd)) }
                EtaTextMenu(
                    expanded = settingsMenu,
                    onDismissRequest = { settingsMenu = false },
                    entries = listOf(
                        EtaMenuEntry(
                            text = stringResource(R.string.dv_account_title),
                            icon = Icons.Rounded.AccountCircle,
                        ) {
                            TouchHaptics.click(view); settingsMenu = false; showAccount = true; notice = ""
                        },
                        EtaMenuEntry(
                            text = stringResource(R.string.dv_sync_title),
                            icon = Icons.Rounded.Sync,
                            enabled = config.cloneKey.isNotBlank(),
                        ) {
                            TouchHaptics.click(view); settingsMenu = false; showSync = true; notice = ""
                        },
                    ),
                )
            }
        },
    ) { padding, scrollConnection, sidePadding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState.depth >= initialState.depth
                // 与 NavHost 同一套几何：新页从侧边推入，旧页做四分之一屏视差并淡到 0.9。
                val sign = if (forward) 1f else -1f
                val enterSign = if (rtl) -sign else sign
                val enter = slideInHorizontally(
                    animationSpec = pageSlideSpec,
                ) { width -> (width * enterSign).toInt() } + fadeIn(
                    animationSpec = pageFadeSpec,
                    initialAlpha = 0.9f,
                )
                val exit = slideOutHorizontally(
                    animationSpec = pageSlideSpec,
                ) { width -> (-width * enterSign / 4f).toInt() } + fadeOut(
                    animationSpec = pageFadeSpec,
                    targetAlpha = 0.9f,
                )
                enter togetherWith exit
            },
            label = "doubao-voice-page",
        ) { current ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .padding(horizontal = sidePadding).nestedScroll(scrollConnection).imePadding(),
                contentPadding = PaddingValues(bottom = 24.dp + navigationBarBottom),
            ) {
                item {
                    // 与兄弟语音页同一套分段列表：说明 / 提示排在页面背景上，输入与动作收进分段组。
                    Column(Modifier.fillMaxWidth()) {
                        if (current.account) {
                            VoiceGroup {
                                item {
                                    VoiceTextField(cloneKey, { cloneKey = it }, label = stringResource(R.string.dv_api_key_label),
                                        visualTransformation = PasswordVisualTransformation())
                                }
                                item {
                                    EtaClickableRow(title = stringResource(R.string.dv_save_account), showChevron = false, enabled = cloneKey.isNotBlank(),
                                        onClick = {
                                            DoubaoVoiceConfig.save(context, config.copy(cloneKey = cloneKey)); showAccount = false
                                        })
                                }
                            }
                            VoiceGroup {
                                item {
                                    EtaClickableRow(title = stringResource(R.string.dv_open_console), showChevron = false,
                                        onClick = { openVoiceConsole(context) })
                                }
                            }
                            VoiceNote(stringResource(R.string.dv_key_help))
                        } else if (current.sync) {
                            VoiceSlotSync(config.cloneKey, onBusy = { importBusy = it }, onResult = { notice = it })
                        } else {
                            if (current.mode == "create") {
                                VoiceGroup {
                                    item {
                                        EtaSegmentedItemContainer {
                                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                                EtaText(
                                                    text = "${current.step + 1} / 3 · " + listOf(stringResource(R.string.dv_step_slot), stringResource(R.string.dv_step_recording), stringResource(R.string.dv_step_confirm))[current.step],
                                                    color = etaPrimary(),
                                                )
                                                LinearProgressIndicator(progress = { (current.step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            if (current.mode != null && current.step == 0) {
                                VoiceNote(if (current.mode == "import") stringResource(R.string.dv_import_hint) else stringResource(R.string.dv_slot_hint))
                                VoiceGroup {
                                    item {
                                        VoiceTextField(name, { name = it.take(80) }, enabled = !busy,
                                            label = if (current.mode == "import") stringResource(R.string.dv_note_name_label) else stringResource(R.string.dv_voice_name_label),
                                            singleLine = true)
                                    }
                                }
                                if (current.mode == "create") {
                                    VoiceGroup {
                                        item {
                                            EtaClickableRow(title = stringResource(R.string.dv_advanced_postpaid), showChevron = false,
                                                onClick = { advanced = !advanced })
                                        }
                                    }
                                    if (advanced || postpaid) {
                                        VoiceGroup {
                                            item {
                                                VoiceRadioRow(
                                                    title = stringResource(R.string.dv_opt_existing_slot),
                                                    summary = null,
                                                    selected = !postpaid,
                                                    enabled = true,
                                                    onClick = { postpaid = false; consent = false },
                                                )
                                            }
                                            item {
                                                VoiceRadioRow(
                                                    title = stringResource(R.string.dv_opt_new_postpaid),
                                                    summary = null,
                                                    selected = postpaid,
                                                    enabled = true,
                                                    onClick = { postpaid = true; consent = false },
                                                )
                                            }
                                        }
                                    }
                                }
                                if (!postpaid || current.mode == "import") {
                                    val slots = voices.filter { it.account == PersonalVoices.account(config.cloneKey) && it.id.startsWith("S_") && (current.mode == "create" || it.showInMyVoices) }
                                        .sortedWith(compareByDescending<PersonalVoices.Voice> { it.unused }.thenBy { it.name })
                                    EtaSegmentedColumn(
                                        modifier = Modifier.padding(VoiceGroupInsets),
                                        title = if (current.mode == "create") stringResource(R.string.dv_pick_slot) else stringResource(R.string.dv_pick_voice),
                                    ) {
                                        if (slots.isEmpty()) {
                                            item {
                                                EtaSegmentedItemContainer {
                                                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                                        EtaText(stringResource(R.string.dv_no_slots))
                                                        EtaText(stringResource(R.string.dv_no_slots_hint), style = EtaTextStyle.Footnote, modifier = Modifier.padding(top = 4.dp))
                                                    }
                                                }
                                            }
                                        } else {
                                            slots.forEachIndexed { index, slot ->
                                                item(key = slot.id) {
                                                    val slotStatus = voiceSlotStatus(slot)
                                                    val slotQuota = voiceSlotQuota(slot)
                                                    VoiceRadioRow(
                                                        title = if (slot.name == slot.id) stringResource(R.string.dv_slot_index, index + 1) else slot.name,
                                                        summary = "$slotStatus · $slotQuota",
                                                        selected = slotId == slot.id,
                                                        enabled = !busy && !importBusy && (current.mode == "import" || slot.canTrain),
                                                        onClick = { slotId = slot.id; consent = false; manualId = false },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    VoiceGroup {
                                        item {
                                            EtaClickableRow(title = stringResource(R.string.dv_find_slots), showChevron = false, enabled = !busy && !importBusy,
                                                onClick = { showSync = true; notice = "" })
                                        }
                                        item {
                                            EtaClickableRow(title = if (manualId) stringResource(R.string.dv_collapse_manual) else stringResource(R.string.dv_manual_alternative),
                                                showChevron = false, enabled = !busy,
                                                onClick = { manualId = !manualId; slotId = ""; consent = false })
                                        }
                                    }
                                    if (manualId) {
                                        VoiceNote(stringResource(R.string.dv_console_hint_slots))
                                        VoiceGroup {
                                            item {
                                                EtaClickableRow(title = stringResource(R.string.dv_open_console), showChevron = false,
                                                    onClick = { openVoiceConsole(context) })
                                            }
                                            item {
                                                VoiceTextField(slotId, { slotId = it.trim(); consent = false }, enabled = !busy,
                                                    label = stringResource(R.string.dv_paste_voice_id), singleLine = true)
                                            }
                                        }
                                    }
                                    if (slotId.isNotBlank()) VoiceNote(if (current.mode == "import") stringResource(R.string.dv_selected_voice_hint) else stringResource(R.string.dv_selected_slot_hint))
                                } else {
                                    VoiceNote(stringResource(R.string.dv_postpaid_note))
                                }
                                if (current.mode == "import") {
                                    VoiceGroup {
                                        item {
                                            EtaClickableRow(title = if (busy) stringResource(R.string.dv_querying) else stringResource(R.string.dv_mode_import),
                                                showChevron = false, enabled = !busy && !importBusy && slotId.matches(Regex("S_[A-Za-z0-9_-]+")),
                                                onClick = {
                                                    busy = true
                                                    scope.launch {
                                                        try { PersonalVoices.importExisting(config.cloneKey, slotId, name); mode = null; notice = context.getString(R.string.dv_import_success) }
                                                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = e.message ?: context.getString(R.string.dv_import_failed) }
                                                        finally { busy = false }
                                                    }
                                                })
                                        }
                                    }
                                } else {
                                    VoiceGroup {
                                        item {
                                            EtaClickableRow(title = stringResource(R.string.dv_next_recording), showChevron = false,
                                                enabled = !importBusy && name.isNotBlank() && (postpaid || (slotId.matches(Regex("S_[A-Za-z0-9_-]+")) && (PersonalVoices.find(slotId, config.cloneKey)?.canTrain != false))),
                                                onClick = { step = 1; notice = "" })
                                        }
                                    }
                                }
                                if (slotId.isBlank() && !postpaid) VoiceNote(stringResource(R.string.dv_pick_slot_first))
                            }
                            if (current.mode == "create" && current.step == 1) {
                                VoiceNote(stringResource(R.string.dv_recording_tips))
                                VoiceGroup {
                                    item {
                                        EtaClickableRow(title = if (audio == null) stringResource(R.string.dv_choose_recording) else stringResource(R.string.dv_choose_recording_again),
                                            showChevron = false, onClick = { picker.launch(arrayOf("audio/*")) })
                                    }
                                }
                                if (fileName.isNotBlank()) VoiceNote(fileName)
                                VoiceNote(stringResource(R.string.dv_file_not_uploaded))
                                VoiceGroup {
                                    item {
                                        EtaClickableRow(title = stringResource(R.string.dv_next_confirm), showChevron = false, enabled = audio != null,
                                            onClick = { step = 2; consent = false })
                                    }
                                }
                            }
                            if (current.mode == "create" && current.step == 2) {
                                VoiceNote(stringResource(R.string.dv_name_prefix, name))
                                VoiceNote(if (postpaid) stringResource(R.string.dv_opt_new_postpaid) else stringResource(R.string.dv_using_voice, PersonalVoices.find(slotId, config.cloneKey)?.name ?: slotId))
                                VoiceNote(if (postpaid) stringResource(R.string.dv_postpaid_fee_note) else stringResource(R.string.dv_training_note))
                                VoiceGroup {
                                    item {
                                        EtaSwitchRow(title = stringResource(R.string.dv_consent), checked = consent, enabled = !busy,
                                            onCheckedChange = { consent = it })
                                    }
                                }
                                VoiceGroup {
                                    item {
                                        EtaClickableRow(title = if (busy) stringResource(R.string.dv_submitting) else stringResource(R.string.dv_upload_create),
                                            showChevron = false, enabled = consent && !busy,
                                            onClick = {
                                                busy = true
                                                val trainingKey = config.cloneKey
                                                scope.launch {
                                                    try { PersonalVoices.create(context.applicationContext, requireNotNull(audio), name, trainingKey, if (postpaid) null else slotId.trim()); mode = null; step = 0; notice = context.getString(R.string.dv_submit_success) }
                                                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = e.message ?: context.getString(R.string.dv_submit_failed) }
                                                    finally { busy = false }
                                                }
                                            })
                                    }
                                }
                            }
                            if (current.mode == null) {
                                VoiceGroup {
                                    item {
                                        EtaClickableRow(title = stringResource(R.string.dv_entry_create), showChevron = false,
                                            onClick = { if (config.cloneKey.isBlank()) showAccount = true else beginMode("create") })
                                    }
                                    item {
                                        EtaClickableRow(title = stringResource(R.string.dv_entry_import), showChevron = false,
                                            onClick = { if (config.cloneKey.isBlank()) showAccount = true else beginMode("import") })
                                    }
                                }
                                val myVoices = voices.filter { it.account == PersonalVoices.account(config.cloneKey) && it.showInMyVoices }
                                if (myVoices.isEmpty()) {
                                    val emptyAction: (@Composable () -> Unit)? = if (config.cloneKey.isBlank()) {
                                        {
                                            EtaTextButton(text = stringResource(R.string.dv_config_account), onClick = { TouchHaptics.click(view); showAccount = true })
                                        }
                                    } else {
                                        null
                                    }
                                    ListEmptyState(
                                        title = stringResource(R.string.dv_empty_title),
                                        summary = if (config.cloneKey.isBlank()) stringResource(R.string.dv_empty_no_account) else stringResource(R.string.dv_empty_has_account),
                                        action = emptyAction,
                                    )
                                } else {
                                    VoiceGroup {
                                        myVoices.forEach { voice ->
                                            item(key = voice.id) {
                                                PersonalVoiceRow(
                                                    voice = voice,
                                                    enabled = !importBusy,
                                                    previewActive = previewState.matches(voice.account, voice.id),
                                                    previewLoading = previewState.matches(voice.account, voice.id) && previewState.loading,
                                                    onRefresh = { PersonalVoices.refresh(voice, config.cloneKey) },
                                                    onDelete = { deleteVoice = voice },
                                                    onPreview = { preview.toggle(voice.account, voice.id, voice.demo) },
                                                )
                                            }
                                            if (voice.tts && !voice.accepted) {
                                                item(key = voice.id + "-accept") {
                                                    EtaClickableRow(title = stringResource(R.string.dv_accept_voice), showChevron = false,
                                                        onClick = { PersonalVoices.accept(voice) })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (notice.isNotBlank()) VoiceNote(notice)
                    }
                }
            }
        }
    }
}

/**
 * 豆包音色页的内部页面快照。AnimatedContent 必须由目标状态驱动内容，
 * 否则转场期间退场页会读到已经变化的外部状态，提前显示成新页。
 */
private data class DoubaoVoicePage(
    val mode: String?,
    val step: Int,
    val account: Boolean,
    val sync: Boolean,
) {
    /** 页面层级：用于判断转场方向（前进从侧边推入，返回从侧边回到）。 */
    val depth: Int get() = when {
        sync -> 2
        account -> 1
        mode == "create" -> 1 + step
        mode == "import" -> 1
        else -> 0
    }
}

/** 打开火山引擎语音控制台。 */
private fun openVoiceConsole(context: Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.volcengine.com/speech/new/overview?projectName=default")))
}

/** 语音子页的纯说明文字：与分组容器同一套 16dp 边距 + 13dp 间距。 */
@Composable
private fun VoiceNote(message: String, isError: Boolean = false) {
    EtaText(
        text = message,
        style = EtaTextStyle.Secondary,
        color = if (isError) MaterialTheme.colorScheme.error else null,
        modifier = Modifier.padding(VoiceGroupInsets),
    )
}

/**
 * 分段组内的文本输入行（KernelSU `SegmentedTextField` 同款）：无边框输入框嵌进分段行，
 * 与相邻行共用同一容器与圆角，不再是一个孤零零的描边框。
 */
@Composable
private fun VoiceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    EtaSegmentedTextField(label = label, value = value, onValueChange = onValueChange,
        enabled = enabled, singleLine = singleLine, visualTransformation = visualTransformation)
}

/**
 * 自适应单选行：走 [EtaSegmentedRadioRow]（分段单选行）。
 */
@Composable
private fun VoiceRadioRow(
    title: String,
    summary: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    EtaSegmentedRadioRow(title = title, summary = summary, selected = selected, enabled = enabled, onClick = onClick)
}

/** 语音槽位状态文案。 */
@Composable
private fun voiceSlotStatus(slot: PersonalVoices.Voice): String = when {
    slot.status == 1 || slot.catalogState == "Training" -> stringResource(R.string.dv_slot_training)
    slot.status == 4 || slot.catalogState == "Active" -> stringResource(R.string.dv_slot_locked)
    slot.remaining == 0 -> stringResource(R.string.dv_slot_exhausted)
    slot.unused -> stringResource(R.string.dv_slot_unused)
    slot.ready -> stringResource(R.string.dv_slot_ready)
    else -> stringResource(R.string.dv_slot_unknown)
}

/** 语音槽位额度文案。 */
@Composable
private fun voiceSlotQuota(slot: PersonalVoices.Voice): String =
    if (slot.remaining >= 0) stringResource(R.string.dv_slot_remaining, slot.remaining) else stringResource(R.string.dv_slot_quota_unknown)

@Composable
private fun VoiceSlotSync(apiKey: String, onBusy: (Boolean) -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var ak by remember { mutableStateOf(VoiceCatalogPreferences.keyId(context)) }
    val secretStore = remember(context) { VoiceCatalogSecretStore(context) }
    var sk by remember { mutableStateOf(secretStore.read(ak).orEmpty()) }
    var storageError by remember { mutableStateOf("") }
    var project by remember { mutableStateOf(VoiceCatalogPreferences.project(context)) }
    var resultText by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var projectOptions by remember { mutableStateOf(false) }
    var projectPicker by remember { mutableStateOf(false) }
    var projectQuery by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<VoiceProjectCatalog.Project>>(emptyList()) }
    var projectsLoaded by remember { mutableStateOf(false) }
    var projectLoading by remember { mutableStateOf(false) }
    var projectError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    val working = busy || projectLoading

    VoiceNote(stringResource(R.string.dv_sync_intro))
    VoiceGroup {
        item {
            EtaClickableRow(title = stringResource(R.string.dv_open_keymanage), showChevron = false,
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.volcengine.com/iam/keymanage/"))) })
        }
    }
    VoiceNote(stringResource(R.string.dv_keymanage_hint))
    VoiceGroup {
        item {
            VoiceTextField(ak, {
                if (it.trim() != ak.trim()) {
                    runCatching { secretStore.clear() }.onFailure { storageError = context.getString(R.string.dv_old_sk_clear_failed) }
                    sk = ""
                    projects = emptyList(); projectsLoaded = false; projectError = ""
                }
                ak = it; VoiceCatalogPreferences.save(context, it, project)
            }, label = stringResource(R.string.dv_access_key_label), enabled = !working,
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
        item {
            VoiceTextField(sk, {
                if (sk != it) { projects = emptyList(); projectsLoaded = false; projectError = "" }
                sk = it
                storageError = runCatching { secretStore.save(ak, it) }.fold({ "" }, { context.getString(R.string.dv_sk_save_failed) })
            }, label = stringResource(R.string.dv_secret_key_label), enabled = !working,
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }
    VoiceGroup {
        item {
            EtaClickableRow(
                title = stringResource(R.string.dv_current_project, project.ifBlank { stringResource(R.string.dv_not_selected) }),
                showChevron = false,
            )
        }
        item {
            EtaClickableRow(title = if (projectLoading) stringResource(R.string.dv_loading_projects) else if (projectsLoaded) stringResource(R.string.dv_refresh_projects) else stringResource(R.string.dv_load_projects),
                showChevron = false, enabled = !working && ak.isNotBlank() && sk.isNotBlank(),
                onClick = {
                    projectLoading = true; onBusy(true); projectError = ""
                    val accessKey = ak.trim(); val secretKey = sk.trim()
                    scope.launch {
                        try {
                            projects = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                VoiceProjectCatalog.list(accessKey, secretKey)
                            }
                            projectsLoaded = true
                            projectQuery = ""
                            projectPicker = projects.isNotEmpty()
                            if (projects.isEmpty()) projectError = context.getString(R.string.dv_no_projects)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            projects = emptyList(); projectsLoaded = false
                            projectError = when (e) {
                                is java.io.IOException -> context.getString(R.string.dv_projects_io_error)
                                is org.json.JSONException -> context.getString(R.string.dv_projects_json_error)
                                else -> e.message ?: context.getString(R.string.dv_projects_failed)
                            }
                        } finally { projectLoading = false; onBusy(false) }
                    }
                })
        }
        if (projects.isNotEmpty()) {
            item {
                EtaClickableRow(title = stringResource(R.string.dv_pick_project), showChevron = false, enabled = !working,
                    onClick = { projectQuery = ""; projectPicker = true })
            }
        }
        item {
            EtaClickableRow(title = if (projectOptions) stringResource(R.string.dv_collapse_manual) else stringResource(R.string.dv_manual_project),
                showChevron = false, enabled = !working,
                onClick = { projectOptions = !projectOptions })
        }
        if (projectOptions) {
            item {
                VoiceTextField(project, { project = it; resultText = ""; failed = false; onResult(""); VoiceCatalogPreferences.save(context, ak, it) },
                    label = stringResource(R.string.dv_project_name), enabled = !working, singleLine = true)
            }
        }
    }
    if (projectError.isNotBlank()) VoiceNote(projectError, isError = true)
    VoiceNote(stringResource(R.string.dv_project_hint))
    VoiceGroup {
        item {
            EtaClickableRow(title = if (busy) stringResource(R.string.dv_searching_slots) else stringResource(R.string.dv_read_my_slots),
                showChevron = false, enabled = !working && apiKey.isNotBlank() && ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank(),
                onClick = {
                    busy = true; onBusy(true); resultText = ""; failed = false; onResult("")
                    val savedKey = apiKey; val accessKey = ak.trim(); val secretKey = sk.trim(); val selectedProject = project.trim()
                    scope.launch {
                        try {
                            val count = PersonalVoices.importPurchased(savedKey, accessKey, secretKey, selectedProject)
                            resultText = if (count == 0) context.getString(R.string.dv_no_slots_found) else context.getString(R.string.dv_synced_count, count)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            failed = true
                            resultText = e.message ?: context.getString(R.string.dv_sync_failed)
                        } finally { busy = false; onBusy(false) }
                    }
                })
        }
    }
    if (resultText.isNotBlank()) VoiceNote(resultText, isError = failed)
    if (storageError.isNotBlank()) VoiceNote(storageError, isError = true)
    VoiceNote(stringResource(R.string.dv_sk_storage_note))
    VoiceGroup {
        item {
            EtaClickableRow(title = stringResource(R.string.dv_clear_remembered), showChevron = false, enabled = !working,
                onClick = {
                    runCatching { secretStore.clear() }.onSuccess {
                        VoiceCatalogPreferences.clear(context); ak = ""; project = "default"; sk = ""; storageError = ""
                        projects = emptyList(); projectsLoaded = false; projectError = ""; projectPicker = false
                        resultText = context.getString(R.string.dv_cleared); failed = false
                    }.onFailure { storageError = context.getString(R.string.dv_clear_failed) }
                })
        }
    }
    if (projectPicker) {
        val visibleProjects = remember(projects, projectQuery) {
            val needle = projectQuery.trim()
            if (needle.isEmpty()) projects
            else projects.filter {
                it.displayName.contains(needle, ignoreCase = true) || it.name.contains(needle, ignoreCase = true)
            }
        }
        EtaPickerSheet(
            title = stringResource(R.string.dv_pick_project),
            onDismiss = { projectPicker = false },
            query = projectQuery,
            onQueryChange = { projectQuery = it },
            searchPlaceholder = stringResource(R.string.dv_search_project),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()).selectableGroup(),
            ) {
                if (visibleProjects.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_no_matching_options),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                visibleProjects.forEach { item ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = project == item.name, enabled = item.allowed && !working, role = Role.RadioButton, onClick = {
                            TouchHaptics.click(view)
                            project = item.name
                            resultText = ""; failed = false; onResult("")
                            VoiceCatalogPreferences.save(context, ak, item.name)
                            projectPicker = false; projectOptions = false
                        }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = project == item.name, enabled = item.allowed && !working, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            EtaText(item.displayName, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (item.allowed) 1f else 0.38f))
                            if (item.displayName != item.name) EtaText(item.name, style = EtaTextStyle.Footnote)
                            if (!item.allowed) EtaText(stringResource(R.string.dv_no_project_access), style = EtaTextStyle.Footnote, secondary = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalVoiceRow(
    voice: PersonalVoices.Voice,
    enabled: Boolean,
    previewActive: Boolean,
    previewLoading: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
) {
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    EtaClickableRow(
        title = voice.name,
        summary = if (previewLoading) stringResource(R.string.dv_preview_loading) else if (previewActive) stringResource(R.string.dv_previewing) else when (voice.status) {
            -2 -> stringResource(R.string.dv_status_rejected); 0 -> stringResource(R.string.dv_status_notfound); 1 -> stringResource(R.string.dv_status_training); 2 -> stringResource(R.string.dv_status_train_ok); 3 -> stringResource(R.string.dv_status_train_fail); 4 -> stringResource(R.string.dv_status_accepted); else -> stringResource(R.string.dv_status_pending)
        },
        onClick = null,
        showChevron = false,
        leading = { Icon(Icons.Rounded.Mic, contentDescription = null) },
        endActions = {
            IconButton(enabled = previewActive || voice.demo.startsWith("https://"), onClick = { TouchHaptics.click(view); onPreview() }) {
                Icon(if (previewActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = if (previewActive) stringResource(R.string.dv_preview_stop) else stringResource(R.string.dv_preview))
            }
            Box {
                IconButton(onClick = { TouchHaptics.click(view); menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.dv_voice_actions)) }
                EtaTextMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    entries = buildList {
                        add(
                            EtaMenuEntry(
                                text = stringResource(R.string.dv_refresh_status),
                                icon = Icons.Rounded.Refresh,
                            ) {
                                TouchHaptics.click(view); menu = false; onRefresh()
                            },
                        )
                        if (voice.error.isNotBlank()) {
                            add(
                                EtaMenuEntry(
                                    text = stringResource(R.string.dv_error_details),
                                    icon = Icons.Rounded.ErrorOutline,
                                ) {
                                    TouchHaptics.click(view); menu = false; showError = true
                                },
                            )
                        }
                        add(
                            EtaMenuEntry(
                                text = stringResource(R.string.dv_remove_local),
                                icon = Icons.Rounded.DeleteOutline,
                                enabled = enabled,
                                destructive = true,
                            ) {
                                TouchHaptics.click(view); menu = false; onDelete()
                            },
                        )
                    },
                )
            }
        },
    )
    if (showError) AlertDialog(
        onDismissRequest = { showError = false }, title = { Text(stringResource(R.string.dv_error_details)) },
        text = { Text(voice.error, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { TouchHaptics.click(view); showError = false }) { Text(stringResource(R.string.action_close)) } },
    )
}
