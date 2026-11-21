// `pickup_code` 执行器：Eta 自有取餐码记录的读写口。
// 形态对齐 AgentMediaSearchTools（action 分发 + 统一 JSON 错误码）；存储是
// PickupCodeRepository（原生 SQLite，低频个人记录）。

package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.data.repository.PickupCodeRepository
import io.github.mangi.eta.data.repository.PickupCodeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class AgentPickupCodeTools(private val context: Context) {

    fun execute(args: JSONObject): String = io {
        try {
            when (val action = args.optString("action").trim()) {
                "save" -> save(args)
                "search" -> search(args)
                "list" -> list(args)
                "history" -> history(args)
                "complete" -> complete(args)
                "delete" -> delete(args)
                else -> error("INVALID_ARGUMENT", "action 取值无效：$action")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (invalid: IllegalArgumentException) {
            error("INVALID_ARGUMENT", invalid.message ?: "参数无效")
        } catch (failure: Throwable) {
            error("INTERNAL_ERROR", failure.message ?: "取餐码记录操作失败")
        }
    }

    private fun save(args: JSONObject): String {
        val code = args.optString("code").trim()
        if (code.isEmpty()) return error("INVALID_ARGUMENT", "action=save 需要 code")
        val orderType = args.optString("order_type", "餐食").ifBlank { "餐食" }
        val brandName = args.optString("brand_name").trim().takeIf { it.isNotEmpty() }
        val location = args.optString("pickup_location").trim().takeIf { it.isNotEmpty() }
        val repository = repository()
        // 幂等：同码未完成记录直接返回已有行，避免模型在多轮里重复落库。
        repository.findUnfinishedByCode(code)?.let { existing ->
            return JSONObject()
                .put("ok", true)
                .put("action", "save")
                .put("duplicate", true)
                .put("record", recordJson(existing))
                .toString()
        }
        val id = repository.insert(
            code = code,
            orderType = orderType,
            brandName = brandName,
            pickupLocation = location,
            source = PickupCodeSource.AGENT,
            sourcePackage = null,
            fullText = null,
        )
        return JSONObject()
            .put("ok", true)
            .put("action", "save")
            .put("duplicate", false)
            .put("id", id)
            .put("code", code)
            .toString()
    }

    private fun search(args: JSONObject): String {
        val query = args.optString("query").ifBlank {
            args.optString("code").trim()
        }
        val records = repository().search(query, parseLimit(args))
        return recordsJson("search", query, records)
    }

    private fun list(args: JSONObject): String =
        recordsJson("list", null, repository().pending(parseLimit(args)))

    private fun history(args: JSONObject): String =
        recordsJson("history", null, repository().completed(parseLimit(args)))

    private fun complete(args: JSONObject): String {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return error("INVALID_ARGUMENT", "action=complete 需要 id")
        val changed = repository().markCompleted(id)
        return JSONObject()
            .put("ok", true)
            .put("action", "complete")
            .put("id", id)
            .put("changed", changed)
            .toString()
    }

    private fun delete(args: JSONObject): String {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return error("INVALID_ARGUMENT", "action=delete 需要 id")
        val changed = repository().delete(id)
        return JSONObject()
            .put("ok", true)
            .put("action", "delete")
            .put("id", id)
            .put("changed", changed)
            .toString()
    }

    private fun recordsJson(action: String, query: String?, records: List<io.github.mangi.eta.data.repository.PickupCodeRecord>): String {
        val items = JSONArray().also { array -> records.forEach { array.put(recordJson(it)) } }
        return JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("query", query)
            .put("count", items.length())
            .put("records", items)
            .toString()
    }

    private fun recordJson(record: io.github.mangi.eta.data.repository.PickupCodeRecord): JSONObject =
        JSONObject()
            .put("id", record.id)
            .put("code", record.code)
            .put("order_type", record.orderType)
            .put("brand_name", record.brandName)
            .put("pickup_location", record.pickupLocation)
            .put("source", record.source.name.lowercase())
            .put("created_at", record.createdAt)
            .put("is_completed", record.isCompleted)
            .put("completed_at", record.completedAt)

    private fun parseLimit(args: JSONObject): Int =
        args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    private fun repository(): PickupCodeRepository = PickupCodeRepository(context)

    private fun io(block: suspend () -> String): String = runBlocking(Dispatchers.IO) { block() }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
    }
}
