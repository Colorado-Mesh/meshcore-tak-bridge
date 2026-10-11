package io.github.robertmtalbot.takbridge.app.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.robertmtalbot.takbridge.app.BridgeStatus
import io.github.robertmtalbot.takbridge.protocol.CompanionFrame
import io.github.robertmtalbot.takbridge.protocol.CompanionFrames

/**
 * The bridge's conversation with one radio: opens the link, introduces itself, and reports what
 * the radio says about itself. For now it stops there; contacts, channels and messages come in
 * the next milestones.
 */
class RadioSession(private val context: Context, private val address: String) : GattLink.Events {
    private val link = GattLink(context, this)

    fun open() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            onClosed("Bluetooth is off")
            return
        }
        BridgeStatus.update { it.copy(radio = "Connecting to radio…", radioBusy = true) }
        link.connect(adapter.getRemoteDevice(address))
    }

    fun close() = link.close()

    override fun onLog(message: String) = BridgeStatus.log("Radio: $message")

    override fun onReady(packetSize: Int) {
        if (packetSize < COMFORTABLE_PACKET_BYTES) {
            onLog("Warning: packets are limited to $packetSize bytes, so long replies will be cut short")
        }
        BridgeStatus.update { it.copy(radio = "Radio linked; asking who it is…") }
        link.send(CompanionFrames.appStart(APP_NAME))
    }

    override fun onFrame(frame: ByteArray) {
        when (val parsed = CompanionFrames.parse(frame)) {
            is CompanionFrame.Self -> {
                val info = parsed.info
                onLog(
                    "This is \"${info.name}\", key ${info.publicKeyHex.take(8)}…, " +
                        "${info.frequencyMhz} MHz, BW ${info.bandwidthKhz} kHz, SF${info.spreadingFactor}, CR${info.codingRate}",
                )
                BridgeStatus.update { it.copy(radio = "Radio: ${info.name}") }
                link.send(CompanionFrames.deviceQuery())
            }
            is CompanionFrame.Device -> {
                val info = parsed.info
                onLog(
                    "Firmware ${info.version} (protocol ${info.firmwareVersion}), ${info.model}, " +
                        "${info.maxChannels} channel slots, ${info.maxContacts} contacts",
                )
                onLog("Handshake complete")
            }
            is CompanionFrame.Error -> onLog("The radio answered with error ${parsed.code}")
            is CompanionFrame.Other -> onLog("Frame 0x%02X, %d bytes (not read yet)".format(parsed.code, parsed.length))
            CompanionFrame.Ok, null -> Unit
        }
    }

    override fun onClosed(reason: String) {
        BridgeStatus.update { it.copy(radio = "No radio: $reason", radioBusy = false) }
    }

    private companion object {
        const val APP_NAME = "TAKBridge"

        /** Enough for the longest frame the companion protocol sends. */
        const val COMFORTABLE_PACKET_BYTES = 172
    }
}
