package io.github.mangi.eta.ui.pages.providers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaSearchHistory
import io.github.mangi.eta.ui.adaptive.EtaSearchPage
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType

/** 查询历史分域键：供应商搜索历史独立存储。 */
private const val HISTORY_KEY = "model_providers"

/**
 * 独立搜索页：从供应商主页右上角的单搜索图标跳入，整页即搜索。
 *
 * 顶栏是「返回钮 + 透明底搜索框 + 清除按钮」（官方 [EtaSearchPage]），进入即自动聚焦
 * 弹键盘；列表与主页共用 [materialProviderListContent]，按名称 / BaseURL / 类型实时过滤。
 * 搜索页不做多选批量删除（selectionMode 恒 false），点按行进详情页。
 */
@Composable
internal fun ModelProviderSearchScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
) {
    val context = LocalContext.current
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val storedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val selectedProviderId = currentProviderId?.takeIf { it.isNotBlank() } ?: storedProviderId

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    EtaSearchPage(
        searchPlaceholder = stringResource(R.string.ui_search_provider_74e049),
        onBack = onBack,
        historyKey = HISTORY_KEY,
    ) { query ->
        // LazyListScope 不是 Composable 作用域，过滤直接内联计算（主页列表与搜索结果共用）。
        val needle = query.trim()
        val filteredProviders = providers.filter { provider ->
            needle.isBlank() ||
                provider.name.contains(needle, ignoreCase = true) ||
                provider.baseUrl.contains(needle, ignoreCase = true) ||
                provider.typeLabel.contains(needle, ignoreCase = true)
        }
        val selectableProviders = filteredProviders.filterNot(ProviderSetting::isBuiltIn)
        materialProviderListContent(
            searchQuery = needle,
            filteredProviders = filteredProviders,
            selectableProviders = selectableProviders,
            // 搜索页不进入多选态：勾选 / 全选 / 长按进多选全部置空。
            selectionMode = false,
            selectedProviderIds = emptySet(),
            selectedProviderId = selectedProviderId,
            onToggleChecked = {},
            onOpen = { id ->
                EtaSearchHistory.add(context, HISTORY_KEY, query)
                onNavigate(AppRoute.ModelProviderDetail(id))
            },
            onEnterSelection = {},
            onAddProvider = { type ->
                onNavigate(
                    when (type) {
                        NewProviderType.OpenAiCompatible -> {
                            AppRoute.ModelProviderAuthMethod(NewProviderType.OpenAiCompatible)
                        }
                        else -> AppRoute.ModelProviderNew(type)
                    },
                )
            },
        )
    }
}
