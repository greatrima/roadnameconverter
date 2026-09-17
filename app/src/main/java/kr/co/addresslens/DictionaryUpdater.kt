package kr.co.addresslens

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

sealed interface DictionaryUpdateResult {
    data class Updated(val version: String, val bytes: Long) : DictionaryUpdateResult
    data object NoAsset : DictionaryUpdateResult
    data object UpToDate : DictionaryUpdateResult
    data class Error(val message: String) : DictionaryUpdateResult
}

object DictionaryUpdater {
    private const val RELEASES_URL =
        "https://api.github.com/repos/greatrima/roadnameconverter/releases/latest"
    private const val ASSET_PREFIX = "address_dictionary"
    private const val MAX_UNCOMPRESSED_CHARS = 100_000_000L
    private val executor = Executors.newSingleThreadExecutor()
    private val initialAttemptStarted = AtomicBoolean(false)
    private const val INITIAL_CHECK_DONE = "initial_dictionary_check_done_v1"

    fun initializeOnce(context: Context, callback: (DictionaryUpdateResult) -> Unit) {
        val app = context.applicationContext
        if (!NetworkAvailability.isOnline(app)) return
        if (ApiSettingsStore.preferences(app).getBoolean(INITIAL_CHECK_DONE, false) ||
            !initialAttemptStarted.compareAndSet(false, true)) return
        // Use the bundled dictionary immediately; retry on a future launch after an offline failure.
        update(app) { result ->
            if (result !is DictionaryUpdateResult.Error) {
                ApiSettingsStore.preferences(app).edit().putBoolean(INITIAL_CHECK_DONE, true).apply()
            }
            initialAttemptStarted.set(false)
            callback(result)
        }
    }

    fun update(context: Context, callback: (DictionaryUpdateResult) -> Unit) {
        val appContext = context.applicationContext
        if (!NetworkAvailability.isOnline(appContext)) {
            callback(DictionaryUpdateResult.Error(appContext.getString(R.string.dictionary_update_offline)))
            return
        }
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                check(NetworkAvailability.isOnline(appContext)) { appContext.getString(R.string.dictionary_update_offline) }
                connection = open(URL(RELEASES_URL))
                if (connection.responseCode !in 200..299) {
                    callback(DictionaryUpdateResult.Error("GitHub 응답 코드 ${connection.responseCode}"))
                    return@execute
                }
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val assets = release.optJSONArray("assets")
                var downloadUrl: String? = null
                if (assets != null) for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.startsWith(ASSET_PREFIX) && name.endsWith(".tsv.gz")) {
                        downloadUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (downloadUrl.isNullOrBlank()) {
                    callback(DictionaryUpdateResult.NoAsset)
                    return@execute
                }
                connection.disconnect()
                check(NetworkAvailability.isOnline(appContext)) { appContext.getString(R.string.dictionary_update_offline) }
                connection = open(URL(downloadUrl))
                if (connection.responseCode !in 200..299) {
                    callback(DictionaryUpdateResult.Error("사전 다운로드 응답 코드 ${connection.responseCode}"))
                    return@execute
                }
                val target = File(appContext.filesDir, AddressDictionary.UPDATED_FILE_NAME)
                val temporary = File(appContext.filesDir, "${AddressDictionary.UPDATED_FILE_NAME}.download")
                connection.inputStream.use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(16_384)
                    var total = 0L
                    while (true) {
                        check(NetworkAvailability.isOnline(appContext)) { appContext.getString(R.string.dictionary_update_offline) }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 20_000_000L) { "사전 파일 크기가 너무 큽니다." }
                        output.write(buffer, 0, count)
                    }
                } }
                val version = validate(temporary)
                val currentVersion = AddressDictionary.get(appContext).version
                val currentDigest = if (target.isFile) {
                    try {
                        DictionaryFiles.contentDigest(target.inputStream())
                    } catch (_: Exception) {
                        DictionaryFiles.contentDigest(AddressDictionary.openBundled(appContext))
                    }
                } else DictionaryFiles.contentDigest(AddressDictionary.openBundled(appContext))
                if (isOlderVersion(version, currentVersion) ||
                    currentDigest.contentEquals(DictionaryFiles.contentDigest(temporary.inputStream()))) {
                    temporary.delete()
                    callback(DictionaryUpdateResult.UpToDate)
                    return@execute
                }
                // POSIX rename replaces the validated file atomically on supported Android versions.
                Os.rename(temporary.absolutePath, target.absolutePath)
                AddressDictionary.invalidate()
                callback(DictionaryUpdateResult.Updated(version, target.length()))
            } catch (error: Exception) {
                callback(DictionaryUpdateResult.Error(error.message ?: "사전 업데이트 오류"))
            } finally {
                connection?.disconnect()
            }
        }
    }

    internal fun validate(file: File): String {
        require(file.length() in 1_024..20_000_000) { "사전 파일 크기가 올바르지 않습니다." }
        return file.inputStream().use { source ->
            GZIPInputStream(source.buffered()).bufferedReader(Charsets.UTF_8).use { reader ->
                require(reader.readLine() == AddressDictionary.FORMAT_HEADER) {
                    "지원하지 않는 사전입니다."
                }
                val generatedLine = requireNotNull(reader.readLine()) {
                    "사전 버전 정보가 없습니다."
                }
                require(generatedLine.startsWith("# generated=")) { "사전 버전 정보가 없습니다." }
                val version = generatedLine.substringAfter('=').trim().ifBlank { "갱신본" }
                var entryCount = 0
                var uncompressedChars = AddressDictionary.FORMAT_HEADER.length.toLong() +
                    generatedLine.length + 2L
                reader.lineSequence().forEach { line ->
                    uncompressedChars += line.length + 1L
                    require(uncompressedChars <= MAX_UNCOMPRESSED_CHARS) {
                        "사전 압축 해제 크기가 너무 큽니다."
                    }
                    if (line.isBlank() || line.startsWith('#')) return@forEach
                    val fields = line.split('\t')
                    require(fields.size >= 4 && fields[1].isNotBlank() && fields[2].isNotBlank()) {
                        "사전 항목 형식이 올바르지 않습니다."
                    }
                    when (fields[0]) {
                        "A" -> if (fields[3].isNotBlank()) entryCount++
                        "R" -> {
                            require(fields.size >= 5 && fields[4].isNotBlank()) {
                                "도로명 사전 항목 형식이 올바르지 않습니다."
                            }
                            entryCount++
                        }
                        else -> error("알 수 없는 사전 항목입니다.")
                    }
                }
                require(entryCount > 0) { "사전에 주소 항목이 없습니다." }
                version
            }
        }
    }

    internal fun isOlderVersion(downloaded: String, installed: String): Boolean {
        val date = Regex("\\d{4}-\\d{2}-\\d{2}")
        val remoteDate = date.find(downloaded)?.value ?: return false
        val localDate = date.find(installed)?.value ?: return false
        return remoteDate < localDate
    }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        setRequestProperty("User-Agent", "RoadNameConverter/${BuildConfig.VERSION_NAME}")
    }
}
