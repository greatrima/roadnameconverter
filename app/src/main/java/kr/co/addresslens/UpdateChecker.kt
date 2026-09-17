package kr.co.addresslens

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class GitHubRelease(
    val version: String,
    val downloadUrl: String,
    val hasApkAsset: Boolean
)

sealed interface UpdateCheckResult {
    data class Available(val release: GitHubRelease) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/greatrima/roadnameconverter/releases/latest"
    private const val CHECK_PREFERENCES = "update_check_preferences"
    private const val LAST_SUCCESSFUL_CHECK = "last_successful_check"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val executor = Executors.newSingleThreadExecutor()

    fun check(context: Context, currentVersion: String, callback: (UpdateCheckResult) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            if (!NetworkAvailability.isOnline(app)) {
                callback(UpdateCheckResult.Error(app.getString(R.string.update_check_offline)))
                return@execute
            }
            var connection: HttpURLConnection? = null
            val result = try {
                connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7_000
                    readTimeout = 7_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "RoadNameConverter-Android")
                }
                when (val status = connection.responseCode) {
                    200 -> parseLatestRelease(
                        connection.inputStream.bufferedReader().use { it.readText() },
                        currentVersion
                    )
                    404 -> UpdateCheckResult.NoRelease
                    else -> UpdateCheckResult.Error("GitHub 응답 코드 $status")
                }
            } catch (_: Exception) {
                UpdateCheckResult.Error("업데이트 서버에 연결할 수 없습니다.")
            } finally {
                connection?.disconnect()
            }
            callback(result)
        }
    }

    fun shouldAutoCheck(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val lastCheck = context.getSharedPreferences(CHECK_PREFERENCES, Context.MODE_PRIVATE)
            .getLong(LAST_SUCCESSFUL_CHECK, 0L)
        return now - lastCheck >= AUTO_CHECK_INTERVAL_MS
    }

    fun recordSuccessfulCheck(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(CHECK_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_SUCCESSFUL_CHECK, now)
            .apply()
    }

    internal fun isNewerVersion(latestTag: String, currentVersion: String): Boolean {
        val latest = versionParts(latestTag)
        val current = versionParts(currentVersion)
        if (latest.isEmpty() || current.isEmpty()) return false
        val size = maxOf(latest.size, current.size)
        for (index in 0 until size) {
            val latestPart = latest.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (latestPart != currentPart) return latestPart > currentPart
        }
        return false
    }

    internal fun parseLatestRelease(body: String, currentVersion: String): UpdateCheckResult {
        return try {
            val release = JSONObject(body)
            val tag = release.optString("tag_name").trim()
            val releasePage = release.optString("html_url").trim()
            if (tag.isBlank() || releasePage.isBlank()) {
                return UpdateCheckResult.Error("GitHub Release 정보가 올바르지 않습니다.")
            }
            val assets = release.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    if (asset.optString("name").lowercase().endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url").trim()
                        if (apkUrl.isNotBlank()) break
                    }
                }
            }
            if (isNewerVersion(tag, currentVersion)) {
                UpdateCheckResult.Available(
                    GitHubRelease(
                        version = tag.removePrefix("v").removePrefix("V"),
                        downloadUrl = apkUrl.ifBlank { releasePage },
                        hasApkAsset = apkUrl.isNotBlank()
                    )
                )
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (_: Exception) {
            UpdateCheckResult.Error("GitHub Release 정보를 처리할 수 없습니다.")
        }
    }

    private fun versionParts(version: String): List<Int> = Regex("\\d+")
        .findAll(version.substringBefore('-'))
        .mapNotNull { it.value.toIntOrNull() }
        .toList()
}
