// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see third_party/openminis-browser/NOTICE.md.
package io.github.mangi.eta.agent.browser.ported.ui.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.ported.browser.BrowserHistoryStore
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.agent.browser.ported.browser.UserAgentProfile
import io.github.mangi.eta.agent.browser.ported.logging.AppLogger
import io.github.mangi.eta.agent.browser.ported.ui.util.bringIntoViewOnFocus
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch

/**
 * 浏览器设置页（M3 Expressive）。
 *
 * 独立页面骨架 + [EtaScaffold] 页头；四个分区
 * （User Agent / Web Viewport / Idle Tab Eviction / Cookies）各自收进
 * [EtaSegmentedColumn] 分组，行用 [EtaSegmentedRadioRow] / [EtaSegmentedRow] /
 * [EtaSegmentedTextField]，按钮用官方 [TextButton] / [EtaTextButton]，
 * 预设尺寸用官方 [FilterChip]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsPage(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }

    val selectedProfile by tabPool.currentUserAgentProfile.collectAsState()
    var customUA by remember { mutableStateOf(prefs.getString("custom_user_agent", "") ?: "") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var idleTimeoutText by remember { mutableStateOf(tabPool.idleTimeoutMinutes.toString()) }

    // Viewport state — bound to the pool's flows so changes from other surfaces
    // (e.g. the agent calling set_viewport) reflect in this sheet immediately.
    val customWidthFromPool by tabPool.customViewportWidth.collectAsState()
    val customHeightFromPool by tabPool.customViewportHeight.collectAsState()
    var viewportWidthText by remember(customWidthFromPool) {
        mutableStateOf(if (customWidthFromPool > 0) customWidthFromPool.toString() else "")
    }
    var viewportHeightText by remember(customHeightFromPool) {
        mutableStateOf(if (customHeightFromPool > 0) customHeightFromPool.toString() else "")
    }
    val isCustomViewportActive = customWidthFromPool > 0 && customHeightFromPool > 0
    // Bug 1: decouple radio selection from pool state. The radio reflects what the
    // user picked *in the sheet*; Apply (or a preset tap) is what writes to the
    // pool. Without this, tapping Custom with empty width/height fields was a
    // no-op visually because isCustomViewportActive stayed false.
    var viewportMode by remember(isCustomViewportActive) {
        mutableStateOf(if (isCustomViewportActive) ViewportMode.CUSTOM else ViewportMode.DEFAULT)
    }
    var showCustomViewportEditor by remember { mutableStateOf(isCustomViewportActive) }

    // Cookie filter (mirrors iOS `.searchable("Filter by domain")`).
    var cookieFilterText by remember { mutableStateOf("") }
    val hasCookies = CookieManager.getInstance().hasCookies()
    val historyStore = remember { BrowserHistoryStore.getInstance(context) }
    val allDomains = remember(hasCookies) { historyStore.uniqueDomains() }
    val domains = remember(allDomains, cookieFilterText) {
        if (cookieFilterText.isBlank()) allDomains
        else allDomains.filter { it.contains(cookieFilterText.trim(), ignoreCase = true) }
    }

    EtaScaffold(
        title = stringResource(R.string.om_browser_settings_title),
        onBack = onDismiss,
        modifier = Modifier.fillMaxSize(),
    ) { padding, scrollConnection, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // [T-android-browser-settings-keyboard-overlap] imePadding
                // shrinks the page's content frame when the keyboard opens;
                // verticalScroll lets the focused TextField scroll into view.
                .nestedScroll(scrollConnection)
                .imePadding()
                .verticalScroll(rememberScrollState())
                // 页面铺到屏幕底缘：导航栏高度补在滚动内容末尾，滚到末尾时最后一项仍能避开手势条。
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            val groupPadding = Modifier.padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            )

            // ── User Agent ──
            // EtaSegmentedColumn 的 DSL 作用域不是 @Composable：字符串资源与
            // 警告文案在此（组合上下文）先解析，分组 DSL 内只读普通值。
            val notSetPlaceholder = stringResource(R.string.om_browser_settings_ua_not_set)
            val uaProfileLabels = UserAgentProfile.entries.associateWith { profile ->
                stringResource(
                    when (profile) {
                        UserAgentProfile.MOBILE_CHROME -> R.string.om_browser_settings_ua_mobile_chrome
                        UserAgentProfile.DESKTOP_CHROME -> R.string.om_browser_settings_ua_desktop_chrome
                        UserAgentProfile.CUSTOM -> R.string.om_browser_settings_ua_custom
                    },
                )
            }
            val uaMismatchText = uaMismatchWarning(selectedProfile, viewportWidthText)
            EtaSegmentedColumn(
                title = stringResource(R.string.om_browser_settings_user_agent),
                modifier = groupPadding,
            ) {
                for (profile in UserAgentProfile.entries) {
                    val label = uaProfileLabels.getValue(profile)
                    item(key = profile.name) {
                        EtaSegmentedRadioRow(
                            title = label,
                            summary = displayUA(profile, customUA, notSetPlaceholder),
                            selected = selectedProfile == profile,
                            onClick = {
                                tabPool.setUserAgentFromUI(
                                    profile,
                                    if (profile == UserAgentProfile.CUSTOM) customUA else null,
                                )
                            },
                        )
                    }
                }
                if (selectedProfile == UserAgentProfile.CUSTOM) {
                    item(key = "custom_ua") {
                        EtaSegmentedTextField(
                            label = stringResource(R.string.om_browser_settings_custom_ua_label),
                            value = customUA,
                            onValueChange = {
                                customUA = it
                                prefs.edit().putString("custom_user_agent", it).apply()
                            },
                            singleLine = false,
                        )
                    }
                    item(key = "custom_ua_apply", surface = false) {
                        SettingsApplyRow(
                            onClick = {
                                TouchHaptics.click(view)
                                tabPool.setUserAgentFromUI(UserAgentProfile.CUSTOM, customUA)
                            },
                        )
                    }
                }
            }

            // ── Web Viewport ──
            EtaSegmentedColumn(
                title = stringResource(R.string.om_browser_settings_viewport),
                modifier = groupPadding,
            ) {
                item(key = "viewport_default") {
                    val (uaW, uaH) = selectedProfile.viewportSize
                    EtaSegmentedRadioRow(
                        title = stringResource(R.string.om_browser_settings_viewport_default),
                        summary = "$uaW × $uaH",
                        selected = viewportMode == ViewportMode.DEFAULT,
                        onClick = {
                            viewportMode = ViewportMode.DEFAULT
                            AppLogger.info("BrowserSettings", "viewportMode → DEFAULT")
                            coroutineScope.launch {
                                tabPool.setGlobalViewport(0, 0)
                                AppLogger.info("BrowserSettings", "viewport set to UA default")
                            }
                            viewportWidthText = ""
                            viewportHeightText = ""
                            showCustomViewportEditor = false
                        },
                    )
                }
                item(key = "viewport_custom") {
                    EtaSegmentedRadioRow(
                        title = stringResource(R.string.om_browser_settings_viewport_custom),
                        summary = stringResource(R.string.om_browser_settings_viewport_custom_summary),
                        selected = viewportMode == ViewportMode.CUSTOM,
                        onClick = {
                            viewportMode = ViewportMode.CUSTOM
                            AppLogger.info("BrowserSettings", "viewportMode → CUSTOM")
                            showCustomViewportEditor = true
                            val w = viewportWidthText.toIntOrNull()
                            val h = viewportHeightText.toIntOrNull()
                            if (w != null && h != null && w > 0 && h > 0) {
                                coroutineScope.launch {
                                    tabPool.setGlobalViewport(
                                        w.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX),
                                        h.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX),
                                    )
                                    AppLogger.info("BrowserSettings", "viewport set to ${w}x$h (custom)")
                                }
                            }
                        },
                    )
                }
                if (showCustomViewportEditor) {
                    item(key = "viewport_width") {
                        EtaSegmentedTextField(
                            label = stringResource(R.string.om_browser_settings_viewport_width),
                            value = viewportWidthText,
                            onValueChange = {
                                viewportWidthText = it.filter { ch -> ch.isDigit() }.take(5)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            // [T-android-browser-settings-keyboard-overlap] focus →
                            // scroll-into-view so the soft keyboard doesn't cover
                            // the Custom viewport editor.
                            modifier = Modifier.bringIntoViewOnFocus(),
                        )
                    }
                    item(key = "viewport_height") {
                        EtaSegmentedTextField(
                            label = stringResource(R.string.om_browser_settings_viewport_height),
                            value = viewportHeightText,
                            onValueChange = {
                                viewportHeightText = it.filter { ch -> ch.isDigit() }.take(5)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.bringIntoViewOnFocus(),
                        )
                    }
                    item(key = "viewport_apply", surface = false) {
                        SettingsApplyRow(
                            onClick = {
                                TouchHaptics.click(view)
                                val w = viewportWidthText.toIntOrNull()
                                val h = viewportHeightText.toIntOrNull()
                                if (w != null && h != null && w > 0 && h > 0) {
                                    val cw = w.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX)
                                    val ch = h.coerceIn(VIEWPORT_MIN, VIEWPORT_MAX)
                                    viewportWidthText = cw.toString()
                                    viewportHeightText = ch.toString()
                                    viewportMode = ViewportMode.CUSTOM
                                    coroutineScope.launch {
                                        tabPool.setGlobalViewport(cw, ch)
                                        AppLogger.info("BrowserSettings", "viewport applied ${cw}x$ch")
                                    }
                                }
                            },
                        )
                    }
                    // UA mismatch banner — same 768px breakpoint as iOS.
                    // 文案已在上方组合作用域解析（uaMismatchText）。
                    if (uaMismatchText != null) {
                        item(key = "viewport_warning", surface = false) {
                            UaMismatchBanner(
                                message = uaMismatchText,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item(key = "viewport_presets", surface = false) {
                        ViewportPresetRow(
                            widthText = viewportWidthText,
                            heightText = viewportHeightText,
                            onPreset = { w, h ->
                                viewportWidthText = w.toString()
                                viewportHeightText = h.toString()
                                viewportMode = ViewportMode.CUSTOM
                                coroutineScope.launch {
                                    tabPool.setGlobalViewport(w, h)
                                    AppLogger.info("BrowserSettings", "viewport preset ${w}x$h")
                                }
                            },
                        )
                    }
                }
                item(key = "viewport_summary", surface = false) {
                    val (resW, resH) = tabPool.resolvedViewportSize()
                    Text(
                        text = if (isCustomViewportActive) {
                            stringResource(
                                R.string.om_browser_settings_viewport_using_custom,
                                resW,
                                resH,
                            )
                        } else {
                            stringResource(
                                R.string.om_browser_settings_viewport_using_default,
                                resW,
                                resH,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // ── Idle Tab Eviction ──
            EtaSegmentedColumn(
                title = stringResource(R.string.om_browser_settings_idle_timeout),
                modifier = groupPadding,
            ) {
                item(key = "idle_desc", surface = false) {
                    Text(
                        text = stringResource(R.string.om_browser_settings_idle_timeout_desc) + " " +
                            "(default ${BrowserTabPool.DEFAULT_IDLE_TIMEOUT_MINUTES}, range " +
                            "${BrowserTabPool.MIN_IDLE_TIMEOUT_MINUTES}–${BrowserTabPool.MAX_IDLE_TIMEOUT_MINUTES})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item(key = "idle_input") {
                    EtaSegmentedTextField(
                        label = stringResource(R.string.om_browser_settings_minutes_label),
                        value = idleTimeoutText,
                        onValueChange = { new -> idleTimeoutText = new.filter { it.isDigit() }.take(3) },
                        singleLine = true,
                        // [T-android-browser-settings-keyboard-overlap] this
                        // field sits near the bottom of the sheet — without
                        // scroll-into-view it sits behind the soft keyboard
                        // every time the user taps it.
                        modifier = Modifier.bringIntoViewOnFocus(),
                    )
                }
                item(key = "idle_apply", surface = false) {
                    SettingsApplyRow(
                        onClick = {
                            TouchHaptics.click(view)
                            val minutes = idleTimeoutText.toIntOrNull()
                                ?: BrowserTabPool.DEFAULT_IDLE_TIMEOUT_MINUTES
                            tabPool.setIdleTimeoutMinutes(minutes)
                            // Reflect clamping back into the field.
                            idleTimeoutText = tabPool.idleTimeoutMinutes.toString()
                        },
                    )
                }
            }

            // ── Cookies & Website Data ──
            EtaSegmentedColumn(
                title = stringResource(R.string.om_browser_settings_cookies_title),
                modifier = groupPadding,
            ) {
                if (allDomains.isNotEmpty()) {
                    item(key = "cookie_filter") {
                        EtaSegmentedTextField(
                            label = stringResource(R.string.om_browser_settings_filter_by_domain),
                            value = cookieFilterText,
                            onValueChange = { cookieFilterText = it },
                            singleLine = true,
                        )
                    }
                }
                if (domains.isEmpty()) {
                    item(key = "cookies_empty", surface = false) {
                        Text(
                            text = when {
                                cookieFilterText.isNotBlank() -> stringResource(
                                    R.string.om_browser_settings_no_domains_match,
                                    cookieFilterText,
                                )
                                hasCookies -> stringResource(R.string.om_browser_settings_cookies_stored)
                                else -> stringResource(R.string.om_browser_settings_no_cookies)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    domains.take(MAX_COOKIE_DOMAIN_ROWS).forEach { domain ->
                        item(key = "domain_$domain") {
                            EtaSegmentedRow(
                                modifier = Modifier.fillMaxWidth(),
                                content = {
                                    Text(
                                        text = domain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = { clearCookiesForDomain(domain) },
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = stringResource(
                                                R.string.om_browser_settings_clear_cookies_for,
                                                domain,
                                            ),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                item(key = "cookies_clear_all", surface = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        TextButton(
                            onClick = { showClearConfirm = true },
                            enabled = hasCookies,
                        ) {
                            Text(
                                text = stringResource(R.string.om_browser_settings_clear_all_cookies),
                                color = if (hasCookies) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearConfirm) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.om_browser_settings_clear_all_dialog_title),
            summary = stringResource(R.string.om_browser_settings_clear_all_dialog_message),
            onDismissRequest = { showClearConfirm = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.om_browser_settings_clear),
                    destructive = true,
                    confirmEnabled = hasCookies,
                    onCancel = { showClearConfirm = false },
                    onConfirm = {
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        showClearConfirm = false
                    },
                )
            },
        ) {
        }
    }
}

/** 分区内的「应用」按钮行：右对齐官方 [EtaTextButton]。 */
@Composable
private fun SettingsApplyRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        EtaTextButton(
            text = stringResource(R.string.om_browser_settings_apply),
            onClick = onClick,
        )
    }
}

