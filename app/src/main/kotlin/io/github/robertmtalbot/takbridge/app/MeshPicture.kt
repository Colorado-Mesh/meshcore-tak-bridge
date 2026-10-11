package io.github.robertmtalbot.takbridge.app

import io.github.robertmtalbot.takbridge.cot.ChatSpec
import io.github.robertmtalbot.takbridge.cot.MarkerSpec
import io.github.robertmtalbot.takbridge.protocol.Channel
import io.github.robertmtalbot.takbridge.protocol.ChannelMessage
import io.github.robertmtalbot.takbridge.protocol.CompanionFrames
import io.github.robertmtalbot.takbridge.protocol.Contact
import io.github.robertmtalbot.takbridge.protocol.SelfInfo
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * What the bridge knows about the mesh through the connected radio, and how each piece is shown
 * in ATAK: nodes with a position become markers, and each channel becomes a chat room.
 *
 * Every chat line is sent as coming from one contact that stands for the radio. ATAK only sends
 * into a room when it knows a member it can reach, and that contact, announced as reachable
 * through this connection, is that member. The mesh sender's name stays in the text.
 */
class MeshPicture {
    @Volatile
    private var self: SelfInfo? = null

    @Volatile
    private var rooms: Map<String, Channel> = emptyMap()
    private val contacts = ConcurrentHashMap<String, Contact>()

    val hasRadio: Boolean
        get() = self != null

    val channelCount: Int
        get() = rooms.size

    fun setSelf(info: SelfInfo) {
        self = info
    }

    fun putContact(contact: Contact) {
        contacts[contact.publicKeyHex] = contact
    }

    /** Channels that share a name get their slot number added so each has its own room. */
    fun setChannels(channels: List<Channel>) {
        val repeated = channels.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        rooms = channels.associateBy { if (it.name in repeated) "${it.name} (${it.index})" else it.name }
    }

    fun clear() {
        self = null
        rooms = emptyMap()
        contacts.clear()
    }

    fun channelFor(room: String): Channel? = rooms[room]

    private fun roomFor(channelIndex: Int): String? = rooms.entries.firstOrNull { it.value.index == channelIndex }?.key

    /** The most text ATAK may send on a channel: the radio puts its own name and ": " in front. */
    fun maxTextBytes(): Int {
        val nameBytes = (self?.name ?: "").toByteArray(Charsets.UTF_8).size
        return (CompanionFrames.MAX_CHANNEL_TEXT_BYTES - nameBytes - 3).coerceIn(20, 133)
    }

    /** The radio contact first, then every node that shares a position. */
    fun markers(): List<MarkerSpec> {
        val radio = self ?: return emptyList()
        val list = ArrayList<MarkerSpec>()
        list.add(
            MarkerSpec(
                uid = RADIO_UID,
                cotType = "a-f-G-U-C",
                callsign = "Mesh via ${radio.name}",
                latitude = radio.latitude.takeIf { it in -90.0..90.0 } ?: 0.0,
                longitude = radio.longitude.takeIf { it in -180.0..180.0 } ?: 0.0,
                team = "Cyan",
                role = "Team Member",
                remarks = "MeshCore radio linked through MeshCore TAK Bridge",
                endpoint = "*:-1:stcp",
            ),
        )
        for (contact in contacts.values) {
            if (!contact.hasPosition) continue
            val companion = contact.type == Contact.TYPE_COMPANION
            list.add(
                MarkerSpec(
                    uid = "MESHCORE-${contact.publicKeyHex.take(16)}",
                    cotType = when (contact.type) {
                        Contact.TYPE_COMPANION -> "a-f-G-U-C"
                        Contact.TYPE_SENSOR -> "a-f-G-E-S"
                        else -> "a-f-G-I"
                    },
                    callsign = contact.name.ifEmpty { contact.publicKeyHex.take(8) },
                    latitude = contact.latitude,
                    longitude = contact.longitude,
                    team = if (companion) "Cyan" else null,
                    role = if (companion) "Team Member" else null,
                    remarks = "MeshCore ${kind(contact.type)}, last advert ${TIME.format(Instant.ofEpochSecond(contact.lastAdvert))} UTC",
                ),
            )
        }
        return list
    }

    val positionedCount: Int
        get() = contacts.values.count { it.hasPosition }

    val contactCount: Int
        get() = contacts.size

    private fun kind(type: Int) = when (type) {
        Contact.TYPE_COMPANION -> "companion"
        Contact.TYPE_REPEATER -> "repeater"
        Contact.TYPE_ROOM_SERVER -> "room server"
        Contact.TYPE_SENSOR -> "sensor"
        else -> "node"
    }

    /** One line per channel so its room exists in ATAK. The fixed id makes ATAK keep only one copy. */
    fun roomOpeners(now: Instant): List<ChatSpec> = rooms.keys.map { room ->
        line(room, "Mesh channel \"$room\" is linked. Messages sent here go out over the radio.", now, "open-${room.hashCode()}")
    }

    /** A received channel message as a line in its room, or null when the channel is not known. */
    fun chatLine(message: ChannelMessage, now: Instant): ChatSpec? {
        val room = roomFor(message.channelIndex) ?: return null
        val id = "rx-${message.channelIndex}-${message.timestamp}-${Integer.toHexString(message.text.hashCode())}"
        return line(room, message.text, Instant.ofEpochSecond(message.timestamp), id)
    }

    /** A line from the bridge itself, such as a note that a message could not be sent. */
    fun notice(room: String, text: String, now: Instant): ChatSpec =
        line(room, text, now, "note-${now.toEpochMilli()}")

    private fun line(room: String, text: String, sentAt: Instant, id: String) = ChatSpec(
        room = room,
        senderUid = RADIO_UID,
        senderCallsign = "Mesh",
        text = text,
        messageId = id,
        sentAt = sentAt,
    )

    private companion object {
        const val RADIO_UID = "MESHCORE-BRIDGE"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}
