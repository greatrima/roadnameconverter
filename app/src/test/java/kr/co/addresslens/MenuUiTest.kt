package kr.co.addresslens

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kr.co.addresslens.databinding.ActivityMainBinding
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuUiTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        ApiSettingsStore.preferences(context).edit().clear().commit()
    }

    @Test fun defaultsKeepButtonsAndOnlyEightOptionalSwitchesExist() {
        val controller = Robolectric.buildActivity(MenuActivity::class.java).setup()
        try {
            val options = controller.get().findViewById<ViewGroup>(R.id.menuOptions)
            assertEquals(8, options.childCount)
            for (button in MenuButton.entries) {
                val toggle = options.findViewWithTag<SwitchMaterial>(button.preferenceKey)
                assertTrue(toggle.isChecked)
            }
            val root = controller.get().findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
            render(root, "menu")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun togglesPersistAcrossRecreationAndMigrationWithoutChangingFeatures() {
        ApiSettingsStore.preferences(context).edit()
            .putBoolean(ApiSettingsStore.FREEZE_SELECTION, true)
            .putBoolean(ApiSettingsStore.CONTINUOUS_SCAN, true)
            .putBoolean(ApiSettingsStore.OFFLINE_MODE, true).commit()
        val first = Robolectric.buildActivity(MenuActivity::class.java).setup()
        MenuButton.entries.forEach { button ->
            first.get().findViewById<ViewGroup>(R.id.menuOptions)
                .findViewWithTag<SwitchMaterial>(button.preferenceKey).performClick()
        }
        first.pause().stop().destroy()
        ApiSettingsStore.migrate(context)
        val second = Robolectric.buildActivity(MenuActivity::class.java).setup()
        try {
            MenuButton.entries.forEach { button ->
                assertFalse(ApiSettingsStore.showMenuButton(context, button))
                assertFalse(second.get().findViewById<ViewGroup>(R.id.menuOptions)
                    .findViewWithTag<SwitchMaterial>(button.preferenceKey).isChecked)
            }
            assertTrue(ApiSettingsStore.freezeSelection(context))
            assertTrue(ApiSettingsStore.continuousScan(context))
            assertTrue(ApiSettingsStore.offlineMode(context))
        } finally { second.pause().stop().destroy() }
    }

    @Test fun actionRowsWrapLargeTextAndReclaimHiddenButtonSpace() {
        val config = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_AddressLens)
        val row = ActionRow(themed)
        val convert = MaterialButton(themed).apply { text = "주소 변환"; textSize = 28f }
        val again = MaterialButton(themed).apply { text = "다시 인식"; textSize = 28f }
        row.addView(convert)
        row.addView(again)
        measure(row, 240)
        assertTrue("Buttons must wrap: first=${convert.width}x${convert.height}, second=${again.width} at ${again.top}",
            again.top >= convert.bottom)
        assertTrue(again.right <= row.width)
        val oldHeight = row.height
        convert.visibility = View.GONE
        measure(row, 240)
        assertEquals(0, again.left)
        assertEquals(0, again.top)
        assertEquals(row.width, again.width)
        assertTrue(row.height < oldHeight)
        again.visibility = View.GONE
        measure(row, 240)
        assertEquals(0, row.height)
    }

    @Test fun mainLayoutKeepsSettingsAndRescanOutsideScrollableResults() {
        for (font in listOf(1f, 2f)) {
            val config = Configuration(context.resources.configuration).apply { fontScale = font }
            val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_AddressLens)
            val ui = ActivityMainBinding.inflate(LayoutInflater.from(themed))
            ui.regionButton.text = "경기도 안산시 단원구"
            ui.addressInput.setText("경기도 안산시 단원구 초지동 716-7")
            ui.roadAddressText.text = "경기도 안산시 단원구 원선1로 37"
            ui.copyInputButton.isEnabled = true
            ui.copyResultButton.isEnabled = true
            ui.cameraPane.layoutParams.height = 144
            ui.root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            ui.root.layout(0, 0, 320, 480)
            assertTrue(ui.resultCard.height > 0)
            assertTrue(ui.infoToolbar.bottom <= ui.resultCard.top)
            assertTrue(ui.resultCard.bottom <= ui.actionBar.top)
            assertTrue(ui.scanAgainButton.height >= 48)
            assertEquals(View.VISIBLE, ui.apiKeyButton.visibility)
            assertEquals(View.VISIBLE, ui.scanAgainButton.visibility)
            render(ui.root, "main-font-$font")
            if (font == 1f) {
                ui.root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(760, View.MeasureSpec.EXACTLY))
                ui.root.layout(0, 0, 360, 760)
                render(ui.root, "main-normal")
            }
        }
    }

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun render(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/ui/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
