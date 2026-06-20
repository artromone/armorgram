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
            text = "─── Recovery ───"
            setPadding(0, 48, 0, 16)
            gravity = Gravity.CENTER
        }

        val cmdHelp = TextView(this).apply {
            text = "If some messages didn't arrive, ask the server to resend a range " +
                "of frames by their sequence numbers (shown as #N in messages)."
            setPadding(0, 0, 0, 16)
        }

        val resendField = EditText(this).apply {
            hint = "100-110"
            setSingleLine(true)
        }

        val resendBtn = Button(this).apply {
            text = "Request resend"
            setOnClickListener {
                val range = resendField.text.toString().trim()
                if (range.isEmpty()) {
                    Toast.makeText(this@BridgeSettingsActivity, "type a range first, e.g. 100-110", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val ok = bridge.sendCommand(-1, "/resend $range")
                Toast.makeText(
                    this@BridgeSettingsActivity,
                    if (ok) "sent" else "failed (set gateway phone first)",
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) resendField.setText("")
            }
        }

        val pingBtn = Button(this).apply {
            text = "Ping server"
            setOnClickListener {
                val ok = bridge.sendCommand(-1, "/ping")
                Toast.makeText(
                    this@BridgeSettingsActivity,
                    if (ok) "ping sent" else "failed (set gateway phone first)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        listOf(
            help, gatewayLabel, gatewayField, switch, seqRow, saveBtn, resetBtn,
            divider, cmdHelp, resendField, resendBtn, pingBtn
        ).forEach(root::addView)
        setContentView(root)
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
