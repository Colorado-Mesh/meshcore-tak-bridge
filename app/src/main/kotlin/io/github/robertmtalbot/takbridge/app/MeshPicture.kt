package io.github.robertmtalbot.takbridge.app

import io.github.robertmtalbot.takbridge.cot.ChatSpec
import io.github.robertmtalbot.takbridge.cot.ClientIdentity
import io.github.robertmtalbot.takbridge.cot.MarkerSpec
import io.github.robertmtalbot.takbridge.protocol.Channel
import io.github.robertmtalbot.takbridge.protocol.ChannelMessage
import io.github.robertmtalbot.takbridge.protocol.CompanionFrames
import io.github.robertmtalbot.takbridge.protocol.Contact
import io.github.robertmtalbot.takbridge.protocol.DirectMessage
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
 *
 * Companion nodes are reachable contacts too, so the ATAK user can message one directly. Only
 * companions: a repeater or room server reads plain text from its admin as a command.
 * A companion with no position on the map still gets a contact, at 0, 0 like the channels,
 * once a direct message has passed between it and the ATAK user, so the conversation can go on.
 */
class MeshPicture {
    @Volatile
    private var self: SelfInfo? = null

    @Volatile
    private var rooms: Map<String, Channel> = emptyMap()
    private val contacts = ConcurrentHashMap<String, Contact>()

    /** Public keys of companions a direct message has passed with since the radio was chosen. */
    private val talkedTo: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Icon for repeaters and room servers, as `<icon set uid>/<group>/<file>`, or null for the
     * plain installation symbol. ATAK draws the symbol anyway when it lacks the icon set.
     */
    @Volatile
    var relayIconPath: String? = null

    /** Icon for room servers, in the same form as [relayIconPath]. */
    @Volatile
    var roomIconPath: String? = null

