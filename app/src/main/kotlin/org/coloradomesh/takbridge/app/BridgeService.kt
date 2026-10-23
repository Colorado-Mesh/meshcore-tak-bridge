package org.coloradomesh.takbridge.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import org.coloradomesh.takbridge.R
import org.coloradomesh.meshcore.ble.RadioSession
import org.coloradomesh.takbridge.cot.ClientIdentity
import org.coloradomesh.takbridge.cot.CotEvents
import org.coloradomesh.takbridge.cot.CotParser
import org.coloradomesh.takbridge.cot.InboundChat
import org.coloradomesh.meshcore.protocol.Channel
import org.coloradomesh.meshcore.protocol.ChannelMessage
import org.coloradomesh.meshcore.protocol.ChannelReplies
import org.coloradomesh.meshcore.protocol.CompanionClient
import org.coloradomesh.meshcore.protocol.Contact
import org.coloradomesh.meshcore.protocol.DeviceInfo
import org.coloradomesh.meshcore.protocol.DirectMessage
import org.coloradomesh.meshcore.protocol.DirectOutcome
import org.coloradomesh.meshcore.protocol.LowBatteryWatch
import org.coloradomesh.meshcore.protocol.Mecp
import org.coloradomesh.meshcore.protocol.RadioBattery
import org.coloradomesh.meshcore.protocol.SelfInfo
import org.coloradomesh.meshcore.protocol.TelemetryOutcome
import org.coloradomesh.takbridge.takserver.ApiServer
import org.coloradomesh.takbridge.takserver.TakServer
import org.coloradomesh.takbridge.takserver.TakServerListener
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Keeps the local TAK server running while ATAK is in use. A foreground service with a visible
 * notification is what stops Android from ending the bridge when its screen is not showing.
 *
 * It joins the radio to ATAK: nodes become markers, channels and companions become contacts,
 * and channel and direct chat flows both ways. With no radio chosen it serves [DemoData]: fixed
 * markers and a chat contact that echoes.
 */
class BridgeService : Service(), TakServerListener, CompanionClient.Listener {
    /** Socket work stays off the main thread, where Android forbids network calls. */
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var server: TakServer? = null

    /** Answers ATAK's poll for server contacts; worker thread only. */
    private var api: ApiServer? = null
    private var refresher: ScheduledExecutorService? = null
    @Volatile
    private var radio: RadioSession? = null
    private val mesh = MeshPicture()

    /** Who has spoken on each channel recently, so ATAK users can reply with `@name` or `@@`. */
    private val replies = ChannelReplies()

    /** Uids of the markers ATAK currently has from this bridge, so that dropped ones can be removed. */
    private val onMap: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val main = Handler(Looper.getMainLooper())

    /** The radio the user chose, by Bluetooth address; null when none is chosen. Main thread only. */
    private var wanted: String? = null
    private var wantedName = "radio"
    private var failures = 0

    /** True once the radio has been read and can take messages. */
    @Volatile
    private var radioUp = false

    /** Channel and direct messages received while ATAK was not there to take them. */
    private val backlog = ArrayList<Any>()

