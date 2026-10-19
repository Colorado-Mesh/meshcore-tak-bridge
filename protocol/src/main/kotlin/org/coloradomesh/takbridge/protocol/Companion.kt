package org.coloradomesh.takbridge.protocol

import java.security.MessageDigest
import java.util.Base64

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

/** A node the radio knows about, from its contact list or a newly heard advert. */
data class Contact(
    /** 64 lowercase hex characters. */
    val publicKeyHex: String,
    /** One of the `TYPE_` constants; other values are kinds this app does not know. */
    val type: Int,
    val name: String,
    /** Unix seconds when the node last advertised, by the sender's clock. */
    val lastAdvert: Long,
    val latitude: Double,
    val longitude: Double,
) {
    /** A node that shares no position reports exactly 0, 0. */
    val hasPosition: Boolean
        get() = (latitude != 0.0 || longitude != 0.0) && latitude in -90.0..90.0 && longitude in -180.0..180.0

    companion object {
        const val TYPE_COMPANION = 1
        const val TYPE_REPEATER = 2
        const val TYPE_ROOM_SERVER = 3
        const val TYPE_SENSOR = 4
    }
}

/**
 * A channel programmed into the radio. Its secret is only compared with the keys of the public
 * and hashtag channels to tell which [kind] it is; it is never kept.
 */
data class Channel(val index: Int, val name: String, val kind: Kind = Kind.PRIVATE) {
    enum class Kind {
        /** MeshCore's default Public channel, whose key every radio knows. */
        PUBLIC,

        /** A `#name` channel, whose key anyone can work out from its name. */
        HASHTAG,

        /** A channel with its own key, shared only with the people meant to read it. */
        PRIVATE,
    }
}

/** A text message received on a channel. The text is as sent: usually `Sender name: message`. */
data class ChannelMessage(
    val channelIndex: Int,
    val text: String,
    /** Unix seconds, by the sender's clock. */
    val timestamp: Long,
)

/** A plain text message sent to this radio alone by another node. */
data class DirectMessage(
    /** The first 6 bytes of the sender's public key as 12 lowercase hex characters; all the radio gives. */
    val senderPrefixHex: String,
    val text: String,
    /** Unix seconds, by the sender's clock. A sender's retries of one message keep the same value. */
    val timestamp: Long,
)

/** A position a node reported, from its GPS. */
data class Position(val latitude: Double, val longitude: Double, val altitudeMeters: Double?)

/**
 * A node's answer to a telemetry request: its battery, and its position when it has a GPS that
 * is on and it lets this radio see where it is.
 */
data class Telemetry(
    /** The first 6 bytes of the node's public key as 12 lowercase hex characters. */
    val senderPrefixHex: String,
    val position: Position?,
    val millivolts: Int?,
)

/** One frame received from the radio, as far as the bridge understands it. */
sealed interface CompanionFrame {
    data class Self(val info: SelfInfo) : CompanionFrame
    data class Device(val info: DeviceInfo) : CompanionFrame
    data object Ok : CompanionFrame

    /** First answer to a contact list request. */
    data object ContactsStart : CompanionFrame

    /** [pushed] is true when the radio volunteered it for a newly heard node. */
    data class ContactEntry(val contact: Contact, val pushed: Boolean) : CompanionFrame

    /** Last answer to a contact list request; [lastModified] can be sent back to ask for changes only. */
    data class ContactsEnd(val lastModified: Long) : CompanionFrame

    /** One channel slot; [channel] is null when the slot is not programmed. */
    data class ChannelSlot(val index: Int, val channel: Channel?) : CompanionFrame

    data class ChannelText(val message: ChannelMessage) : CompanionFrame

    data class DirectText(val message: DirectMessage) : CompanionFrame

    /**
     * The radio has transmitted a direct message. [ackCode] is what the recipient's
     * acknowledgement will carry, and [timeoutMs] is how long the radio expects that to take.
     */
    data class Sent(val flood: Boolean, val ackCode: Long, val timeoutMs: Long) : CompanionFrame

    /** A recipient acknowledged the direct message whose [Sent.ackCode] this is. */
    data class SendConfirmed(val ackCode: Long, val tripMs: Long) : CompanionFrame

    /** A node answered a telemetry request. */
    data class TelemetryReply(val telemetry: Telemetry) : CompanionFrame

