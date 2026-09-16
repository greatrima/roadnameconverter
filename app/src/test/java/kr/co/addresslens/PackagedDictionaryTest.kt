package kr.co.addresslens

import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression for an asset that existed in sources but changed name/format inside the shipped APK. */
class PackagedDictionaryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun apk(): File = File(requireNotNull(System.getProperty("dictionary.testApk")) {
        "Packaged dictionary test requires the assembled APK"
    }).also { check(it.isFile) { "APK not built: $it" } }

    private fun sourceAsset(): File = File(requireNotNull(System.getProperty("dictionary.sourceAsset")))

    @Test fun loadsRealPackagedAssetAndSupportsProvinceCityAndCountySelections() {
        ZipFile(apk()).use { zip ->
            val dictionary = AddressDictionary.loadWithFallback(temporaryFolder.root.resolve("no-update")) {
                DictionaryFiles.openBundled { name ->
                    val entry = zip.getEntry("assets/$name") ?: throw FileNotFoundException(name)
                    zip.getInputStream(entry)
                }
            }
            assertTrue(dictionary.provinces().containsAll(listOf("경기도", "서울특별시", "부산광역시")))
            assertTrue("안산시" in dictionary.districts("경기도"))
            assertTrue("양평군" in dictionary.districts("경기도"))
            assertTrue("본오동" in dictionary.localities("경기도", "안산시"))
            val candidate = AddressCandidateEngine(dictionary).candidate("본오동 123-4", RegionSelection("경기도", "안산시"))
            assertEquals("경기도 안산시 상록구 본오동 123-4", candidate?.text)
            println("Packaged dictionary loaded: ${dictionary.version}, ${dictionary.provinces().size} provinces")
        }
    }

    @Test fun packagedPlainAssetMatchesValidatedGzipUpdateContent() {
        val version = DictionaryUpdater.validate(sourceAsset())
        assertFalse(version.isBlank())
        ZipFile(apk()).use { zip ->
            val packed = DictionaryFiles.openBundled { name ->
                val entry = zip.getEntry("assets/$name") ?: throw FileNotFoundException(name)
                zip.getInputStream(entry)
            }
            assertArrayEquals(DictionaryFiles.contentDigest(sourceAsset().inputStream()),
                DictionaryFiles.contentDigest(packed))
        }
    }

    @Test fun offlineApkContainsKoreanOcrModelsAndCorrectsWithoutDownloadedDictionary() {
        ZipFile(apk()).use { zip ->
            val koreanModels = zip.entries().asSequence().filter {
                it.name.startsWith("assets/mlkit-google-ocr-models/") &&
                    it.name.contains("/Kore_ctc/") && it.name.endsWith("_model.fb")
            }.toList()
            assertTrue("Korean OCR models must be bundled, not downloaded at first launch",
                koreanModels.size >= 2 && koreanModels.all { it.size > 0L })
            val dictionary = AddressDictionary.loadWithFallback(temporaryFolder.root.resolve("never-downloaded")) {
                DictionaryFiles.openBundled { name ->
                    zip.getInputStream(zip.getEntry("assets/$name") ?: throw FileNotFoundException(name))
                }
            }
            val engine = AddressCandidateEngine(dictionary)
            val region = RegionSelection("경기도", "안산시")
            assertEquals("경기도 안산시 상록구 본오동 123-4",
                engine.candidate("경기도 안산시 상콕구 본오동 123-4", region)?.text)
            assertEquals("경기도 안산시 단원구 신촌1길 4-1",
                engine.candidate("신촌1길 4-1, 101동 1203호", region)?.text)
            // A syntactically valid sample is not necessarily a road in the nationwide data.
            // Fuzzy letter suggestions are allowed, but numbers and uncertainty must survive.
            val uncertain = engine.candidates(
                listOf("경기도 안산시 상록구 샘골로12길 123-4, 101동 1203호"), region)
            assertTrue(uncertain.isNotEmpty())
            uncertain.forEach { candidate ->
                val parts = AddressTextParser.parseParts(candidate.text)!!
                assertEquals(listOf("12"), Regex("\\d+").findAll(parts.name).map { it.value }.toList())
                assertEquals("123-4", parts.number)
                assertFalse(candidate.verified)
            }
            val policy = AutoConversionPolicy()
            repeat(3) { assertNull(policy.update(uncertain.map { it.copy(manualOnly = true) }, null, it * 300L)) }
            val restored = engine.candidate("경기도 안산시 단원구 광덕\n동로 25(고잔동. 안산레이\n크타운 푸르지오) 102동 2\n901호", region)
            assertEquals("경기도 안산시 단원구 광덕동로 25", restored?.text)
            assertFalse("Dictionary candidates are not API-verified buildings", restored!!.verified)
            val partial = engine.candidates(listOf("본오동"), region).firstOrNull()
            assertEquals(CandidateCompleteness.PARTIAL, partial?.completeness)
            assertNull(AddressTextParser.parseParts(partial!!.text)?.number)
        }
    }
}
