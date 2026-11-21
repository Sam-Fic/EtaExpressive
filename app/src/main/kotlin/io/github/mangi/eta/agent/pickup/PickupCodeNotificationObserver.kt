// 通知监听 → 取餐码自动拦截。
// 通道编排移植自 badnng/Hyper-pick-up-code（AGPL-3.0-only）
//   com.Badnng.moe.service.NotificationListenerRecognitionService（全量通知文本抽码 +
//   5 秒窗口去重），合规登记见 docs/THIRD_PARTY_NOTICES.md。
// Eta 差异：无分组/手表同步；识别前先做关键词预筛，避免对每条通知都跑规则引擎。

package io.github.mangi.eta.agent.pickup

import android.content.Context
import android.util.Log
import io.github.mangi.eta.data.repository.PickupCodeRepository
import io.github.mangi.eta.data.repository.PickupCodeSource
import io.github.mangi.eta.agent.pickup.rules.SimpleRuleSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 挂在 [io.github.mangi.eta.agent.device.AgentNotificationHistoryService] 上的自动拦截器。
 *
 * fire-and-forget：record() 在 binder 线程上被系统高频调用，本类自带 IO scope，
 * 抽码、入库与发通知全部异步执行，不阻塞通知历史写入。
 */
internal object PickupCodeNotificationObserver {
    private const val TAG = "PickupCodeObserver"

    /** 关键词预筛：通知文本不含任何触发词时直接跳过，不进规则引擎。 */
    private val TRIGGER_KEYWORDS = listOf(
        "取餐", "取件", "取货", "提货", "餐号", "取茶", "外卖", "驿站", "快递柜", "快递", "凭",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dedupMap = ConcurrentHashMap<String, Long>()

    fun onPosted(
        context: Context,
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
    ) {
        PickupCodeSettings.ensureLoaded(context)
        if (!PickupCodeSettings.interceptionEnabled) return
        if (packageName == context.packageName) return
        val combined = listOfNotNull(title, text, subText)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (combined.isBlank()) return
        if (TRIGGER_KEYWORDS.none { combined.contains(it) }) return
        if (isDuplicate(packageName, combined)) return

        scope.launch {
            try {
                process(context.applicationContext, packageName, title, text, subText)
            } catch (t: Throwable) {
                Log.e(TAG, "取餐码通知拦截失败: pkg=$packageName", t)
            }
        }
    }

    private suspend fun process(
        context: Context,
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
    ) {
        val drafts = PickupCodeExtractor.fromText(
            context = context,
            title = title,
            text = text,
            subText = subText,
            source = SimpleRuleSource.NOTIFICATION,
            sourcePackage = packageName,
        )
        if (drafts.isEmpty()) return

        val repository = PickupCodeRepository(context)
        for (draft in drafts) {
            // 同码未完成已存在：不重复入库、不重复提醒（取件码通知常被多次推送）。
            if (repository.findUnfinishedByCode(draft.code) != null) continue
            repository.insert(
                code = draft.code,
                orderType = draft.orderType,
                brandName = draft.brandName,
                pickupLocation = draft.pickupLocation,
                source = PickupCodeSource.NOTIFICATION,
                sourcePackage = packageName,
                fullText = draft.fullText,
            )
            PickupCodeNotifier.notifyNewCode(
                context = context,
                code = draft.code,
                orderType = draft.orderType,
                brandName = draft.brandName,
                pickupLocation = draft.pickupLocation,
            )
        }
    }

    /** 同一条通知的 5 秒窗口去重（澎湃记口径），防止系统重复投递触发重复识别。 */
    private fun isDuplicate(packageName: String, text: String): Boolean {
        val key = "$packageName:${text.hashCode()}"
        val now = System.currentTimeMillis()
        val lastTime = dedupMap[key]
        if (lastTime != null && now - lastTime < 5_000) return true
        dedupMap[key] = now
        if (dedupMap.size > 100) {
            dedupMap.entries
                .sortedBy { it.value }
                .take(dedupMap.size - 80)
                .forEach { dedupMap.remove(it.key) }
        }
        return false
    }
}