    /** ATAK team colour companions are drawn in; one of [TEAM_COLOURS]. */
    @Volatile
    var companionTeam: String = DEFAULT_COMPANION_TEAM

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
        talkedTo.clear()
    }

    /** The companion a chat event from ATAK is for, going by the contact uid it was addressed to. */
    fun companionFor(uid: String?): Contact? {
        if (uid == null) return null
        return contacts.values.firstOrNull { it.type == Contact.TYPE_COMPANION && markerUid(it) == uid }
    }

    fun contactByKey(publicKeyHex: String): Contact? = contacts[publicKeyHex]

    /** The node a direct message came from; the radio names it by the first 6 bytes of its key. */
    fun contactFor(message: DirectMessage): Contact? = contacts.values.firstOrNull { it.publicKeyHex.startsWith(message.senderPrefixHex) }

    /**
     * Notes that a direct message passed with [contact]. True when ATAK needs its contact sent
     * now: the node is a companion that the map does not already show.
     */
    fun noteTalkedTo(contact: Contact, nowSeconds: Long): Boolean {
        if (contact.type != Contact.TYPE_COMPANION) return false
        val added = talkedTo.add(contact.publicKeyHex)
        return added && !isShown(contact, nowSeconds)
    }

    /** A direct message has no prefix added, so it can use every byte the protocol allows. */
    fun maxDirectTextBytes(): Int = CompanionFrames.MAX_DIRECT_TEXT_BYTES

    /** A received direct message as a line from its sender's contact to the ATAK user. */
    fun directLine(message: DirectMessage, atak: ClientIdentity): ChatSpec {
        val contact = contactFor(message)
        val id = "dm-${message.senderPrefixHex}-${message.timestamp}-${Integer.toHexString(message.text.hashCode())}"
        return ChatSpec(
            room = atak.callsign,
            roomId = atak.uid,
            senderUid = contact?.let { markerUid(it) } ?: "MESHCORE-${message.senderPrefixHex}",
            senderCallsign = contact?.let { displayName(it) } ?: message.senderPrefixHex.take(8),
            text = message.text,
            messageId = id,
            sentAt = Instant.ofEpochSecond(message.timestamp),
        )
    }

    /** A line from the bridge in the conversation with [contact], such as a note that a message was not delivered. */
    fun directNotice(contact: Contact, atak: ClientIdentity, text: String, now: Instant) = ChatSpec(
        room = atak.callsign,
        roomId = atak.uid,
        senderUid = markerUid(contact),
        senderCallsign = displayName(contact),
        text = text,
        messageId = "note-${now.toEpochMilli()}",
        sentAt = now,
    )

    fun displayName(contact: Contact) = contact.name.ifEmpty { contact.publicKeyHex.take(8) }

    /** The channel a chat event from ATAK is for, going by the contact it was addressed to. */
    fun channelFor(room: String, roomId: String?): Channel? =
        rooms.values.firstOrNull { uidFor(it.index) == roomId } ?: rooms.entries.firstOrNull { callsign(it.value, it.key) == room }?.value

    private fun nameFor(channelIndex: Int): String? = rooms.entries.firstOrNull { it.value.index == channelIndex }?.key

    private fun uidFor(channelIndex: Int) = "MESHCORE-CH-$channelIndex"

    /**
     * How a channel is named in ATAK. Every channel starts with `#`, so ATAK's alphabetical
     * contact list keeps them together, ahead of the nodes: `#Public`, a hashtag channel as
     * MeshCore writes it, and a private channel with a lock after the `#`. The lock sorts after
     * letters, so the private channels follow the others within the group.
     */
    internal fun callsign(channel: Channel, name: String) = when (channel.kind) {
        Channel.Kind.HASHTAG -> if (name.startsWith("#")) name else "#$name"
        Channel.Kind.PRIVATE -> "#$LOCK $name"
        Channel.Kind.PUBLIC -> if (name.startsWith("#")) name else "#$name"
    }

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
                    callsign = callsign(channel, name),
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
            val companion = contact.type == Contact.TYPE_COMPANION
            if (!isShown(contact, nowSeconds)) {
                // Off the map, but in a conversation with the ATAK user: keep it reachable.
                if (companion && contact.publicKeyHex in talkedTo) {
                    list.add(
                        MarkerSpec(
                            uid = markerUid(contact),
                            cotType = "a-f-G-U-C",
                            callsign = displayName(contact),
                            latitude = 0.0,
                            longitude = 0.0,
                            team = companionTeam,
                            role = "Team Member",
                            remarks = "MeshCore companion with no position on the map, for direct messages",
                            endpoint = "*:-1:stcp",
                        ),
                    )
                }
                continue
            }
            list.add(
                MarkerSpec(
                    uid = markerUid(contact),
                    cotType = when (contact.type) {
                        Contact.TYPE_COMPANION -> "a-f-G-U-C"
                        Contact.TYPE_SENSOR -> "a-f-G-E-S"
                        else -> "a-f-G-I"
                    },
                    callsign = displayName(contact),
                    latitude = contact.latitude,
                    longitude = contact.longitude,
                    endpoint = if (companion) "*:-1:stcp" else null,
                    iconsetPath = when (contact.type) {
                        Contact.TYPE_REPEATER -> relayIconPath
                        Contact.TYPE_ROOM_SERVER -> roomIconPath
                        else -> null
                    },
                    team = if (companion) companionTeam else null,
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
        senderCallsign = rooms[name]?.let { callsign(it, name) } ?: name,
        text = text,
        messageId = id,
        sentAt = sentAt,
    )

    companion object {
        val ALL_TYPES: Set<Int> =
            setOf(Contact.TYPE_COMPANION, Contact.TYPE_REPEATER, Contact.TYPE_ROOM_SERVER, Contact.TYPE_SENSOR)

        /** The uid of the GeoOps icon set. */
        private const val GEOOPS_SET = "83198b4872a8c34eb9c549da8a4de5a28f07821185b39a2277948f66c24ac17a"

        /** A few GeoOps WildFire icons that suit radio sites, by the name the set gives each. */
        val GEOOPS_ICONS: Map<String, String> = listOf(
            "Repeater",
            "Repeater Mobile Relay",
            "Internet Access",
            "Telephone",
            "Lookout",
            "Incident Command Post",
            "Ranger Station",
        ).associateWith { "$GEOOPS_SET/WildFire/$it.png" }

        /** The team colours ATAK offers. */
        val TEAM_COLOURS = listOf(
            "White", "Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple",
            "Dark Blue", "Blue", "Cyan", "Teal", "Green", "Dark Green", "Brown",
        )
        const val DEFAULT_COMPANION_TEAM = "Cyan"
        private const val LOCK = "🔒"
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
