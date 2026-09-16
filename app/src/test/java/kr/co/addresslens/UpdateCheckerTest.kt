package kr.co.addresslens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun detectsNewerGitHubRelease() {
        assertTrue(UpdateChecker.isNewerVersion("v1.8.1", "1.8.0"))
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun ignoresSameOrOlderRelease() {
        assertFalse(UpdateChecker.isNewerVersion("v1.8.0", "1.8.0"))
        assertFalse(UpdateChecker.isNewerVersion("v1.7.9", "1.8.0"))
    }
}
