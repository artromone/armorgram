package com.armorgram.feature.bridge

import android.os.Bundle
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal standalone settings activity to configure SMS-bridge mode without
 * having to wire into the existing MVI settings screen.
 *
 * Launchable via:
 *     adb shell am start -n com.armorgram/.feature.bridge.BridgeSettingsActivity
 *
 * Writes to the same default SharedPreferences that [com.armorgram.util.Preferences]
 * reads from, so changes take effect immediately for new SMS.
 */
class BridgeSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SMS bridge"
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        val help = TextView(this).apply {
            text = "Gateway phone is the number of your Phone1 (sms-gate.app server). " +
                "When enabled, SMS from that number are parsed as wire frames and shown " +
                "as separate chats per alias."
            setPadding(0, 0, 0, 32)
        }

        val gatewayLabel = TextView(this).apply {
            text = "Gateway phone (E.164, e.g. +79991234567)"
        }
        val gatewayField = EditText(this).apply {
            setText(prefs.getString("bridgeGatewayPhone", ""))
            hint = "+7..."
            setSingleLine(true)
        }

        val switch = Switch(this).apply {
            text = "Bridge mode enabled"
            isChecked = prefs.getBoolean("bridgeEnabled", false)
            gravity = Gravity.START
        }

        val seqRow = TextView(this).apply {
            text = "Outbound seq: ${prefs.getLong("bridgeOutSeq", 0L)}"
            setPadding(0, 32, 0, 0)
        }

        val saveBtn = Button(this).apply {
            text = "Save"
            setOnClickListener {
                prefs.edit()
                    .putString("bridgeGatewayPhone", gatewayField.text.toString().trim())
                    .putBoolean("bridgeEnabled", switch.isChecked)
                    .apply()
                Toast.makeText(this@BridgeSettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        val resetBtn = Button(this).apply {
            text = "Reset outbound seq to 0"
            setOnClickListener {
                prefs.edit().putLong("bridgeOutSeq", 0L).apply()
                seqRow.text = "Outbound seq: 0"
            }
        }

        listOf(help, gatewayLabel, gatewayField, switch, seqRow, saveBtn, resetBtn).forEach(root::addView)
        setContentView(root)
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
