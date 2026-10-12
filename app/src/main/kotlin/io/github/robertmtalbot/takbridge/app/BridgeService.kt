package io.github.robertmtalbot.takbridge.app

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
import io.github.robertmtalbot.takbridge.app.ble.RadioSession
import io.github.robertmtalbot.takbridge.cot.ClientIdentity
import io.github.robertmtalbot.takbridge.cot.CotEvents
import io.github.robertmtalbot.takbridge.cot.CotParser
import io.github.robertmtalbot.takbridge.cot.InboundChat
import io.github.robertmtalbot.takbridge.protocol.Channel
import io.github.robertmtalbot.takbridge.protocol.ChannelMessage
import io.github.robertmtalbot.takbridge.protocol.CompanionClient
import io.github.robertmtalbot.takbridge.protocol.Contact
import io.github.robertmtalbot.takbridge.protocol.DeviceInfo
import io.github.robertmtalbot.takbridge.protocol.SelfInfo
import io.github.robertmtalbot.takbridge.takserver.TakServer
import io.github.robertmtalbot.takbridge.takserver.TakServerListener
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps the local TAK server running while ATAK is in use. A foreground service with a visible
 * notification is what stops Android from ending the bridge when its screen is not showing.
 *
 * Until the radio link exists it serves [DemoData]: fixed markers and a chat room that echoes.
 */
class BridgeService : Service(), TakServerListener, CompanionClient.Listener {
    /** Socket work stays off the main thread, where Android forbids network calls. */
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var server: TakServer? = null
    private var refresher: ScheduledExecutorService? = null
    @Volatile
    private var radio: RadioSession? = null
    private val mesh = MeshPicture()
    private val main = Handler(Looper.getMainLooper())

    /** The radio the user chose, by Bluetooth address; null when none is chosen. Main thread only. */
    private var wanted: String? = null
    private var wantedName = "radio"
    private var failures = 0

    /** True once the radio has been read and can take messages. */
    @Volatile
    private var radioUp = false

    /** Channel messages received while ATAK was not there to take them. */
    private val backlog = ArrayList<ChannelMessage>()

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
            ACTION_RELOAD_STYLE -> worker.execute { sendMarkers() }
            else -> resumeSavedRadio()
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadStyle() {
        mesh.relayIconPath = prefs().getString(PREF_RELAY_ICON, null)?.takeIf { MeshPicture.isIconPath(it) }
    }

