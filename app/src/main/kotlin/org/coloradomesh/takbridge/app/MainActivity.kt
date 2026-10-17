package org.coloradomesh.takbridge.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.Spinner
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.coloradomesh.takbridge.R
import org.coloradomesh.takbridge.app.ble.FoundRadio
import org.coloradomesh.takbridge.app.ble.RadioScanner
import org.coloradomesh.takbridge.protocol.Contact
import org.coloradomesh.takbridge.takserver.TakServer

/**
 * The bridge's one screen: a Colorado Mesh header, start and stop, connection status, how to
 * point ATAK at it, where the project lives, and a log of what has happened. Built in code; the
 * only resources are the icons.
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

    /**
     * The button for each radio in [found], by address, and the line above them while scanning.
     * Kept and updated rather than rebuilt; see [showFound]. Main thread only.
     */
    private val radioRows = HashMap<String, Button>()
    private var scanNote: TextView? = null

    /** True while a scan runs. Main thread only. */
    private var scanning = false
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
        column.addView(aboutSection(pad), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(logView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(pad), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        window.statusBarColor = NAVY_TOP
        setContentView(ScrollView(this).apply { addView(page) })

        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        BridgeStatus.addListener(onState)
    }

    override fun onStop() {
        BridgeStatus.removeListener(onState)
        if (scanning) endScan()
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
        // Once the bridge is connecting to a radio, for example the one it reconnects to on
        // start, a scan list left on screen would only invite a second tap that restarts it.
        if (state.radioBusy && (scanning || found.isNotEmpty())) {
            scanner.stop()
            scanning = false
            found.clear()
            clearRadioList()
        }
        showRadioButton(state)
    }

    private fun showRadioButton(state: BridgeState = BridgeStatus.current()) {
        radioButton.text = when {
            state.radioBusy -> "Disconnect radio"
            scanning -> "Stop scanning"
            else -> "Scan for radios"
        }
    }

    /**
     * Disconnects, stops a scan in progress, or starts one. A second tap during a scan stops it
     * rather than starting again: restarting cleared the list, and Android ignores an app that
     * starts scans too often.
     */
    private fun onRadioButton() {
        if (BridgeStatus.current().radioBusy) {
            startForegroundService(serviceIntent().setAction(BridgeService.ACTION_DISCONNECT_RADIO))
            return
        }
        if (scanning) {
            endScan()
            BridgeStatus.log("Scan stopped")
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
            clearRadioList()
            showRadioNote("Cannot scan: $it")
            return
        }
        found.clear()
        clearRadioList()
        scanning = true
        showRadioButton()
        showFound()
        BridgeStatus.log("Scanning for radios for a few seconds")
        scanner.start(
            onFound = { radio ->
                found[radio.address] = radio
                showFound()
            },
            onDone = { problem ->
                scanning = false
                showRadioButton()
                val note = when {
                    problem != null -> "Radio scan: $problem"
                    found.isEmpty() ->
                        "No radios found. Check the radio is on, in Bluetooth mode and not connected to " +
                            "another app" + if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) ", and that Location is on" else ""
                    else -> null
                }
                BridgeStatus.log(note ?: "Scan finished; tap a radio to connect")
                showFound()
                note?.let { showRadioNote(it) }
            },
        )
    }

    private fun endScan() {
        scanner.stop()
        scanning = false
        showRadioButton()
        showFound()
    }

    /** A line of text under the radio button, where results appear, so the user sees it without scrolling to the log. */
    private fun showRadioNote(text: String) {
        radioList.addView(TextView(this).apply { this.text = text }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun clearRadioList() {
        radioList.removeAllViews()
        radioRows.clear()
        scanNote = null
    }

    /**
     * Shows the radios found so far. While scanning, results arrive several times a second, one
     * per advert. Rebuilding the buttons for each one replaced the button under the user's
     * finger between press and release, so a tap often did nothing until the scan ended. So each
     * radio keeps its button, and only the text changes; new radios are added at the end.
     */
    private fun showFound() {
        val noteText = when {
            !scanning -> null
            found.isEmpty() -> "Looking for radios…"
            else -> "Still looking; tap a radio to connect"
        }
        if (noteText == null) {
            scanNote?.let { radioList.removeView(it) }
            scanNote = null
        } else {
            val note = scanNote ?: TextView(this).also {
                radioList.addView(it, 0, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                scanNote = it
            }
            if (note.text.toString() != noteText) note.text = noteText
        }
        for (radio in found.values) {
            val signal = if (radio.rssi != 0) "  ${radio.rssi} dBm" else ""
            val label = "${radio.name}$signal"
            val row = radioRows.getOrPut(radio.address) {
                Button(this).apply { isAllCaps = false }.also {
                    radioList.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
            }
            if (row.text.toString() != label) row.text = label
            row.setOnClickListener { connect(radio) }
        }
    }

    private fun connect(radio: FoundRadio) {
        scanner.stop()
        scanning = false
        found.clear()
        clearRadioList()
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
                // The theme's default box was hard to see on the dark background.
                buttonTintList = checkboxTint()
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
            adapter = choices(ages.map { it.second })
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
        section.addView(labelledRow("Hide nodes not heard for", spinner, dp(4)))
        return section
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * Choices for a drop-down list: the compact item layout while closed, the roomy one when
     * open. Using the drop-down layout for both made the closed list tall and wide.
     */
    private fun choices(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    /**
     * A label with a drop-down list beside it. The label takes the room left over and wraps if
     * it must, so a long choice never pushes the list off the screen.
     */
    private fun labelledRow(label: String, spinner: Spinner, topPad: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, topPad, 0, 0)
        addView(TextView(context).apply { text = label }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(spinner, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    /** Tells a running bridge to re-read the map settings and update ATAK. */
    private fun applyMapSettings() {
        if (BridgeStatus.current().running) {
            startForegroundService(serviceIntent().setAction(BridgeService.ACTION_RELOAD_STYLE))
        }
    }

    /** Icons for repeaters and room servers, and the team colour companions are drawn in. */
    private fun iconSection(pad: Int): LinearLayout {
        val prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }
        section.addView(iconRow("Repeater icon (optional)", "Repeaters", BridgeService.PREF_RELAY_ICON))
        section.addView(iconRow("Room server icon (optional)", "Room servers", BridgeService.PREF_ROOM_ICON, topPad = pad))
        section.addView(
            TextView(this).apply {
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
                text = "Icons come from an icon set installed in ATAK. Leave a field empty for the standard installation symbol."
            },
        )

        val teams = MeshPicture.TEAM_COLOURS
        val current = prefs.getString(BridgeService.PREF_COMPANION_TEAM, MeshPicture.DEFAULT_COMPANION_TEAM)
        val spinner = Spinner(this).apply {
            adapter = choices(teams)
            setSelection(teams.indexOf(current).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val team = teams[position]
                    if (team == prefs.getString(BridgeService.PREF_COMPANION_TEAM, MeshPicture.DEFAULT_COMPANION_TEAM)) return
                    prefs.edit().putString(BridgeService.PREF_COMPANION_TEAM, team).apply()
                    BridgeStatus.log("Companions will show as $team team members")
                    applyMapSettings()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        section.addView(labelledRow("Companion team colour", spinner, pad))
        return section
    }

    /**
     * One icon setting: a path field with Save beside it, and below them a full-width list of
     * icons from the GeoOps set that fills the field and saves in one step, so a choice cannot
     * be left unsaved.
     */
    private fun iconRow(title: String, kind: String, prefKey: String, topPad: Int = 0): LinearLayout {
        val prefs = getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE)
        val field = EditText(this).apply {
            setText(prefs.getString(prefKey, ""))
            hint = "icon set UID/group/file name"
            textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        fun save() {
            val path = field.text.toString().trim()
            if (path.isNotEmpty() && !MeshPicture.isIconPath(path)) {
                BridgeStatus.log("Icon not saved: it must be icon set UID/group/file name")
                return
            }
            prefs.edit().putString(prefKey, path).apply()
            BridgeStatus.log(if (path.isEmpty()) "$kind will use the installation symbol" else "$kind icon saved")
            applyMapSettings()
        }
        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        }
        val presets = listOf("GeoOps icon…") + MeshPicture.GEOOPS_ICONS.keys
        val presetSpinner = Spinner(this).apply {
            adapter = choices(presets)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) return
                    field.setText(MeshPicture.GEOOPS_ICONS.getValue(presets[position]))
                    save()
                    setSelection(0)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, topPad, 0, 0)
            addView(TextView(context).apply { text = title })
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(
                        saveButton,
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) },
                    )
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
            addView(
                presetSpinner,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) },
            )
        }
    }

    /** The app's icon and name on Colorado Mesh navy, over the arc's red-to-yellow line. */
    private fun header(pad: Int): LinearLayout {
        val title = TextView(this).apply {
            text = "MeshCore TAK Bridge"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val subtitle = TextView(this).apply {
            text = "A Colorado Mesh project" + (versionName()?.let { "  ·  v$it" } ?: "")
            textSize = 14f
            setTextColor(OFF_WHITE)
        }
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(NAVY_TOP, NAVY_BOTTOM))
            addView(
                ImageView(context).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) },
            )
            addView(words, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        val arc = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ARC_RED, ARC_YELLOW, ARC_LIGHT_YELLOW))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(arc, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)))
        }
    }

    /** Who makes the app, its licence, and links to the source and to Colorado Mesh. */
    private fun aboutSection(pad: Int): LinearLayout {
        fun link(label: String, url: String) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { open(url) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad)
            addView(
                TextView(context).apply {
                    text = ABOUT_TEXT
                    setTextIsSelectable(true)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(link("Source code", REPOSITORY_URL))
                    addView(
                        link("coloradomesh.org", COLORADO_MESH_URL),
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) },
                    )
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            BridgeStatus.log("No app on this device can open $url")
        }
    }

    @Suppress("DEPRECATION") // The replacement needs Android 13; this works on every version.
    private fun versionName(): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Colorado Mesh yellow when ticked, light grey when not, so both show on the dark background. */
    private fun checkboxTint() = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(ARC_YELLOW, OFF_WHITE),
    )

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

        const val REPOSITORY_URL = "https://github.com/Colorado-Mesh/meshcore-tak-bridge"
        const val COLORADO_MESH_URL = "https://coloradomesh.org/"

        // Colorado Mesh palette (Colorado-Mesh/branding, colors/colors.css), as in the app icon.
        val NAVY_TOP = Color.parseColor("#011033")
        val NAVY_BOTTOM = Color.parseColor("#0A4483")
        val ARC_RED = Color.parseColor("#DD506D")
        val ARC_YELLOW = Color.parseColor("#F2B138")
        val ARC_LIGHT_YELLOW = Color.parseColor("#ECC455")
        val OFF_WHITE = Color.parseColor("#C7C7C7")

        val ABOUT_TEXT = """
            MeshCore TAK Bridge is free software from Colorado Mesh, licensed GPL-3.0-or-later.
            Bug reports, ideas and code are welcome on GitHub.
            Not affiliated with MeshCore or the TAK Product Center.
        """.trimIndent()

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

        /**
         * One line per step, with no line breaks inside a sentence: the screen wraps the text
         * itself, and breaks written into it showed up mid-sentence.
         */
        val SETUP_TEXT = listOf(
            "To use the bridge:",
            "1. Start the bridge, then scan and tap your MeshCore companion radio.",
            "2. In ATAK, open Settings, Network Preferences, TAK Servers, and add a server: address 127.0.0.1, " +
                "port ${TakServer.DEFAULT_PORT}, protocol TCP, with SSL/TLS off.",
            "3. Nodes that share a position appear on the map. Each radio channel appears in ATAK's contacts, " +
                "starting with #, and private channels show a lock. Chat with a channel's contact to send on that channel.",
            "4. To message someone directly, chat with the contact of their companion node.",
            "5. To reply to someone on a channel, start with @ and the first part of their name (@silver), " +
                "or @@ for whoever spoke last.",
            "The bridge never changes the radio's settings, channels, contacts or clock.",
        ).joinToString("\n")
    }
}
