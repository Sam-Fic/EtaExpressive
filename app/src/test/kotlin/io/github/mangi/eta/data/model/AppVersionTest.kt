package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun normalizesTagAndDottedName() {
        assertEquals("5.2.3", AppVersion.normalize("v5.2.3"))
        assertEquals("5.2.3", AppVersion.normalize("5.2.3"))
        assertEquals("5.2.3", AppVersion.normalize("  V5.2.3-release  "))
    }

    @Test
    fun extractsVersionFromNamesWithPrefixesAndSuffixes() {
        // release 的 name 常带前缀/后缀；忽略状态必须仍能归一化，否则「忽略此版本」失效。
        assertEquals("5.3.16", AppVersion.normalize("Eta 5.3.16"))
        assertEquals("5.3.16", AppVersion.normalize("release-5.3.16"))
        assertEquals("5.3.16", AppVersion.normalize("5.3.16 (2026-10-29)"))
        assertEquals("5.3.16", AppVersion.normalize("v5.3.16-hotfix"))
        assertEquals("", AppVersion.normalize("nightly"))
    }

    @Test
    fun offerFallsBackToTagWhenNameHasNoVersion() {
        val offer = AppUpdateOffer(
            versionName = "nightly",
            tagName = "v5.3.16",
            notes = "",
            htmlUrl = "",
        )
        assertEquals("5.3.16", offer.normalizedVersion)
    }

    @Test
    fun ignoreCoversSameAndOlderVersionsButNotNewerOnes() {
        // 忽略 5.3.16 后：同一版本、更低版本静默；更高的版本继续提示。
        assertTrue(AppVersion.isIgnoredBy("5.3.16", "5.3.16"))
        assertTrue(AppVersion.isIgnoredBy("5.3.9", "5.3.16"))
        assertFalse(AppVersion.isIgnoredBy("5.3.17", "5.3.16"))
        // 远端 name 带前后缀时仍能比较。
        assertTrue(AppVersion.isIgnoredBy("Eta 5.3.16", "v5.3.16"))
        assertFalse(AppVersion.isIgnoredBy("5.3.17 (2026-11-01)", "5.3.16"))
        // 解析不出来时宁可提示。
        assertFalse(AppVersion.isIgnoredBy("nightly", "5.3.16"))
        assertFalse(AppVersion.isIgnoredBy("5.3.16", ""))
    }

    @Test
    fun comparesDottedVersions() {
        assertTrue(AppVersion.isNewer("5.2.3", "5.2.2"))
        assertTrue(AppVersion.isNewer("v5.3.0", "5.2.9"))
        assertTrue(AppVersion.isNewer("5.2.10", "5.2.9"))
        assertFalse(AppVersion.isNewer("5.2.2", "5.2.2"))
        assertFalse(AppVersion.isNewer("5.2.1", "5.2.2"))
        assertTrue(AppVersion.isNewer("5.2.2", ""))
        assertFalse(AppVersion.isNewer("", "5.2.2"))
    }
}
