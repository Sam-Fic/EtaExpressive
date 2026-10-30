package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.data.model.CustomBody
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Preserve incomplete JSON in the UI draft, but never persist it as a provider field. */
internal data class ProviderBodyDraft(
    val id: String = UUID.randomUUID().toString(),
    val key: String = "",
    val valueJson: String = "",
)

internal fun parseProviderBodies(rows: List<ProviderBodyDraft>): Result<List<CustomBody>> = runCatching {
    val keys = mutableSetOf<String>()
    rows.mapIndexed { index, row ->
        val key = row.key.trim()
        require(key.isNotEmpty()) { "第 ${index + 1} 项请求体字段名不能为空" }
        require(keys.add(key)) { "第 ${index + 1} 项请求体字段名重复" }
        val value = runCatching {
            Json.parseToJsonElement(row.valueJson).also { require(it.hasStrictJsonLiterals()) }
        }.getOrElse {
            // Do not expose entered JSON or parser diagnostics: it may contain secrets.
            throw IllegalArgumentException("第 ${index + 1} 项值不是有效 JSON；字符串请加双引号，如 \"1h\"")
        }
        CustomBody(key, value)
    }
}

private val jsonNumber = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

private fun JsonElement.hasStrictJsonLiterals(): Boolean = when (this) {
    is JsonObject -> values.all { it.hasStrictJsonLiterals() }
    is JsonArray -> all { it.hasStrictJsonLiterals() }
    JsonNull -> true
    is JsonPrimitive -> isString || content == "true" || content == "false" || jsonNumber.matches(content)
}