/** Local UI state for the viewport radios — separate from the pool's
 *  customViewportWidth/Height because the radio reflects the user's *intent*
 *  (which option is highlighted), and the pool reflects the *committed*
 *  override. Tapping Custom flips the radio immediately even if no width/
 *  height has been entered yet. */
private enum class ViewportMode { DEFAULT, CUSTOM }

/** UA list subtitle: full UA string in monospace, or "Not set" when the
 *  Custom slot is empty. Mirrors the iOS sheet which shows the full UA below
 *  each radio so the user can verify exactly what's being sent. */
private fun displayUA(profile: UserAgentProfile, customUA: String, notSetPlaceholder: String): String =
    when (profile) {
        UserAgentProfile.CUSTOM -> customUA.ifBlank { notSetPlaceholder }
        else -> profile.userAgentString.orEmpty()
    }

// ── Viewport ──────────────────────────────────────────────────────────────

/** Min / max permitted viewport dimensions. Wider than typical phone screens
 *  but bounded so the user can't accidentally enter `99999` and DOS the
 *  WebView's layout pass. Mirrors a similar implicit clamp on iOS where the
 *  user types into a 72pt-wide field. */
private const val VIEWPORT_MIN = 320
private const val VIEWPORT_MAX = 2560

/** UA-vs-viewport breakpoint copied from iOS BrowserManagementView. Sites
 *  using UA-sniffing will hand back the wrong layout when these cross. */
