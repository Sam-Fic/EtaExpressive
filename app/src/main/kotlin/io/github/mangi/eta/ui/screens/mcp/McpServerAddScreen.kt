package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** JSON 导入解析失败的原因，UI 层按语言映射文案（解析器本身不持字符串）。 */
internal enum class McpJsonImportError { MALFORMED, UNSUPPORTED_STDIO, MISSING_URL, URL_INVALID }

/** JSON 导入解析结果：[McpServerJsonImport.Success.name] / [McpServerJsonImport.Success.token] 可缺省由用户自填。 */
internal sealed interface McpServerJsonImport {
    data class Success(val name: String?, val url: String, val token: String?) : McpServerJsonImport

    data class Failure(val error: McpJsonImportError) : McpServerJsonImport
}

/**
 * 解析接入文档里的标准 MCP 服务器 JSON 片段（可选 `mcpServers` 包裹层）。
 *
 * 支持形态：
 * - `{"mcpServers": {"<名>": {"url": …, "headers": {"Authorization": "Bearer …"}}}}`——
 *   键名即服务器名；含多个条目时取第 1 个（文档常一段给一台，多台可再贴一次）；
 * - 裸对象 `{"url": …}`，名字取可选的 `name` 字段。
 *
 * 本应用只接远程 URL 型：只有 `command`/`args` 的本地 stdio 条目报
 * [McpJsonImportError.UNSUPPORTED_STDIO]。这里只做结构解析，不校验 URL 合法性——
 * 校验走提交前同款 `validateMcpEndpoint` 门槛，由 UI 层补一道。
 */
internal fun parseMcpServerJson(text: String): McpServerJsonImport {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return McpServerJsonImport.Failure(McpJsonImportError.MALFORMED)
    val root = runCatching { JSONObject(trimmed) }
        .getOrElse { return McpServerJsonImport.Failure(McpJsonImportError.MALFORMED) }
    var name: String? = null
    val entry: JSONObject = if (root.has("mcpServers")) {
        val servers = root.optJSONObject("mcpServers")
            ?: return McpServerJsonImport.Failure(McpJsonImportError.MALFORMED)
        val firstKey = servers.keys().asSequence().firstOrNull()
            ?: return McpServerJsonImport.Failure(McpJsonImportError.MALFORMED)
        name = firstKey.trim().takeIf(String::isNotEmpty)
        servers.optJSONObject(firstKey)
            ?: return McpServerJsonImport.Failure(McpJsonImportError.MALFORMED)
    } else {
        name = root.optString("name").trim().takeIf(String::isNotEmpty)
        root
    }
    // 远程 URL 型优先：url 缺失时若带 command 即 stdio 条目，否则视为缺 url。
    val url = entry.optString("url").trim()
    if (url.isEmpty()) {
        return if (entry.has("command")) {
            McpServerJsonImport.Failure(McpJsonImportError.UNSUPPORTED_STDIO)
        } else {
            McpServerJsonImport.Failure(McpJsonImportError.MISSING_URL)
        }
    }
    val token = entry.optJSONObject("headers")
        ?.let { headers ->
            headers.keys().asSequence()
                .firstOrNull { it.equals("authorization", ignoreCase = true) }
                ?.let { headers.optString(it).trim() }
        }
        ?.takeIf(String::isNotEmpty)
        ?.let { value ->
            // 「Bearer <token>」去前缀；裸值原样当 token。
            if (value.startsWith("Bearer ", ignoreCase = true)) value.substring("Bearer ".length).trim() else value
        }
        ?.takeIf(String::isNotEmpty)
    return McpServerJsonImport.Success(name, url, token)
}

/**
 * MCP 添加服务器整屏路由页（`AppRoute.McpServerAdd`）。
 *
 * 表单 + 连接测试 + 错误回显是一次完整提交流程而非一次决策，按 `docs/RULES.md` §3.3
 * 做成路由，进出场交给 miuix `NavDisplay` 程序化转场，不再走 `EtaPageSurface` 窗口。
 * 成功落库后 Toast 并返回（列表经 serversFlow 自动刷新）；失败留在本页回显错误。
 */
