package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 契约钉子：实时通话（DoubaoDuplexSession.captureInput）与听写 / 语音对话
 * （SpeechInputSession.recognize）共用同一把进程级麦克风互斥锁。
 *
 * 两条链路各持一个 VoiceModeController（应用内 / 浮窗），互不感知；没有这把锁，
 * 实时通话会绕开 recognize 的占麦检查，出现两条 AudioRecord 同时录音。
 */
class SpeechInputSessionMicrophoneLockTest {
    @Test fun secondHolderWhileLockedIsRejected() {
        SpeechInputSession.lockMicrophoneOrThrow()
        try {
            // JUnit4 的 expected 只认从测试方法直接抛出的异常，锁必须在这里收尾释放。
            try {
                SpeechInputSession.lockMicrophoneOrThrow()
                throw AssertionError("锁被占用时二次获锁应当抛出")
            } catch (expected: IllegalStateException) {
                assertEquals("语音输入正在使用麦克风", expected.message)
            }
        } finally {
            SpeechInputSession.unlockMicrophone()
        }
    }

    @Test fun lockIsReacquirableAfterUnlock() {
        SpeechInputSession.lockMicrophoneOrThrow()
        SpeechInputSession.unlockMicrophone()
        // 解锁后必须能重新获锁：语音对话每轮 recognize 前后都要走一遍加解锁。
        SpeechInputSession.lockMicrophoneOrThrow()
        SpeechInputSession.unlockMicrophone()
    }
}
