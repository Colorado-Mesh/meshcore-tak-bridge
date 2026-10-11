package io.github.robertmtalbot.takbridge.protocol

/** What the radio says about itself in answer to [CompanionFrames.appStart]. */
data class SelfInfo(
    val name: String,
    /** The node's public key as 64 lowercase hex characters. */
    val publicKeyHex: String,
    /** The position the node advertises; 0, 0 when it has none. */
    val latitude: Double,
    val longitude: Double,
    val frequencyMhz: Double,
    val bandwidthKhz: Double,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPowerDbm: Int,
)

/** What the radio reports about its firmware and capacity in answer to a device query. */
data class DeviceInfo(
    val firmwareVersion: Int,
    /** Zero when the firmware is too old to report it. */
    val maxContacts: Int,
    /** Channel slots the radio has; zero when the firmware is too old to report it. */
    val maxChannels: Int,
    val build: String,
    val model: String,
    val version: String,
)

/** One frame received from the radio, as far as the bridge understands it. */
sealed interface CompanionFrame {
    data class Self(val info: SelfInfo) : CompanionFrame
    data class Device(val info: DeviceInfo) : CompanionFrame
    data object Ok : CompanionFrame
    data class Error(val code: Int?) : CompanionFrame

    /** A frame the bridge does not read yet, or one too short to be what its code claims. */
    data class Other(val code: Int, val length: Int) : CompanionFrame
}

/**
 * The MeshCore companion protocol, as documented at docs.meshcore.io/companion_protocol.
 * Over Bluetooth each write and each notification carries exactly one frame.
 *
 * Only commands that read from the radio or send a message belong here. The bridge never
 * changes a radio's configuration, so nothing in this file builds a command that would.
 */
object CompanionFrames {
    /** The protocol version this app asks the radio to speak. */
    const val APP_PROTOCOL_VERSION = 3

    private const val CMD_APP_START = 0x01
    private const val CMD_DEVICE_QUERY = 0x16
    private const val RESP_OK = 0x00
    private const val RESP_ERROR = 0x01
    private const val RESP_SELF_INFO = 0x05
    private const val RESP_DEVICE_INFO = 0x0D

    private const val SELF_INFO_FIXED_BYTES = 58
    private const val DEVICE_INFO_V3_BYTES = 80
    private const val MAX_APP_NAME_BYTES = 12

    /**
     * Opens the session; the radio answers with [CompanionFrame.Self]. The name is kept short so
     * the frame fits a single write even when the link is still at the smallest packet size.
     */
    fun appStart(appName: String): ByteArray {
        val name = appName.toByteArray(Charsets.UTF_8).take(MAX_APP_NAME_BYTES).toByteArray()
        return byteArrayOf(CMD_APP_START.toByte(), 0, 0, 0, 0, 0, 0, 0) + name
    }

    /** Asks for firmware and capacity; the radio answers with [CompanionFrame.Device]. */
    fun deviceQuery(): ByteArray = byteArrayOf(CMD_DEVICE_QUERY.toByte(), APP_PROTOCOL_VERSION.toByte())

    fun parse(frame: ByteArray): CompanionFrame? {
        if (frame.isEmpty()) return null
        val code = frame[0].toInt() and 0xFF
        return when (code) {
            RESP_OK -> CompanionFrame.Ok
            RESP_ERROR -> CompanionFrame.Error(frame.getOrNull(1)?.let { it.toInt() and 0xFF })
            RESP_SELF_INFO -> parseSelfInfo(frame)
            RESP_DEVICE_INFO -> parseDeviceInfo(frame)
            else -> null
        } ?: CompanionFrame.Other(code, frame.size)
    }

    private fun parseSelfInfo(frame: ByteArray): CompanionFrame? {
        if (frame.size < SELF_INFO_FIXED_BYTES) return null
        return CompanionFrame.Self(
            SelfInfo(
                name = text(frame, SELF_INFO_FIXED_BYTES, frame.size - SELF_INFO_FIXED_BYTES),
                publicKeyHex = frame.copyOfRange(4, 36).joinToString("") { "%02x".format(it.toInt() and 0xFF) },
                latitude = int32(frame, 36) / 1e6,
                longitude = int32(frame, 40) / 1e6,
                frequencyMhz = uint32(frame, 48) / 1000.0,
                bandwidthKhz = uint32(frame, 52) / 1000.0,
                spreadingFactor = frame[56].toInt() and 0xFF,
                codingRate = frame[57].toInt() and 0xFF,
                txPowerDbm = frame[2].toInt(),
            ),
        )
    }

    /** The radio's Bluetooth PIN sits at bytes 4 to 7; it is deliberately never read. */
    private fun parseDeviceInfo(frame: ByteArray): CompanionFrame? {
        if (frame.size < 2) return null
        val firmware = frame[1].toInt() and 0xFF
        if (firmware < 3 || frame.size < DEVICE_INFO_V3_BYTES) {
            return CompanionFrame.Device(DeviceInfo(firmware, 0, 0, "", "", ""))
        }
        return CompanionFrame.Device(
            DeviceInfo(
                firmwareVersion = firmware,
                maxContacts = (frame[2].toInt() and 0xFF) * 2,
                maxChannels = frame[3].toInt() and 0xFF,
                build = text(frame, 8, 12),
                model = text(frame, 20, 40),
                version = text(frame, 60, 20),
            ),
        )
    }

    private fun uint32(frame: ByteArray, offset: Int): Long =
        (0..3).sumOf { (frame[offset + it].toLong() and 0xFF) shl (8 * it) }

    private fun int32(frame: ByteArray, offset: Int): Int = uint32(frame, offset).toInt()

    /** Text up to the first NUL, with control characters dropped so it is safe to display. */
    private fun text(frame: ByteArray, offset: Int, length: Int): String {
        val raw = frame.copyOfRange(offset, offset + length)
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.UTF_8).filter { !it.isISOControl() }.trim()
    }
}
