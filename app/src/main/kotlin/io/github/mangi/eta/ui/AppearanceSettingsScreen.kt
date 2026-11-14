package io.github.mangi.eta.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.filled.Brightness1
import androidx.compose.material.icons.filled.Brightness3
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AnswerFoldLevel
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.MAX_INTERFACE_SCALE
import io.github.mangi.eta.data.model.MIN_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExpandChevron
import io.github.mangi.eta.ui.adaptive.EtaExpressiveToggleButton
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSlider
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.adaptive.EtaTonalCard
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.lazyRowEdgeFade
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.seedColor
import io.github.mangi.eta.ui.app.toEtaColorScheme
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    val coroutineScope = rememberCoroutineScope()
    var scaleDraft by remember(appearance.interfaceScale) {
        mutableFloatStateOf(appearance.interfaceScale * 100f)
    }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    var morphLoadingExpanded by remember { mutableStateOf(false) }
    var iconExpanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update(transform)
        }
    }

    fun confirmIcon(light: Int, dark: Int) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update { current ->
                current.copy(iconLightColor = light, iconDarkColor = dark)
            }
            val latest = AppearanceSettingsRepository.settings()
            LauncherIconSync.apply(context.applicationContext, latest)
        }
    }

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        update { current -> current.copy(interfaceScale = scale) }
    }

    val paletteStyles = AppearancePaletteStyle.entries
    val paletteLabels = listOf(
        stringResource(R.string.appearance_palette_tonal_spot),
        stringResource(R.string.appearance_palette_neutral),
        stringResource(R.string.appearance_palette_vibrant),
        stringResource(R.string.appearance_palette_expressive),
        stringResource(R.string.appearance_palette_rainbow),
        stringResource(R.string.appearance_palette_fruit_salad),
        stringResource(R.string.appearance_palette_monochrome),
        stringResource(R.string.appearance_palette_fidelity),
        stringResource(R.string.appearance_palette_content),
    )
    // 回答后收起的三档（顺序即 AnswerFoldLevel.entries）。
    val foldLevelLabels = AnswerFoldLevel.entries.map { level ->
        when (level) {
            AnswerFoldLevel.AGGRESSIVE -> stringResource(R.string.appearance_fold_level_aggressive)
            AnswerFoldLevel.NORMAL -> stringResource(R.string.appearance_fold_level_normal)
            AnswerFoldLevel.NONE -> stringResource(R.string.appearance_fold_level_off)
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
    ) {
        // Material 3：KernelSU ColorPalette 式布局 —— 预览卡、色板按钮行、
        // 主题模式 4 连按钮组（含纯黑）、分段下拉/开关组、滑杆卡收尾。
        appearanceMaterial3Content(
            appearance = appearance,
            paletteStyles = paletteStyles,
            paletteLabels = paletteLabels,
            foldLevelLabels = foldLevelLabels,
            scaleDraft = scaleDraft,
            onScaleDraftChange = { scaleDraft = it },
            onCommitScale = { commitScale(it) },
            onOpenScaleDialog = {
                scaleInput = scaleDraft.roundToInt().toString()
                showScaleDialog = true
            },
            morphLoadingExpanded = morphLoadingExpanded,
            onMorphLoadingExpandedChange = { morphLoadingExpanded = it },
            iconExpanded = iconExpanded,
            onIconExpandedChange = { iconExpanded = it },
            onConfirmIcon = ::confirmIcon,
            onSetAccentColor = { accent -> update { current -> current.copy(accentColor = accent) } },
            onSetThemeMode = { mode -> update { current -> current.copy(themeMode = mode) } },
            onSetPaletteStyle = { style -> update { current -> current.copy(paletteStyle = style) } },
            onSetPureBlack = { enabled -> update { current -> current.copy(pureBlackEnabled = enabled) } },
            onSetTimestamps = { enabled -> update { current -> current.copy(messageTimestampsEnabled = enabled) } },
            onSetAnswerFoldLevel = { level ->
                update { current -> current.copy(answerFoldLevel = level) }
            },
            onSetMorphLoading = { enabled ->
                update { current -> current.copy(morphLoadingIndicator = enabled) }
                morphLoadingExpanded = enabled
            },
            onSetMorphBeforeResponse = { enabled ->
                update { current -> current.copy(morphLoadingBeforeResponseOnly = enabled) }
            },
            onSetPredictiveBack = { enabled -> update { current -> current.copy(predictiveBackEnabled = enabled) } },
            onSetSwipeBack = { enabled -> update { current -> current.copy(swipeBackEnabled = enabled) } },
        )
    }

    val parsedScale = scaleInput.toIntOrNull()
    EtaDialog(
        show = showScaleDialog,
        title = stringResource(R.string.appearance_interface_scale_dialog_title),
        summary = stringResource(R.string.appearance_interface_scale_dialog_summary),
        onDismissRequest = { showScaleDialog = false },
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                confirmEnabled = parsedScale != null && parsedScale in 80..110,
                onCancel = { showScaleDialog = false },
                onConfirm = {
                    parsedScale?.let { commitScale(it.toFloat()) }
                    showScaleDialog = false
                },
            )
        },
    ) {
        Column {
            EtaTextField(
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.appearance_interface_scale_input_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Material 3 外观页内容（KernelSU ColorPalette 式）：
 * 主题预览卡 → 色板按钮行 → 主题模式 4 连按钮组 → 配色风格 → 预测性返回 →
 * 图标外观 → 界面行为 → 加载动效 → 界面缩放卡（KernelSU 有对应功能的条目
 * 按对方顺序排在前，Eta 独有条目居后，滑杆卡收尾与对方节奏一致）。
 *
 * 以 [androidx.compose.foundation.lazy.LazyListScope] 扩展声明（非 @Composable），
 * 各条目内容写在 `item { … }` 的可组合 lambda 内。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.appearanceMaterial3Content(
    appearance: AppearanceSettings,
    paletteStyles: List<AppearancePaletteStyle>,
    paletteLabels: List<String>,
    foldLevelLabels: List<String>,
    scaleDraft: Float,
    onScaleDraftChange: (Float) -> Unit,
    onCommitScale: (Float) -> Unit,
    onOpenScaleDialog: () -> Unit,
    morphLoadingExpanded: Boolean,
    onMorphLoadingExpandedChange: (Boolean) -> Unit,
    iconExpanded: Boolean,
    onIconExpandedChange: (Boolean) -> Unit,
    onConfirmIcon: (Int, Int) -> Unit,
    onSetAccentColor: (AppearanceAccentColor) -> Unit,
    onSetThemeMode: (AppearanceThemeMode) -> Unit,
    onSetPaletteStyle: (AppearancePaletteStyle) -> Unit,
    onSetPureBlack: (Boolean) -> Unit,
    onSetTimestamps: (Boolean) -> Unit,
    onSetAnswerFoldLevel: (AnswerFoldLevel) -> Unit,
    onSetMorphLoading: (Boolean) -> Unit,
    onSetMorphBeforeResponse: (Boolean) -> Unit,
    onSetPredictiveBack: (Boolean) -> Unit,
    onSetSwipeBack: (Boolean) -> Unit,
) {
    // ① 主题预览卡（按候选配色实时渲染缩略屏幕）
    // 页面统一节奏：每个分组自带 16dp 页边距 + 13dp 组间距，数值取自适应层令牌。
    item(key = "m3_theme_preview") {
        ThemePreviewCard(
            appearance = appearance,
            modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing),
        )
    }

    // ② 色板按钮行（首个 = 系统动态取色，其后为固定强调色）
    // 全宽滚动行：两端由 contentPadding 对齐页边距，间距与页边距同为 16dp（同 KernelSU 色板行）。
    item(key = "m3_color_buttons") {
        // 色板按钮 72dp 宽，一屏放不下几枚，滚动时首尾的色板会被视口竖直切开，
        // 留下半枚色板。这里在两端各挂一条 DstIn 淡出带（与抽屉胶囊行、热力图同一套）。
        val colorListState = rememberLazyListState()
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .lazyRowEdgeFade(colorListState)
                .padding(bottom = EtaSegmentedGroupSpacing),
            state = colorListState,
            contentPadding = PaddingValues(horizontal = EtaGroupHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(EtaGroupHorizontalPadding),
        ) {
            item {
                ColorButtonM3(
                    color = Color.Unspecified,
                    isSelected = appearance.accentColor == AppearanceAccentColor.SYSTEM,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.SYSTEM) },
                )
            }
            item {
                ColorButtonM3(
                    color = AppearanceAccentColor.BLUE.seedColor(),
                    isSelected = appearance.accentColor == AppearanceAccentColor.BLUE,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.BLUE) },
                )
            }
            item {
                ColorButtonM3(
                    color = AppearanceAccentColor.PURPLE.seedColor(),
                    isSelected = appearance.accentColor == AppearanceAccentColor.PURPLE,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.PURPLE) },
                )
            }
            item {
                ColorButtonM3(
                    color = AppearanceAccentColor.PINK.seedColor(),
                    isSelected = appearance.accentColor == AppearanceAccentColor.PINK,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.PINK) },
                )
            }
            item {
                ColorButtonM3(
                    color = AppearanceAccentColor.RED.seedColor(),
                    isSelected = appearance.accentColor == AppearanceAccentColor.RED,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.RED) },
                )
            }
            item {
                ColorButtonM3(
                    color = AppearanceAccentColor.ORANGE.seedColor(),
                    isSelected = appearance.accentColor == AppearanceAccentColor.ORANGE,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.ORANGE) },
                )
            }
            item {
                ColorButtonM3(
                    color = AppearanceAccentColor.YELLOW.seedColor(),
                    isSelected = appearance.accentColor == AppearanceAccentColor.YELLOW,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.YELLOW) },
                )
            }
            item {
                ColorButtonM3(
                    color = AppearanceAccentColor.GREEN.seedColor(),
                    isSelected = appearance.accentColor == AppearanceAccentColor.GREEN,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.GREEN) },
                )
            }
            item {
                ColorButtonM3(
                    color = AppearanceAccentColor.TEAL.seedColor(),
                    isSelected = appearance.accentColor == AppearanceAccentColor.TEAL,
                    appearance = appearance,
                    onClick = { onSetAccentColor(AppearanceAccentColor.TEAL) },
                )
            }
        }
    }

    // ③ 主题模式：4 连按钮组（跟随系统 / 浅色 / 深色 / 纯黑，语义 Role.RadioButton）。
    // 对齐 KernelSU 把 AMOLED 作为第 4 个模式按钮：纯黑态 = 深色 + 纯黑背景。
    // 跟随系统下开启的纯黑仍然合法（随系统暗色生效），选中态只认「深色+纯黑」组合。
    item(key = "m3_theme_mode") {
        ThemeModeButtonGroup(
            selectedMode = appearance.themeMode,
            pureBlackEnabled = appearance.pureBlackEnabled,
            onSetThemeMode = onSetThemeMode,
            onSetPureBlack = onSetPureBlack,
        )
    }

    // ④ 配色风格组（分段下拉行）。纯黑背景不再是开关行：已并入主题模式的第 4 个按钮。
    item(key = "m3_color_style") {
        EtaSegmentedColumn(
            modifier = Modifier.padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
        ) {
            item {
                EtaSegmentedDropdownRow(
                    icon = Icons.Rounded.Style,
                    title = stringResource(R.string.appearance_palette_style),
                    items = paletteLabels,
                    selectedIndex = appearance.paletteStyle.ordinal,
                    onItemSelected = { index ->
                        paletteStyles.getOrNull(index)?.let(onSetPaletteStyle)
                    },
                )
            }
        }
    }

    // ⑤ 返回手势组（KernelSU 同位置，色彩条目之后）：预测性返回在上、横移返回在下。
    // 两者互不影响：前者是系统 API 34+ 的动画开关，后者是应用内 miuix entry.swipeDismiss，
    // 不依赖系统版本，所以横移返回那一行在低版本也必须保留。
    item(key = "m3_back_gestures") {
        EtaSegmentedColumn(
            modifier = Modifier.padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
        ) {
            // 仅 API 34+ 显示——底层 PredictiveBackController.apply 在更低版本本就 no-op。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                item {
                    EtaSwitchRow(
                        leading = {
                            PreferenceIcon(icon = Icons.AutoMirrored.Rounded.MenuOpen)
                        },
                        title = stringResource(R.string.appearance_predictive_back),
                        summary = stringResource(R.string.appearance_predictive_back_summary),
                        checked = appearance.predictiveBackEnabled,
                        onCheckedChange = onSetPredictiveBack,
                    )
                }
            }
            item {
                EtaSwitchRow(
                    leading = {
                        PreferenceIcon(icon = Icons.Rounded.Swipe)
                    },
                    title = stringResource(R.string.appearance_swipe_back),
                    summary = stringResource(R.string.appearance_swipe_back_summary),
                    checked = appearance.swipeBackEnabled,
                    onCheckedChange = onSetSwipeBack,
                )
            }
        }
    }

    // ⑥ 图标外观组
    item(key = "m3_icon") {
        EtaSegmentedColumn(
            modifier = Modifier.padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
        ) {
            item {
                EtaClickableRow(
                    leading = {
                        PreferenceIcon(icon = Icons.Rounded.Launch)
                    },
                    title = stringResource(R.string.appearance_icon),
                    summary = stringResource(R.string.appearance_icon_summary),
                    showChevron = false,
                    onClick = {
                        onIconExpandedChange(!iconExpanded)
                    },
                    holdDownState = iconExpanded,
                    endActions = {
                        EtaExpandChevron(expanded = iconExpanded)
                    },
                )
            }
            // 展开后的编辑器作为同组的下一个分段（兄弟页面 ProviderBodiesEditor / ContextCompressionSettings
            // 同款写法）。挂在行内 bottomAction 上时它脱离分组容器，会自成一块没有边距的浮层。
            item(visible = iconExpanded) {
                EtaSegmentedItemContainer {
                    IconAppearanceEditor(
                        light = appearance.iconLightColor,
                        dark = appearance.iconDarkColor,
                        onConfirm = onConfirmIcon,
                    )
                }
            }
        }
    }

    // ⑦ 界面行为组（分段开关行；预测性返回已独立成组⑤）
    item(key = "m3_interface") {
        EtaSegmentedColumn(
            modifier = Modifier.padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
        ) {
            item {
                EtaSwitchRow(
                    leading = {
                        PreferenceIcon(icon = Icons.Rounded.Schedule)
                    },
                    title = stringResource(R.string.appearance_message_timestamps),
                    summary = stringResource(R.string.appearance_message_timestamps_summary),
                    checked = appearance.messageTimestampsEnabled,
                    onCheckedChange = onSetTimestamps,
                )
            }
            item {
                // 三档分级：激进只留用户消息与最终回复；普通只收推理/工具；不折叠始终摊开。
                EtaSegmentedDropdownRow(
                    // 1.7.8 里 UnfoldLess 未迁移进 AutoMirrored，只能用常规 rounded 集。
                    icon = Icons.Rounded.UnfoldLess,
                    title = stringResource(R.string.appearance_fold_work_process),
                    summary = stringResource(R.string.appearance_fold_work_process_summary),
                    items = foldLevelLabels,
                    selectedIndex = appearance.answerFoldLevel.ordinal,
                    onItemSelected = { index ->
                        AnswerFoldLevel.entries.getOrNull(index)?.let(onSetAnswerFoldLevel)
                    },
                )
            }
        }
    }

    // ⑧ 加载动效组（可展开子项）
    item(key = "m3_morph_loading") {
        EtaSegmentedColumn(
            modifier = Modifier.padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
        ) {
            item {
                EtaClickableRow(
                    leading = {
                        PreferenceIcon(icon = Icons.Rounded.Animation)
                    },
                    title = stringResource(R.string.appearance_morph_loading),
                    summary = when {
                        !appearance.morphLoadingIndicator ->
                        stringResource(R.string.appearance_morph_loading_summary)
                        appearance.morphLoadingBeforeResponseOnly ->
                        stringResource(R.string.appearance_morph_loading_before_response)
                        else ->
                        stringResource(R.string.appearance_morph_loading_during_generation)
                    },
                    showChevron = false,
                    onClick = {
                        if (appearance.morphLoadingIndicator) {
                            onMorphLoadingExpandedChange(!morphLoadingExpanded)
                        } else {
                            onSetMorphLoading(true)
                            onMorphLoadingExpandedChange(true)
                        }
                    },
                    holdDownState = appearance.morphLoadingIndicator && morphLoadingExpanded,
                    endActions = {
                        if (appearance.morphLoadingIndicator) {
                            EtaExpandChevron(
                                expanded = morphLoadingExpanded,
                                contentDescription = stringResource(
                                    if (morphLoadingExpanded) {
                                        R.string.appearance_morph_loading_collapse
                                    } else {
                                        R.string.appearance_morph_loading_expand
                                    },
                                ),
                            )
                            // 尾槽顺序统一：开关在最右、展开箭头在左，中间留 12dp。
                            Spacer(modifier = Modifier.width(EtaDimens.TrailingElementGap))
                        }
                        EtaSwitch(
                            checked = appearance.morphLoadingIndicator,
                            onCheckedChange = { enabled ->
                                onSetMorphLoading(enabled)
                                onMorphLoadingExpandedChange(enabled)
                            },
                        )
                    },
                )
            }
            // 子项作为同组的下一个分段（不再 dense 嵌进行内 bottomAction），
            // 首/末圆角由分段列按可见条目自动接管。
            item(visible = appearance.morphLoadingIndicator && morphLoadingExpanded) {
                EtaSwitchRow(
                    title = stringResource(R.string.appearance_morph_loading_before_response),
                    summary = stringResource(R.string.appearance_morph_loading_before_response_summary),
                    checked = appearance.morphLoadingBeforeResponseOnly,
                    onCheckedChange = onSetMorphBeforeResponse,
                )
            }
        }
    }

    // ⑨ 界面缩放滑杆卡（KernelSU TonalCard + Slider 布局）
    item(key = "m3_interface_scale") {
        EtaTonalCard(
            modifier = Modifier.padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
            // 整卡可点 → 精确输入对话框。
            onClick = onOpenScaleDialog,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.AspectRatio,
                        contentDescription = stringResource(R.string.appearance_interface_scale),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.appearance_interface_scale),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.appearance_interface_scale_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${scaleDraft.roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                EtaSlider(
                    value = scaleDraft.coerceIn(
                        MIN_INTERFACE_SCALE * 100f,
                        MAX_INTERFACE_SCALE * 100f,
                    ),
                    onValueChange = onScaleDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                    keyPoints = listOf(100f),
                    onValueChangeFinished = { onCommitScale(scaleDraft) },
                )
            }
        }
    }

}

