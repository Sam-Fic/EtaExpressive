package io.github.mangi.eta.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalPlatformDensity

/** 宽屏起点：与 Material 3 的展开态断点一致，平板横屏落在这一档。 */
val WideScreenMinWidth = 600.dp

/** 宽屏下内容列的最大宽度：文字与设置列表超过这个宽度就只加两侧留白，不再铺满。 */
val MaxPageContentWidth = 800.dp

/** 首页空态建议形状组的最大宽度：平板横屏下形状不再随屏幕一起放大。 */
val MaxChatSuggestionWidth = 440.dp

/**
 * 宽屏二级页左侧索引栏的最大宽度：与 [io.github.mangi.eta.ui.components.ConversationSidePaneScaffold]
 * 的 standard side sheet 同口径（360dp 上限、窗口刚过断点时按 0.46 比例收窄）。
 * 不直接复用该文件里的 `DrawerMetrics`：它是 private，且宽屏尺寸令牌应集中在本文件。
 */
val SettingsSideRailMaxWidth = 360.dp

/** 索引栏在窄一点的宽屏上按可用宽度的比例收窄，避免把右侧内容挤到放不下一行设置项。 */
val SettingsSideRailWidthFraction = 0.46f

@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return isWideScreen(containerSize.width, density)
}

internal fun isWideScreen(containerWidthPx: Int, density: androidx.compose.ui.unit.Density): Boolean =
    with(density) { containerWidthPx.toDp() >= WideScreenMinWidth }

/** 按可用宽度计算内容列左右留白：宽度不超过 [MaxPageContentWidth] 时不留白。 */
fun pageContentSidePadding(width: Dp): Dp =
    if (width >= WideScreenMinWidth) {
        ((width - MaxPageContentWidth) / 2).coerceAtLeast(0.dp)
    } else {
        0.dp
    }

/**
 * 宿主注入的「当前内容列可用宽度」。
 *
 * 侧栏与对话并排时，对话列不再占满窗口，宽度要按被挤窄后的实际宽度算；
 * 为空表示没有人覆盖，按窗口宽度计算。
 */
val LocalPageContentWidth = staticCompositionLocalOf<Dp?> { null }

/**
 * 当前窗口下内容列的左右留白。
 *
 * 供 LazyColumn 的 `contentPadding` 等拿不到父布局宽度的场景使用；
 * 列表本身仍铺满全宽，保证宽屏两侧不会出现滚动死区。
 */
@Composable
fun rememberPageContentSidePadding(): Dp {
    val overridden = LocalPageContentWidth.current
    if (overridden != null) return pageContentSidePadding(overridden)
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    return with(density) { pageContentSidePadding(containerSize.width.toDp()) }
}

/**
 * 列表本身保持全宽，只把内容限制在居中的最大宽度内，避免宽屏两侧形成滚动死区。
 */
@Composable
fun WidePageContent(
    modifier: Modifier = Modifier,
    content: @Composable (sidePadding: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        content(pageContentSidePadding(maxWidth))
    }
}

/**
 * 二级页内容只补水平方向的屏幕缺口与手势区；顶部由 TopAppBar、底部由页面末尾留白负责。
 */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier = windowInsetsPadding(
    WindowInsets.displayCutout
        .union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal),
)
