package io.github.robertmtalbot.takbridge.app.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.robertmtalbot.takbridge.app.BridgeStatus
import io.github.robertmtalbot.takbridge.protocol.CompanionClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One radio, from connect to disconnect: the Bluetooth link plus the protocol client that talks
 * over it. What the radio reports goes to [listener]; [onClosed] runs once when the link ends.
 */
class RadioSession(
    private val context: Context,
    private val address: String,
    private val listener: CompanionClient.Listener,
    private val onClosed: (reason: String) -> Unit,
) : GattLink.Events {
    private val link = GattLink(context, this)
    private val client = CompanionClient({ link.send(it) }, listener)
    private val ticker = Executors.newSingleThreadScheduledExecutor()

    fun open() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            ticker.shutdownNow()
            onClosed.invoke("Bluetooth is off")
            return
        }
        BridgeStatus.update { it.copy(radio = "Connecting to radio…") }
        link.connect(adapter.getRemoteDevice(address))
    }

    fun close() = link.close()

    fun sendChannelText(channelIndex: Int, text: String) = client.sendChannelText(channelIndex, text)

    /** Returns the id the listener's direct-message result will carry, or null when the radio cannot take it now. */
    fun sendDirectText(publicKeyHex: String, text: String): Long? = client.sendDirectText(publicKeyHex, text)

    override fun onLog(message: String) = BridgeStatus.log("Radio: $message")

    override fun onReady(packetSize: Int) {
        if (packetSize < COMFORTABLE_PACKET_BYTES) {
            onLog("Warning: packets are limited to $packetSize bytes, so long replies will be cut short")
        }
        BridgeStatus.update { it.copy(radio = "Radio linked; reading it…") }
        client.start()
        ticker.scheduleWithFixedDelay({ client.tick() }, 1, 1, TimeUnit.SECONDS)
    }

    override fun onFrame(frame: ByteArray) = client.onFrame(frame)

    override fun onClosed(reason: String) {
        ticker.shutdownNow()
        onClosed.invoke(reason)
    }

    private companion object {
        /** Enough for the longest frame the companion protocol sends. */
        const val COMFORTABLE_PACKET_BYTES = 172
    }
}
