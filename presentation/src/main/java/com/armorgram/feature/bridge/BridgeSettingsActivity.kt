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
import com.armorgram.bridge.BridgeRepository
import com.armorgram.injection.appComponent
import javax.inject.Inject

/**
 * Standalone settings + actions for SMS-bridge mode.
 *
 * Reachable from the main Settings screen ("SMS bridge" row) or via:
 *     adb shell am start -n com.armorgram/.feature.bridge.BridgeSettingsActivity
 */
class BridgeSettingsActivity : AppCompatActivity() {

    @Inject lateinit var bridge: BridgeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        appComponent.inject(this)
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
                "When enabled, SMS from that number is parsed as wire frames and shown " +
                "as separate chats per alias. Non-frame SMS from that number still arrive " +
                "into the regular conversation thread."
            setPadding(0, 0, 0, 32)
        }

        val gatewayLabel = TextView(this).apply { text = "Gateway phone (E.164)" }
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
            }
        }

        val resetBtn = Button(this).apply {
            text = "Reset outbound seq to 0"
            setOnClickListener {
                prefs.edit().putLong("bridgeOutSeq", 0L).apply()
                seqRow.text = "Outbound seq: 0"
            }
        }

        val divider = TextView(this).apply {
            text = "─── Send command ───"
            setPadding(0, 48, 0, 16)
            gravity = Gravity.CENTER
        }

        val cmdHelp = TextView(this).apply {
            text = "Send a control command to backend. Examples:\n" +
                "  /wl add abc      — whitelist alias\n" +
                "  /approve abc     — approve pending contact\n" +
                "  /block abc       — block contact\n" +
                "  /hist abc 20     — request last 20 messages\n" +
                "  /resend 100-110  — request resend of frames\n" +
                "  /ping            — heartbeat"
            setPadding(0, 0, 0, 16)
        }

        val cmdField = EditText(this).apply {
            hint = "/ping"
            setSingleLine(true)
        }

        val sendCmdBtn = Button(this).apply {
            text = "Send command"
            setOnClickListener {
                val text = cmdField.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this@BridgeSettingsActivity, "type a command first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val ok = bridge.sendCommand(-1, text)
                Toast.makeText(
                    this@BridgeSettingsActivity,
                    if (ok) "sent" else "failed (set gateway phone first)",
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) cmdField.setText("")
            }
        }

        listOf(
            help, gatewayLabel, gatewayField, switch, seqRow, saveBtn, resetBtn,
            divider, cmdHelp, cmdField, sendCmdBtn
        ).forEach(root::addView)
        setContentView(root)
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