/** 主题模式单钮的选中态 / 图标 / 无障碍名 / 点击动作。 */
private data class ThemeModeOption(
    val checked: Boolean,
    val icon: ImageVector,
    val label: String,
    val onSelect: () -> Unit,
)

/**
 * 主题模式连接按钮组（KernelSU `ExpressiveToggleButton` + `ButtonGroupDefaults.connected*Shapes`）。
 * 4 态 = 跟随系统 / 浅色 / 深色 / 纯黑（AMOLED）：纯黑态 = 深色 + 纯黑背景，点深色会关掉纯黑、
 * 点纯黑会切到深色并打开——数据仍是 themeMode + pureBlackEnabled 两个字段，无需迁移。
 */
@Composable
private fun ThemeModeButtonGroup(
    selectedMode: AppearanceThemeMode,
    pureBlackEnabled: Boolean,
    onSetThemeMode: (AppearanceThemeMode) -> Unit,
    onSetPureBlack: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    // 四种模式恒为一行（KernelSU 按 chunked(4) 分行，这里恰好只占一行）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val options = listOf(
            ThemeModeOption(
                checked = selectedMode == AppearanceThemeMode.SYSTEM,
                icon = Icons.Filled.Brightness4,
                label = stringResource(R.string.appearance_theme_system),
            ) {
                onSetThemeMode(AppearanceThemeMode.SYSTEM)
            },
            ThemeModeOption(
                checked = selectedMode == AppearanceThemeMode.LIGHT,
                icon = Icons.Filled.Brightness7,
                label = stringResource(R.string.appearance_theme_light),
            ) {
                onSetThemeMode(AppearanceThemeMode.LIGHT)
            },
            ThemeModeOption(
                checked = selectedMode == AppearanceThemeMode.DARK && !pureBlackEnabled,
                icon = Icons.Filled.Brightness3,
                label = stringResource(R.string.appearance_theme_dark),
            ) {
                onSetThemeMode(AppearanceThemeMode.DARK)
                onSetPureBlack(false)
            },
            ThemeModeOption(
                checked = selectedMode == AppearanceThemeMode.DARK && pureBlackEnabled,
                icon = Icons.Filled.Brightness1,
                // 与 KernelSU 不同处：这里给纯黑钮独立的无障碍名（复用「纯黑背景」文案），
                // 而不是和深色共用一个「深色」。
                label = stringResource(R.string.appearance_pure_black),
            ) {
                onSetThemeMode(AppearanceThemeMode.DARK)
                onSetPureBlack(true)
            },
        )
        options.forEachIndexed { index, option ->
            EtaExpressiveToggleButton(
                checked = option.checked,
                onCheckedChange = { checked ->
                    if (checked) {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        option.onSelect()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Icon(imageVector = option.icon, contentDescription = option.label)
            }
        }
    }
}