    /**
     * Direct messages from ATAK waiting for the mesh's acknowledgement, by the id the radio's
     * result will carry, so ATAK can be told when one is delivered. Oldest dropped past a limit.
     */
    private val awaitingAck = object : LinkedHashMap<Long, PendingReceipt>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PendingReceipt>?) = size > MAX_AWAITING_ACK
    }

    /** ATAK's id for a message it sent, and the node contact it went to. */
    private data class PendingReceipt(val messageId: String, val nodeUid: String, val nodeCallsign: String)

    /** When the radio's battery deserves an alert; used while locked on itself. */
    private val lowBattery = LowBatteryWatch()

    /** The battery percentage last written to the log, so a steady battery does not fill it. */
    @Volatile
    private var loggedBatteryPercent: Int? = null

    /** How tracking each node is going, by public key, as shown on the screen. */
    private val trackState = ConcurrentHashMap<String, String>()

    /** The kind of the last outcome logged for each tracked node, so only changes are logged. */
    private val trackLogged = ConcurrentHashMap<String, String>()

    /** What the status notification says now; main thread only. */
    private var notificationText = ""

    /** Who the connected ATAK says it is; learned from its first position report. */
    @Volatile
    private var identity: ClientIdentity? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        loadStyle()
        worker.execute { startServer() }
        when (intent?.action) {
            ACTION_CONNECT_RADIO -> intent.getStringExtra(EXTRA_ADDRESS)?.let {
                chooseRadio(it, intent.getStringExtra(EXTRA_NAME) ?: "radio")
            }
            ACTION_DISCONNECT_RADIO -> forgetRadio()
            ACTION_RELOAD_TRACKING -> {
                applyTracking()
                // A node no longer tracked may now be one the filters leave off the map.
                worker.execute { sendMarkers() }
            }
            ACTION_RELOAD_STYLE -> {
                BridgeStatus.log("Map settings applied; ${mesh.positionedCount} nodes shown")
                worker.execute { sendMarkers() }
            }
            else -> resumeSavedRadio()
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadStyle() {
        val prefs = prefs()
        mesh.relayIconPath = prefs.getString(PREF_RELAY_ICON, null)?.takeIf { MeshPicture.isIconPath(it) }
        mesh.roomIconPath = prefs.getString(PREF_ROOM_ICON, null)?.takeIf { MeshPicture.isIconPath(it) }
        mesh.companionTeam = prefs.getString(PREF_COMPANION_TEAM, null)
            ?.takeIf { it in MeshPicture.TEAM_COLOURS } ?: MeshPicture.DEFAULT_COMPANION_TEAM
        mesh.shownTypes = prefs.getStringSet(PREF_SHOWN_TYPES, null)
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: MeshPicture.ALL_TYPES
        val hours = prefs.getInt(PREF_MAX_AGE_HOURS, DEFAULT_MAX_AGE_HOURS)
        mesh.maxAgeSeconds = if (hours > 0) hours * 3600L else null
        mesh.tracked = trackedKeys()
    }

    private fun trackedKeys(): Set<String> = prefs().getStringSet(PREF_TRACKED, null)?.toSet() ?: emptySet()

    /**
     * Tell the radio which companions to ask for their position. Only while ATAK is connected:
     * each request uses airtime on a shared mesh, and nobody sees the answers without ATAK.
     */
    private fun applyTracking() {
        val keys = trackedKeys()
        mesh.tracked = keys
        trackState.keys.retainAll(keys)
        trackLogged.keys.retainAll(keys)
        val active = server?.hasClient == true
        val minutes = prefs().getInt(PREF_TRACK_MINUTES, DEFAULT_TRACK_MINUTES)
        radio?.setTracking(if (active) keys else emptySet(), minutes * 60_000L)
        showTracking()
    }

    /** The tracking lines on the screen: each tracked node and how it is going. */
    private fun showTracking() {
        val keys = trackedKeys()
        val lines = if (keys.isEmpty()) {
            emptyList()
        } else {
            val paused = if (server?.hasClient == true) emptyList() else listOf("Paused until ATAK connects")
            paused + keys.map { key -> "${trackedName(key)}: ${trackState[key] ?: "not asked yet"}" }.sorted()
        }
        BridgeStatus.update { it.copy(tracking = lines) }
    }

    /** A different radio, or none: its companions and the answers heard through it no longer apply. */
    private fun forgetTrackingProgress() {
        trackState.clear()
        trackLogged.clear()
        BridgeStatus.update { it.copy(trackable = emptyList()) }
        showTracking()
    }

    private fun trackedName(key: String) = mesh.contactByKey(key)?.let { mesh.displayName(it) } ?: "node ${key.take(8)}"

    /** How a position request ended: move the marker, and say how it went. */
    override fun onTelemetry(publicKeyHex: String, outcome: TelemetryOutcome) {
        val at = LocalTime.now().format(CLOCK)
        val name = trackedName(publicKeyHex)
        val (kind, state) = when (outcome) {
            is TelemetryOutcome.Answered -> {
                val position = outcome.telemetry.position
                if (position == null) {
                    "no position" to "answered at $at but shares no position (GPS off, or location not allowed)"
                } else {
                    mesh.putFix(publicKeyHex, position, Instant.now().epochSecond)
                    mesh.contactByKey(publicKeyHex)?.let { contact -> server?.let { sendMeshMarkers(it, only = contact) } }
                    val where = String.format(Locale.ROOT, "%.4f, %.4f", position.latitude, position.longitude)
                    "position" to "at $where ($at)"
                }
            }
            TelemetryOutcome.NoAnswer -> "no answer" to "no answer at $at: out of range, telemetry not allowed (base and location both need allowing), or its contact for this radio is out of date (an advert from each radio fixes that)"
            TelemetryOutcome.NotSent -> "not sent" to "the radio did not send the request at $at"
        }
        trackState[publicKeyHex] = state
        if (trackLogged.put(publicKeyHex, kind) != kind) BridgeStatus.log("Tracking $name: $state")
        showTracking()
    }

    /** The user picked a radio: remember it, and keep it connected until told otherwise. */
    private fun chooseRadio(address: String, name: String) {
        // Starting again would cut off a connection that is still coming up, and the clash is a
        // common cause of Android's status 133. A radio waiting to retry is connected at once.
        if (address == wanted && (radio != null || pendingOpen != null)) {
            BridgeStatus.log(if (radioUp) "Radio: already connected to $name" else "Radio: already connecting to $name")
            return
        }
        prefs().edit().putString(PREF_ADDRESS, address).putString(PREF_NAME, name).apply()
        if (address != wanted) {
            mesh.clear()
            replies.clear()
            synchronized(lowBattery) { lowBattery.reset() }
            forgetTrackingProgress()
        }
        wanted = address
        wantedName = name
        failures = 0
        connectRadio(address)
    }

    /** The user disconnected: stop reconnecting and forget which radio it was. */
    private fun forgetRadio() {
        prefs().edit().remove(PREF_ADDRESS).remove(PREF_NAME).apply()
        wanted = null
        main.removeCallbacks(retry)
        cancelPendingOpen()
        radio?.close()
        radio = null
        radioUp = false
        mesh.clear()
        replies.clear()
        synchronized(lowBattery) { lowBattery.reset() }
        forgetTrackingProgress()
        BridgeStatus.update { it.copy(radio = "No radio", radioBusy = false) }
        clearBattery()
    }

    /** On a plain start, go back to the radio used last time. */
    private fun resumeSavedRadio() {
        if (wanted != null) return
        val address = prefs().getString(PREF_ADDRESS, null) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            BridgeStatus.log("Radio: Bluetooth permission is missing, so the saved radio was not reconnected")
            return
        }
        wanted = address
        wantedName = prefs().getString(PREF_NAME, null) ?: "radio"
        failures = 0
        BridgeStatus.log("Radio: reconnecting to $wantedName, used last time")
        connectRadio(address)
    }

    /**
     * Main thread. Opens a link to [address], replacing any earlier one. An earlier link is given
     * a moment to close first: Android often fails a connection opened while another is closing.
     */
    private fun connectRadio(address: String) {
        main.removeCallbacks(retry)
        cancelPendingOpen()
        val previous = radio
        previous?.close()
        radio = null
        radioUp = false
        BridgeStatus.update { it.copy(radioBusy = true, radio = "Connecting to $wantedName…") }
        clearBattery()
        val open = Runnable { openRadio(address) }
        if (previous == null) {
            open.run()
        } else {
            pendingOpen = open
            main.postDelayed(open, REPLACE_PAUSE_MS)
        }
    }

    /** Waiting to open a link while an earlier one closes; main thread only. */
    private var pendingOpen: Runnable? = null

    private fun cancelPendingOpen() {
        pendingOpen?.let { main.removeCallbacks(it) }
        pendingOpen = null
    }

    private fun openRadio(address: String) {
        pendingOpen = null
        if (wanted != address) return
        var session: RadioSession? = null
        session = RadioSession(
            context = applicationContext,
            address = address,
            listener = this,
            appName = APP_NAME,
            onLog = { BridgeStatus.log("Radio: $it") },
            onLinkReady = { _ -> BridgeStatus.update { status -> status.copy(radio = "Radio linked; reading it…") } },
        ) { reason ->
            main.post { if (radio === session) onRadioLost(reason) }
        }
        radio = session
        session.open()
    }

    /**
     * Main thread. The link ended without the user asking. Keep what is known about the mesh, so
     * ATAK's picture survives a short gap, and try again after a pause that grows with each failure.
     */
    private fun onRadioLost(reason: String) {
        val wasUp = radioUp
        radio = null
        radioUp = false
        if (wanted == null) return
        val wait = RETRY_SECONDS[failures.coerceAtMost(RETRY_SECONDS.size - 1)]
        failures++
        val what = if (wasUp) "Lost $wantedName" else "Could not connect to $wantedName"
        BridgeStatus.update { it.copy(radio = "$what: $reason\nTrying again in $wait s") }
        clearBattery()
        main.postDelayed(retry, wait * 1000L)
    }

    private val retry = Runnable { wanted?.let { connectRadio(it) } }

    override fun onLog(message: String) = BridgeStatus.log("Radio: $message")

    override fun onSelf(info: SelfInfo) {
        mesh.setSelf(info)
        BridgeStatus.update { it.copy(radio = "Radio: ${info.name}") }
        BridgeStatus.log(
            "Radio: this is \"${info.name}\", ${info.frequencyMhz} MHz, BW ${info.bandwidthKhz} kHz, " +
                "SF${info.spreadingFactor}, CR${info.codingRate}",
        )
    }

    override fun onDevice(info: DeviceInfo) {
        BridgeStatus.log("Radio: firmware ${info.version}, ${info.model}, ${info.maxChannels} channel slots")
    }

    /**
     * A battery reading, at connect and every few minutes. It is shown on the screen and in the
     * notification; an alert pops up, over ATAK too, as it falls to 20% and to 10%.
     */
    override fun onBattery(millivolts: Int) {
        BridgeStatus.update { it.copy(batteryMillivolts = millivolts) }
        val percent = RadioBattery.percent(millivolts) ?: NOT_MEASURED
        val logged = loggedBatteryPercent
        val worthLogging = logged == null || (percent == NOT_MEASURED) != (logged == NOT_MEASURED) ||
            abs(percent - logged) >= BATTERY_LOG_STEP
        if (worthLogging) {
            BridgeStatus.log("Radio: battery ${RadioBattery.describe(millivolts)}")
            loggedBatteryPercent = percent
        }
        val alert = if (percent == NOT_MEASURED) null else synchronized(lowBattery) { lowBattery.reading(percent) }
        val name = mesh.selfName ?: wantedName
        main.post {
            refreshNotification()
            if (alert != null) alertLowBattery(name, alert, millivolts)
        }
    }

    /** Main thread. No reading is shown once the radio it came from is gone. */
    private fun clearBattery() {
        loggedBatteryPercent = null
        BridgeStatus.update { it.copy(batteryMillivolts = null) }
        refreshNotification()
    }

    override fun onContact(contact: Contact) {
        val new = mesh.contactByKey(contact.publicKeyHex) == null
        mesh.putContact(contact)
        // A companion heard after start-up can be chosen for tracking too.
        if (radioUp && new && contact.type == Contact.TYPE_COMPANION) BridgeStatus.update { it.copy(trackable = mesh.companions()) }
        server?.let { active -> sendMeshMarkers(active, only = contact) }
    }

    override fun onChannels(channels: List<Channel>) {
        mesh.setChannels(channels)
        radioUp = true
        main.post { failures = 0 }
        val names = channels.joinToString { "${it.index}: ${it.name}" }.ifEmpty { "none" }
        BridgeStatus.log("Radio: ${mesh.contactCount} contacts, ${mesh.positionedCount} with a position")
        BridgeStatus.log("Radio: ${channels.size} channels ($names)")
        BridgeStatus.update {
            it.copy(radio = "${it.radio}\n${channels.size} channels, ${mesh.positionedCount} nodes on the map", trackable = mesh.companions())
        }
        sendPicture()
        applyTracking()
    }

    override fun onChannelMessage(message: ChannelMessage) {
        BridgeStatus.log("Mesh, channel ${message.channelIndex}: ${message.text}")
        replies.heard(message.channelIndex, message.text, Instant.now().epochSecond, mesh.selfName)
        holdOrDeliver(message)
    }

    override fun onDirectMessage(message: DirectMessage) {
        val from = mesh.contactFor(message)?.let { mesh.displayName(it) } ?: message.senderPrefixHex
        BridgeStatus.log("Mesh, direct from $from: ${message.text}")
        holdOrDeliver(message)
    }

    /** Chat is addressed to the ATAK user, who is not known until ATAK connects and reports in. */
    private fun holdOrDeliver(message: Any) {
        val atak = identity
        if (atak == null) {
            synchronized(backlog) {
                backlog.add(message)
                while (backlog.size > MAX_BACKLOG) backlog.removeAt(0)
            }
            return
        }
        deliver(message, atak)
    }

    private fun deliver(message: Any, atak: ClientIdentity) {
        when (message) {
            is ChannelMessage -> {
                // An MECP message is shown decoded, with the code line kept below it.
                val mecp = Mecp.find(message.text)
                val shown = if (mecp == null) message else message.copy(text = mecpText(mecp))
                val line = mesh.chatLine(shown, atak)
                if (line == null) {
                    BridgeStatus.log("Radio: message on unknown channel ${message.channelIndex} ignored")
                    return
                }
                server?.send(CotEvents.geoChat(line, Instant.now()))
                mecp?.let { onMecp(it.sender ?: "an unnamed sender", "on ${line.senderCallsign}", it.message, verified = false) }
            }
            is DirectMessage -> {
                val active = server ?: return
                val contact = mesh.contactFor(message)
                // The sender must be a contact ATAK can reach before a reply to it can work.
                contact?.let {
                    if (mesh.noteTalkedTo(it, Instant.now().epochSecond)) sendMeshMarkers(active, only = it)
                }
                val mecp = Mecp.find(message.text)
                val shown = if (mecp == null) message else message.copy(text = mecpText(mecp))
                active.send(CotEvents.geoChat(mesh.directLine(shown, atak), Instant.now()))
                if (mecp != null) {
                    val sender = contact?.let { mesh.displayName(it) } ?: message.senderPrefixHex.take(8)
                    onMecp(sender, "in a direct message", mecp.message, verified = contact != null)
                }
            }
        }
    }

    /** An MECP message as ATAK shows it: the sender, the message in English, and the code line below. */
    private fun mecpText(found: Mecp.Received): String {
        val from = found.sender?.let { "$it: " } ?: ""
        return "$from${Mecp.describe(found.message)}\n${found.message.raw}"
    }

    /**
     * A received MECP message: log it, put it on the map when it gives a position, and, when
     * the user has chosen it, sound ATAK's emergency alarm for a real MAYDAY.
     *
     * [verified] is true when the radio knows who sent it: a direct message, whose sender is
     * the contact whose key it was encrypted with. A channel message's sender is only the name
     * written in front of it, which anyone on the channel can write. So a cancel ("I am OK",
     * B03, or "sent in error", D04) is acted on only when verified; on a channel it is noted on
     * the marker it would have cancelled, which stays as it was.
     */
    private fun onMecp(sender: String, where: String, message: Mecp.Message, verified: Boolean) {
        BridgeStatus.log("MECP from $sender $where: ${Mecp.describe(message)}")
        val active = server ?: return
        val now = Instant.now()
        val beaconOff = "B03" in message.codes
        if (beaconOff || "D04" in message.codes) {
            if (!verified) {
                val at = LocalTime.now(java.time.ZoneOffset.UTC).format(CLOCK)
                val note = "$at UTC: \"${Mecp.describe(message)}\" sent $where under this name, which a channel does not verify; not acted on"
                mesh.noteOnMecp(sender, note, beaconFirst = beaconOff)?.let { sendMecpMarker(active, it.uid, now) }
                BridgeStatus.log("MECP: a cancel from \"$sender\" $where is not acted on, since a channel cannot show who sent it")
                return
            }
            if (beaconOff) {
                mesh.endBeacon(sender, where, message, now.epochSecond)?.let { sendMecpMarker(active, it.uid, now) }
                cancelAlertsFrom(active, sender, now)
            } else {
                mesh.forgetLatestMecp(sender)?.let { place ->
                    cancelAlert(active, alertUid(place), now)
                    sendMeshMarkers(active)
                }
            }
            return
        }
        val place = mesh.putMecp(sender, where, message, now.epochSecond) ?: return
        sendMecpMarker(active, place.uid, now)
        if (message.severity == 0 && !message.isDrill && prefs().getBoolean(PREF_MECP_ALARM, false)) raiseAlert(active, place, now)
    }

    private fun sendMecpMarker(active: TakServer, uid: String, now: Instant) {
        val marker = mesh.markers(now.epochSecond).firstOrNull { it.uid == uid } ?: return
        if (active.send(CotEvents.marker(marker, now))) onMap.add(uid)
    }

    /** ATAK alerts raised for MECP messages, by the alert's uid, so each sounds once and can be cancelled. */
    private val alerts = ConcurrentHashMap<String, MeshPicture.MecpPlace>()

    private fun alertUid(place: MeshPicture.MecpPlace) = "MECP-ALERT-${place.uid.removePrefix("MECP-")}"

    /** Raise ATAK's alert once per MAYDAY; it counts as raised only once ATAK has been sent it. */
    private fun raiseAlert(active: TakServer, place: MeshPicture.MecpPlace, now: Instant) {
        val uid = alertUid(place)
        if (alerts.containsKey(uid)) return
        val about = mesh.companionNamed(place.sender)?.let { mesh.markerUid(it) } ?: place.uid
        if (active.send(CotEvents.emergencyAlert(uid, about, place.sender, place.latitude, place.longitude, now, ALERT_STALE))) {
            alerts[uid] = place
            BridgeStatus.log("MECP: sounded ATAK's emergency alarm for the MAYDAY from ${place.sender}")
        }
    }

    /** Cancel one alert; it stays on the list, so a later cancel can try again, unless ATAK was sent the cancel. */
    private fun cancelAlert(active: TakServer, uid: String, now: Instant) {
        val place = alerts[uid] ?: return
        if (active.send(CotEvents.emergencyCancel(uid, place.sender, place.latitude, place.longitude, now))) {
            alerts.remove(uid)
            BridgeStatus.log("MECP: cancelled ATAK's emergency alert for ${place.sender}")
        }
    }

    /** "I am OK" from [sender]: cancel every alert raised for them, whatever marker it came from. */
    private fun cancelAlertsFrom(active: TakServer, sender: String, now: Instant) {
        for ((uid, place) in alerts.entries.toList()) if (place.sender == sender) cancelAlert(active, uid, now)
    }

    override fun onDirectResult(id: Long, publicKeyHex: String, text: String, outcome: DirectOutcome) {
        val pending = synchronized(awaitingAck) { awaitingAck.remove(id) }
        val contact = mesh.contactByKey(publicKeyHex) ?: return
        val name = mesh.displayName(contact)
        val note = when (outcome) {
            DirectOutcome.DELIVERED -> {
                BridgeStatus.log("ATAK to $name: delivered")
                sendDeliveryReceipt(pending)
                return
            }
            DirectOutcome.UNCONFIRMED -> {
                BridgeStatus.log("ATAK to $name: no acknowledgement after ${CompanionClient.MAX_DIRECT_ATTEMPTS} tries")
                "Not confirmed: no acknowledgement from $name after ${CompanionClient.MAX_DIRECT_ATTEMPTS} tries. It may not have arrived: ${text.take(40)}"
            }
            DirectOutcome.REFUSED -> {
                BridgeStatus.log("ATAK to $name: the radio did not take the message")
                "Not sent to the mesh: ${text.take(40)}"
            }
        }
        val atak = identity ?: return
        val now = Instant.now()
        server?.send(CotEvents.geoChat(mesh.directNotice(contact, atak, note, now), now))
    }

    /** Tell ATAK its message reached the node, so ATAK shows it as delivered. */
    private fun sendDeliveryReceipt(pending: PendingReceipt?) {
        if (pending == null) return
        val atak = identity ?: return
        val active = server ?: return
        active.send(CotEvents.deliveryReceipt(pending.messageId, pending.nodeUid, pending.nodeCallsign, atak.uid, Instant.now()))
    }

    override fun onSendResult(channelIndex: Int, text: String, accepted: Boolean) {
        if (accepted) {
            BridgeStatus.log("ATAK to mesh, channel $channelIndex: sent")
            return
        }
        BridgeStatus.log("ATAK to mesh, channel $channelIndex: the radio did not take the message")
        val atak = identity ?: return
        val now = Instant.now()
        mesh.notice(channelIndex, atak, "Not sent to the mesh: ${text.take(40)}", now)?.let { server?.send(CotEvents.geoChat(it, now)) }
    }

    /**
     * Everything ATAK should see for the linked radio: markers and contacts, then held chat.
     * No greeting line is posted in each channel: a reachable contact is enough to open its
     * chat, and a greeting on every connect marked every channel as unread.
     */
    private fun sendPicture() {
        val active = server ?: return
        if (!active.hasClient) return
        sendMeshMarkers(active)
        val atak = identity ?: return
        val held = synchronized(backlog) { ArrayList(backlog).also { backlog.clear() } }
        for (message in held) deliver(message, atak)
    }

    /**
     * Send the mesh markers, or just the one for [only]. A node the filters now leave out is
     * removed from ATAK at once rather than left to go stale.
     */
    private fun sendMeshMarkers(active: TakServer, only: Contact? = null) {
        if (!active.hasClient) return
        val now = Instant.now()
        val markers = mesh.markers(now.epochSecond)
        if (only != null) {
            val uid = mesh.markerUid(only)
            val marker = markers.firstOrNull { it.uid == uid }
            if (marker != null) {
                active.send(CotEvents.marker(marker, now))
                onMap.add(uid)
            } else if (onMap.remove(uid)) {
                active.send(CotEvents.delete(uid, now))
            }
            return
        }
        val current = markers.mapTo(HashSet()) { it.uid }
        for (marker in markers) active.send(CotEvents.marker(marker, now))
        for (uid in onMap.toList()) {
            if (uid !in current) {
                active.send(CotEvents.delete(uid, now))
                onMap.remove(uid)
            }
        }
        onMap.addAll(current)
    }

    override fun onDestroy() {
        wanted = null
        main.removeCallbacks(retry)
        cancelPendingOpen()
        radio?.close()
        radio = null
        BridgeStatus.update { it.copy(radio = "No radio", radioBusy = false, batteryMillivolts = null) }
        worker.execute {
            refresher?.shutdownNow()
            refresher = null
            server?.stop()
            server = null
            api?.stop()
            api = null
            BridgeStatus.update { it.copy(running = false, clientConnected = false) }
            BridgeStatus.log("Bridge stopped")
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun notifications() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun openScreen(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** The status notification's text: the radio's battery when there is a reading, and the server. */
    private fun statusText(): String {
        val server = "TAK server on 127.0.0.1:${TakServer.DEFAULT_PORT}"
        val battery = BridgeStatus.current().batteryMillivolts
            ?.takeIf { RadioBattery.percent(it) != null }
            ?.let { "Radio battery ${RadioBattery.describe(it)}" }
        return listOfNotNull(battery, server).joinToString(" · ")
    }

    private fun statusNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshCore TAK Bridge")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openScreen())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** Main thread. Rewrites the status notification when its text has changed. */
    private fun refreshNotification() {
        val text = statusText()
        if (text == notificationText || notificationText.isEmpty()) return
        notificationText = text
        notifications().notify(NOTIFICATION_ID, statusNotification(text))
    }

    /** Main thread. A heads-up alert, so it shows over ATAK, as the radio's battery falls to [level] percent. */
    private fun alertLowBattery(radioName: String, level: Int, millivolts: Int) {
        BridgeStatus.log("Radio: battery is low, ${RadioBattery.describe(millivolts)}")
        val advice = if (level <= CRITICAL_BATTERY) "Charge it now to stay on the mesh." else "Charge it soon to stay on the mesh."
        val alert = Notification.Builder(this, BATTERY_CHANNEL_ID)
            .setContentTitle("Radio battery low")
            .setContentText("$radioName is at ${RadioBattery.describe(millivolts)}. $advice")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openScreen())
            .setAutoCancel(true)
            .build()
        notifications().notify(BATTERY_NOTIFICATION_ID, alert)
    }

    private fun startInForeground() {
        val manager = notifications()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bridge status", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(BATTERY_CHANNEL_ID, "Radio battery low", NotificationManager.IMPORTANCE_HIGH),
        )
        notificationText = statusText()
        val notification = statusNotification(notificationText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android only allows the connected-device type once Bluetooth permission is held.
            val bluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (bluetooth) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Runs on [worker]. The answer to ATAK's contact-list poll on port 8080; see [ApiServer].
     * Not essential: if another app holds the port, the bridge still works, and ATAK only shows
     * its "Failed to get server contacts" message.
     */
    private fun startApi() {
        if (api != null) return
        val created = ApiServer { request -> BridgeStatus.log("ATAK asked the web API for $request; not provided") }
        try {
            created.start()
            api = created
        } catch (e: IOException) {
            BridgeStatus.log("Web API port ${ApiServer.DEFAULT_PORT} is in use (${e.message}); ATAK may say it cannot get server contacts")
        }
    }

    /** Runs on [worker]. */
    private fun startServer() {
        if (server != null) return
        val created = TakServer(this)
        try {
            val port = created.start()
            server = created
            BridgeStatus.update { it.copy(running = true, port = port) }
            BridgeStatus.log("Listening on 127.0.0.1:$port")
        } catch (e: IOException) {
            BridgeStatus.log("Could not start the server: ${e.message}")
            stopSelf()
            return
        }
        startApi()
        // A marker goes stale in ATAK after ten minutes, so re-send well inside that.
        refresher = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ sendMarkers() }, REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES)
        }
    }

    private fun sendMarkers() {
        val active = server ?: return
        if (!active.hasClient) return
        if (mesh.hasRadio) {
            sendMeshMarkers(active)
            return
        }
        val now = Instant.now()
        for (marker in DemoData.markers) active.send(CotEvents.marker(marker, now))
    }

    override fun onClientConnected() {
        BridgeStatus.update { it.copy(clientConnected = true) }
        identity = null
        onMap.clear()
        applyTracking()
        if (mesh.hasRadio) {
            BridgeStatus.log("ATAK connected; sending ${mesh.positionedCount} mesh nodes and ${mesh.channelCount} channels")
            sendPicture()
            return
        }
        BridgeStatus.log("ATAK connected; no radio linked, so sending ${DemoData.markers.size} demo markers")
        sendMarkers()
        val now = Instant.now()
        server?.send(CotEvents.geoChat(DemoData.message("No radio linked yet. This is a demo room.", now), now))
    }

    override fun onEvent(xml: String) {
        val who = CotParser.parseClientIdentity(xml) ?: return
        if (identity == who) return
        identity = who
        BridgeStatus.log("ATAK is ${who.callsign} (${who.uid})")
        if (mesh.hasRadio) sendPicture()
    }

    override fun onClientDisconnected() {
        BridgeStatus.update { it.copy(clientConnected = false) }
        identity = null
        BridgeStatus.log("ATAK disconnected")
        applyTracking()
    }

    override fun onChat(chat: InboundChat) {
        val now = Instant.now()
        val session = radio
        if (!mesh.hasRadio) {
            BridgeStatus.log("Chat from ATAK in \"${chat.room}\" (no radio linked): ${chat.text}")
            val reply = DemoData.message("Bridge heard: ${chat.text}", now, room = chat.room, roomId = chat.roomId)
            server?.send(CotEvents.geoChat(reply, now))
            return
        }
        val channel = mesh.channelFor(chat.room, chat.roomId)
        val node = if (channel == null) mesh.companionFor(chat.roomId) else null
        if (channel == null && node == null) {
            BridgeStatus.log("Chat from ATAK to \"${chat.room}\" (id ${chat.roomId}) is not for a mesh channel or companion; not sent")
            return
        }
        if (session == null || !radioUp) {
            BridgeStatus.log("ATAK to mesh, \"${chat.room}\": the radio is not connected; not sent")
            noteToAtak(channel, node, "Not sent: the radio is not connected.")
            return
        }
        // On a channel, "@name …" and "@@ …" become MeshCore's "@[Name] …" reply form.
        val text = if (channel == null) chat.text else when (val outcome = replies.resolve(channel.index, chat.text, now.epochSecond)) {
            is ChannelReplies.Outcome.Send -> {
                outcome.repliedTo?.let { BridgeStatus.log("ATAK reply on ${channel.name}: to $it") }
                outcome.text
            }
            is ChannelReplies.Outcome.Ambiguous -> {
                val shown = outcome.names.take(MAX_NAMES_SHOWN).joinToString(", ")
                val more = (outcome.names.size - MAX_NAMES_SHOWN).takeIf { it > 0 }?.let { " and $it more" } ?: ""
                BridgeStatus.log("ATAK reply on ${channel.name}: @${outcome.typed} matches ${outcome.names.size} names; not sent")
                noteToAtak(channel, null, "Not sent: @${outcome.typed} matches $shown$more. Type more of the name.")
                return
            }
            is ChannelReplies.Outcome.NobodyYet -> {
                BridgeStatus.log("ATAK reply on ${channel.name}: @@ with nobody heard recently; not sent")
                noteToAtak(channel, null, "Not sent: nobody has spoken on this channel recently, so @@ has no one to reply to.")
                return
            }
        }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        val limit = if (node != null) mesh.maxDirectTextBytes() else mesh.maxTextBytes()
        if (bytes > limit) {
            BridgeStatus.log("ATAK to mesh, \"${chat.room}\": too long ($bytes of $limit bytes); not sent")
            noteToAtak(channel, node, "Not sent: too long for the mesh ($bytes of $limit bytes).")
            return
        }
        if (node != null) {
            BridgeStatus.log("ATAK to ${mesh.displayName(node)}, direct: ${chat.text}")
            mesh.noteTalkedTo(node, now.epochSecond)
            val id = session.sendDirectText(node.publicKeyHex, chat.text)
            if (id == null) {
                BridgeStatus.log("ATAK to ${mesh.displayName(node)}: the radio is busy or still starting; not sent")
                noteToAtak(null, node, "Not sent: the radio is busy or still starting. Try again shortly.")
            } else {
                // ATAK names each message it sends; a receipt with that id marks it delivered.
                chat.messageId?.let {
                    synchronized(awaitingAck) { awaitingAck[id] = PendingReceipt(it, mesh.markerUid(node), mesh.displayName(node)) }
                }
            }
            return
        }
        val target = channel ?: return
        BridgeStatus.log("ATAK to mesh, channel ${target.index} (${target.name}): $text")
        session.sendChannelText(target.index, text)
    }

    /** A note from the bridge in the ATAK conversation a message was sent in: a channel's or a companion's. */
    private fun noteToAtak(channel: Channel?, node: Contact?, text: String) {
        val atak = identity ?: return
        val now = Instant.now()
        val line = when {
            channel != null -> mesh.notice(channel.index, atak, text, now)
            node != null -> mesh.directNotice(node, atak, text, now)
            else -> null
        } ?: return
        server?.send(CotEvents.geoChat(line, now))
    }

    override fun onNotice(message: String) {
        BridgeStatus.log(message)
    }

    companion object {
        const val ACTION_STOP = "org.coloradomesh.takbridge.STOP"
        const val ACTION_CONNECT_RADIO = "org.coloradomesh.takbridge.CONNECT_RADIO"
        const val ACTION_DISCONNECT_RADIO = "org.coloradomesh.takbridge.DISCONNECT_RADIO"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        const val ACTION_RELOAD_STYLE = "org.coloradomesh.takbridge.RELOAD_STYLE"
        const val PREFS = "bridge"
        const val PREF_RELAY_ICON = "relay_icon"
        const val PREF_ROOM_ICON = "room_icon"
        const val PREF_COMPANION_TEAM = "companion_team"
        const val PREF_SHOWN_TYPES = "shown_types"
        const val PREF_MAX_AGE_HOURS = "max_age_hours"
        const val ACTION_RELOAD_TRACKING = "org.coloradomesh.takbridge.RELOAD_TRACKING"
        const val PREF_TRACKED = "tracked_nodes"
        const val PREF_TRACK_MINUTES = "track_minutes"

        /** Whether a real MAYDAY with a position sounds ATAK's emergency alarm; off unless chosen. */
        const val PREF_MECP_ALARM = "mecp_alarm"

        /** The name the bridge gives the radio when it says hello, as before the shared code. */
        private const val APP_NAME = "TAKBridge"

        /** How long ATAK keeps an alert raised for an MECP MAYDAY, unless it is cancelled first. */
        private val ALERT_STALE: Duration = Duration.ofHours(1)

        /** How often each tracked node is asked for its position, unless the user chooses otherwise. */
        const val DEFAULT_TRACK_MINUTES = 5

        /** The most companions that can be tracked at once, to keep the airtime used reasonable. */
        const val MAX_TRACKED = 10
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Nodes silent for a week are left off the map unless the user chooses otherwise. */
        const val DEFAULT_MAX_AGE_HOURS = 7 * 24
        private const val PREF_ADDRESS = "radio_address"
        private const val PREF_NAME = "radio_name"

        /** How long a replaced radio link gets to close before the next one opens. */
        private const val REPLACE_PAUSE_MS = 1_000L

        /** Seconds to wait before each reconnection attempt; the last value repeats. */
        private val RETRY_SECONDS = intArrayOf(3, 5, 10, 20, 30, 60)
        private const val CHANNEL_ID = "bridge-status"
        private const val NOTIFICATION_ID = 1
        private const val BATTERY_CHANNEL_ID = "radio-battery"
        private const val BATTERY_NOTIFICATION_ID = 2

        /** At or below this percentage the low-battery alert says to charge now, not soon. */
        private const val CRITICAL_BATTERY = 10

        /** The log notes the battery again only when it has moved this many percentage points. */
        private const val BATTERY_LOG_STEP = 5

        /** Stands for a reading from a radio that cannot measure its battery. */
        private const val NOT_MEASURED = -1
        private const val REFRESH_MINUTES = 5L
        private const val MAX_BACKLOG = 50

        /** How many direct messages can wait for an acknowledgement at once. */
        private const val MAX_AWAITING_ACK = 100

        /** How many matching names a "which one?" note lists. */
        private const val MAX_NAMES_SHOWN = 5
    }
}
