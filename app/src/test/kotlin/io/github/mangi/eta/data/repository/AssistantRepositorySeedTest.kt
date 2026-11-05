package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AssistantPrompt
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 内置助手补种：只补缺失，不覆盖已存在（用户改过的名称/提示词必须原样保留）。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AssistantRepositorySeedTest {
    private val context = RuntimeEnvironment.getApplication()
    private val directory = File(context.filesDir, "eta-assistants")

    @Before fun setUp() {
        directory.deleteRecursively()
        Prefs.initLocal(context)
        SettingsDataStore.init(context)
        AgentMemoryRepository.init(context)
    }

    private fun writeIndex(activeId: String, profilesJson: String) {
        directory.mkdirs()
        File(directory, "index.json").writeText(
            """{"activeId":"$activeId","profiles":[$profilesJson]}""",
            Charsets.UTF_8,
        )
    }

    @Test fun missingDefaultAssistantIsReseededOnInit() {
        writeIndex(AssistantPrompt.DISPATCHER_ID, """{"id":"${AssistantPrompt.DISPATCHER_ID}","name":"调度台","prompt":"x"}""")
        AssistantRepository.init(context)
        val restored = AssistantRepository.profile(AssistantPrompt.DEFAULT_ID)
        assertNotNull(restored)
        assertEquals(AssistantPrompt.DEFAULT_NAME, restored?.name)
        assertEquals("", restored?.prompt)
    }

    @Test fun existingDefaultAssistantKeepsUserEdits() {
        writeIndex(
            AssistantPrompt.DEFAULT_ID,
            """{"id":"${AssistantPrompt.DEFAULT_ID}","name":"我的主助手","prompt":"用户改过的提示词"}""",
        )
        AssistantRepository.init(context)
        val kept = AssistantRepository.profile(AssistantPrompt.DEFAULT_ID)
        assertNotNull(kept)
        assertEquals("我的主助手", kept?.name)
        assertEquals("用户改过的提示词", kept?.prompt)
    }
}
