package kr.co.addresslens

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import kr.co.addresslens.databinding.ActivityMenuBinding

class MenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.menuToolbar.setNavigationOnClickListener { finish() }
        val labels = mapOf(
            MenuButton.REGION to R.string.menu_region,
            MenuButton.FLASH to R.string.flash,
            MenuButton.MAP to R.string.open_in_map,
            MenuButton.CONVERT to R.string.convert,
            MenuButton.FREEZE to R.string.freeze_frame,
            MenuButton.RECOGNIZE_SELECTION to R.string.recognize_selection,
            MenuButton.COPY_INPUT to R.string.copy_input_address,
            MenuButton.COPY_RESULT to R.string.copy_result_address
        )
        val spacing = (16 * resources.displayMetrics.density).toInt()
        for (button in MenuButton.entries) {
            binding.menuOptions.addView(SwitchMaterial(this).apply {
                tag = button.preferenceKey
                setText(labels.getValue(button))
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                minHeight = (64 * resources.displayMetrics.density).toInt()
                setPadding(spacing, spacing / 2, spacing, spacing / 2)
                switchPadding = spacing
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isChecked = ApiSettingsStore.showMenuButton(context, button)
                setOnCheckedChangeListener { _, checked ->
                    ApiSettingsStore.setMenuButtonVisible(context, button, checked)
                }
            })
        }
    }
}
