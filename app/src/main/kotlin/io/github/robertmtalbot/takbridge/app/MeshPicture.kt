package io.github.robertmtalbot.takbridge.app

import io.github.robertmtalbot.takbridge.cot.ChatSpec
import io.github.robertmtalbot.takbridge.cot.ClientIdentity
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
 * in ATAK: nodes with a position become markers, and each channel becomes a contact.
 *
 * A channel is a contact, not a group chat room, because of how ATAK addresses replies. It sends
 * a reply to a contact it can reach, and the event names that contact, so a contact per channel
 * tells the bridge exactly which channel a message is for. The mesh sender's name stays in the
 * text, as MeshCore sends it.
 */
class MeshPicture {
    @Volatile
    private var self: SelfInfo? = null

    @Volatile
    private var rooms: Map<String, Channel> = emptyMap()
    private val contacts = ConcurrentHashMap<String, Contact>()

    /**
     * Icon for repeaters and room servers, as `<icon set uid>/<group>/<file>`, or null for the
     * plain installation symbol. ATAK draws the symbol anyway when it lacks the icon set.
     */
    @Volatile
    var relayIconPath: String? = null

    /** Node types to put on the map, as `Contact.TYPE_` values. Kinds this app does not know always show. */
    @Volatile
    var shownTypes: Set<Int> = ALL_TYPES

    /** Leave off nodes whose last advert is older than this many seconds; null shows them all. */
    @Volatile
    var maxAgeSeconds: Long? = null

    /** Whether a node goes on the map under the current filters. [nowSeconds] is Unix time. */
    fun isShown(contact: Contact, nowSeconds: Long): Boolean {
        if (!contact.hasPosition) return false
        if (contact.type in ALL_TYPES && contact.type !in shownTypes) return false
        val limit = maxAgeSeconds ?: return true
        // A sender's clock can be wrong; an advert dated in the future counts as fresh.
        return nowSeconds - contact.lastAdvert <= limit
    }

    fun markerUid(contact: Contact) = "MESHCORE-${contact.publicKeyHex.take(16)}"

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

    /** The channel a chat event from ATAK is for, going by the contact it was addressed to. */
    fun channelFor(room: String, roomId: String?): Channel? =
        rooms.values.firstOrNull { uidFor(it.index) == roomId } ?: rooms.entries.firstOrNull { callsign(it.key) == room }?.value

    private fun nameFor(channelIndex: Int): String? = rooms.entries.firstOrNull { it.value.index == channelIndex }?.key

    private fun uidFor(channelIndex: Int) = "MESHCORE-CH-$channelIndex"

    /** `#Public`, as MeshCore writes hashtag channels; a name that already starts with # keeps its one. */
    private fun callsign(channelName: String) = if (channelName.startsWith("#")) channelName else "#$channelName"

    /** The most text ATAK may send on a channel: the radio puts its own name and ": " in front. */
    fun maxTextBytes(): Int {
        val nameBytes = (self?.name ?: "").toByteArray(Charsets.UTF_8).size
        return (CompanionFrames.MAX_CHANNEL_TEXT_BYTES - nameBytes - 3).coerceIn(20, 133)
    }