private const val VIEWPORT_UA_BREAKPOINT = 768

/** 域名列表一次最多展示的行数，其余靠上方的域名过滤框缩小范围。 */
private const val MAX_COOKIE_DOMAIN_ROWS = 6

private data class ViewportPreset(val labelRes: Int, val width: Int, val height: Int)

private val viewportPresets = listOf(
    ViewportPreset(R.string.om_browser_settings_preset_phone, 412, 915),
    ViewportPreset(R.string.om_browser_settings_preset_phone_pro, 430, 932),
    ViewportPreset(R.string.om_browser_settings_preset_tablet, 820, 1180),
    ViewportPreset(R.string.om_browser_settings_preset_laptop, 1280, 800),
    ViewportPreset(R.string.om_browser_settings_preset_desktop, 1440, 900),
    ViewportPreset(R.string.om_browser_settings_preset_full_hd, 1920, 1080),
)

/** 快速预设：官方 [FilterChip] 横向滚动排布，选中态由当前输入决定。 */
@Composable
private fun ViewportPresetRow(
    widthText: String,
    heightText: String,
    onPreset: (Int, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.om_browser_settings_quick_set),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (preset in viewportPresets) {
                val active = widthText.toIntOrNull() == preset.width &&
                    heightText.toIntOrNull() == preset.height
                FilterChip(
                    selected = active,
                    onClick = { onPreset(preset.width, preset.height) },
                    label = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = stringResource(preset.labelRes))
                            Text(
                                text = "${preset.width} × ${preset.height}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    },
                )
            }
        }
    }
}

