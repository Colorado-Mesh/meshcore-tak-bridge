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
        worker.execute { startServer() }
        when (intent?.action) {
            ACTION_CONNECT_RADIO -> intent.getStringExtra(EXTRA_ADDRESS)?.let { connectRadio(it) }
            ACTION_DISCONNECT_RADIO -> radio?.close()
        }
        return START_STICKY
    }

    /** Main thread. Replaces any earlier radio link. */
    private fun connectRadio(address: String) {
        radio?.close()
        mesh.clear()
        var session: RadioSession? = null
        session = RadioSession(applicationContext, address, this) {
            // Only the current session's end clears the picture; an old one closing late must not.
            if (radio === session) mesh.clear()
        }
        radio = session
        session.open()
    }

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
        radio?.close()
        radio = null
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
        private const val CHANNEL_ID = "bridge-status"
        private const val NOTIFICATION_ID = 1
        private const val REFRESH_MINUTES = 5L
        private const val MAX_BACKLOG = 50
    }
}
