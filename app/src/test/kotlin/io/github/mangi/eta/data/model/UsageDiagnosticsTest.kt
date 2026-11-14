package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageDiagnosticsTest {

    @Test
    fun roundTripsAllFourStatesAndCacheCreation() {
        val value = UsageDiagnostics(
            prefixState = "changed",
            toolsState = "changed",
            systemState = "same",
            historyState = "same",
            cacheCreationTokens = 2048,
        )

        assertEquals(value, UsageDiagnosticsJson.decode(UsageDiagnosticsJson.encode(value)))
    }

    @Test
    fun stablePrefixWithZeroCreationIsNotDropped() {
        val value = UsageDiagnostics(
            prefixState = "same",
            toolsState = "same",
            systemState = "same",
            historyState = "same",
            cacheCreationTokens = 0,
        )

        // creation=0 也必须保留四段状态；这正是「上游丢缓存」取证所需的行。
        assertEquals(value, UsageDiagnosticsJson.decode(UsageDiagnosticsJson.encode(value)))
    }

    @Test
    fun legacyEmptyAndMalformedColumnsDecodeToNull() {
        assertEquals("", UsageDiagnosticsJson.encode(null))
        assertEquals("", UsageDiagnosticsJson.encode(UsageDiagnostics()))
        assertNull(UsageDiagnosticsJson.decode(null))
        assertNull(UsageDiagnosticsJson.decode(""))
        assertNull(UsageDiagnosticsJson.decode("not-json"))
        assertNull(UsageDiagnosticsJson.decode("{}"))
    }

    @Test
    fun oldFieldShapesStillDecodeTheirKnownParts() {
        val decoded = UsageDiagnosticsJson.decode("""{"tools":"changed","creation":512}""")

        assertEquals("changed", decoded?.toolsState)
        assertNull(decoded?.prefixState)
        assertEquals(512, decoded?.cacheCreationTokens)
    }
}
