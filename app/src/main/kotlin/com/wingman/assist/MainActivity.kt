package com.wingman.assist

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bare-bones launcher: links out to the two system grants you need, and a button to
 * dump the current screen's node tree to Logcat (for finding selectors). Flesh this
 * out into a real settings screen (persona, prefs, API key, per-app toggles).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "Wingman setup\n\n1. Enable the accessibility service.\n2. Allow drawing over other apps.\n3. Fill selectors in AppProfiles.kt."
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Allow overlay (display over apps)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "Open dev panel (tune behavior)"
            setOnClickListener { startActivity(Intent(this@MainActivity, DevPanelActivity::class.java)) }
        })
        setContentView(root)
    }
}