@Composable
internal fun McpServerAddScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    // 连接测试是网络往返，进行中锁住整页；进程重建不值得续跑一个网络请求，维持非 saveable。
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // JSON 导入：接入文档常给整段配置，粘贴即自动解析填入下方表单；解析失败在 supportingText 提示。
    var jsonText by rememberSaveable { mutableStateOf("") }
    val trimmedJson = jsonText.trim()
    val jsonImport = remember(trimmedJson) {
        if (trimmedJson.isEmpty()) null else parseMcpServerJson(trimmedJson)
    }
    // 结构解析成功后按提交同款门槛校验 URL，让「已解析」名副其实。
    val jsonImportEffective = remember(jsonImport) {
        when (jsonImport) {
            null -> null
            is McpServerJsonImport.Failure -> jsonImport
            is McpServerJsonImport.Success -> runCatching { validateMcpEndpoint(jsonImport.url) }
                .fold(
                    onSuccess = { jsonImport },
                    onFailure = { McpServerJsonImport.Failure(McpJsonImportError.URL_INVALID) },
                )
        }
    }
    LaunchedEffect(jsonImportEffective) {
        val parsed = jsonImportEffective as? McpServerJsonImport.Success ?: return@LaunchedEffect
        parsed.name?.let { name = it }
        url = parsed.url
        parsed.token?.let { token = it }
    }
    val jsonSupportingText = when (val parsed = jsonImportEffective) {
        null -> null
        is McpServerJsonImport.Failure -> stringResource(
            when (parsed.error) {
                McpJsonImportError.MALFORMED -> R.string.mcp_json_invalid
                McpJsonImportError.UNSUPPORTED_STDIO -> R.string.mcp_json_stdio_unsupported
                McpJsonImportError.MISSING_URL -> R.string.mcp_json_missing_url
                McpJsonImportError.URL_INVALID -> R.string.mcp_json_url_invalid
            },
        )
        is McpServerJsonImport.Success -> stringResource(R.string.mcp_json_parsed, parsed.name ?: parsed.url)
    }
    val onSubmit: () -> Unit = {
        val normalizedName = name.trim()
        val normalizedUrl = url.trim()
        error = when {
            normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
            else -> runCatching { validateMcpEndpoint(normalizedUrl) }
                .exceptionOrNull()?.message
        }
        if (error == null) scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val draft = McpServerSetting(
                        id = "",
                        name = normalizedName,
                        url = normalizedUrl,
                        protocolMode = McpProtocolMode.AUTO,
                        authorizationType = if (token.isBlank()) {
                            McpAuthorizationType.NONE
                        } else {
                            McpAuthorizationType.BEARER
                        },
                    )
                    val discovered = McpServerManager.discover(draft, token)
                    McpServerRepository.add(discovered, token)
                }
            }
            working = false
            result.onSuccess {
                Toast.makeText(
                    context,
                    resources.getString(R.string.mcp_server_added, it.tools.size),
                    Toast.LENGTH_SHORT,
                ).show()
                onBack()
            }.onFailure {
                error = it.message ?: resources.getString(R.string.mcp_connection_failed)
            }
        }
    }
    EtaScaffold(
        title = stringResource(R.string.mcp_add_server),
        onBack = { if (!working) onBack() },
        modifier = Modifier.fillMaxSize().imePadding(),
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                EtaDialogActions(
                    modifier = Modifier.navigationBarsPadding().imePadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    confirmText = if (working) {
                        stringResource(R.string.mcp_connecting)
                    } else {
                        stringResource(R.string.mcp_add_server)
                    },
                    confirmEnabled = !working,
                    cancelEnabled = !working,
                    onCancel = { if (!working) onBack() },
                    onConfirm = onSubmit,
                )
            }
        },
    ) { padding, scrollConnection, _ ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .nestedScroll(scrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            // JSON 导入框：官方 OutlinedTextField（label/textStyle/supportingText/isError/minLines/
            // maxLines 均取本仓 material3 1.5.0-alpha29 sources 取证的真实参数，TextField.kt:385
            // 的 maxLines 默认值与 1<=minLines<=maxLines 约束满足）。等宽字体——JSON 是机器格式。
            OutlinedTextField(
                value = jsonText,
                onValueChange = { jsonText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { M3Text(text = stringResource(R.string.mcp_json_import_label)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = jsonImportEffective is McpServerJsonImport.Failure,
                supportingText = if (jsonSupportingText != null) {
                    {
                        M3Text(
                            text = jsonSupportingText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (jsonImportEffective is McpServerJsonImport.Failure) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                } else {
                    null
                },
                minLines = 3,
                maxLines = 8,
                enabled = !working,
            )
            McpTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.mcp_server_name),
                enabled = !working,
            )
            McpTextField(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.mcp_server_url),
                enabled = !working,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            McpTextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.mcp_bearer_optional),
                enabled = !working,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            // 连接测试是网络往返：无分母走不定型，按钮与表单同步锁住（底层不可中断，不给假取消）。
            if (working) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(EtaDimens.TrailingElementGap),
                ) {
                    EtaLoadingSlot(progress = null, size = EtaDimens.DenseIconSize)
                    M3Text(
                        text = stringResource(R.string.mcp_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let {
                M3Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
