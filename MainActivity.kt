package com.example.voicecontrol

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The setup screen. You only come here once to give permissions (and to test commands by typing). */
class MainActivity : Activity() {

    private lateinit var micStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var engineButton: Button
    private lateinit var langButton: Button
    private lateinit var engineInfo: TextView
    private lateinit var input: EditText
    private lateinit var planView: TextView
    private lateinit var logView: TextView

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        }

    private fun heading(text: String): TextView = label(text, 18f, true).apply { setPadding(0, dp(24), 0, dp(4)) }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }

        root.addView(label("Voice Control", 28f, true))
        root.addView(
            label(
                "Control your phone with your voice. No chatbot, no internet needed. Say things like:\n" +
                    "• open youtube\n" +
                    "• open browser and open claude\n" +
                    "• open whatsapp and select Tanvir and tap type box then type hello and send\n" +
                    "• scroll down / go back / go home"
            )
        )

        root.addView(heading("Setup (do these 3 things once)"))
        micStatus = label("")
        root.addView(micStatus)
        root.addView(button("1. Allow microphone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        overlayStatus = label("")
        root.addView(overlayStatus)
        root.addView(button("2. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        serviceStatus = label("")
        root.addView(serviceStatus)
        root.addView(button("3. Turn on the Voice Control service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(
            label(
                "In step 3: find \"Voice Control\" in the list, open it and switch it ON.\n" +
                    "If the switch is greyed out (\"Restricted setting\"), tap the button below, then the ⋮ menu at the top right, then \"Allow restricted settings\", and try step 3 again.",
                13f
            )
        )
        root.addView(button("Open App info (for restricted settings)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(heading("How it listens"))
        engineInfo = label("", 13f)
        root.addView(engineInfo)
        engineButton = button("") {
            val next = when (prefs.getString("engine", "auto")) {
                "auto" -> "vosk"
                "vosk" -> "android"
                else -> "auto"
            }
            prefs.edit().putString("engine", next).apply()
            refresh()
        }
        root.addView(engineButton)
        langButton = button("") {
            val next = if (prefs.getString("lang", "en-US") == "en-US") "bn-BD" else "en-US"
            prefs.edit().putString("lang", next).apply()
            refresh()
        }
        root.addView(langButton)

        root.addView(heading("Try it by typing"))
        root.addView(label("Type a command and see what the app understood. \"Run it\" really does it on your phone.", 13f))
        input = EditText(this).apply {
            hint = "e.g. open youtube"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(input)
        root.addView(button("Show what it understood") {
            val steps = CommandParser.parse(input.text.toString())
            planView.text = if (steps.isEmpty()) {
                "I didn't find a command in that."
            } else {
                steps.mapIndexed { i, s -> "${i + 1}. ${s.describe()}" }.joinToString("\n")
            }
        })
        root.addView(button("Run it") {
            val svc = VoiceControlService.instance
            if (svc == null) {
                Toast.makeText(this, "Turn on the Voice Control service first (step 3)", Toast.LENGTH_LONG).show()
            } else if (input.text.isNotBlank()) {
                svc.runTextCommand(input.text.toString())
            }
        })
        planView = label("", 15f)
        root.addView(planView)

        root.addView(heading("Log"))
        logView = label("", 12f).apply { typeface = Typeface.MONOSPACE }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        VoiceControlService.logListener = { logView.text = VoiceControlService.logText() }
        refresh()
    }

    override fun onPause() {
        VoiceControlService.logListener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        val svc = VoiceControlService.instance

        micStatus.text = (if (micOk) "✅ " else "❌ ") + "Microphone"
        overlayStatus.text = (if (overlayOk) "✅ " else "❌ ") + "Display over other apps"
        serviceStatus.text = (if (svc != null) "✅ " else "❌ ") + "Voice Control service"
        if (overlayOk) svc?.ensureBubble()

        val engine = prefs.getString("engine", "auto")
        val bundled = VoskEngine.isModelBundled(this)
        engineButton.text = "Engine: " + when (engine) {
            "vosk" -> "Vosk offline model (tap to change)"
            "android" -> "Phone's recognizer (tap to change)"
            else -> "Auto (tap to change)"
        }
        langButton.text = "Language for phone's recognizer: " + prefs.getString("lang", "en-US") + " (tap to change)"
        engineInfo.text = if (bundled) {
            "✅ Vosk offline model is inside this app. Auto uses it (English)."
        } else {
            "Vosk model not added (optional). Auto uses your phone's own recognizer. See the guide to add Vosk."
        }
        logView.text = VoiceControlService.logText()
    }
}
