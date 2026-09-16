package kr.co.addresslens

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionarySafetyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sample = "${AddressDictionary.FORMAT_HEADER}\n" +
        "# generated=2026-09-14\n" +
        "A\t경기도\t안산시 상록구\t본오동\n"

    @Test fun readsPlainTsvWhenAndroidPackagingRemovedGzipSuffix() {
        val attempts = mutableListOf<String>()
        val dictionary = AddressDictionary.loadWithFallback(temporaryFolder.root.resolve("missing-update")) {
            DictionaryFiles.openBundled { name ->
                attempts += name
                if (name != "address_dictionary.tsv") throw FileNotFoundException(name)
                ByteArrayInputStream(sample.toByteArray(Charsets.UTF_8))
            }
        }
        assertEquals(listOf("경기도"), dictionary.provinces())
        assertEquals("2026-09-14", dictionary.version)
        assertEquals(listOf("address_dictionary.tsv"), attempts)
    }

    @Test fun stillReadsGzipAssetsAndGzipUpdates() {
        val bytes = gzip(sample)
        val dictionary = AddressDictionary.loadWithFallback(temporaryFolder.root.resolve("missing-update")) {
            DictionaryFiles.openBundled { name ->
                if (name != "address_dictionary.tsv.gz") throw FileNotFoundException(name)
                ByteArrayInputStream(bytes)
            }
        }
        assertEquals(listOf("경기도"), dictionary.provinces())
        val update = temporaryFolder.newFile("current.tsv.gz").apply { writeBytes(bytes) }
        assertEquals("2026-09-14", AddressDictionary.loadWithFallback(update) {
            error("Valid update must not require a bundled asset")
        }.version)
    }

    @Test fun updateComparisonUsesDecodedContentNotCompressionFormat() {
        val plain = sample.toByteArray(Charsets.UTF_8)
        assertArrayEquals(DictionaryFiles.contentDigest(ByteArrayInputStream(plain)),
            DictionaryFiles.contentDigest(ByteArrayInputStream(gzip(sample))))
        assertFalse(DictionaryFiles.contentDigest(ByteArrayInputStream(plain)).contentEquals(
            DictionaryFiles.contentDigest(ByteArrayInputStream(gzip(sample.replace("본오동", "고잔동"))))))
    }

    @Test fun reportsMissingBundleOnlyAfterTryingBothSupportedNames() {
        val attempts = mutableListOf<String>()
        val error = assertThrows(IOException::class.java) {
            DictionaryFiles.openBundled { name ->
                attempts += name
                throw FileNotFoundException(name)
            }
        }
        assertEquals(listOf("address_dictionary.tsv", "address_dictionary.tsv.gz"), attempts)
        assertTrue(error.message!!.contains("내장 주소 사전"))
    }

    @Test fun corruptUpdateFallsBackToPlainPackagedAsset() {
        val update = temporaryFolder.newFile("broken-update.tsv.gz").apply {
            writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0))
        }
        val dictionary = AddressDictionary.loadWithFallback(update) {
            ByteArrayInputStream(sample.toByteArray(Charsets.UTF_8))
        }
        assertEquals(listOf("본오동"), dictionary.localities("경기도", "안산시 상록구"))
    }

    @Test
    fun validatorReadsToEndAndRejectsTruncatedGzip() {
        val text = buildString {
            appendLine(AddressDictionary.FORMAT_HEADER)
            appendLine("# generated=2026-09-14")
            appendLine("A\t경기도\t안산시 상록구\t본오동")
            repeat(20_000) { index ->
                appendLine("# filler-$index-${(index * 2_654_435_761L).toString(16)}")
            }
        }
        val complete = gzip(text)
        val truncated = complete.copyOf(complete.size - 8)
        assertTrue(truncated.size >= 1_024)
        val file = temporaryFolder.newFile("truncated.tsv.gz").apply { writeBytes(truncated) }

        assertThrows(Exception::class.java) { DictionaryUpdater.validate(file) }
    }

    @Test
    fun loaderFallsBackToBundledDictionaryWhenUpdateIsInvalid() {
        val invalidUpdate = temporaryFolder.newFile("current.tsv.gz").apply {
            writeBytes(
                gzip(
                    "${AddressDictionary.FORMAT_HEADER}\n" +
                        "# generated=broken\n"
                )
            )
        }
        val bundled = gzip(
            "${AddressDictionary.FORMAT_HEADER}\n" +
                "# generated=bundled-test\n" +
                "A\t경기도\t안산시 상록구\t본오동\n" +
                "R\t경기도\t안산시 상록구\t본오동\t샘골로12길\n"
        )

        val dictionary = AddressDictionary.loadWithFallback(invalidUpdate) {
            ByteArrayInputStream(bundled)
        }

        assertEquals("bundled-test", dictionary.version)
        assertEquals(listOf("경기도"), dictionary.provinces())
        assertEquals(listOf("안산시", "안산시 상록구"), dictionary.districts("경기도"))
        assertEquals(listOf("본오동"), dictionary.localities("경기도", "안산시 상록구"))
    }

    @Test
    fun matchCacheReusesResultsAndRemainsBounded() {
        val dictionary = AddressDictionary.fromBuckets(
            mapOf(
                "경기도" to mapOf(
                    "안산시 상록구" to AddressDictionary.Bucket(
                        localities = setOf("본오동"),
                        roads = emptyMap()
                    )
                )
            )
        )
        val first = dictionary.match("본오동", AddressKind.PARCEL, RegionSelection())
        val repeated = dictionary.match("본오동", AddressKind.PARCEL, RegionSelection())
        assertSame(first, repeated)

        repeat(AddressDictionary.MATCH_CACHE_LIMIT + 20) { index ->
            dictionary.match("후보${index}동", AddressKind.PARCEL, RegionSelection())
        }
        assertTrue(dictionary.cachedMatchCount() <= AddressDictionary.MATCH_CACHE_LIMIT)
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        output.toByteArray()
    }
}
