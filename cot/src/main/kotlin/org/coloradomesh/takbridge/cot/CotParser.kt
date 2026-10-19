package org.coloradomesh.takbridge.cot

/** A GeoChat message a TAK client sent. */
data class InboundChat(
    val room: String,
    /** The uid the event claims as its sender; the caller decides whether to trust it. */
    val senderUid: String,
    /** Empty when the event carried none. */
    val senderCallsign: String,
    val text: String,
    /** Message id from the event uid, when it has a usable one. */
    val messageId: String?,
    /** The conversation id: the recipient's uid for a direct message, else usually the room name. */
    val roomId: String? = null,
)

/** The TAK client's own position report, which names the device and its user. */
data class ClientIdentity(val uid: String, val callsign: String)

/**
 * Reads the few things the bridge needs out of CoT events sent by ATAK.
 *
 * This is deliberately not a general XML parser: it pulls named attributes out of named opening
 * tags, bounds every field, and returns null for anything it does not recognise.
 */
object CotParser {
    const val MAX_ROOM_LENGTH = 64
    const val MAX_CALLSIGN_LENGTH = 64
    const val MAX_TEXT_LENGTH = 1024
    private const val MAX_UID_LENGTH = 128

    private val UID_PATTERN = Regex("^[A-Za-z0-9_.:@-]{1,128}$")
    private val MESSAGE_ID_PATTERN = Regex("^[A-Za-z0-9-]{1,64}$")
    private val CONTROL_CHARS = Regex("\\p{Cc}+")

    private fun openTag(xml: String, name: String): String? =
        Regex("<" + Regex.escape(name) + "(?=[\\s/>])[^>]*>").find(xml)?.value

    private fun attr(tag: String?, name: String): String? {
        if (tag == null) return null
        val match = Regex("\\s" + Regex.escape(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").find(tag)
            ?: return null
        val raw = match.groups[1]?.value ?: match.groups[2]?.value ?: ""
        return unescapeXml(raw)
    }

    /** The event's `type` attribute, or null when the text is not an event. */
    fun eventType(xml: String): String? = attr(openTag(xml, "event"), "type")

    /** True for a client keepalive ping, which the server answers with a pong. */
    fun isPing(xml: String): Boolean = eventType(xml) == CotEvents.PING_TYPE

    /** The event's `uid` attribute, or null when the text is not an event. */
    fun eventUid(xml: String): String? = attr(openTag(xml, "event"), "uid")

    /**
     * Read who the client is from its own position report: an atom event that carries the
     * `takv` version element only a TAK client adds about itself.
     */
    fun parseClientIdentity(xml: String): ClientIdentity? {
        val eventTag = openTag(xml, "event")
        if (attr(eventTag, "type")?.startsWith("a-") != true) return null
        if (openTag(xml, "takv") == null) return null
        val uid = attr(eventTag, "uid")?.takeIf { UID_PATTERN.matches(it) } ?: return null
        val callsign = label(attr(openTag(xml, "contact"), "callsign"), MAX_CALLSIGN_LENGTH) ?: return null
        return ClientIdentity(uid, callsign)
    }

    /** Single line, trimmed and cut to [max]; null when nothing is left. */
    private fun label(text: String?, max: Int): String? {
        val oneLine = text?.replace(CONTROL_CHARS, " ")?.trim()
        if (oneLine.isNullOrEmpty()) return null
        return if (oneLine.length > max) oneLine.substring(0, max) else oneLine
    }

    private fun remarksText(xml: String): String? {
        val open = Regex("<remarks(?=[\\s>])[^>]*>").find(xml) ?: return null
        if (open.value.endsWith("/>")) return null
        val bodyStart = open.range.last + 1
        val end = xml.indexOf("</remarks>", bodyStart)
        return if (end < 0) null else unescapeXml(xml.substring(bodyStart, end))
    }

    /**
     * Parse a GeoChat (`b-t-f`) event into room, sender and text.
     *
     * ATAK's event uids are `GeoChat.<sender uid>.<room>.<message id>`, and the sender is read
     * only from there. The message id is read from `__chat`'s `messageId`, as ATAK does, or else
     * from the end of the uid. Delivery (`b-t-f-d`) and read (`b-t-f-r`) receipts are not messages.
     * Returns null for anything else and for a message without a room, sender uid or text.
     */
    fun parseGeoChat(xml: String): InboundChat? {
        val eventTag = openTag(xml, "event")
        if (attr(eventTag, "type") != CotEvents.GEOCHAT_TYPE) return null
        val chatTag = openTag(xml, "__chat")
        val room = label(attr(chatTag, "chatroom"), MAX_ROOM_LENGTH) ?: return null
        val text = label(remarksText(xml), MAX_TEXT_LENGTH) ?: return null

        val parts = (attr(eventTag, "uid") ?: "").split('.')
        if (parts.size < 4 || parts[0] != "GeoChat") return null
        val senderUid = parts[1]
        if (!UID_PATTERN.matches(senderUid)) return null
        // ATAK writes the id on `__chat` and at the end of the uid; it reads the attribute first.
        val messageId = listOfNotNull(attr(chatTag, "messageId"), parts.last())
            .firstOrNull { MESSAGE_ID_PATTERN.matches(it) }

        return InboundChat(
            room = room,
            senderUid = senderUid,
            senderCallsign = label(attr(chatTag, "senderCallsign"), MAX_CALLSIGN_LENGTH) ?: "",
            text = text,
            messageId = messageId,
            roomId = label(attr(chatTag, "id"), MAX_UID_LENGTH),
        )
    }
}