    /** A contact for every channel, then every node that shares a position. */
    fun markers(nowSeconds: Long = System.currentTimeMillis() / 1000): List<MarkerSpec> {
        if (self == null) return emptyList()
        val list = ArrayList<MarkerSpec>()
        for ((name, channel) in rooms) {
            // A contact has to have a point. A channel has no place, so it sits at 0, 0, off any map in use.
            list.add(
                MarkerSpec(
                    uid = uidFor(channel.index),
                    cotType = "a-f-G-U-C",
                    callsign = callsign(name),
                    latitude = 0.0,
                    longitude = 0.0,
                    team = "White",
                    role = "Team Member",
                    remarks = "MeshCore channel ${channel.index}, linked through MeshCore TAK Bridge",
                    endpoint = "*:-1:stcp",
                ),
            )
        }
        for (contact in contacts.values) {
            if (!isShown(contact, nowSeconds)) continue
            val companion = contact.type == Contact.TYPE_COMPANION
            list.add(
                MarkerSpec(
                    uid = markerUid(contact),
                    cotType = when (contact.type) {
                        Contact.TYPE_COMPANION -> "a-f-G-U-C"
                        Contact.TYPE_SENSOR -> "a-f-G-E-S"
                        else -> "a-f-G-I"
                    },
                    callsign = contact.name.ifEmpty { contact.publicKeyHex.take(8) },
                    latitude = contact.latitude,
                    longitude = contact.longitude,
                    iconsetPath = relayIconPath.takeIf { contact.type == Contact.TYPE_REPEATER || contact.type == Contact.TYPE_ROOM_SERVER },
                    team = if (companion) "Cyan" else null,
                    role = if (companion) "Team Member" else null,
                    remarks = "MeshCore ${kind(contact.type)}, last advert ${TIME.format(Instant.ofEpochSecond(contact.lastAdvert))} UTC",
                ),
            )
        }
        return list
    }

    /** Nodes on the map under the current filters. */
    val positionedCount: Int
        get() {
            val now = System.currentTimeMillis() / 1000
            return contacts.values.count { isShown(it, now) }
        }

    val contactCount: Int
        get() = contacts.size

    private fun kind(type: Int) = when (type) {
        Contact.TYPE_COMPANION -> "companion"
        Contact.TYPE_REPEATER -> "repeater"
        Contact.TYPE_ROOM_SERVER -> "room server"
        Contact.TYPE_SENSOR -> "sensor"
        else -> "node"
    }

    /** One line per channel so its conversation exists in ATAK. The fixed id makes ATAK keep only one copy. */
    fun openers(atak: ClientIdentity, now: Instant): List<ChatSpec> = rooms.map { (name, channel) ->
        line(channel.index, name, atak, "Mesh channel \"$name\" is linked. Messages sent here go out over the radio.", now, "open-${name.hashCode()}")
    }

    /** A received channel message as a line from its channel's contact, or null when the channel is not known. */
    fun chatLine(message: ChannelMessage, atak: ClientIdentity): ChatSpec? {
        val name = nameFor(message.channelIndex) ?: return null
        val id = "rx-${message.channelIndex}-${message.timestamp}-${Integer.toHexString(message.text.hashCode())}"
        return line(message.channelIndex, name, atak, message.text, Instant.ofEpochSecond(message.timestamp), id)
    }

    /** A line from the bridge itself, such as a note that a message could not be sent. */
    fun notice(channelIndex: Int, atak: ClientIdentity, text: String, now: Instant): ChatSpec? {
        val name = nameFor(channelIndex) ?: return null
        return line(channelIndex, name, atak, text, now, "note-${now.toEpochMilli()}")
    }

    /** A direct message to the ATAK user from the channel's contact. */
    private fun line(channelIndex: Int, name: String, atak: ClientIdentity, text: String, sentAt: Instant, id: String) = ChatSpec(
        room = atak.callsign,
        roomId = atak.uid,
        senderUid = uidFor(channelIndex),
        senderCallsign = callsign(name),
        text = text,
        messageId = id,
        sentAt = sentAt,
    )

    companion object {
        val ALL_TYPES: Set<Int> =
            setOf(Contact.TYPE_COMPANION, Contact.TYPE_REPEATER, Contact.TYPE_ROOM_SERVER, Contact.TYPE_SENSOR)

        /** The Repeater icon in the GeoOps icon set's WildFire group. */
        const val GEOOPS_REPEATER_ICON = "83198b4872a8c34eb9c549da8a4de5a28f07821185b39a2277948f66c24ac17a/WildFire/Repeater.png"
        private const val MAX_ICON_PATH = 256

        /** True for `<set uid>/<group>/<file>` with nothing that could break out of an XML attribute. */
        fun isIconPath(value: String): Boolean {
            if (value.length > MAX_ICON_PATH || value.any { it in "<>\"'&" || it.isISOControl() }) return false
            val parts = value.split('/')
            return parts.size == 3 && parts.all { it.isNotBlank() }
        }

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}
