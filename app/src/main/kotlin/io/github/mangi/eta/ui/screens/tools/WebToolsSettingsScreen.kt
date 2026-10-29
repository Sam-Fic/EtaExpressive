package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.CustomWebFetchConfig
import io.github.mangi.eta.data.model.CustomWebSearchConfig
import io.github.mangi.eta.data.model.WebHttpMethod
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess

@Composable
internal fun WebToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WebToolsSettingsStore(context, scope) }
    val settings = store.settings
    val credentials = store.credentials
    val usesDeepSeek = settings.searchBackend == WebToolBackend.DEEPSEEK ||
        settings.fetchBackend == WebToolBackend.DEEPSEEK
    val usesRemote = settings.searchBackend != WebToolBackend.BUILTIN ||
        settings.fetchBackend != WebToolBackend.BUILTIN
    val backendLabels = listOf(
        stringResource(R.string.web_tools_backend_builtin),
        stringResource(R.string.web_tools_backend_deepseek),
        stringResource(R.string.web_tools_backend_custom),
    )
    val methodLabels = listOf(WebHttpMethod.POST.name, WebHttpMethod.GET.name)
    var showSearchMapping by rememberSaveable { mutableStateOf(false) }
    var showFetchMapping by rememberSaveable { mutableStateOf(false) }

    EtaScaffoldPage(
        title = stringResource(R.string.web_tools_settings_title),
        onBack = onBack,
        listModifier = Modifier.imePadding(),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
            ) {
                EtaSegmentedColumn(
                    title = stringResource(R.string.web_tools_group_service),
                    content = listOf(
                        {
                            EtaSegmentedDropdownRow(
                                title = stringResource(R.string.web_tools_search_service),
                                items = backendLabels,
                                selectedIndex = settings.searchBackend.ordinal,
                                onItemSelected = {
                                    store.edit(settings.copy(searchBackend = WebToolBackend.entries[it]))
                                },
                            )
                        },
                        {
                            EtaSegmentedDropdownRow(
                                title = stringResource(R.string.web_tools_fetch_service),
                                items = backendLabels,
                                selectedIndex = settings.fetchBackend.ordinal,
                                onItemSelected = {
                                    store.edit(settings.copy(fetchBackend = WebToolBackend.entries[it]))
                                },
                            )
                        },
                    ),
                )
                WebToolsNote(
                    stringResource(
                        if (usesRemote) R.string.web_tools_overview_remote_note else R.string.web_tools_overview_builtin_note,
                    ),
                )
                if (usesDeepSeek) {
                    EtaSegmentedColumn(
                        title = stringResource(R.string.web_tools_backend_deepseek),
                        content = listOf(
                            {
                                EtaSegmentedTextField(
                                    label = stringResource(R.string.web_tools_endpoint_url),
                                    value = settings.deepSeek.baseUrl,
                                    singleLine = true,
                                    onValueChange = {
                                        store.edit(settings.copy(deepSeek = settings.deepSeek.copy(baseUrl = it)))
                                    },
                                )
                            },
                            {
                                WebSecretField(
                                    label = stringResource(R.string.web_tools_api_key_optional),
                                    value = credentials.deepSeekApiKey,
                                    onValueChange = {
                                        store.editCredentials(credentials.copy(deepSeekApiKey = it))
                                    },
                                )
                            },
                        ),
                    )
                    WebToolsNote(
                        stringResource(
                            if (store.hasProviderDeepSeekKey && credentials.deepSeekApiKey.isBlank()) {
                                R.string.web_tools_deepseek_linked_note
                            } else {
                                R.string.web_tools_deepseek_note
                            },
                        ),
                    )
                }
                if (settings.searchBackend == WebToolBackend.CUSTOM) {
                    EtaSegmentedColumn(
                        title = stringResource(R.string.web_tools_group_custom_search),
                        content = customSearchItems(
                            settings = settings,
                            credentials = credentials,
                            showMapping = showSearchMapping,
                            onToggleMapping = { showSearchMapping = !showSearchMapping },
                            store = store,
                        ),
                    )
                    WebToolsNote(stringResource(R.string.web_tools_custom_search_note))
                }
                if (settings.fetchBackend == WebToolBackend.CUSTOM) {
                    EtaSegmentedColumn(
                        title = stringResource(R.string.web_tools_group_custom_fetch),
                        content = customFetchItems(
                            settings = settings,
                            credentials = credentials,
                            showMapping = showFetchMapping,
                            onToggleMapping = { showFetchMapping = !showFetchMapping },
                            store = store,
                        ),
                    )
                    WebToolsNote(stringResource(R.string.web_tools_custom_fetch_note))
                }
                EtaTextButton(
                    text = stringResource(if (store.saving) R.string.web_tools_saving else R.string.action_save),
                    enabled = store.loaded && !store.saving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = store::save,
                )
                store.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (store.messageIsError) StatusError else StatusSuccess,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun customSearchItems(
    settings: io.github.mangi.eta.data.model.WebToolsSettings,
    credentials: io.github.mangi.eta.data.model.WebToolsCredentials,
    showMapping: Boolean,
    onToggleMapping: () -> Unit,
    store: WebToolsSettingsStore,
): List<@Composable () -> Unit> = buildList {
    fun edit(transform: (CustomWebSearchConfig) -> CustomWebSearchConfig) {
        store.edit(settings.copy(customSearch = transform(settings.customSearch)))
    }
    add {
        EtaSegmentedDropdownRow(
            title = stringResource(R.string.web_tools_http_method),
            items = listOf(WebHttpMethod.POST.name, WebHttpMethod.GET.name),
            selectedIndex = settings.customSearch.method.ordinal,
            onItemSelected = { index -> edit { it.copy(method = WebHttpMethod.entries[index]) } },
        )
    }
    add {
        EtaSegmentedTextField(
            label = stringResource(R.string.web_tools_endpoint_url),
            value = settings.customSearch.url,
            singleLine = true,
            onValueChange = { value -> edit { it.copy(url = value) } },
        )
    }
    add {
        WebSecretField(
            label = stringResource(R.string.web_tools_api_key_optional),
            value = credentials.customSearchApiKey,
            onValueChange = { store.editCredentials(credentials.copy(customSearchApiKey = it)) },
        )
    }
    add {
        EtaClickableRow(
            title = stringResource(R.string.web_tools_mapping_and_template),
            onClick = onToggleMapping,
        )
    }
    if (showMapping) {
        add {
            EtaSegmentedTextField(
                label = stringResource(R.string.web_tools_body_template),
                value = settings.customSearch.bodyTemplate,
                onValueChange = { value -> edit { it.copy(bodyTemplate = value) } },
            )
        }
        add {
            EtaSegmentedTextField(
                label = stringResource(R.string.web_tools_results_path),
                value = settings.customSearch.resultsPath,
                singleLine = true,
                onValueChange = { value -> edit { it.copy(resultsPath = value) } },
            )
        }
        add {
            EtaSegmentedTextField(
                label = stringResource(R.string.web_tools_title_field),
                value = settings.customSearch.titleField,
                singleLine = true,
                onValueChange = { value -> edit { it.copy(titleField = value) } },
            )
        }
        add {
            EtaSegmentedTextField(
                label = stringResource(R.string.web_tools_url_field),
                value = settings.customSearch.urlField,
                singleLine = true,
                onValueChange = { value -> edit { it.copy(urlField = value) } },
            )
        }
        add {
            EtaSegmentedTextField(
                label = stringResource(R.string.web_tools_snippet_field),
                value = settings.customSearch.snippetField,
                singleLine = true,
                onValueChange = { value -> edit { it.copy(snippetField = value) } },
            )
        }
    }
    add {
        EtaSegmentedTextField(
            label = stringResource(R.string.web_tools_custom_headers),
            value = settings.customSearch.headersText,
            onValueChange = { value -> edit { it.copy(headersText = value) } },
        )
    }
}

private fun customFetchItems(
    settings: io.github.mangi.eta.data.model.WebToolsSettings,
    credentials: io.github.mangi.eta.data.model.WebToolsCredentials,
    showMapping: Boolean,
    onToggleMapping: () -> Unit,
    store: WebToolsSettingsStore,
): List<@Composable () -> Unit> = buildList {
    fun edit(transform: (CustomWebFetchConfig) -> CustomWebFetchConfig) {
        store.edit(settings.copy(customFetch = transform(settings.customFetch)))
    }
    add {
        EtaSegmentedDropdownRow(
            title = stringResource(R.string.web_tools_http_method),
            items = listOf(WebHttpMethod.POST.name, WebHttpMethod.GET.name),
            selectedIndex = settings.customFetch.method.ordinal,
            onItemSelected = { index -> edit { it.copy(method = WebHttpMethod.entries[index]) } },
        )
    }
    add {
        EtaSegmentedTextField(
            label = stringResource(R.string.web_tools_endpoint_url),
            value = settings.customFetch.url,
            singleLine = true,
            onValueChange = { value -> edit { it.copy(url = value) } },
        )
    }
    add {
        WebSecretField(
            label = stringResource(R.string.web_tools_api_key_optional),
            value = credentials.customFetchApiKey,
            onValueChange = { store.editCredentials(credentials.copy(customFetchApiKey = it)) },
        )
    }
    add {
        EtaClickableRow(
            title = stringResource(R.string.web_tools_mapping_and_template),
            onClick = onToggleMapping,
        )
    }
    if (showMapping) {
        add {
            EtaSegmentedTextField(
                label = stringResource(R.string.web_tools_body_template),
                value = settings.customFetch.bodyTemplate,
                onValueChange = { value -> edit { it.copy(bodyTemplate = value) } },
            )
        }
        add {
            EtaSegmentedTextField(
                label = stringResource(R.string.web_tools_content_field),
                value = settings.customFetch.contentField,
                singleLine = true,
                onValueChange = { value -> edit { it.copy(contentField = value) } },
            )
        }
        add {
            EtaSegmentedTextField(
                label = stringResource(R.string.web_tools_title_field),
                value = settings.customFetch.titleField,
                singleLine = true,
                onValueChange = { value -> edit { it.copy(titleField = value) } },
            )
        }
    }
    add {
        EtaSegmentedTextField(
            label = stringResource(R.string.web_tools_custom_headers),
            value = settings.customFetch.headersText,
            onValueChange = { value -> edit { it.copy(headersText = value) } },
        )
    }
}

@Composable
private fun WebToolsNote(text: String) {
    EtaText(text = text, style = EtaTextStyle.Secondary)
}

@Composable
private fun WebSecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    EtaSegmentedTextField(
        label = label,
        value = value,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        onValueChange = onValueChange,
        trailingContent = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = stringResource(
                        if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                    ),
                )
            }
        },
    )
}
