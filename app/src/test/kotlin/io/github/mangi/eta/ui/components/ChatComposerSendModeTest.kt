package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入栏尾部按钮态真值表。用户可见的「暂停/继续」已移除（停止即真停止），
 * `continue` 态只剩失败/断线（RuntimeFailed 收尾）后的续跑键。
 */
class ChatComposerSendModeTest {
    @Test
    fun streamingEmptyShowsStop() {
        assertEquals(
            "stop",
            resolveChatComposerSendMode(
                isStreaming = true,
                hasSteerContent = false,
                canStartNewSend = false,
            ),
        )
    }

    @Test
    fun streamingWithTextShowsSend() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = true,
                hasSteerContent = true,
                canStartNewSend = true,
            ),
        )
    }

    @Test
    fun idleEmptyShowsIdle() {
        assertEquals(
            "idle",
            resolveChatComposerSendMode(
                isStreaming = false,
                hasSteerContent = false,
                canStartNewSend = false,
            ),
        )
    }

    @Test
    fun idleReadyShowsSend() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = false,
                hasSteerContent = true,
                canStartNewSend = true,
            ),
        )
    }

    @Test
    fun streamingWithAttachmentsShowsSend() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = true,
                hasSteerContent = true,
                canStartNewSend = true,
            ),
        )
    }

    @Test
    fun compressingReadyShowsIdle() {
        assertEquals(
            "idle",
            resolveChatComposerSendMode(
                isStreaming = false,
                hasSteerContent = true,
                canStartNewSend = false,
            ),
        )
    }

    @Test
    fun compressingWhileStreamingBlocksSteer() {
        assertEquals(
            "blocked",
            resolveChatComposerSendMode(
                isStreaming = true,
                hasSteerContent = true,
                canStartNewSend = true,
                isCompressingContext = true,
            ),
        )
    }

    @Test
    fun compressingIdleWithDraftShowsBlockedSend() {
        assertEquals(
            "blocked",
            resolveChatComposerSendMode(
                isStreaming = false,
                hasSteerContent = true,
                canStartNewSend = true,
                isCompressingContext = true,
            ),
        )
    }

    @Test
    fun disconnectedFailureEmptyShowsContinue() {
        assertEquals(
            "continue",
            resolveChatComposerSendMode(
                isStreaming = false,
                hasSteerContent = false,
                canStartNewSend = false,
                canContinueDisconnected = true,
            ),
        )
    }

    @Test
    fun disconnectedFailureWithDraftSendsNewMessage() {
        assertEquals(
            "send",
            resolveChatComposerSendMode(
                isStreaming = false,
                hasSteerContent = true,
                canStartNewSend = true,
                canContinueDisconnected = true,
            ),
        )
    }

    @Test
    fun compressingDisconnectedFailureHidesContinue() {
        assertEquals(
            "idle",
            resolveChatComposerSendMode(
                isStreaming = false,
                hasSteerContent = false,
                canStartNewSend = false,
                isCompressingContext = true,
                canContinueDisconnected = true,
            ),
        )
    }

    @Test
    fun compressingWhileStreamingWithoutDraftStillStops() {
        assertEquals(
            "stop",
            resolveChatComposerSendMode(
                isStreaming = true,
                hasSteerContent = false,
                canStartNewSend = true,
                isCompressingContext = true,
            ),
        )
    }
}
