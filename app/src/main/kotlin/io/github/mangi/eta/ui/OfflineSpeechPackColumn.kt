package io.github.mangi.eta.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.SpeechModelCatalog
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaPickerSheet
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle

/**
 * 离线语音包的选择块：模型、下载来源与下载行共用同一组分段条目。
 *
 * 「语音转文字」与「对话识别」两个设置页都要展示同一份离线语音包状态，因此抽成独立组件；
 * 两边布局差异（对话识别只在非云识别时展示）由调用方用 `item(visible = …)` 处理。
 */
@Composable
internal fun OfflineSpeechPackColumn(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by OfflineSpeechPack.state.collectAsState()
    var modelPicker by remember { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf(false) }
    // 摘要与确认文案都带下载体积，体积随所选模型变化。
    val size = remember(state.model) { Formatter.formatShortFileSize(context, state.model.totalBytes) }
    // 下载中不允许改选，避免下载目录与所选模型不一致。
    val canChoose = !state.downloading && !state.checking
    // 分段 DSL 的构建块不是组合作用域，来源文案必须先在这里解析好再传进去。
    val sourceLabels = speechSourceLabels(state.model.sources)

    EtaSegmentedColumn(modifier = modifier) {
        item(key = "speech_model") {
            EtaClickableRow(
                title = stringResource(R.string.speech_model),
                summary = speechModelLabel(state.model.id),
                enabled = canChoose,
                onClick = { modelPicker = true },
            )
        }
        if (state.model.sources.size > 1) {
            item(key = "speech_source") {
                EtaSegmentedDropdownRow(
                    title = stringResource(R.string.speech_source),
                    items = sourceLabels,
                    selectedIndex = state.model.sources.indexOfFirst { it.id == state.source.id }.coerceAtLeast(0),
                    enabled = canChoose,
                    onItemSelected = { index ->
                        OfflineSpeechPack.select(context, state.model.id, state.model.sources[index].id)
                    },
                )
            }
        }
        item(key = "speech_download") {
            // 下载行与进度条同属一个分段条目：由容器统一提供外形与底色，行内交出底色
            // （LocalEtaSegmentedRowBare），不再拆成两个分段、中间露出 2dp 缝隙。
            EtaSegmentedItemContainer {
                EtaClickableRow(
                    title = stringResource(
                        when {
                            state.downloading -> R.string.speech_cancel_download
                            state.ready -> R.string.speech_pack_ready
                            else -> R.string.speech_download
                        },
                    ),
                    summary = stringResource(
                        when {
                            state.checking -> R.string.speech_checking
                            state.error -> R.string.speech_download_failed
                            else -> R.string.speech_pack_summary
                        },
                        size,
                    ),
                    enabled = !state.checking && !state.ready,
                    onClick = {
                        if (state.downloading) OfflineSpeechPack.cancelDownload() else confirmDownload = true
                    },
                )
                AnimatedVisibility(
                    visible = state.downloading,
                    // 下载进度块的高度展开 / 收缩取 M3E Spatial：入场 default、出场 fast。
                    enter = expandVertically(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    ),
                    exit = shrinkVertically(
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    ),
                ) {
                    val progress = (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                    Column(Modifier.fillMaxWidth().padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = 16.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        EtaText("${(progress * 100).toInt()}%", style = EtaTextStyle.Footnote,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    // 选中即提交（与相邻的资源选择弹窗一致），不再多一次「确认」。
    // 换用与模型选择面板同款的 EtaPickerSheet：底部弹层、下拉/点遮罩/返回键均可关闭，
    // 因此不再需要右上角或底部的「关闭」按钮。
    if (modelPicker) {
        EtaPickerSheet(
            title = stringResource(R.string.speech_model),
            onDismiss = { modelPicker = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            ) {
                EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    SpeechModelCatalog.models.forEach { model ->
                        item(key = model.id) {
                            EtaSegmentedRadioRow(
                                title = speechModelLabel(model.id),
                                selected = model.id == state.model.id,
                                onClick = {
                                    modelPicker = false
                                    OfflineSpeechPack.select(context, model.id, state.source.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    EtaDialog(
        show = confirmDownload,
        title = stringResource(R.string.speech_download),
        onDismissRequest = { confirmDownload = false },
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.speech_download),
                cancelText = stringResource(R.string.action_cancel),
                onCancel = { confirmDownload = false },
                onConfirm = { confirmDownload = false; OfflineSpeechPack.download(context) },
            )
        },
    ) {
        EtaText(
            stringResource(R.string.speech_download_confirm, size, speechSourceLabel(state.source.id)),
            style = EtaTextStyle.Secondary,
        )
    }
}

private val SpeechModelCatalog.Model.labelRes: Int
    get() = when (id) {
        SpeechModelCatalog.SENSE_VOICE_ID -> R.string.speech_model_sense_voice
        SpeechModelCatalog.X_ASR_ID -> R.string.speech_model_x_asr
        else -> R.string.speech_model_zipformer
    }

@Composable
private fun speechModelLabel(id: String): String =
    stringResource(SpeechModelCatalog.model(id).labelRes)

private val SpeechModelCatalog.SourceId.labelRes: Int
    get() = when (this) {
        SpeechModelCatalog.SourceId.HUGGING_FACE -> R.string.speech_source_hugging_face
        SpeechModelCatalog.SourceId.MODEL_SCOPE -> R.string.speech_source_model_scope
    }

@Composable
private fun speechSourceLabel(id: SpeechModelCatalog.SourceId): String = stringResource(id.labelRes)

/** 用 for 循环而不是 map/forEach，保证 stringResource 的调用点确实落在组合作用域内。 */
@Composable
private fun speechSourceLabels(sources: List<SpeechModelCatalog.Source>): List<String> {
    val labels = ArrayList<String>(sources.size)
    for (source in sources) labels += stringResource(source.id.labelRes)
    return labels
}
