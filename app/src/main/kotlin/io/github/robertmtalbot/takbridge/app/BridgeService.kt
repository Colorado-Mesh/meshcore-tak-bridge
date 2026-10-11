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
class BridgeService : Service(), TakServerListener {
    /** Socket work stays off the main thread, where Android forbids network calls. */
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var server: TakServer? = null
    private var refresher: ScheduledExecutorService? = null
    private var radio: RadioSession? = null

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
        radio = RadioSession(applicationContext, address).also { it.open() }
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
        val now = Instant.now()
        for (marker in DemoData.markers) active.send(CotEvents.marker(marker, now))
    }

    override fun onClientConnected() {
        BridgeStatus.update { it.copy(clientConnected = true) }
        BridgeStatus.log("ATAK connected; sent ${DemoData.markers.size} markers")
        sendMarkers()
        // Two room styles, to learn which ATAK shows. The third, a direct message, follows once
        // ATAK has said who it is.
        identity = null
        val now = Instant.now()
        server?.send(CotEvents.geoChat(DemoData.message("Test 1 of 3: named room. Reply here.", now), now))
        server?.send(
            CotEvents.geoChat(
                DemoData.message("Test 2 of 3: All Chat Rooms. Reply here.", now, room = DemoData.ALL_CHAT),
                now,
            ),
        )
    }

    override fun onEvent(xml: String) {
        val who = CotParser.parseClientIdentity(xml)
        if (who != null) {
            if (identity == who) return
            identity = who
            BridgeStatus.log("ATAK is ${who.callsign} (${who.uid})")
            val now = Instant.now()
            server?.send(
                CotEvents.geoChat(
                    DemoData.message("Test 3 of 3: direct message. Reply here.", now, who.callsign, who.uid),
                    now,
                ),
            )
            return
        }
        val type = CotParser.eventType(xml) ?: "?"
        if (type == CotEvents.GEOCHAT_TYPE) return
        BridgeStatus.log("Event from ATAK: type $type, uid ${CotParser.eventUid(xml)?.take(80)}")
    }

    override fun onClientDisconnected() {
        BridgeStatus.update { it.copy(clientConnected = false) }
        BridgeStatus.log("ATAK disconnected")
    }

    override fun onChat(chat: InboundChat) {
        val from = chat.senderCallsign.ifEmpty { chat.senderUid }
        BridgeStatus.log("Chat from $from, room \"${chat.room}\", id \"${chat.roomId}\": ${chat.text}")
        val now = Instant.now()
        val text = "Bridge heard: ${chat.text}"
        // Answer in the conversation the message came from: a message addressed to the bridge
        // contact goes back to the sender directly, anything else back to its room.
        val reply = if (chat.roomId == DemoData.BRIDGE_UID) {
            DemoData.message(text, now, room = from, roomId = chat.senderUid)
        } else {
            DemoData.message(text, now, room = chat.room, roomId = chat.roomId)
        }
        server?.send(CotEvents.geoChat(reply, now))
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
    }
}
