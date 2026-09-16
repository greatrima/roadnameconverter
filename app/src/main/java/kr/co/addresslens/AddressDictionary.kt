package kr.co.addresslens

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.LinkedHashMap

data class RegionSelection(
    val province: String = "",
    val district: String = "",
    val localities: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = province.isBlank() && district.isBlank()
    fun displayName(): String = listOf(province, district).filter(String::isNotBlank).joinToString(" ")
        .ifBlank { "지역 설정 안 됨" }
}

data class DictionaryMatch(
    val province: String,
    val district: String,
    val name: String,
    val kind: AddressKind,
    val distance: Int,
    val localityContext: Boolean
)

class AddressDictionary private constructor(
    private val buckets: Map<String, Map<String, Bucket>>,
    val version: String
) {
    private data class NameKey(val suffix: String, val length: Int)
    private data class MatchCacheKey(
        val name: String,
        val kind: AddressKind,
        val province: String,
        val district: String,
        val localities: List<String>,
        val explicitProvince: String
    )

    private val matchCacheLock = Any()
    private val matchCache = object : LinkedHashMap<MatchCacheKey, List<DictionaryMatch>>(
        MATCH_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<MatchCacheKey, List<DictionaryMatch>>?
        ): Boolean = size > MATCH_CACHE_LIMIT
    }

    data class Bucket(val localities: Set<String>, val roads: Map<String, Set<String>>) {
        private val localityIndex = buildNameIndex(localities)
        private val roadIndex = buildNameIndex(roads.keys)

        internal fun names(
            kind: AddressKind,
            targetSuffix: String,
            minimumLength: Int,
            maximumLength: Int
        ): Sequence<String> {
            val index = if (kind == AddressKind.ROAD) roadIndex else localityIndex
            return (minimumLength..maximumLength).asSequence().flatMap { length ->
                index[NameKey(targetSuffix, length)].orEmpty().asSequence()
            }
        }
    }

    fun provinces(): List<String> = buckets.keys.sorted()
    fun districts(province: String): List<String> = buckets[province]?.keys.orEmpty()
        .flatMap { listOf(it.substringBefore(' '), it) }.distinct().sorted()
    fun localities(province: String, district: String): List<String> =
        buckets[province].orEmpty().filterKeys { withinDistrict(it, district) }
            .values.flatMap { it.localities }.distinct().sorted()

    internal fun cachedMatchCount(): Int = synchronized(matchCacheLock) { matchCache.size }

    fun match(
        name: String,
        kind: AddressKind,
        region: RegionSelection,
        explicitProvince: String? = null
    ): List<DictionaryMatch> {
        val cacheKey = MatchCacheKey(
            name,
            kind,
            region.province,
            region.district,
            region.localities.sorted(),
            explicitProvince.orEmpty()
        )
        synchronized(matchCacheLock) { matchCache[cacheKey] }?.let { return it }
        val result = findMatches(name, kind, region, explicitProvince)
        synchronized(matchCacheLock) { matchCache[cacheKey] = result }
        return result
    }

    private fun findMatches(
        name: String,
        kind: AddressKind,
        region: RegionSelection,
        explicitProvince: String?
    ): List<DictionaryMatch> {
        val targetSuffix = suffix(name)
        val distanceLimit = allowedDistance(name.length)
        val minimumLength = (name.length - distanceLimit).coerceAtLeast(0)
        val maximumLength = name.length + distanceLimit
        val numberTokens = numbersInName.findAll(name).map { it.value }.toList()
        fun search(provinces: Collection<String>, districtOnly: String? = null): List<DictionaryMatch> {
            val matches = mutableListOf<DictionaryMatch>()
            provinces.forEach provinceLoop@ { province ->
                val districts = buckets[province].orEmpty()
                val districtNames = districtOnly?.let { parent ->
                    districts.keys.filter { withinDistrict(it, parent) }
                } ?: districts.keys
                districtNames.forEach districtLoop@ { district ->
                    val bucket = districts[district] ?: return@districtLoop
                    bucket.names(kind, targetSuffix, minimumLength, maximumLength).forEach nameLoop@ { candidate ->
                        // Correct letters, never invent/delete/change numbers in a road or locality.
                        if (numbersInName.findAll(candidate).map { it.value }.toList() != numberTokens) {
                            return@nameLoop
                        }
                        val distance = levenshtein(name, candidate)
                        if (distance <= distanceLimit) {
                            val context = region.localities.isEmpty() || if (kind == AddressKind.ROAD) {
                                bucket.roads[candidate].orEmpty().any(region.localities::contains)
                            } else candidate in region.localities
                            matches += DictionaryMatch(
                                province, district, candidate, kind,
                                distance + if (context) 0 else 1,
                                context
                            )
                        }
                    }
                }
            }
            return rank(matches)
        }

        val preferredProvince = explicitProvince?.takeIf(String::isNotBlank)
            ?: region.province.takeIf(String::isNotBlank)
        if (preferredProvince != null) {
            region.district.takeIf(String::isNotBlank)?.let { district ->
                search(listOf(preferredProvince), district).takeIf(List<DictionaryMatch>::isNotEmpty)
                    ?.let { return it }
            }
            search(listOf(preferredProvince)).takeIf(List<DictionaryMatch>::isNotEmpty)?.let { return it }
            // An explicit region is authoritative; do not "correct" it to an unrelated province.
            if (!explicitProvince.isNullOrBlank()) return emptyList()
        }
        return search(buckets.keys)
    }

    private fun rank(matches: List<DictionaryMatch>): List<DictionaryMatch> = matches.sortedWith(
        compareBy<DictionaryMatch> { it.distance }
            .thenByDescending { it.localityContext }
            .thenBy { it.province }
            .thenBy { it.district }
            .thenBy { it.name }
    ).take(8)

    fun correctProvince(token: String): String? = bestToken(token, buckets.keys)

    fun correctDistrict(token: String, province: String?): String? {
        val preferred = province?.let(::districts).orEmpty()
        bestDistrictToken(token, preferred)?.let { return it }
        return bestDistrictToken(token, provinces().flatMap(::districts).toSet())
    }

    fun provinceForDistrict(district: String, preferredProvince: String? = null): String? {
        if (!preferredProvince.isNullOrBlank() &&
            buckets[preferredProvince]?.keys?.any { withinDistrict(it, district) } == true) {
            return preferredProvince
        }
        return buckets.entries.firstOrNull { entry -> entry.value.keys.any { withinDistrict(it, district) } }?.key
    }

    private fun bestDistrictToken(token: String, values: Collection<String>): String? {
        if (values.isEmpty()) return null
        if (token in values) return token
        val expanded = values.flatMap { district ->
            listOf(district to district) + district.split(Regex("\\s+")).map { it to district }
        }
        return expanded.asSequence()
            .filter { suffix(it.first) == suffix(token) }
            .map { it.second to levenshtein(token, it.first) }
            .filter { it.second <= allowedDistance(token.length) }
            .minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
            ?.first
    }
    private fun bestToken(token: String, values: Collection<String>): String? = values
        .asSequence()
        .filter { suffix(it) == suffix(token) }
        .map { it to levenshtein(token, it) }
        .filter { it.second <= allowedDistance(token.length) }
        .minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
        ?.first

    companion object {
        private val numbersInName = Regex("\\d+")
        private fun withinDistrict(actual: String, selected: String): Boolean =
            actual == selected || actual.startsWith("$selected ")
        const val ASSET_NAME = DictionaryFiles.COMPRESSED_ASSET
        const val UPDATED_FILE_NAME = "address_dictionary.current.tsv.gz"
        internal const val FORMAT_HEADER = "# address-lens-dictionary-v1"
        internal const val MATCH_CACHE_LIMIT = 128

        @Volatile private var cached: AddressDictionary? = null

        fun get(context: Context): AddressDictionary = cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

        fun invalidate() { cached = null }

        internal fun fromBuckets(
            buckets: Map<String, Map<String, Bucket>>,
            version: String = "test"
        ): AddressDictionary = AddressDictionary(buckets, version)

        internal fun load(context: Context): AddressDictionary {
            val updated = File(context.filesDir, UPDATED_FILE_NAME)
            return loadWithFallback(updated) { openBundled(context) }
        }

        internal fun openBundled(context: Context): InputStream =
            DictionaryFiles.openBundled { name -> context.assets.open(name) }

        internal fun loadWithFallback(
            updated: File,
            bundledInput: () -> InputStream
        ): AddressDictionary {
            if (updated.isFile) {
                try {
                    return read(updated.inputStream())
                } catch (_: Exception) {
                    // A partial or corrupt update must never make the bundled dictionary unusable.
                }
            }
            return read(bundledInput())
        }

        private fun read(input: InputStream): AddressDictionary {
            val mutable = linkedMapOf<String, MutableMap<String, MutableBucket>>()
            var version = "내장본"
            var entryCount = 0
            input.use { source ->
                DictionaryFiles.decoded(source).bufferedReader(Charsets.UTF_8).use { reader ->
                    require(reader.readLine() == FORMAT_HEADER) { "지원하지 않는 사전입니다." }
                    reader.lineSequence().forEach { line ->
                        if (line.startsWith("# generated=")) {
                            version = line.substringAfter('=')
                            return@forEach
                        }
                        if (line.isBlank() || line.startsWith('#')) return@forEach
                        val fields = line.split('\t')
                        if (fields.size < 4 || (fields[0] != "A" && fields[0] != "R")) return@forEach
                        val province = fields[1]
                        val district = fields[2]
                        val locality = fields[3]
                        if (province.isBlank() || district.isBlank()) return@forEach
                        val bucket = mutable.getOrPut(province) { linkedMapOf() }
                            .getOrPut(district) { MutableBucket() }
                        if (locality.isNotBlank() && bucket.localities.add(locality)) entryCount++
                        if (fields[0] == "R" && fields.size >= 5 && fields[4].isNotBlank()) {
                            val roadLocalities = bucket.roads.getOrPut(fields[4]) {
                                entryCount++
                                linkedSetOf()
                            }
                            roadLocalities.apply {
                                if (locality.isNotBlank()) add(locality)
                            }
                        }
                    }
                }
            }
            require(entryCount > 0) { "사전에 주소 항목이 없습니다." }
            return AddressDictionary(
                mutable.mapValues { (_, districts) ->
                    districts.mapValues { (_, bucket) ->
                        Bucket(bucket.localities.toSet(), bucket.roads.mapValues { it.value.toSet() })
                    }
                },
                version
            )
        }

        private fun suffix(value: String): String = when {
            value.endsWith("특별자치도") -> "특별자치도"
            value.endsWith("특별자치시") -> "특별자치시"
            value.endsWith("광역시") -> "광역시"
            value.endsWith("특별시") -> "특별시"
            value.endsWith("대로") -> "대로"
            value.endsWith("길") -> "길"
            value.endsWith("로") -> "로"
            else -> value.takeLast(1)
        }

        private fun allowedDistance(length: Int): Int = when {
            length <= 2 -> 0
            length <= 5 -> 1
            length <= 9 -> 2
            else -> 3
        }

        internal fun levenshtein(left: String, right: String): Int {
            if (left == right) return 0
            if (left.isEmpty()) return right.length
            if (right.isEmpty()) return left.length
            var previous = IntArray(right.length + 1) { it }
            var current = IntArray(right.length + 1)
            left.forEachIndexed { i, lc ->
                current[0] = i + 1
                right.forEachIndexed { j, rc ->
                    current[j + 1] = minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + if (lc == rc) 0 else 1
                    )
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[right.length]
        }

        private fun buildNameIndex(names: Collection<String>): Map<NameKey, List<String>> =
            names.groupBy { name -> NameKey(suffix(name), name.length) }
    }

    private class MutableBucket(
        val localities: MutableSet<String> = linkedSetOf(),
        val roads: MutableMap<String, MutableSet<String>> = linkedMapOf()
    )
}