/** UA/视口不匹配时的提示文案（与上游同一 768px 断点）。 */
@Composable
private fun uaMismatchWarning(profile: UserAgentProfile, widthText: String): String? {
    val width = widthText.toIntOrNull() ?: return null
    if (width <= 0) return null
    return when (profile) {
        UserAgentProfile.MOBILE_CHROME, UserAgentProfile.CUSTOM ->
            if (width >= VIEWPORT_UA_BREAKPOINT) {
                stringResource(
                    R.string.om_browser_settings_ua_mismatch_mobile,
                    VIEWPORT_UA_BREAKPOINT,
                )
            } else {
                null
            }
        UserAgentProfile.DESKTOP_CHROME ->
            if (width < VIEWPORT_UA_BREAKPOINT) {
                stringResource(
                    R.string.om_browser_settings_ua_mismatch_desktop,
                    VIEWPORT_UA_BREAKPOINT,
                )
            } else {
                null
            }
    }
}

/** UA/视口不匹配提示条：官方 [Surface] 容器 + 官方 tertiaryContainer 角色色。 */
@Composable
private fun UaMismatchBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                modifier = Modifier.size(EtaDimens.DenseIconSize),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 清掉某个域名的 Cookie（与上游实现一致：逐条 setCookie 过期 + flush）。 */
private fun clearCookiesForDomain(domain: String) {
    val cookieManager = CookieManager.getInstance()
    val url = "https://$domain/"
    cookieManager.getCookie(url).orEmpty().split(';').forEach { cookie ->
        val name = cookie.substringBefore('=').trim()
        if (name.isNotEmpty()) {
            cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure")
            cookieManager.setCookie(url, "$name=; Max-Age=0; Domain=$domain; Secure")
        }
    }
    cookieManager.flush()
}
