package io.github.robertmtalbot.takbridge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.robertmtalbot.takbridge.takserver.TakServer

/**
 * The bridge's one screen: start and stop, connection status, how to point ATAK at it, and a
 * log of what has happened. Built in code so the app carries no layout resources yet.
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private lateinit var logView: TextView
    private val onState: (BridgeState) -> Unit = { state -> runOnUiThread { render(state) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply { textSize = 18f }
        toggleButton = Button(this).apply { setOnClickListener { toggleBridge() } }
        val setupView = TextView(this).apply {
            text = SETUP_TEXT
            setPadding(0, pad, 0, pad)
            setTextIsSelectable(true)
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }

        column.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(toggleButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        column.addView(setupView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(logView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(ScrollView(this).apply { addView(column) })

        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        BridgeStatus.addListener(onState)
    }

    override fun onStop() {
        BridgeStatus.removeListener(onState)
        super.onStop()
    }

    private fun render(state: BridgeState) {
        statusView.text = when {
            !state.running -> "Bridge stopped"
            state.clientConnected -> "Running on 127.0.0.1:${state.port}\nATAK connected"
            else -> "Running on 127.0.0.1:${state.port}\nWaiting for ATAK"
        }
        toggleButton.text = if (state.running) "Stop bridge" else "Start bridge"
        logView.text = state.log.asReversed().joinToString("\n")
    }

    private fun toggleBridge() {
        val intent = Intent(this, BridgeService::class.java)
        if (BridgeStatus.current().running) {
            startService(intent.setAction(BridgeService.ACTION_STOP))
        } else {
            startForegroundService(intent)
        }
    }

    /** Without this on Android 13 and later the service still runs, but its notification is hidden. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    private companion object {
        val SETUP_TEXT = """
            To connect ATAK:
            1. Start the bridge.
            2. In ATAK open Settings, Network Preferences, TAK Servers, and add a server.
            3. Address 127.0.0.1, port ${TakServer.DEFAULT_PORT}, protocol TCP. Leave SSL/TLS off.
            4. Three demo markers appear in central Denver, and a chat room called "${DemoData.ROOM}".
            5. Reply in that room. The bridge answers "Bridge heard: ..." and logs it below.
        """.trimIndent()
    }
}