    /** The user picked a radio: remember it, and keep it connected until told otherwise. */
    private fun chooseRadio(address: String, name: String) {
        prefs().edit().putString(PREF_ADDRESS, address).putString(PREF_NAME, name).apply()
        if (address != wanted) mesh.clear()
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
        radio?.close()
        radio = null
        radioUp = false
        mesh.clear()
        BridgeStatus.update { it.copy(radio = "No radio", radioBusy = false) }
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

    /** Main thread. Opens a link to [address], replacing any earlier one. */
    private fun connectRadio(address: String) {
        main.removeCallbacks(retry)
        radio?.close()
        BridgeStatus.update { it.copy(radioBusy = true) }
        var session: RadioSession? = null
        session = RadioSession(applicationContext, address, this) { reason ->
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
        radio = null
        radioUp = false
        if (wanted == null) return
        val wait = RETRY_SECONDS[failures.coerceAtMost(RETRY_SECONDS.size - 1)]
        failures++
        BridgeStatus.update { it.copy(radio = "Lost $wantedName: $reason\nTrying again in $wait s") }
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

    override fun onContact(contact: Contact) {
        mesh.putContact(contact)
        if (contact.hasPosition) server?.let { active -> sendMeshMarkers(active, only = contact) }
    }

    override fun onChannels(channels: List<Channel>) {
        mesh.setChannels(channels)
        radioUp = true
        main.post { failures = 0 }
        val names = channels.joinToString { "${it.index}: ${it.name}" }.ifEmpty { "none" }
        BridgeStatus.log("Radio: ${mesh.contactCount} contacts, ${mesh.positionedCount} with a position")
        BridgeStatus.log("Radio: ${channels.size} channels ($names)")
        BridgeStatus.update { it.copy(radio = "${it.radio}\n${channels.size} channels, ${mesh.positionedCount} nodes on the map") }
        sendPicture()
    }

    override fun onChannelMessage(message: ChannelMessage) {
        BridgeStatus.log("Mesh, channel ${message.channelIndex}: ${message.text}")
        val atak = identity
        if (atak == null) {
            // Chat is addressed to the ATAK user, who is not known until ATAK connects and reports in.
            synchronized(backlog) {
                backlog.add(message)
                while (backlog.size > MAX_BACKLOG) backlog.removeAt(0)
            }
            return
        }
        deliver(message, atak)
    }

    private fun deliver(message: ChannelMessage, atak: ClientIdentity) {
        val line = mesh.chatLine(message, atak)
        if (line == null) {
            BridgeStatus.log("Radio: message on unknown channel ${message.channelIndex} ignored")
            return
        }
        server?.send(CotEvents.geoChat(line, Instant.now()))
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

    /** Everything ATAK should see for the linked radio: markers, a line to open each channel, and held chat. */
    private fun sendPicture() {
        val active = server ?: return
        if (!active.hasClient) return
        sendMeshMarkers(active)
        val atak = identity ?: return
        val now = Instant.now()
        for (opener in mesh.openers(atak, now)) active.send(CotEvents.geoChat(opener, now))
        val held = synchronized(backlog) { ArrayList(backlog).also { backlog.clear() } }
        for (message in held) deliver(message, atak)
    }

    private fun sendMeshMarkers(active: TakServer, only: Contact? = null) {
        if (!active.hasClient) return
        val now = Instant.now()
        val wanted = only?.let { "MESHCORE-${it.publicKeyHex.take(16)}" }
        for (marker in mesh.markers()) {
            if (wanted == null || marker.uid == wanted) active.send(CotEvents.marker(marker, now))
        }
    }

    override fun onDestroy() {
        wanted = null
        main.removeCallbacks(retry)
        radio?.close()
        radio = null
        BridgeStatus.update { it.copy(radio = "No radio", radioBusy = false) }
        worker.execute {
            refresher?.shutdownNow()
            refresher = null
            server?.stop()
            server = null
            BridgeStatus.update { it.copy(running = false, clientConnected = false) }
            BridgeStatus.log("Bridge stopped")
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bridge status", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshCore TAK Bridge")
            .setContentText("Local TAK server running on 127.0.0.1:${TakServer.DEFAULT_PORT}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
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
    }

    override fun onChat(chat: InboundChat) {
        val now = Instant.now()
        val session = radio
        if (mesh.hasRadio && (session == null || !radioUp)) {
            val channel = mesh.channelFor(chat.room, chat.roomId) ?: return
            BridgeStatus.log("ATAK to mesh, channel ${channel.index}: the radio is not connected; not sent")
            val note = identity?.let { mesh.notice(channel.index, it, "Not sent: the radio is not connected.", now) }
            note?.let { server?.send(CotEvents.geoChat(it, now)) }
            return
        }
        if (!mesh.hasRadio || session == null) {
            BridgeStatus.log("Chat from ATAK in \"${chat.room}\" (no radio linked): ${chat.text}")
            val reply = DemoData.message("Bridge heard: ${chat.text}", now, room = chat.room, roomId = chat.roomId)
            server?.send(CotEvents.geoChat(reply, now))
            return
        }
        val channel = mesh.channelFor(chat.room, chat.roomId)
        if (channel == null) {
            BridgeStatus.log("Chat from ATAK to \"${chat.room}\" (id ${chat.roomId}) is not for a mesh channel; not sent")
            return
        }
        val atak = identity
        val bytes = chat.text.toByteArray(Charsets.UTF_8).size
        val limit = mesh.maxTextBytes()
        if (bytes > limit) {
            BridgeStatus.log("ATAK to mesh, \"${chat.room}\": too long ($bytes of $limit bytes); not sent")
            val note = atak?.let { mesh.notice(channel.index, it, "Not sent: too long for the mesh ($bytes of $limit bytes).", now) }
            note?.let { server?.send(CotEvents.geoChat(it, now)) }
            return
        }
        BridgeStatus.log("ATAK to mesh, channel ${channel.index} (${channel.name}): ${chat.text}")
        session.sendChannelText(channel.index, chat.text)
    }

    override fun onNotice(message: String) {
        BridgeStatus.log(message)
    }

    companion object {
        const val ACTION_STOP = "io.github.robertmtalbot.takbridge.STOP"
        const val ACTION_CONNECT_RADIO = "io.github.robertmtalbot.takbridge.CONNECT_RADIO"
        const val ACTION_DISCONNECT_RADIO = "io.github.robertmtalbot.takbridge.DISCONNECT_RADIO"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        const val ACTION_RELOAD_STYLE = "io.github.robertmtalbot.takbridge.RELOAD_STYLE"
        const val PREFS = "bridge"
        const val PREF_RELAY_ICON = "relay_icon"
        private const val PREF_ADDRESS = "radio_address"
        private const val PREF_NAME = "radio_name"

        /** Seconds to wait before each reconnection attempt; the last value repeats. */
        private val RETRY_SECONDS = intArrayOf(3, 5, 10, 20, 30, 60)
        private const val CHANNEL_ID = "bridge-status"
        private const val NOTIFICATION_ID = 1
        private const val REFRESH_MINUTES = 5L
        private const val MAX_BACKLOG = 50
    }
}
