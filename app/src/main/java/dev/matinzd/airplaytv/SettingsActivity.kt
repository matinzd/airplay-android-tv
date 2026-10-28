package dev.matinzd.airplaytv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.matinzd.airplaytv.TvUi.dp

/** Receiver settings, navigable with the TV remote. Changes restart the receiver. */
class SettingsActivity : Activity() {
    private lateinit var settings: ReceiverSettings
    private val refreshers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ReceiverSettings(this)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this@SettingsActivity, 80), dp(this@SettingsActivity, 48), dp(this@SettingsActivity, 80), dp(this@SettingsActivity, 48))
        }
        list.addView(TvUi.text(this, 36f, bold = true).apply { text = "Settings" })

        val nameRow = row(list, "AirPlay name", "The name shown in the AirPlay list on your devices",
            { settings.name ?: AirPlaySession.state.serverName }) { editName() }
        row(list, "Require a code for new devices", "A new iPhone, iPad or Mac must enter a code shown on the TV once",
            { onOff(settings.requirePin) }) { settings.requirePin = !settings.requirePin; apply() }
        row(list, "4K mirroring", "Sharper picture from devices that support it. Uses more Wi-Fi",
            { onOff(settings.allow4k) }) { settings.allow4k = !settings.allow4k; apply() }
        row(list, "Low-latency mirroring", "The picture is shown as soon as it arrives. Sound can be behind, and motion less smooth on a weak Wi-Fi",
            { onOff(settings.lowLatency) }) { settings.lowLatency = !settings.lowLatency; apply() }
        row(list, "Remembered devices", "Devices that entered the code. Select to forget them all",
            { settings.rememberedDevices.size.let { if (it == 0) "None" else "$it" } }) {
            settings.forgetAllDevices()
            apply()
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(TvUi.BG)
            isFillViewport = true
            addView(list)
        })
        nameRow.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    private fun onOff(value: Boolean) = if (value) "On" else "Off"

    /** One remote-friendly row: title and description on the left, current value on the right. */
    private fun row(list: LinearLayout, title: String, hint: String, value: () -> String, onSelect: () -> Unit): LinearLayout {
        val valueView = TvUi.text(this, 22f, TvUi.ACCENT, bold = true).apply { gravity = Gravity.END }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TvUi.text(this@SettingsActivity, 22f).apply { text = title })
            addView(TvUi.text(this@SettingsActivity, 16f, TvUi.TEXT_DIM).apply { text = hint })
        }
        val row = TvUi.card(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(valueView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@SettingsActivity, 24) })
            setOnClickListener { onSelect() }
        }, grow = false) as LinearLayout
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(this@SettingsActivity, 16) })
        refreshers += { valueView.text = value() }
        valueView.text = value()
        return row
    }

    private fun editName() {
        val input = EditText(this).apply {
            setText(settings.name ?: AirPlaySession.state.serverName)
            setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("AirPlay name")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> settings.name = input.text.toString(); apply() }
            .setNeutralButton("Use TV name") { _, _ -> settings.name = null; apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Restart the receiver so the change takes effect, then refresh the values. */
    private fun apply() {
        AirPlayService.restart(this)
        refreshers.forEach { it() }
        window.decorView.postDelayed({ refreshers.forEach { it() } }, 500)
    }
}