/**
 * KernelSU `ThemePreviewCard` 对应物：按候选配色渲染的缩略屏幕
 * （顶栏 + 次级卡 + 内容卡 + 底部导航栏），全部颜色显式传入候选色板。
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun ThemePreviewCard(
    appearance: AppearanceSettings,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    val screenRatio = screenWidth / screenHeight
    val colorScheme = appearance.previewM3ColorScheme()
    // 纯黑模式下预览背景同样压黑，与真实主题一致。
    val previewDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val previewContainer = if (appearance.pureBlackEnabled && previewDark) {
        Color.Black
    } else {
        colorScheme.surfaceContainer
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .aspectRatio(screenRatio),
            color = previewContainer,
            shape = MaterialTheme.shapes.largeIncreased,
            border = BorderStroke(1.dp, color = colorScheme.outlineVariant),
        ) {
            val content: @Composable ColumnScope.() -> Unit = {
                // 顶栏
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface,
                        )
                    }
                }

                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val showInfoCard = maxHeight >= 72.dp
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EtaTonalCard(
                            containerColor = colorScheme.secondaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            shape = MaterialTheme.shapes.small,
                        ) {}
                        if (showInfoCard) {
                            EtaTonalCard(
                                containerColor = colorScheme.surfaceBright,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                shape = MaterialTheme.shapes.small,
                            ) {}
                        }
                    }
                }
            }

            Column {
                content()

                // 底部导航栏
                Surface(
                    color = previewContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Filled.Home, null, tint = colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * KernelSU `ColorButtonMaterial` 对应物：72dp 色板按钮（20dp 圆角），
 * 48dp 双半圆色轮（primaryContainer / tertiaryContainer）+ 选中态缩放、描边圆环与勾选。
 */
