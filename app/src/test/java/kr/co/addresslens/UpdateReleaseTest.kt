package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateReleaseTest {
    private fun release(tag: String) = """
        {
          "tag_name": "$tag",
          "html_url": "https://github.com/greatrima/roadnameconverter/releases/tag/$tag",
          "assets": [{
            "name": "RoadNameConverter-v2.0.1.apk",
            "browser_download_url": "https://github.com/greatrima/roadnameconverter/releases/download/v2.0.1/RoadNameConverter-v2.0.1.apk"
          }]
        }
    """.trimIndent()

    @Test fun publishedPatchDoesNotOfferThisBuildAgain() {
        // Regression: the v2.0.1 download used to contain VERSION_NAME=2.0.0 / code 26.
        assertTrue("The corrected build must replace code 26", BuildConfig.VERSION_CODE > 26)
        assertEquals(UpdateCheckResult.UpToDate,
            UpdateChecker.parseLatestRelease(release("v2.0.1"), BuildConfig.VERSION_NAME))
    }

    @Test fun sameVersionWithTagPrefixIsUpToDate() {
        assertEquals(UpdateCheckResult.UpToDate,
            UpdateChecker.parseLatestRelease(release(" V2.0.1 "), "2.0.1"))
    }

    @Test fun previousBuildCanStillDownloadTheCorrectedPatch() {
        val result = UpdateChecker.parseLatestRelease(release("v2.0.1"), "2.0.0")
        assertTrue(result is UpdateCheckResult.Available)
        val available = (result as UpdateCheckResult.Available).release
        assertEquals("2.0.1", available.version)
        assertTrue(available.hasApkAsset)
        assertTrue(available.downloadUrl.endsWith("/RoadNameConverter-v2.0.1.apk"))
    }

    @Test fun aFutureReleaseStillOffersAnUpdate() {
        assertTrue(UpdateChecker.parseLatestRelease(release("v2.0.2"), "2.0.1")
            is UpdateCheckResult.Available)
    }
}
