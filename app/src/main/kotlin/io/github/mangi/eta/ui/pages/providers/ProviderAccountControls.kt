package io.github.mangi.eta.ui.pages.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextStyle

@Composable
internal fun ProviderAccountControls(
    state: ProviderLoginState,
    authMode: String,
    enabled: Boolean,
    browserError: String?,
    onOpenBrowser: () -> Unit,
    onManageUsage: () -> Unit,
    onLogin: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val prompt = state.prompt
    val account = state.account
    val codex = authMode == ProviderAuthModes.OPENAI_CHATGPT
    val brand = if (codex) "Codex" else "Grok"
    val loggedIn = account != null && !account.needsReauthentication
    val needsLogin = !loggedIn || !account.planEnabled
    val summary = browserError ?: state.errorMessage ?: when (state.notice) {
        ProviderLoginNotice.Interrupted -> stringResource(R.string.provider_account_login_interrupted)
        ProviderLoginNotice.LoadFailed -> stringResource(R.string.provider_account_load_failed)
        ProviderLoginNotice.LoginFailed -> stringResource(R.string.provider_account_login_failed)
        ProviderLoginNotice.DisconnectFailed -> stringResource(R.string.provider_account_disconnect_failed)
        ProviderLoginNotice.SyncFailed -> stringResource(R.string.provider_account_sync_failed)
        ProviderLoginNotice.SignedOut -> stringResource(R.string.provider_account_signed_out)
        ProviderLoginNotice.SignedOutRemoteUnconfirmed ->
            stringResource(R.string.provider_account_signed_out_remote_unconfirmed)
        ProviderLoginNotice.Synced ->
            context.getString(R.string.provider_account_models_synced, state.syncedModelCount)
        null -> when (state.phase) {
            ProviderLoginPhase.Loading -> stringResource(R.string.provider_account_loading)
            ProviderLoginPhase.Saving -> stringResource(R.string.provider_account_saving)
            ProviderLoginPhase.Disconnecting -> stringResource(R.string.provider_account_disconnecting)
            ProviderLoginPhase.Authorizing -> if (prompt == null) {
                stringResource(R.string.provider_account_preparing_auth)
            } else {
                stringResource(R.string.provider_account_authorize_in_browser)
            }
            ProviderLoginPhase.SyncingModels -> stringResource(R.string.provider_account_syncing_models)
            ProviderLoginPhase.Idle -> when {
                account == null -> stringResource(
                    if (codex) R.string.provider_account_sign_in_codex else R.string.provider_account_sign_in_grok,
                )
                account.needsReauthentication -> stringResource(R.string.provider_account_reauth_required)
                !account.planEnabled -> stringResource(R.string.provider_account_plan_disabled)
                codex -> stringResource(R.string.provider_account_signed_in_codex)
                else -> stringResource(R.string.provider_account_signed_in_grok)
            }
        }
    }
    EtaText(
        text = account?.email ?: account?.displayName ?: context.getString(R.string.provider_account_label, brand),
        style = EtaTextStyle.Body,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    EtaText(
        text = summary,
        style = EtaTextStyle.Secondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    )
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        prompt?.userCode?.let { userCode ->
            SelectionContainer {
                M3Text(
                    text = userCode,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isBusy) {
                if (prompt != null) {
                    EtaTextButton(
                        text = stringResource(R.string.provider_account_open_browser),
                        onClick = onOpenBrowser,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.phase in setOf(
                        ProviderLoginPhase.Saving,
                        ProviderLoginPhase.Authorizing,
                        ProviderLoginPhase.SyncingModels,
                    )
                ) {
                    EtaTextButton(
                        text = stringResource(R.string.provider_account_cancel),
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                if (needsLogin) {
                    EtaTextButton(
                        text = if (loggedIn) {
                            stringResource(R.string.provider_account_relogin)
                        } else {
                            context.getString(R.string.provider_account_sign_in_with, brand)
                        },
                        enabled = enabled,
                        onClick = onLogin,
                        modifier = Modifier.weight(1f),
                    )
                } else if (codex) {
                    EtaTextButton(
                        text = stringResource(R.string.provider_account_manage_usage),
                        enabled = enabled,
                        onClick = onManageUsage,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (loggedIn) {
                    EtaTextButton(
                        text = stringResource(R.string.provider_account_sign_out),
                        enabled = enabled,
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

internal fun openProviderAccountLink(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    context.getString(R.string.provider_account_no_browser)
} catch (_: SecurityException) {
    context.getString(R.string.provider_account_browser_blocked)
}
