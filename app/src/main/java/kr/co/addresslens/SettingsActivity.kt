package kr.co.addresslens

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kr.co.addresslens.databinding.ActivitySettingsBinding
import kr.co.addresslens.databinding.DialogKakaoApiBinding
import kr.co.addresslens.databinding.DialogNaverApiBinding
import kr.co.addresslens.databinding.DialogVworldApiBinding
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var updatingVworldSwitch = false
    private val settingsExecutor = Executors.newSingleThreadExecutor()
    private var dictionary: AddressDictionary? = null
    private var dictionaryLoading = false
    private var openRegionWhenReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = ContextCompat.getColor(this, R.color.navy)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.paper)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                    Configuration.UI_MODE_NIGHT_YES
        }
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val toolbarHeight = binding.settingsToolbar.layoutParams.height
        val toolbarPaddingTop = binding.settingsToolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.settingsToolbar.updateLayoutParams {
                height = toolbarHeight + bars.top
            }
            binding.settingsToolbar.setPadding(
                binding.settingsToolbar.paddingLeft,
                toolbarPaddingTop + bars.top,
                binding.settingsToolbar.paddingRight,
                binding.settingsToolbar.paddingBottom
            )
            binding.settingsScroll.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        binding.settingsToolbar.setNavigationOnClickListener { finish() }
        binding.regionCard.setOnClickListener { requestRegionPicker() }
        binding.candidateListSwitch.isChecked = ApiSettingsStore.showCandidateList(this)
        binding.candidateListSwitch.setOnCheckedChangeListener { _, checked ->
            ApiSettingsStore.preferences(this).edit()
                .putBoolean(ApiSettingsStore.SHOW_CANDIDATE_LIST, checked).apply()
        }
        binding.continuousScanSwitch.isChecked = ApiSettingsStore.continuousScan(this)
        binding.continuousScanSwitch.setOnCheckedChangeListener { _, checked ->
            ApiSettingsStore.preferences(this).edit()
                .putBoolean(ApiSettingsStore.CONTINUOUS_SCAN, checked).apply()
        }
        binding.dictionaryUpdateCard.setOnClickListener { updateDictionary() }
        binding.freezeSelectionSwitch.isChecked = ApiSettingsStore.freezeSelection(this)
        binding.freezeSelectionSwitch.setOnCheckedChangeListener { _, checked ->
            ApiSettingsStore.preferences(this).edit().putBoolean(ApiSettingsStore.FREEZE_SELECTION, checked).apply()
        }
        binding.developerModeSwitch.isChecked = ApiSettingsStore.developerMode(this)
        binding.developerModeSwitch.setOnCheckedChangeListener { _, checked ->
            ApiSettingsStore.preferences(this).edit().putBoolean(ApiSettingsStore.DEVELOPER_MODE, checked).apply()
        }
        binding.vworldCard.setOnClickListener { showVworldEditor() }
        binding.vworldEnabledSwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingVworldSwitch) return@setOnCheckedChangeListener
            ApiSettingsStore.preferences(this)
                .edit()
                .putBoolean(ApiSettingsStore.VWORLD_ENABLED, enabled)
                .apply()
            updateProviderStatuses()
        }
        binding.naverCard.setOnClickListener { showNaverEditor() }
        binding.kakaoCard.setOnClickListener { showKakaoEditor() }
        binding.updateCard.setOnClickListener { checkForUpdates() }
        binding.versionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        updateProviderStatuses()
        updateRegionStatus()
        loadDictionary()
    }

    private fun requestRegionPicker() {
        if (dictionary != null) {
            showProvincePicker()
            return
        }
        openRegionWhenReady = true
        if (dictionaryLoading) {
            Toast.makeText(this, R.string.region_loading_wait, Toast.LENGTH_SHORT).show()
        } else loadDictionary()
    }

    private fun loadDictionary() {
        if (dictionaryLoading) return
        dictionaryLoading = true
        binding.regionStatusText.setText(R.string.region_dictionary_loading)
        val appContext = applicationContext
        settingsExecutor.execute {
            try {
                val loaded = AddressDictionary.get(appContext)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dictionaryLoading = false
                    dictionary = loaded
                    updateRegionStatus()
                    binding.dictionaryStatusText.text = getString(
                        R.string.dictionary_version_status, loaded.version
                    )
                    if (openRegionWhenReady || intent.getBooleanExtra(EXTRA_OPEN_REGION, false)) {
                        openRegionWhenReady = false
                        intent.removeExtra(EXTRA_OPEN_REGION)
                        showProvincePicker()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dictionaryLoading = false
                    openRegionWhenReady = false
                    binding.regionStatusText.setText(R.string.region_dictionary_retry)
                    binding.dictionaryStatusText.setText(R.string.dictionary_load_failed)
                }
            }
        }
    }

    private fun updateRegionStatus() {
        val region = ApiSettingsStore.loadRegion(this)
        binding.regionStatusText.text = if (region.localities.isEmpty()) {
            region.displayName()
        } else {
            getString(R.string.region_with_localities, region.displayName(), region.localities.size)
        }
    }

    private fun showProvincePicker() {
        val dictionary = dictionary ?: return
        val provinces = dictionary.provinces()
        val choices = listOf(getString(R.string.no_region_preference)) + provinces
        var selected = provinces.indexOf(ApiSettingsStore.loadRegion(this).province) + 1
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_province)
            .setSingleChoiceItems(choices.toTypedArray(), selected) { shown, index ->
                selected = index
                (shown as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = index > 0
            }
            .setPositiveButton(R.string.save_region_here) { _, _ ->
                saveRegion(if (selected == 0) RegionSelection() else RegionSelection(provinces[selected - 1]))
            }
            .setNeutralButton(R.string.choose_region_details) { _, _ ->
                if (selected > 0) showDistrictPicker(provinces[selected - 1])
            }
            .setNegativeButton(R.string.cancel, null).show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = selected > 0
    }

    private fun showDistrictPicker(province: String) {
        val dictionary = dictionary ?: return
        val districts = dictionary.districts(province)
        if (districts.isEmpty()) {
            saveRegion(RegionSelection(province))
            return
        }
        val current = ApiSettingsStore.loadRegion(this)
        var selected = if (current.province == province) districts.indexOf(current.district) + 1 else 0
        val choices = listOf(getString(R.string.use_whole_province, province)) + districts
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.choose_district_in_province, province))
            .setSingleChoiceItems(choices.toTypedArray(), selected) { shown, index ->
                selected = index
                (shown as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = index > 0
            }
            .setPositiveButton(R.string.save_region_here) { _, _ ->
                saveRegion(RegionSelection(province, if (selected == 0) "" else districts[selected - 1]))
            }
            .setNeutralButton(R.string.choose_region_details) { _, _ ->
                if (selected > 0) showLocalityPicker(province, districts[selected - 1])
            }
            .setNegativeButton(R.string.cancel, null).show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = selected > 0
    }

    private fun showLocalityPicker(province: String, district: String) {
        val dictionary = dictionary ?: return
        val localities = dictionary.localities(province, district)
        if (localities.isEmpty()) {
            saveRegion(RegionSelection(province, district))
            return
        }
        val current = ApiSettingsStore.loadRegion(this)
        val checked = BooleanArray(localities.size) { index ->
            current.province == province && current.district == district &&
                localities[index] in current.localities
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_localities)
            .setMultiChoiceItems(localities.toTypedArray(), checked) { _, which, value -> checked[which] = value }
            .setNeutralButton(R.string.no_locality_limit) { _, _ ->
                saveRegion(RegionSelection(province, district))
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val selected = localities.filterIndexed { index, _ -> checked[index] }.toSet()
                saveRegion(RegionSelection(province, district, selected))
            }.show()
    }

    private fun saveRegion(region: RegionSelection) {
        ApiSettingsStore.saveRegion(this, region)
        updateRegionStatus()
        Toast.makeText(this, R.string.region_saved, Toast.LENGTH_SHORT).show()
    }

    private fun updateDictionary() {
        binding.dictionaryUpdateCard.isEnabled = false
        binding.dictionaryStatusText.setText(R.string.dictionary_checking)
        DictionaryUpdater.update(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.dictionaryUpdateCard.isEnabled = true
                when (result) {
                    is DictionaryUpdateResult.Updated -> {
                        binding.dictionaryStatusText.text = getString(
                            R.string.dictionary_updated,
                            result.version,
                            android.text.format.Formatter.formatFileSize(this, result.bytes)
                        )
                        dictionary = null
                        loadDictionary()
                    }
                    DictionaryUpdateResult.NoAsset ->
                        binding.dictionaryStatusText.setText(R.string.dictionary_no_asset)
                    DictionaryUpdateResult.UpToDate -> {
                        binding.dictionaryStatusText.setText(R.string.dictionary_current)
                        if (dictionary == null) loadDictionary()
                    }
                    is DictionaryUpdateResult.Error -> binding.dictionaryStatusText.text =
                        getString(R.string.dictionary_update_failed, result.message)
                }
            }
        }
    }

    private fun updateProviderStatuses() {
        val hasPrivateApi = ApiSettingsStore.hasPrivateApi(this)
        val vworldEnabled = ApiSettingsStore.isVworldEnabled(this)
        updatingVworldSwitch = true
        binding.vworldEnabledSwitch.isChecked = vworldEnabled
        binding.vworldEnabledSwitch.isEnabled = hasPrivateApi
        updatingVworldSwitch = false
        binding.vworldStatusText.setText(
            when {
                !hasPrivateApi -> R.string.vworld_automatic
                vworldEnabled -> R.string.vworld_enabled
                else -> R.string.vworld_disabled
            }
        )
        binding.vworldStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (vworldEnabled) R.color.success else R.color.muted
            )
        )
        setProviderStatus(
            binding.naverStatusText,
            ApiSettingsStore.hasNaver(this)
        )
        setProviderStatus(
            binding.kakaoStatusText,
            ApiSettingsStore.hasKakao(this)
        )
    }

    private fun setProviderStatus(view: TextView, configured: Boolean) {
        view.setText(
            if (configured) R.string.settings_configured
            else R.string.settings_not_configured
        )
        view.setTextColor(
            ContextCompat.getColor(this, if (configured) R.color.success else R.color.muted)
        )
    }

    private fun showVworldEditor() {
        val editor = DialogVworldApiBinding.inflate(layoutInflater)
        // Deliberately do not place the bundled or saved key into the field.
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vworld_provider)
            .setView(editor.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.use_bundled_api, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                ApiSettingsStore.preferences(this)
                    .edit()
                    .remove(ApiSettingsStore.VWORLD_CUSTOM_KEY)
                    .apply()
                updateProviderStatuses()
                showSavedToast()
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = editor.vworldApiKeyInput.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    editor.vworldApiKeyLayout.error = getString(R.string.enter_api_key)
                    return@setOnClickListener
                }
                ApiSettingsStore.preferences(this)
                    .edit()
                    .putString(ApiSettingsStore.VWORLD_CUSTOM_KEY, key)
                    .apply()
                updateProviderStatuses()
                showSavedToast()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showNaverEditor() {
        val editor = DialogNaverApiBinding.inflate(layoutInflater)
        val preferences = ApiSettingsStore.preferences(this)
        editor.naverClientIdInput.setText(
            preferences.getString(ApiSettingsStore.NAVER_CLIENT_ID, null).orEmpty()
        )
        editor.naverClientSecretInput.setText(
            preferences.getString(ApiSettingsStore.NAVER_CLIENT_SECRET, null).orEmpty()
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.naver_provider)
            .setView(editor.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete_settings, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                preferences.edit()
                    .remove(ApiSettingsStore.NAVER_CLIENT_ID)
                    .remove(ApiSettingsStore.NAVER_CLIENT_SECRET)
                    .apply()
                updateProviderStatuses()
                showSavedToast()
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clientId = editor.naverClientIdInput.text?.toString()?.trim().orEmpty()
                val secret = editor.naverClientSecretInput.text?.toString()?.trim().orEmpty()
                editor.naverClientIdLayout.error = null
                editor.naverClientSecretLayout.error = null
                if (clientId.isBlank() || secret.isBlank()) {
                    if (clientId.isBlank()) {
                        editor.naverClientIdLayout.error = getString(R.string.enter_client_id)
                    }
                    if (secret.isBlank()) {
                        editor.naverClientSecretLayout.error = getString(R.string.enter_client_secret)
                    }
                    return@setOnClickListener
                }
                preferences.edit()
                    .putString(ApiSettingsStore.NAVER_CLIENT_ID, clientId)
                    .putString(ApiSettingsStore.NAVER_CLIENT_SECRET, secret)
                    .apply()
                updateProviderStatuses()
                showSavedToast()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showKakaoEditor() {
        val editor = DialogKakaoApiBinding.inflate(layoutInflater)
        val preferences = ApiSettingsStore.preferences(this)
        editor.kakaoApiKeyInput.setText(
            preferences.getString(ApiSettingsStore.KAKAO_REST_API_KEY, null).orEmpty()
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.kakao_provider)
            .setView(editor.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete_settings, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                preferences.edit()
                    .remove(ApiSettingsStore.KAKAO_REST_API_KEY)
                    .apply()
                updateProviderStatuses()
                showSavedToast()
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = editor.kakaoApiKeyInput.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    editor.kakaoApiKeyLayout.error = getString(R.string.enter_api_key)
                    return@setOnClickListener
                }
                preferences.edit()
                    .putString(ApiSettingsStore.KAKAO_REST_API_KEY, key)
                    .apply()
                updateProviderStatuses()
                showSavedToast()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun checkForUpdates() {
        binding.updateCard.isEnabled = false
        binding.updateStatusText.setText(R.string.checking_for_updates)
        UpdateChecker.check(BuildConfig.VERSION_NAME) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.updateCard.isEnabled = true
                when (result) {
                    is UpdateCheckResult.Available -> {
                        UpdateChecker.recordSuccessfulCheck(this)
                        binding.updateStatusText.text =
                            getString(R.string.new_version_available, result.release.version)
                        showUpdateDialog(result.release)
                    }
                    UpdateCheckResult.UpToDate -> {
                        UpdateChecker.recordSuccessfulCheck(this)
                        binding.updateStatusText.setText(R.string.latest_version_in_use)
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.update)
                            .setMessage(R.string.latest_version_in_use)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    UpdateCheckResult.NoRelease -> {
                        UpdateChecker.recordSuccessfulCheck(this)
                        binding.updateStatusText.setText(R.string.no_github_release)
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.update)
                            .setMessage(R.string.no_github_release)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    is UpdateCheckResult.Error -> {
                        binding.updateStatusText.setText(R.string.update_check_failed)
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.update)
                            .setMessage(result.message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available)
            .setMessage(getString(R.string.update_available_message, release.version))
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(
                if (release.hasApkAsset) R.string.download_apk else R.string.open_github
            ) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
            }
            .show()
    }

    private fun showSavedToast() {
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        settingsExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_REGION = "open_region_settings"
    }
}
