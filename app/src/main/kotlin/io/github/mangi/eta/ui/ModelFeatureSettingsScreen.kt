package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.ModelFeature
import io.github.mangi.eta.agent.model.ModelFeaturePreferences
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn

@Composable
internal fun ModelFeatureSettingsScreen(feature: ModelFeature, onBack: () -> Unit) {
    var selection by remember(feature) { mutableStateOf(ModelFeaturePreferences.selection(feature)) }
    var picker by remember { mutableStateOf(false) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val vision = feature == ModelFeature.VISION
    val title = stringResource(if (vision) R.string.auxiliary_vision_title else R.string.title_model_title)
    val models = remember(providers, selection, vision) {
        val all = AgentModelPickerProjector.project(providers, selection.providerId, selection.modelId)
        all.copy(
            providerGroups = all.providerGroups.map { group ->
                group.copy(models = group.models.filter { !it.supportsImageGeneration && !it.supportsVideoGeneration && (!vision || it.supportsVision) })
            }.filter { it.models.isNotEmpty() },
            selectedModel = all.selectedModel?.takeIf { !it.supportsImageGeneration && !it.supportsVideoGeneration && (!vision || it.supportsVision) },
        )
    }
    EtaScaffoldPage(title = title, onBack = onBack) {
        item {
            EtaText(
                text = stringResource(if (vision) R.string.vision_feature_description else R.string.title_feature_description),
                style = EtaTextStyle.Secondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            EtaSegmentedColumn(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.model_feature_custom),
                        checked = selection.custom,
                        onCheckedChange = {
                            selection = selection.copy(custom = it)
                            ModelFeaturePreferences.save(feature, selection)
                        },
                    )
                }
                item(visible = selection.custom) {
                    EtaClickableRow(
                        title = stringResource(R.string.model_feature_select),
                        summary = models.selectedModel?.let { "${it.providerName} / ${it.displayName}" }
                        ?: stringResource(R.string.model_feature_missing),
                        onClick = { picker = true },
                    )
                }
            }
        }
    }
    TtsModelPickerDialog(models, picker, { picker = false }, { providerId, modelId ->
        selection = selection.copy(providerId = providerId, modelId = modelId)
        ModelFeaturePreferences.save(feature, selection)
        picker = false
    }, title)
}
