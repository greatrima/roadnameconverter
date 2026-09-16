package kr.co.addresslens

import android.content.Context
import android.content.SharedPreferences

data class ApiCredentials(
    val vworldApiKey: String,
    val naverClientId: String,
    val naverClientSecret: String,
    val kakaoRestApiKey: String
)

object ApiSettingsStore {
    const val PREFERENCES = "address_lens_preferences"
    const val VWORLD_CUSTOM_KEY = "vworld_custom_api_key"
    const val VWORLD_ENABLED = "vworld_enabled"
    const val NAVER_CLIENT_ID = "naver_map_client_id"
    const val NAVER_CLIENT_SECRET = "naver_map_client_secret"
    const val KAKAO_REST_API_KEY = "kakao_rest_api_key"
    const val DEFAULT_PROVINCE = "default_region_province"
    const val DEFAULT_DISTRICT = "default_region_district"
    const val DEFAULT_LOCALITIES = "default_region_localities"
    const val SHOW_CANDIDATE_LIST = "show_candidate_list"
    const val CONTINUOUS_SCAN = "continuous_scan"
    const val FREEZE_SELECTION = "freeze_selection"
    const val DEVELOPER_MODE = "developer_mode"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun migrate(context: Context) {
        val preferences = preferences(context)
        if (!preferences.getBoolean("bottom_candidates_v1", false)) {
            preferences.edit().putBoolean(SHOW_CANDIDATE_LIST, true)
                .putBoolean("bottom_candidates_v1", true).apply()
        }
        if (preferences.getBoolean(MIGRATED_TO_HIDDEN_VWORLD, false)) return

        val oldVworldKey = preferences.getString(LEGACY_VWORLD_KEY, null).orEmpty()
        preferences.edit().apply {
            if (oldVworldKey.isNotBlank() && oldVworldKey != BuildConfig.VWORLD_API_KEY) {
                putString(VWORLD_CUSTOM_KEY, oldVworldKey)
            }
            remove(LEGACY_VWORLD_KEY)
            remove(LEGACY_VWORLD_DEFAULT_VERSION)
            putBoolean(MIGRATED_TO_HIDDEN_VWORLD, true)
        }.apply()
    }

    fun load(context: Context): ApiCredentials {
        val preferences = preferences(context)
        val useVworld = isVworldEnabled(context)
        return ApiCredentials(
            vworldApiKey = if (useVworld) {
                preferences.getString(VWORLD_CUSTOM_KEY, null)
                    .orEmpty()
                    .ifBlank { BuildConfig.VWORLD_API_KEY }
            } else {
                ""
            },
            naverClientId = preferences.getString(NAVER_CLIENT_ID, null).orEmpty(),
            naverClientSecret = preferences.getString(NAVER_CLIENT_SECRET, null).orEmpty(),
            kakaoRestApiKey = preferences.getString(KAKAO_REST_API_KEY, null).orEmpty()
        )
    }

    fun hasCustomVworld(context: Context): Boolean =
        preferences(context).getString(VWORLD_CUSTOM_KEY, null).orEmpty().isNotBlank()

    fun hasNaver(context: Context): Boolean {
        val preferences = preferences(context)
        return preferences.getString(NAVER_CLIENT_ID, null).orEmpty().isNotBlank() &&
            preferences.getString(NAVER_CLIENT_SECRET, null).orEmpty().isNotBlank()
    }

    fun hasKakao(context: Context): Boolean =
        preferences(context).getString(KAKAO_REST_API_KEY, null).orEmpty().isNotBlank()

    fun hasPrivateApi(context: Context): Boolean = hasNaver(context) || hasKakao(context)

    fun loadRegion(context: Context): RegionSelection {
        val preferences = preferences(context)
        return RegionSelection(
            province = preferences.getString(DEFAULT_PROVINCE, null).orEmpty(),
            district = preferences.getString(DEFAULT_DISTRICT, null).orEmpty(),
            localities = preferences.getStringSet(DEFAULT_LOCALITIES, emptySet()).orEmpty().toSet()
        )
    }

    fun saveRegion(context: Context, selection: RegionSelection) {
        preferences(context).edit()
            .putString(DEFAULT_PROVINCE, selection.province)
            .putString(DEFAULT_DISTRICT, selection.district)
            .putStringSet(DEFAULT_LOCALITIES, selection.localities)
            .apply()
    }

    fun showCandidateList(context: Context): Boolean =
        preferences(context).getBoolean(SHOW_CANDIDATE_LIST, true)

    fun continuousScan(context: Context): Boolean =
        preferences(context).getBoolean(CONTINUOUS_SCAN, false)

    fun freezeSelection(context: Context): Boolean = preferences(context).getBoolean(FREEZE_SELECTION, false)
    fun developerMode(context: Context): Boolean = preferences(context).getBoolean(DEVELOPER_MODE, false)

    fun isVworldEnabled(context: Context): Boolean =
        !hasPrivateApi(context) || preferences(context).getBoolean(VWORLD_ENABLED, false)

    private const val LEGACY_VWORLD_KEY = "vworld_api_key"
    private const val LEGACY_VWORLD_DEFAULT_VERSION = "vworld_default_version"
    private const val MIGRATED_TO_HIDDEN_VWORLD = "migrated_to_hidden_vworld_v1"
}