    /** The radio's battery voltage. Zero on boards that cannot measure it. */
    data class Battery(val millivolts: Int) : CompanionFrame

    /** A queued message of a kind the bridge does not carry, such as channel data or a room server post. */
    data class OtherMessage(val code: Int) : CompanionFrame

    data object NoMoreMessages : CompanionFrame

    /** The radio has queued messages to collect. */
    data object MessagesWaiting : CompanionFrame

    /** The radio heard an advert from a node already in its contact list. */
    data object AdvertHeard : CompanionFrame
    data class Error(val code: Int?) : CompanionFrame

    /** A frame the bridge does not read yet, or one too short to be what its code claims. */
    data class Other(val code: Int, val length: Int) : CompanionFrame
}

/**
 * The MeshCore companion protocol, as documented at docs.meshcore.io/companion_protocol.
 * Over Bluetooth each write and each notification carries exactly one frame.
 *
 * Only commands that read from the radio, send a message, or ask a node for its telemetry belong here. The bridge never
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

    private const val CMD_SEND_DIRECT_TEXT = 0x02
    private const val CMD_SEND_CHANNEL_TEXT = 0x03
    private const val CMD_GET_CONTACTS = 0x04
    private const val CMD_SYNC_NEXT_MESSAGE = 0x0A
    private const val CMD_GET_CHANNEL = 0x1F
    private const val CMD_GET_BATTERY = 0x14
    private const val CMD_SEND_TELEMETRY_REQ = 0x27
    private const val RESP_CONTACTS_START = 0x02
    private const val RESP_CONTACT = 0x03
    private const val RESP_END_OF_CONTACTS = 0x04
    private const val RESP_SENT = 0x06
    private const val RESP_CONTACT_MSG = 0x07
    private const val RESP_CHANNEL_MSG = 0x08
    private const val RESP_NO_MORE_MESSAGES = 0x0A
    private const val RESP_BATTERY = 0x0C
    private const val RESP_CONTACT_MSG_V3 = 0x10
    private const val RESP_CHANNEL_MSG_V3 = 0x11
    private const val RESP_CHANNEL_INFO = 0x12
    private const val RESP_CHANNEL_DATA = 0x1B
    private const val PUSH_ADVERT = 0x80
    private const val PUSH_SEND_CONFIRMED = 0x82
    private const val PUSH_MSG_WAITING = 0x83
    private const val PUSH_NEW_ADVERT = 0x8A
    private const val PUSH_TELEMETRY_RESPONSE = 0x8B
    private const val TEXT_TYPE_PLAIN = 0

    /** The key of MeshCore's default Public channel, from the firmware (base64 izOH6cXN6mrJ5e26oRXNcg==). */
    private val PUBLIC_CHANNEL_KEY = Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==")

    private const val CONTACT_BYTES = 148
    private const val CHANNEL_INFO_BYTES = 50

    /** The most text bytes a channel message carries, sender name and separator included. */
    const val MAX_CHANNEL_TEXT_BYTES = 160

    /** The most text bytes a direct message carries; nothing is added in front. */
    const val MAX_DIRECT_TEXT_BYTES = 160

    /** How many bytes of a public key name a contact in direct-message frames. */
    const val KEY_PREFIX_BYTES = 6

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

    /** Asks for the contact list, or with [since] only for contacts changed after that time. */
    fun getContacts(since: Long = 0): ByteArray =
        if (since > 0) byteArrayOf(CMD_GET_CONTACTS.toByte()) + le32(since) else byteArrayOf(CMD_GET_CONTACTS.toByte())

    fun getChannel(index: Int): ByteArray = byteArrayOf(CMD_GET_CHANNEL.toByte(), index.toByte())

    /** Asks for the battery voltage; the radio answers with [CompanionFrame.Battery]. */
    fun getBattery(): ByteArray = byteArrayOf(CMD_GET_BATTERY.toByte())

    /**
     * Asks the node whose public key is [publicKeyHex] (all 64 hex characters) for its telemetry.
     * The radio answers with [CompanionFrame.Sent]; the node's answer, if any, arrives later as
     * [CompanionFrame.TelemetryReply]. A node sends nothing back when it does not allow the request.
     */
    fun telemetryRequest(publicKeyHex: String): ByteArray {
        require(publicKeyHex.length == 64 && publicKeyHex.all { it in "0123456789abcdef" }) { "need a whole public key in lowercase hex" }
        val key = ByteArray(32) { publicKeyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return byteArrayOf(CMD_SEND_TELEMETRY_REQ.toByte(), 0, 0, 0) + key
    }

    /** Collects one queued message; the radio answers with it or with [CompanionFrame.NoMoreMessages]. */
    fun syncNextMessage(): ByteArray = byteArrayOf(CMD_SYNC_NEXT_MESSAGE.toByte())

    /** Sends [text] on a channel. The radio adds its own name in front before transmitting. */
    fun sendChannelText(channelIndex: Int, timestampSeconds: Long, text: String): ByteArray =
        byteArrayOf(CMD_SEND_CHANNEL_TEXT.toByte(), TEXT_TYPE_PLAIN.toByte(), channelIndex.toByte()) +
            le32(timestampSeconds) + text.toByteArray(Charsets.UTF_8)

    /**
     * Sends [text] to the contact whose public key is [publicKeyHex]; the radio answers with
     * [CompanionFrame.Sent], or an error when it has no such contact. [attempt] counts from 0 and
     * a retry keeps the first try's [timestampSeconds], as the MeshCore apps do.
     */
    fun sendDirectText(publicKeyHex: String, attempt: Int, timestampSeconds: Long, text: String): ByteArray {
        require(publicKeyHex.length >= KEY_PREFIX_BYTES * 2) { "public key too short" }
        val prefix = ByteArray(KEY_PREFIX_BYTES) { publicKeyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return byteArrayOf(CMD_SEND_DIRECT_TEXT.toByte(), TEXT_TYPE_PLAIN.toByte(), (attempt and 3).toByte()) +
            le32(timestampSeconds) + prefix + text.toByteArray(Charsets.UTF_8)
    }

    private fun le32(value: Long) = ByteArray(4) { (value shr (8 * it)).toByte() }

    fun parse(frame: ByteArray): CompanionFrame? {
        if (frame.isEmpty()) return null
        val code = frame[0].toInt() and 0xFF
        return when (code) {
            RESP_OK -> CompanionFrame.Ok
            RESP_ERROR -> CompanionFrame.Error(frame.getOrNull(1)?.let { it.toInt() and 0xFF })
            RESP_SELF_INFO -> parseSelfInfo(frame)
            RESP_DEVICE_INFO -> parseDeviceInfo(frame)
            RESP_SENT -> if (frame.size >= 10) CompanionFrame.Sent(frame[1].toInt() == 1, uint32(frame, 2), uint32(frame, 6)) else null
            PUSH_SEND_CONFIRMED -> if (frame.size >= 9) CompanionFrame.SendConfirmed(uint32(frame, 1), uint32(frame, 5)) else null
            RESP_CONTACTS_START -> CompanionFrame.ContactsStart
            RESP_CONTACT -> parseContact(frame)?.let { CompanionFrame.ContactEntry(it, pushed = false) }
            PUSH_NEW_ADVERT -> parseContact(frame)?.let { CompanionFrame.ContactEntry(it, pushed = true) }
            RESP_END_OF_CONTACTS -> CompanionFrame.ContactsEnd(if (frame.size >= 5) uint32(frame, 1) else 0)
            RESP_CHANNEL_INFO -> parseChannel(frame)
            RESP_CHANNEL_MSG -> parseChannelText(frame, 1)
            RESP_CHANNEL_MSG_V3 -> parseChannelText(frame, 4)
            RESP_CONTACT_MSG -> parseDirectText(frame, 1)
            RESP_CONTACT_MSG_V3 -> parseDirectText(frame, 4)
            RESP_CHANNEL_DATA -> CompanionFrame.OtherMessage(code)
            RESP_NO_MORE_MESSAGES -> CompanionFrame.NoMoreMessages
            // Then the storage used and available, which the bridge does not need.
            RESP_BATTERY -> if (frame.size >= 3) CompanionFrame.Battery(uint16(frame, 1)) else null
            PUSH_MSG_WAITING -> CompanionFrame.MessagesWaiting
            PUSH_ADVERT -> CompanionFrame.AdvertHeard
            // A reserved byte, the sender's key prefix, then the telemetry in CayenneLPP form.
            PUSH_TELEMETRY_RESPONSE -> if (frame.size >= 2 + KEY_PREFIX_BYTES) {
                val lpp = CayenneLpp.read(frame.copyOfRange(2 + KEY_PREFIX_BYTES, frame.size))
                CompanionFrame.TelemetryReply(Telemetry(hex(frame, 2, KEY_PREFIX_BYTES), lpp.position, lpp.millivolts))
            } else {
                null
            }
            else -> null
        } ?: CompanionFrame.Other(code, frame.size)
    }

    private fun parseContact(frame: ByteArray): Contact? {
        if (frame.size < CONTACT_BYTES) return null
        return Contact(
            publicKeyHex = hex(frame, 1, 32),
            type = frame[33].toInt() and 0xFF,
            name = text(frame, 100, 32),
            lastAdvert = uint32(frame, 132),
            latitude = int32(frame, 136) / 1e6,
            longitude = int32(frame, 140) / 1e6,
        )
    }

    /**
     * Bytes 34 to 49 hold the channel's secret. It is compared with the well-known keys and then
     * dropped: only the resulting [Channel.Kind] leaves this function.
     */
    private fun parseChannel(frame: ByteArray): CompanionFrame? {
        if (frame.size < CHANNEL_INFO_BYTES) return null
        val index = frame[1].toInt() and 0xFF
        val name = text(frame, 2, 32)
        val secret = frame.copyOfRange(34, 50)
        val programmed = name.isNotEmpty() && secret.any { it.toInt() != 0 }
        val channel = if (programmed) Channel(index, name, channelKind(name, secret)) else null
        secret.fill(0)
        return CompanionFrame.ChannelSlot(index, channel)
    }

    /** Public's key is fixed; a hashtag channel's key is the first 16 bytes of SHA-256 of its name. */
    private fun channelKind(name: String, secret: ByteArray): Channel.Kind {
        if (MessageDigest.isEqual(secret, PUBLIC_CHANNEL_KEY)) return Channel.Kind.PUBLIC
        if (name.startsWith("#")) {
            val derived = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
            if (MessageDigest.isEqual(secret, derived)) return Channel.Kind.HASHTAG
        }
        return Channel.Kind.PRIVATE
    }

    /** [start] is where the channel index sits: 1 in the old layout, 4 after the signal bytes in the new. */
    private fun parseChannelText(frame: ByteArray, start: Int): CompanionFrame? {
        if (frame.size < start + 7) return null
        if (frame[start + 2].toInt() != TEXT_TYPE_PLAIN) return CompanionFrame.OtherMessage(frame[0].toInt() and 0xFF)
        return CompanionFrame.ChannelText(
            ChannelMessage(
                channelIndex = frame[start].toInt() and 0xFF,
                text = text(frame, start + 7, frame.size - start - 7),
                timestamp = uint32(frame, start + 3),
            ),
        )
    }

    /**
     * [start] is where the sender's key prefix sits: 1 in the old layout, 4 after the signal bytes
     * in the new. Then come the path length, the text type and the sender's timestamp. Only plain
     * text is read; signed posts from room servers and command replies are other kinds.
     */
    private fun parseDirectText(frame: ByteArray, start: Int): CompanionFrame? {
        val textStart = start + KEY_PREFIX_BYTES + 6
        if (frame.size < textStart) return null
        val code = frame[0].toInt() and 0xFF
        if (frame[start + KEY_PREFIX_BYTES + 1].toInt() != TEXT_TYPE_PLAIN) return CompanionFrame.OtherMessage(code)
        return CompanionFrame.DirectText(
            DirectMessage(
                senderPrefixHex = hex(frame, start, KEY_PREFIX_BYTES),
                text = text(frame, textStart, frame.size - textStart),
                timestamp = uint32(frame, start + KEY_PREFIX_BYTES + 2),
            ),
        )
    }

    private fun hex(frame: ByteArray, offset: Int, length: Int): String =
        frame.copyOfRange(offset, offset + length).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun parseSelfInfo(frame: ByteArray): CompanionFrame? {
        if (frame.size < SELF_INFO_FIXED_BYTES) return null
        return CompanionFrame.Self(
            SelfInfo(
                name = text(frame, SELF_INFO_FIXED_BYTES, frame.size - SELF_INFO_FIXED_BYTES),
                publicKeyHex = hex(frame, 4, 32),
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

    private fun uint16(frame: ByteArray, offset: Int): Int =
        (frame[offset].toInt() and 0xFF) or ((frame[offset + 1].toInt() and 0xFF) shl 8)

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
