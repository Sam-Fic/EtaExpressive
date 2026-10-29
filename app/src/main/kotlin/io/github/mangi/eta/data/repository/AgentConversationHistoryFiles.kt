package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File

/**
 * 会话的模型历史存放位置。
 *
 * 模型下一次要发的历史写在文件里（Room 列受游标窗口限制），检查点只是兜底，
 * 所以读历史、写历史都要走同一个路径。
 */
internal object AgentConversationHistoryFiles {
    fun file(context: Context, conversationId: String): File {
        val safe = conversationId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return File(File(context.filesDir, "conversation-history"), "$safe.json")
    }
}
