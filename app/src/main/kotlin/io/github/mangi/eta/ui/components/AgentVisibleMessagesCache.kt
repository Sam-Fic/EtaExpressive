package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.incrementalSnapshot

/** Preserve the existing edit/blank filtering policy without rescanning a live payload-only update. */
internal class AgentVisibleMessagesCache {
    private var source: List<AgentChatMessageUi>? = null
    private var sourceToVisible = IntArray(0)
    private var visible: List<AgentChatMessageUi> = emptyList()

    fun project(messages: List<AgentChatMessageUi>, targetMessageId: String?): List<AgentChatMessageUi> {
        if (targetMessageId != null) {
            source = null
            return AgentConversationRevisionReducer.visibleMessagesForEdit(messages, targetMessageId)
                .filterNot(::hidden).incrementalSnapshot()
        }
        val previous = source
        val changed = if (previous == null) null else
            (messages as? AgentIncrementalList<AgentChatMessageUi>)?.singleReplacementFrom(previous)
        if (changed != null && previous != null && previous.size == messages.size) {
            val old = previous[changed]
            val current = messages[changed]
            if (old.id == current.id && hidden(old) == hidden(current)) {
                val slot = sourceToVisible[changed]
                if (slot >= 0) visible = visible.incrementalSnapshot().replacing(slot, current)
                source = messages
                return visible
            }
        }
        val input = messages.incrementalSnapshot()
        val mapping = IntArray(input.size) { -1 }
        val result = ArrayList<AgentChatMessageUi>(input.size)
        input.forEachIndexed { index, message ->
            if (!hidden(message)) {
                mapping[index] = result.size
                result.add(message)
            }
        }
        source = input
        sourceToVisible = mapping
        visible = result.incrementalSnapshot()
        return visible
    }

    private fun hidden(message: AgentChatMessageUi): Boolean =
        message is AgentMessageUi && message.content.isBlank() ||
            // 派发汇报的文本只作模型输入（steer 补充轮同此），展示由 task_dispatch 卡片承载；
            // 隐藏它，避免回执伪装成用户自己的发言。
            message is UserMessageUi && AgentDispatchNotifier.isConsoleReceipt(message.content)
}
