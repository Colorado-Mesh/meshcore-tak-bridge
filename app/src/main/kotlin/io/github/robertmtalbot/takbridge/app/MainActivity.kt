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
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.Spinner
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.robertmtalbot.takbridge.app.ble.FoundRadio
import io.github.robertmtalbot.takbridge.app.ble.RadioScanner
import io.github.robertmtalbot.takbridge.protocol.Contact
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
        column.addView(filterSection(pad), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(iconSection(pad), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
            serviceIntent().setAction(BridgeService.ACTION_CONNECT_RADIO)
                .putExtra(BridgeService.EXTRA_ADDRESS, radio.address)
                .putExtra(BridgeService.EXTRA_NAME, radio.name.removeSuffix(" (paired)")),
        )
    }

    /** Which nodes go on the ATAK map: by type, and by how recently they were heard. */
    private fun filterSection(pad: Int): LinearLayout {
        val prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }
        section.addView(TextView(this).apply { text = "Nodes on the map" })
        val shown = prefs.getStringSet(BridgeService.PREF_SHOWN_TYPES, null)?.toSet()
            ?: MeshPicture.ALL_TYPES.map { it.toString() }.toSet()
        val boxes = TYPE_LABELS.map { (type, label) ->
            CheckBox(this).apply {
                text = label
                isChecked = type.toString() in shown
            }
        }
        fun saveTypes() {
            val chosen = TYPE_LABELS.indices.filter { boxes[it].isChecked }.map { TYPE_LABELS[it].first.toString() }.toSet()
            prefs.edit().putStringSet(BridgeService.PREF_SHOWN_TYPES, chosen).apply()
            applyMapSettings()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (box in boxes) {
            box.setOnCheckedChangeListener { _, _ -> saveTypes() }
            row.addView(box)
        }
        section.addView(HorizontalScrollView(this).apply { addView(row) })

        val ages = AGE_CHOICES
        val current = prefs.getInt(BridgeService.PREF_MAX_AGE_HOURS, BridgeService.DEFAULT_MAX_AGE_HOURS)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ages.map { it.second })
            setSelection(ages.indexOfFirst { it.first == current }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val hours = ages[position].first
                    if (hours == prefs.getInt(BridgeService.PREF_MAX_AGE_HOURS, BridgeService.DEFAULT_MAX_AGE_HOURS)) return
                    prefs.edit().putInt(BridgeService.PREF_MAX_AGE_HOURS, hours).apply()
                    applyMapSettings()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        val ageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = "Hide nodes not heard for" })
            addView(spinner)
        }
        section.addView(ageRow)
        return section
    }

    /** Tells a running bridge to re-read the map settings and update ATAK. */
    private fun applyMapSettings() {
        if (BridgeStatus.current().running) {
            startForegroundService(serviceIntent().setAction(BridgeService.ACTION_RELOAD_STYLE))
        }
    }

    /** Lets the user give repeaters and room servers an icon from an icon set installed in ATAK. */
    private fun iconSection(pad: Int): LinearLayout {
        val prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
        val field = EditText(this).apply {
            setText(prefs.getString(BridgeService.PREF_RELAY_ICON, ""))
            hint = "icon set UID/group/file name"
            textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val save = Button(this).apply {
            text = "Save icon"
            setOnClickListener {
                val path = field.text.toString().trim()
                if (path.isNotEmpty() && !MeshPicture.isIconPath(path)) {
                    BridgeStatus.log("Icon not saved: it must be icon set UID/group/file name")
                    return@setOnClickListener
                }
                prefs.edit().putString(BridgeService.PREF_RELAY_ICON, path).apply()
                BridgeStatus.log(if (path.isEmpty()) "Repeaters will use the installation symbol" else "Repeater icon saved")
                applyMapSettings()
            }
        }
        val geoOps = Button(this).apply {
            text = "Use GeoOps Repeater"
            isAllCaps = false
            // Fills the field and saves in one step, so the choice cannot be left unsaved.
            setOnClickListener {
                field.setText(MeshPicture.GEOOPS_REPEATER_ICON)
                save.performClick()
            }
        }
        val buttons = LinearLayout(this).apply {
            addView(save)
            addView(geoOps)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
            addView(TextView(context).apply { text = "Icon for repeaters and room servers (optional)" })
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(buttons)
            addView(
                TextView(context).apply {
                    textSize = 12f
                    text = "The icon set must be installed in ATAK. Leave empty for the standard installation symbol."
                },
            )
        }
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

        val TYPE_LABELS = listOf(
            Contact.TYPE_COMPANION to "Companions",
            Contact.TYPE_REPEATER to "Repeaters",
            Contact.TYPE_ROOM_SERVER to "Room servers",
            Contact.TYPE_SENSOR to "Sensors",
        )

        /** Hours, and how each choice reads; 0 means never hide. */
        val AGE_CHOICES = listOf(
            24 to "1 day",
            3 * 24 to "3 days",
            7 * 24 to "7 days",
            30 * 24 to "30 days",
            0 to "never hide",
        )

        val SETUP_TEXT = """
            To use the bridge:
            1. Start the bridge, then scan and tap your MeshCore companion radio.
            2. In ATAK open Settings, Network Preferences, TAK Servers, and add a server.
            3. Address 127.0.0.1, port ${TakServer.DEFAULT_PORT}, protocol TCP. Leave SSL/TLS off.
            4. Mesh nodes that share a position appear on the map, and each radio channel
               appears in ATAK's contacts: #name for hashtag channels, a lock for private ones. Messages sent to that
               contact go out on that channel.
            The bridge reads from the radio and sends channel messages. It never changes
            the radio's settings or channels.
        """.trimIndent()
    }
}
