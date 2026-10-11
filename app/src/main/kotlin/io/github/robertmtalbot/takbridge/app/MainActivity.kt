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
import io.github.robertmtalbot.takbridge.app.ble.FoundRadio
import io.github.robertmtalbot.takbridge.app.ble.RadioScanner
import io.github.robertmtalbot.takbridge.takserver.TakServer

/**
 * The bridge's one screen: start and stop, connection status, how to point ATAK at it, and a
 * log of what has happened. Built in code so the app carries no layout resources yet.
 */
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private lateinit var logView: TextView
    private lateinit var radioView: TextView
    private lateinit var radioButton: Button
    private lateinit var radioList: LinearLayout
    private lateinit var scanner: RadioScanner
    private val found = LinkedHashMap<String, FoundRadio>()
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
        scanner = RadioScanner(this)
        radioView = TextView(this).apply {
            textSize = 18f
            setPadding(0, pad, 0, 0)
        }
        radioButton = Button(this).apply { setOnClickListener { onRadioButton() } }
        radioList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(radioView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(radioButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        column.addView(radioList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
        scanner.stop()
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
        radioView.text = state.radio
        radioButton.text = if (state.radioBusy) "Disconnect radio" else "Scan for radios"
    }

    private fun onRadioButton() {
        if (BridgeStatus.current().radioBusy) {
            startForegroundService(serviceIntent().setAction(BridgeService.ACTION_DISCONNECT_RADIO))
            return
        }
        val missing = bluetoothPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
            return
        }
        scan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            scan()
        } else {
            BridgeStatus.log("Bluetooth permission was not granted, so the bridge cannot look for radios")
        }
    }

    /** Android 11 and earlier tie Bluetooth scanning to the location permission. */
    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun scan() {
        scanner.blocker()?.let {
            BridgeStatus.log("Cannot scan: $it")
            return
        }
        found.clear()
        radioList.removeAllViews()
        BridgeStatus.log("Scanning for radios for a few seconds")
        scanner.start(
            onFound = { radio ->
                found[radio.address] = radio
                showFound()
            },
            onDone = { problem ->
                when {
                    problem != null -> BridgeStatus.log("Radio scan: $problem")
                    found.isEmpty() -> BridgeStatus.log(
                        "No radios found. Check the radio is on, in Bluetooth mode and not connected to " +
                            "another app" + if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) ", and that Location is on" else "",
                    )
                    else -> BridgeStatus.log("Scan finished; tap a radio to connect")
                }
            },
        )
    }

    private fun showFound() {
        radioList.removeAllViews()
        for (radio in found.values) {
            val signal = if (radio.rssi != 0) "  ${radio.rssi} dBm" else ""
            radioList.addView(
                Button(this).apply {
                    text = "${radio.name}$signal"
                    isAllCaps = false
                    setOnClickListener { connect(radio) }
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
    }

    private fun connect(radio: FoundRadio) {
        scanner.stop()
        radioList.removeAllViews()
        startForegroundService(
            serviceIntent().setAction(BridgeService.ACTION_CONNECT_RADIO).putExtra(BridgeService.EXTRA_ADDRESS, radio.address),
        )
    }

    private fun serviceIntent() = Intent(this, BridgeService::class.java)

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
        const val REQUEST_BLUETOOTH = 1

        val SETUP_TEXT = """
            To use the bridge:
            1. Start the bridge, then scan and tap your MeshCore companion radio.
            2. In ATAK open Settings, Network Preferences, TAK Servers, and add a server.
            3. Address 127.0.0.1, port ${TakServer.DEFAULT_PORT}, protocol TCP. Leave SSL/TLS off.
            4. Mesh nodes that share a position appear on the map, and each radio channel
               appears in ATAK's contacts as "Mesh: <channel name>". Messages sent to that
               contact go out on that channel.
            The bridge reads from the radio and sends channel messages. It never changes
            the radio's settings or channels.
        """.trimIndent()
    }
}
