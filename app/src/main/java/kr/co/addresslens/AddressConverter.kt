package kr.co.addresslens

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class AddressResult(
    val recognizedAddress: String,
    val convertedAddress: String,
    val recognizedKind: AddressKind,
    val source: Source
) {
    enum class Source { VWORLD, NAVER, KAKAO }
}

sealed interface ConversionOutcome {
    data class Success(val result: AddressResult) : ConversionOutcome
    data object ApiKeyMissing : ConversionOutcome
    data object NotFound : ConversionOutcome
    data object Offline : ConversionOutcome
    data class NoExactMatch(val suggestions: List<AddressResult>) : ConversionOutcome
    data class NetworkError(val message: String) : ConversionOutcome
}

class AddressConverter(
    vworldApiKey: String,
    naverClientId: String = "",
    naverClientSecret: String = "",
    kakaoRestApiKey: String = "",
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val hasInternet: () -> Boolean = { true },
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    @Volatile private var vworldApiKey = vworldApiKey.trim()
    @Volatile private var naverClientId = naverClientId.trim()
    @Volatile private var naverClientSecret = naverClientSecret.trim()
    @Volatile private var kakaoRestApiKey = kakaoRestApiKey.trim()
    private val requestLock = Any()
    private val inFlight = mutableMapOf<String, MutableList<(ConversionOutcome) -> Unit>>()
    private val cache = object : LinkedHashMap<String, ConversionOutcome>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConversionOutcome>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    fun convert(query: String, callback: (ConversionOutcome) -> Unit) {
        val cleaned = AddressTextParser.extract(query) ?: query.trim()
        val cacheKey = requestKey(cleaned)
        synchronized(requestLock) {
            // A verified result can still be reused during this app session. A cached miss
            // or pending remote request must not hide the immediate offline response.
            cache[cacheKey]?.takeIf { it is ConversionOutcome.Success }?.let {
                callback(it)
                return
            }
            if (!hasInternet()) {
                callback(ConversionOutcome.Offline)
                return
            }
            cache[cacheKey]?.let {
                callback(it)
                return
            }
            inFlight[cacheKey]?.let {
                it += callback
                return
            }
            inFlight[cacheKey] = mutableListOf(callback)
        }
        executor.execute {
            if (!hasInternet()) {
                finishRequest(cacheKey, ConversionOutcome.Offline)
                return@execute
            }
            val kind = AddressTextParser.classify(cleaned)
            val outcomes = mutableListOf<ConversionOutcome>()
            var finalOutcome: ConversionOutcome? = null

            if (vworldApiKey.isNotBlank()) {
                val outcome = requestVworld(cleaned, kind)
                if (outcome is ConversionOutcome.Success) {
                    finalOutcome = outcome
                }
                outcomes += outcome
            }
            if (finalOutcome == null && naverClientId.isNotBlank() && naverClientSecret.isNotBlank()) {
                val outcome = requestNaver(cleaned, kind)
                if (outcome is ConversionOutcome.Success) {
                    finalOutcome = outcome
                }
                outcomes += outcome
            }
            if (finalOutcome == null && kakaoRestApiKey.isNotBlank()) {
                val outcome = requestKakao(cleaned, kind)
                if (outcome is ConversionOutcome.Success) {
                    finalOutcome = outcome
                }
                outcomes += outcome
            }
            finishRequest(
                cacheKey,
                finalOutcome ?: if (outcomes.isEmpty()) ConversionOutcome.ApiKeyMissing
                else selectOutcome(outcomes)
            )
        }
    }

    fun updateApiKeys(
        vworldKey: String,
        naverId: String,
        naverSecret: String,
        kakaoKey: String
    ) {
        val changed = this.vworldApiKey != vworldKey.trim() ||
            this.naverClientId != naverId.trim() ||
            this.naverClientSecret != naverSecret.trim() ||
            this.kakaoRestApiKey != kakaoKey.trim()
        vworldApiKey = vworldKey.trim()
        naverClientId = naverId.trim()
        naverClientSecret = naverSecret.trim()
        kakaoRestApiKey = kakaoKey.trim()
        if (changed) synchronized(requestLock) { cache.clear() }
    }

    fun close() = executor.shutdown()

    internal fun selectOutcome(outcomes: List<ConversionOutcome>): ConversionOutcome =
        if (outcomes.any { it is ConversionOutcome.Offline }) {
            ConversionOutcome.Offline
        } else if (outcomes.any { it is ConversionOutcome.NetworkError }) {
            // A failed provider is not evidence that the address does not exist.
            outcomes.filterIsInstance<ConversionOutcome.NetworkError>().last()
        } else if (outcomes.any { it is ConversionOutcome.NoExactMatch }) {
            ConversionOutcome.NoExactMatch(outcomes.filterIsInstance<ConversionOutcome.NoExactMatch>()
                .flatMap { it.suggestions }.distinctBy { AddressTextParser.normalizeKey(it.recognizedAddress) }.take(3))
        } else if (outcomes.any { it is ConversionOutcome.NotFound }) {
            ConversionOutcome.NotFound
        } else {
            outcomes.filterIsInstance<ConversionOutcome.NetworkError>().lastOrNull()
                ?: ConversionOutcome.NotFound
        }

    private fun requestVworld(query: String, kind: AddressKind): ConversionOutcome {
        val categories = when (kind) {
            AddressKind.PARCEL -> listOf("parcel")
            AddressKind.ROAD -> listOf("road")
            AddressKind.UNKNOWN -> listOf("parcel", "road")
        }
        val outcomes = categories.map { category ->
            requestVworldCategory(query, kind, category)
        }
        return outcomes.filterIsInstance<ConversionOutcome.Success>().firstOrNull()
            ?: selectOutcome(outcomes)
    }

    private fun requestVworldCategory(
        query: String,
        kind: AddressKind,
        category: String
    ): ConversionOutcome {
        var connection: HttpURLConnection? = null
        return try {
            val encodedQuery = encode(query)
            val encodedKey = encode(vworldApiKey)
            val url = URL(
                "https://api.vworld.kr/req/search" +
                    "?service=search&request=search&version=2.0" +
                    "&format=json&errorFormat=json&size=10&page=1" +
                    "&type=address&category=$category" +
                    "&query=$encodedQuery&key=$encodedKey"
            )
            connection = openConnection(url)
            val status = connection.responseCode
            if (status !in 200..299) {
                return ConversionOutcome.NetworkError("VWorld 주소 서버 응답 코드 $status")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseVworldResponse(query, kind, body)
        } catch (_: Exception) {
            connectionFailure("VWorld 주소 서버 연결 오류")
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseVworldResponse(
        query: String,
        kind: AddressKind,
        body: String
    ): ConversionOutcome {
        return try {
            val response = JSONObject(body).optJSONObject("response")
                ?: return ConversionOutcome.NetworkError("VWorld 응답 형식 오류")
            when (response.optString("status")) {
                "NOT_FOUND" -> return ConversionOutcome.NotFound
                "ERROR" -> {
                    val message = response.optJSONObject("error")
                        ?.optString("text")
                        ?.takeIf(String::isNotBlank)
                        ?: "VWorld API 오류"
                    return ConversionOutcome.NetworkError(message)
                }
            }
            val items = response.optJSONObject("result")?.optJSONArray("items")
                ?: return ConversionOutcome.NotFound
            val rows = (0 until items.length()).mapNotNull { index ->
                val address = items.optJSONObject(index)?.optJSONObject("address") ?: return@mapNotNull null
                ProviderAddress(address.optString("parcel").trim(), address.optString("road").trim())
            }
            matchRows(query, kind, rows, AddressResult.Source.VWORLD)
        } catch (_: Exception) {
            ConversionOutcome.NetworkError("VWorld 응답 처리 오류")
        }
    }

    private fun requestNaver(query: String, kind: AddressKind): ConversionOutcome {
        val currentEndpoint = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode"
        val legacyEndpoint = "https://naveropenapi.apigw.ntruss.com/map-geocode/v2/geocode"
        val current = requestNaverEndpoint(currentEndpoint, query, kind)
        return if (current is ConversionOutcome.NetworkError &&
            (current.message.endsWith("401") || current.message.endsWith("404"))
        ) {
            requestNaverEndpoint(legacyEndpoint, query, kind)
        } else current
    }

    private fun requestNaverEndpoint(
        endpoint: String,
        query: String,
        kind: AddressKind
    ): ConversionOutcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(URL("$endpoint?query=${encode(query)}&count=10")).apply {
                setRequestProperty("x-ncp-apigw-api-key-id", naverClientId)
                setRequestProperty("x-ncp-apigw-api-key", naverClientSecret)
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                return ConversionOutcome.NetworkError("네이버 주소 서버 응답 코드 $status")
            }
            val addresses = JSONObject(
                connection.inputStream.bufferedReader().use { it.readText() }
            ).optJSONArray("addresses") ?: return ConversionOutcome.NotFound
            val rows = (0 until addresses.length()).mapNotNull { index ->
                val address = addresses.optJSONObject(index) ?: return@mapNotNull null
                val parcel = address.optString("jibunAddress").trim()
                val related = mutableListOf<String>()
                val elements = address.optJSONArray("addressElements")
                if (elements != null) for (i in 0 until elements.length()) {
                    val element = elements.optJSONObject(i) ?: continue
                    val types = element.optJSONArray("types") ?: continue
                    if ((0 until types.length()).any { types.optString(it) == "LAND_NUMBER" }) {
                        related += AddressMatchValidator.relatedParcels(parcel, element.optString("longName"))
                    }
                }
                ProviderAddress(parcel, address.optString("roadAddress").trim(), related)
            }
            matchRows(query, kind, rows, AddressResult.Source.NAVER)
        } catch (_: Exception) {
            connectionFailure("네이버 주소 서버 연결 오류")
        } finally {
            connection?.disconnect()
        }
    }

    private fun requestKakao(query: String, kind: AddressKind): ConversionOutcome {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(
                "https://dapi.kakao.com/v2/local/search/address.json?query=${encode(query)}&size=10"
            )
            connection = openConnection(url).apply {
                setRequestProperty("Authorization", "KakaoAK $kakaoRestApiKey")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                return ConversionOutcome.NetworkError("카카오맵 주소 서버 응답 코드 $status")
            }
            val documents = JSONObject(
                connection.inputStream.bufferedReader().use { it.readText() }
            ).optJSONArray("documents") ?: return ConversionOutcome.NotFound
            val rows = (0 until documents.length()).mapNotNull { index ->
                val document = documents.optJSONObject(index) ?: return@mapNotNull null
                ProviderAddress(document.optJSONObject("address")?.optString("address_name")?.trim().orEmpty(),
                    document.optJSONObject("road_address")?.optString("address_name")?.trim().orEmpty())
            }
            matchRows(query, kind, rows, AddressResult.Source.KAKAO)
        } catch (_: Exception) {
            connectionFailure("카카오맵 주소 서버 연결 오류")
        } finally {
            connection?.disconnect()
        }
    }

    internal data class ProviderAddress(val parcel: String, val road: String, val related: List<String> = emptyList())

    internal fun matchRows(query: String, kind: AddressKind, rows: List<ProviderAddress>, source: AddressResult.Source): ConversionOutcome {
        val effectiveKind = if (kind == AddressKind.UNKNOWN) AddressTextParser.classify(query) else kind
        val possible = rows.flatMap { row ->
            val names = if (effectiveKind == AddressKind.PARCEL) listOf(row.parcel) + row.related else listOf(row.road)
            names.mapNotNull { name ->
                val recognized = AddressTextParser.extract(qualifyAddress(name,
                    if (effectiveKind == AddressKind.PARCEL) row.road else row.parcel)).orEmpty()
                val converted = AddressTextParser.extract(if (effectiveKind == AddressKind.PARCEL) qualifyAddress(row.road, name)
                    else qualifyAddress(row.parcel, row.road)).orEmpty()
                if (AddressMatchValidator.parts(recognized)?.number == null ||
                    AddressMatchValidator.parts(converted)?.number == null) null
                else AddressResult(recognized, converted, effectiveKind, source)
            }
        }.distinctBy { AddressTextParser.normalizeKey(it.recognizedAddress) + "|" + AddressTextParser.normalizeKey(it.convertedAddress) }
        val exact = possible.filter { AddressMatchValidator.exact(query, it.recognizedAddress) }
        // An omitted region can match several real addresses. Never pick the first arbitrarily.
        return if (exact.size == 1) ConversionOutcome.Success(exact.single())
        else if (possible.isNotEmpty()) ConversionOutcome.NoExactMatch((exact.ifEmpty { possible }).take(3))
        else ConversionOutcome.NotFound
    }

    internal fun qualifyAddress(target: String, reference: String): String {
        val cleanedTarget = target.substringBefore(" (").trim()
        if (cleanedTarget.isBlank() || hasCityOrDistrict(cleanedTarget)) return cleanedTarget
        val referenceTokens = reference.substringBefore(" (")
            .trim()
            .split(Regex("\\s+"))
        val lastAdministrativeIndex = referenceTokens.indexOfLast { token ->
            token.endsWith("도") || token.endsWith("시") ||
                token.endsWith("군") || token.endsWith("구")
        }
        if (lastAdministrativeIndex < 0) return cleanedTarget
        val prefix = referenceTokens.take(lastAdministrativeIndex + 1).joinToString(" ")
        return "$prefix $cleanedTarget".trim()
    }

    private fun hasCityOrDistrict(address: String): Boolean =
        address.split(Regex("\\s+")).any { token ->
            token.endsWith("도") || token.endsWith("시") ||
                token.endsWith("군") || token.endsWith("구")
        }

    private fun inferKind(query: String, parcelName: String, roadName: String): AddressKind {
        val key = AddressTextParser.normalizeKey(query)
        return when {
            parcelName.isNotBlank() && AddressTextParser.normalizeKey(parcelName).endsWith(key) -> AddressKind.PARCEL
            roadName.isNotBlank() && AddressTextParser.normalizeKey(roadName).endsWith(key) -> AddressKind.ROAD
            Regex("(?:대로|로|길)\\s*\\d").containsMatchIn(query) -> AddressKind.ROAD
            else -> AddressKind.PARCEL
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        check(hasInternet()) { "Offline" }
        return connectionFactory(url).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 7_000
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun connectionFailure(message: String): ConversionOutcome =
        if (hasInternet()) ConversionOutcome.NetworkError(message) else ConversionOutcome.Offline

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun requestKey(query: String): String = listOf(
        AddressTextParser.normalizeKey(query),
        vworldApiKey.hashCode(),
        naverClientId.hashCode(),
        kakaoRestApiKey.hashCode()
    ).joinToString(":")

    private fun finishRequest(key: String, outcome: ConversionOutcome) {
        val callbacks = synchronized(requestLock) {
            if (outcome is ConversionOutcome.Success || outcome is ConversionOutcome.NotFound || outcome is ConversionOutcome.NoExactMatch) {
                cache[key] = outcome
            }
            inFlight.remove(key).orEmpty()
        }
        callbacks.forEach { it(outcome) }
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 64
    }
}
