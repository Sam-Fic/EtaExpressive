package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiToolsBridgeToolCatalogTest {
    @Test
    fun `risk flags control Xiaomi bridge tool visibility`() {
        val catalog = XiaomiToolsBridgeProtocol.Catalog(
            listOf(
                definition(
                    "com.aios.tools.builtin.system.WifiInfoTool",
                    "wifi_info",
                ),
                definition(
                    "com.aios.tools.builtin.system.ReadSMSTool",
                    "read_sms",
                ),
                definition(
                    "com.aios.tools.builtin.system.SMSTool",
                    "send_sms",
                ),
            ),
        )

        val direct = catalog.visibleDefinitions(directTools = true, sensitiveReadTools = false, sensitiveActionTools = false)
        val all = catalog.visibleDefinitions(directTools = true, sensitiveReadTools = true, sensitiveActionTools = true)

        assertTrue("xiaomi_wifi_info" in direct.mapTo(mutableSetOf()) { it.modelName })
        assertFalse("xiaomi_read_sms" in direct.mapTo(mutableSetOf()) { it.modelName })
        assertFalse("xiaomi_send_sms" in direct.mapTo(mutableSetOf()) { it.modelName })
        val allNames = all.mapTo(mutableSetOf()) { it.modelName }
        assertTrue("xiaomi_read_sms" in allNames)
        assertTrue("xiaomi_send_sms" in allNames)
        assertEquals(allNames.size, allNames.toSet().size)
    }

    private fun definition(targetClass: String, rawName: String) = requireNotNull(
        XiaomiToolsBridgeProtocol.definitionFromTarget(
            targetClassName = targetClass,
            rawName = rawName,
            description = "test",
            parametersJson = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
                .toString(),
        ),
    )
}