@Composable
private fun ColorButtonM3(
    color: Color,
    isSelected: Boolean,
    appearance: AppearanceSettings,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val colorScheme = appearance.previewM3ColorScheme(seedOverride = color)

    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        shape = MaterialTheme.shapes.largeIncreased,
        color = colorScheme.surfaceContainer,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(48.dp)) {
                drawArc(
                    color = colorScheme.primaryContainer,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                )
                drawArc(
                    color = colorScheme.tertiaryContainer,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true,
                )
            }

            // 色板选中反馈是 chip 级小元素：缩放 / 显隐取 M3E fast 档，
            // 透明度走 Effects（无过冲），缩放走 Spatial（轻微过冲）。
            val swatchScaleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
            val swatchFadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.1f else 1.0f,
                animationSpec = swatchScaleSpec,
                label = "appearance_palette_scale",
            )
            Box(
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedVisibility(
                    visible = isSelected,
                    enter = fadeIn(animationSpec = swatchFadeSpec) +
                        scaleIn(animationSpec = swatchScaleSpec, initialScale = 0.8f),
                    exit = fadeOut(animationSpec = swatchFadeSpec) +
                        scaleOut(animationSpec = swatchScaleSpec, targetScale = 0.8f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .border(2.dp, colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(colorScheme.primary, CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = colorScheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(EtaDimens.StatusIconSize),
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = !isSelected,
                    enter = fadeIn(animationSpec = swatchFadeSpec) +
                        scaleIn(animationSpec = swatchScaleSpec, initialScale = 0.8f),
                    exit = fadeOut(animationSpec = swatchFadeSpec) +
                        scaleOut(animationSpec = swatchScaleSpec, targetScale = 0.8f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * 候选配色：与 [io.github.mangi.eta.ui.app.AgentAppTheme] 的 M3 分支同源 ——
 * material-color-utilities（Spec2025，materialkolor）生成的动态取色，经
 * [io.github.mangi.eta.ui.app.toEtaColorScheme]（真实主题唯一的色板映射）成 Material 3 色板，
 * 保证预览与真实主题逐项一致。
 *
 * @param seedOverride 传入固定种子色（色板按钮）；null 时按当前强调色设置解析。
 */
@Composable
private fun AppearanceSettings.previewM3ColorScheme(
    seedOverride: Color? = null,
): ColorScheme {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val keyColor = when {
        seedOverride != null && seedOverride != Color.Unspecified -> seedOverride
        seedOverride == Color.Unspecified ->
            platformDynamicPrimary(context, isDark)
        accentColor == AppearanceAccentColor.SYSTEM ->
            platformDynamicPrimary(context, isDark)
        else -> accentColor.seedColor()
    }
    val scheme = remember(keyColor.value, paletteStyle, isDark) {
        previewDynamicScheme(paletteStyle, keyColor.toArgb(), isDark)
    }
    // 预览不透传 pureBlackEnabled：纯黑预览由调用方按需把页面背景压黑（见 previewContainer），
    // 色板本身保持原色阶以便观察强调色效果。
    return scheme.toEtaColorScheme(isDark, pureBlackEnabled = false)
}

/** 预览取色链对比度（0.0 = 标准）。 */
private const val PREVIEW_CONTRAST_LEVEL = 0.0

/** 系统动态取色的 primary（Android 12+）；低版本回退到蓝色种子。 */
private fun platformDynamicPrimary(context: Context, isDark: Boolean): Color =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    } else {
        Color(0xFF3482FF)
    }

/** 由配色风格选择对应的 DynamicScheme 变体（Spec2025）。 */
private fun previewDynamicScheme(
    paletteStyle: AppearancePaletteStyle,
    seedArgb: Int,
    isDark: Boolean,
): DynamicScheme {
    val source = Hct.fromInt(seedArgb)
    val spec = ColorSpec.SpecVersion.SPEC_2025
    val platform = DynamicScheme.Platform.PHONE
    return when (paletteStyle) {
        AppearancePaletteStyle.TONAL_SPOT -> SchemeTonalSpot(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.NEUTRAL -> SchemeNeutral(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.VIBRANT -> SchemeVibrant(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.EXPRESSIVE -> SchemeExpressive(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.RAINBOW -> SchemeRainbow(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.FRUIT_SALAD -> SchemeFruitSalad(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.MONOCHROME -> SchemeMonochrome(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.FIDELITY -> SchemeFidelity(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.CONTENT -> SchemeContent(source, isDark, PREVIEW_CONTRAST_LEVEL, spec, platform)
    }
}

private val iconSwatches = listOf(
    0xFFF27A1A, 0xFF7B61FF, 0xFFF26D9A, 0xFF3DDC84, 0xFFF5C542, 0xFF2491FF,
    0xFFF6F7F9, 0xFF1C1C1E,
).map { it.toInt() }

@Composable
private fun IconAppearanceEditor(
    light: Int,
    dark: Int,
    onConfirm: (Int, Int) -> Unit,
) {
    var draftLight by remember(light) { androidx.compose.runtime.mutableIntStateOf(light) }
    var draftDark by remember(dark) { androidx.compose.runtime.mutableIntStateOf(dark) }
    val dirty = draftLight != light || draftDark != dark
    val view = LocalView.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // 预览：两张 56dp 图标并排；下面的色板行统一左对齐，不再整块居中、
        // 导致 Light / Dark 的标签与色板左边缘参差不齐。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            LauncherIconPreview(background = draftLight)
            LauncherIconPreview(background = draftDark)
        }
        IconColorChoices(stringResource(R.string.appearance_icon_light), draftLight) { draftLight = it }
        IconColorChoices(stringResource(R.string.appearance_icon_dark), draftDark) { draftDark = it }
        EtaTextButton(
            text = stringResource(R.string.appearance_icon_apply),
            enabled = dirty,
            // 收在右下角：确认动作不再拉成一条通栏大按钮。
            modifier = Modifier.align(Alignment.End),
            onClick = {
                TouchHaptics.click(view)
                onConfirm(draftLight, draftDark)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconColorChoices(title: String, selected: Int, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EtaText(text = title, secondary = true)
        // 8 个色板在窄屏（360dp）一行放不下：交给 FlowRow 换行，色板不会再被挤出屏幕右侧。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            iconSwatches.forEach { color ->
                // M3 Expressive 没有取色器组件：色板用官方 IconToggleButton 承载，
                // 容器直接填色值本身（KernelSU ColorButtonMaterial 的紧凑版），
                // 选中时在色块上叠一个对比色对勾；尺寸取官方 extraSmall 档（32dp）。
                val swatch = Color(color)
                val onSwatch = if (swatch.luminance() > 0.5f) Color.Black else Color.White
                IconToggleButton(
                    checked = color == selected,
                    onCheckedChange = {
                        TouchHaptics.click(view)
                        onSelect(color)
                    },
                    modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        containerColor = swatch,
                        contentColor = swatch,
                        disabledContainerColor = swatch,
                        disabledContentColor = swatch,
                        checkedContainerColor = swatch,
                        checkedContentColor = onSwatch,
                    ),
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    Icon(
                        imageVector = if (color == selected) Icons.Rounded.Check else Icons.Rounded.Circle,
                        // 色板没有文案：用色值作无障碍名称。
                        // 必须给：IconToggleButton 经 Surface 的 childSemantics() 合并子节点，
                        // 若这里是 contentDescription = null，8 个色块会变成「无名按钮」。
                        contentDescription = stringResource(R.string.appearance_color_a11y, "#%08X".format(color)),
                        modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                        // 未选中时对勾位用色值本身填满（与容器同色、不可见），
                        // 选中时换成黑/白对勾，保证任何底色上都看得清。
                        tint = if (color == selected) onSwatch else swatch,
                    )
                }
            }
        }
    }
}

@Composable
private fun LauncherIconPreview(
    background: Int,
) {
    val context = LocalContext.current
    val name = LauncherIconSync.resourceName(background)
    val id = context.resources.getIdentifier(name, "drawable", context.packageName)
    Box(
        modifier = Modifier
            .size(56.dp)
            // 官方形状令牌（medium = 12dp），不写死圆角。
            .clip(MaterialTheme.shapes.medium),
    ) {
        if (id != 0) {
            Image(
                painter = painterResource(id),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}
