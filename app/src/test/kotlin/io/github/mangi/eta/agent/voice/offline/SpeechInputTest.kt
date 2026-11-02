package io.github.mangi.eta.agent.voice.offline

import org.junit.Assert.*
import org.junit.Test

class SpeechInputTest {
    @Test fun disabledVoiceDoesNotShowAnIdleIndicator() {
        assertFalse(SpeechInputPolicy.visible(generating = false, enabled = false))
        assertTrue(SpeechInputPolicy.visible(generating = true, enabled = false))
    }
    @Test fun enabledVoiceShowsCircleWithoutGeneration() {
        assertTrue(SpeechInputPolicy.visible(generating = false, enabled = true))
        assertTrue(SpeechInputPolicy.visible(generating = true, enabled = true))
    }
    @Test fun enablingRequiresVerifiedCompletePack() {
        assertFalse(SpeechInputPolicy.canEnable(false, false, false))
        assertFalse(SpeechInputPolicy.canEnable(true, true, false))
        assertFalse(SpeechInputPolicy.canEnable(true, false, true))
        assertTrue(SpeechInputPolicy.canEnable(true, false, false))
    }
    @Test fun initialSilenceAndLongSessionHaveFiniteDeadlines() {
        // 总时长从开麦算起；8 秒无语音从引擎就绪后算起，预加载耗时不算进说话窗口。
        assertFalse(SpeechInputPolicy.timedOut(7_999, 7_999, false))
        assertFalse(SpeechInputPolicy.timedOut(30_000, 0, false))
        assertTrue(SpeechInputPolicy.timedOut(30_000, 8_000, false))
        assertFalse(SpeechInputPolicy.timedOut(8_000, 8_000, true))
        assertTrue(SpeechInputPolicy.timedOut(60_000, 0, true))
        assertTrue(SpeechInputPolicy.timedOut(60_000, 0, false))
    }
    @Test fun partialTextReplacesOnlyCurrentDictation() {
        val draft = SpeechDraft("原有文字", 4, 4)
        assertEquals("原有文字你", draft.accept("原有文字", "你"))
        assertEquals("原有文字你好", draft.accept("原有文字你", "你好"))
        assertEquals(6, draft.cursor)
    }
    @Test fun decoderRevisionDoesNotDuplicatePartialWords() {
        val draft = SpeechDraft("", 0, 0)
        assertEquals("识别", draft.accept("", "识别"))
        assertEquals("识别修正", draft.accept("识别", "识别修正"))
        assertEquals("识别更正", draft.accept("识别修正", "识别更正"))
    }
    @Test fun selectionAndSuffixArePreserved() {
        val draft = SpeechDraft("aSELECTz", 7, 1)
        assertEquals("a你好z", draft.accept("aSELECTz", "你好"))
        assertEquals(3, draft.cursor)
    }
    @Test fun typingOrSendingInvalidatesDictationInsteadOfOverwriting() {
        val draft = SpeechDraft("draft", 5, 5)
        assertNull(draft.accept("typed", "speech"))
        assertNull(draft.accept("", "speech"))
        assertEquals("draft", draft.expectedText)
    }
    @Test fun cancellingBeforeTextDoesNotChangeTheDraft() {
        val draft = SpeechDraft("原文", 1, 1)
        assertEquals("原文", draft.expectedText)
    }
    @Test fun catalogIsBoundedPinnedAndContainsOnlyExpectedFiles() {
        assertTrue(SpeechModelCatalog.models.size >= 2)
        // 每个模型的 id 唯一，且 id 直接作为本机存储目录名，不能出现路径穿越。
        assertEquals(SpeechModelCatalog.models.size, SpeechModelCatalog.models.map { it.id }.toSet().size)
        assertTrue(SpeechModelCatalog.models.all { it.id.matches(Regex("[a-z0-9-]+")) })
        for (model in SpeechModelCatalog.models) {
            assertTrue(model.assets.isNotEmpty())
            // 每个资产名唯一、不含路径分隔符，且长度与哈希均已钉住。
            assertEquals(model.assets.size, model.assets.map { it.name }.toSet().size)
            assertTrue(model.assets.none { it.name.contains('/') || it.name.contains("..") })
            assertTrue(model.assets.all { it.bytes > 0 })
            // 权重文件必须有哈希；仅上游未公布哈希的文本文件允许为空（退化为仅校验长度）。
            assertTrue(model.assets.all { it.sha256?.matches(Regex("[0-9a-f]{64}")) ?: it.name.endsWith(".txt") })
            // 独立托管的资产（如 Silero VAD）必须直接指向 HTTPS 文件本身，且仍由上面的哈希钉住。
            assertTrue(model.assets.all { asset ->
                val url = asset.url
                url == null || (url.startsWith("https://") && url.endsWith(asset.name))
            })
            assertTrue(model.totalBytes > 0)
            // 下载源必须带固定 revision（ModelScope 镜像按内容哈希校验，同样钉住文件哈希）。
            assertTrue(model.sources.isNotEmpty())
            assertTrue(model.sources.all { it.baseUrl.startsWith("https://") && it.baseUrl.endsWith("/") })
            assertTrue(model.sources.all { it.baseUrl.contains(model.revision) || it.id == SpeechModelCatalog.SourceId.MODEL_SCOPE })
            assertEquals(model.sources.size, model.sources.map { it.id }.toSet().size)
        }
        // 默认模型必须能按未知 id 回退，偏好损坏也不能让启动失败。
        assertEquals(SpeechModelCatalog.SENSE_VOICE_ID, SpeechModelCatalog.defaultModel.id)
        assertEquals(SpeechModelCatalog.defaultModel, SpeechModelCatalog.model(null))
        assertEquals(SpeechModelCatalog.defaultModel, SpeechModelCatalog.model("does-not-exist"))
        // 每个引擎族都要有模型，否则对应的运行库等于白打包。
        assertEquals(
            SpeechModelCatalog.Engine.entries.toSet(),
            SpeechModelCatalog.models.map { it.engine }.toSet(),
        )
    }
}
