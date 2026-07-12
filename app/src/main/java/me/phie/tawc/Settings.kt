package me.phie.tawc

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.install.EnabledGraphicsBackends
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.verticalLp

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scaffold = buildChildScreen(getString(R.string.title_settings))
        val pad = (16 * resources.displayMetrics.density).toInt()

        scaffold.content.addView(
            buildSectionCard(getString(R.string.settings_graphics_driver), buildGraphicsBackendGroup()),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )
        scaffold.content.addView(
            buildSectionCard(getString(R.string.settings_debug_rendering), buildTintBuffersCheckbox()),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )
        scaffold.content.addView(
            buildSectionCard(getString(R.string.settings_compatibility), buildCompatibilitySettings()),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )
        scaffold.content.addView(
            buildOutputScaleCard(),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )

        // Новая кнопка просмотра логов
        scaffold.content.addView(
            buildSectionCard("Логирование", buildLogButton()),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )

        setContentView(scaffold.root)
    }

    private fun buildLogButton(): android.view.View {
        val button = android.widget.Button(this).apply {
            text = "📋 Просмотр логов приложения"
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, LogActivity::class.java))
            }
        }
        return button
    }

    // Остальной код без изменений (buildSectionCard, buildGraphicsBackendGroup и т.д.)
    private fun buildSectionCard(title: String, body: android.view.View): android.view.View {
        val cardPad = (12 * resources.displayMetrics.density).toInt()
        val card = tawcCard()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
        }
        column.addView(
            TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        column.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(column)
        return card
    }

    // ... (остальные методы buildGraphicsBackendGroup, buildTintBuffersCheckbox и т.д. оставляем как были)
    private fun buildGraphicsBackendGroup(): RadioGroup { /* твой существующий код */ return RadioGroup(this) }
    private fun buildTintBuffersCheckbox(): CheckBox { /* твой код */ return CheckBox(this) }
    private fun buildCompatibilitySettings(): android.view.View { /* твой код */ return LinearLayout(this) }
    private fun buildOutputScaleCard(): android.view.View { /* твой код */ return tawcCard() }
}
