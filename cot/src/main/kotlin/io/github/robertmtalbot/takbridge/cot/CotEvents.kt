package io.github.robertmtalbot.takbridge.cot

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A map marker for one mesh node. */
data class MarkerSpec(
    /** Stable identifier; ATAK keys the marker on it. */
    val uid: String,
    /** MIL-STD-2525 CoT type atom, for example `a-f-G-U-C`. */
    val cotType: String,
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    /** Height above the ellipsoid in metres, when known. */
    val altitudeMeters: Double? = null,
    /** ATAK team colour name, for example `Cyan`. */
    val team: String? = null,
    /** ATAK team role, for example `Team Member`. */
    val role: String? = null,
    /** Icon from an ATAK icon set, as `<set uid>/<group>/<file>`. */
    val iconsetPath: String? = null,
    val remarks: String? = null,
    /**
     * Where ATAK should send messages for this contact. `*:-1:stcp` means "through the server
     * connection"; without an endpoint ATAK lists the contact but cannot message it.
     */
    val endpoint: String? = null,
    /** How long ATAK keeps the marker without a refresh. */
    val staleAfter: Duration = CotEvents.MARKER_STALE,
)

/** One chat message in a GeoChat room. */
data class ChatSpec(
    val room: String,
    /** Identifier of the sender; ATAK groups a sender's messages by it. */
    val senderUid: String,
    val senderCallsign: String,
    val text: String,
    /** Unique per message; ATAK drops a repeated id as a duplicate. */
    val messageId: String,
    /** When the message was sent. Never reported later than the event time. */
    val sentAt: Instant,
    /**
     * The conversation id when it differs from the room name. A direct message carries the
     * recipient's callsign as [room] and the recipient's uid here; a group uses the name for both.
     */
    val roomId: String? = null,
)

/**
 * Builds Cursor on Target events as ATAK expects them on a streaming connection.
 *
 * Every event is a bare `<event>` element with no XML declaration. A TAK stream is a run of
 * `<event>` elements, and ATAK drops every event after the first when declarations are present.
 */
object CotEvents {
    /** ATAK hides a marker this long after it was sent unless a newer one arrives. */
    val MARKER_STALE: Duration = Duration.ofMinutes(10)
    /** Chat stays in ATAK's history; stale only bounds how long servers relay it. */
    private val CHAT_STALE: Duration = Duration.ofHours(24)
    private val PONG_STALE: Duration = Duration.ofSeconds(20)

    /** Event type a client sends as a keepalive when its connection is quiet. */
    const val PING_TYPE = "t-x-c-t"
    const val PONG_TYPE = "t-x-c-t-r"
    const val GEOCHAT_TYPE = "b-t-f"
    private const val DELETE_TYPE = "t-x-d-d"

    /** The value CoT uses for an unknown height or error. */
    private const val UNKNOWN = "9999999"

    private fun time(instant: Instant): String = instant.truncatedTo(ChronoUnit.MILLIS).toString()

    private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    /** Reply to a client ping. The uid is the one TAK Server answers with. */
    fun pong(now: Instant): String {
        val t = time(now)
        return "<event version=\"2.0\" uid=\"takPong\" type=\"$PONG_TYPE\"" +
            " time=\"$t\" start=\"$t\" stale=\"${time(now.plus(PONG_STALE))}\" how=\"h-g-i-g-o\">" +
            "<point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"$UNKNOWN\" le=\"$UNKNOWN\"/>" +
            "</event>"
    }

    fun marker(spec: MarkerSpec, now: Instant): String {
        require(spec.latitude in -90.0..90.0 && spec.longitude in -180.0..180.0) {
            "marker position out of range"
        }
        val t = time(now)
        val hae = spec.altitudeMeters?.let { String.format(Locale.ROOT, "%.1f", it) } ?: UNKNOWN
        val detail = StringBuilder()
        detail.append("<contact callsign=\"").append(escapeXml(spec.callsign)).append('"')
        spec.endpoint?.let { detail.append(" endpoint=\"").append(escapeXml(it)).append('"') }
        detail.append("/>")
        if (spec.team != null || spec.role != null) {
            detail.append("<__group")
            spec.team?.let { detail.append(" name=\"").append(escapeXml(it)).append('"') }
            spec.role?.let { detail.append(" role=\"").append(escapeXml(it)).append('"') }
            detail.append("/>")
        }
        spec.iconsetPath?.let {
            detail.append("<usericon iconsetpath=\"").append(escapeXml(it)).append("\"/>")
        }
        spec.remarks?.let { detail.append("<remarks>").append(escapeXml(it)).append("</remarks>") }
        return "<event version=\"2.0\" uid=\"${escapeXml(spec.uid)}\" type=\"${escapeXml(spec.cotType)}\"" +
            " time=\"$t\" start=\"$t\" stale=\"${time(now.plus(spec.staleAfter))}\" how=\"m-g\">" +
            "<point lat=\"${coordinate(spec.latitude)}\" lon=\"${coordinate(spec.longitude)}\"" +
            " hae=\"$hae\" ce=\"$UNKNOWN\" le=\"$UNKNOWN\"/>" +
            "<detail>$detail</detail>" +
            "</event>"
    }

    /** A GeoChat message addressed to a room. */
    fun geoChat(spec: ChatSpec, now: Instant): String {
        val t = time(now)
        val sent = time(if (spec.sentAt.isAfter(now)) now else spec.sentAt)
        val room = escapeXml(spec.room)
        val roomId = escapeXml(spec.roomId ?: spec.room)
        val sender = escapeXml(spec.senderUid)
        val uid = escapeXml("GeoChat.${spec.senderUid}.${spec.roomId ?: spec.room}.${spec.messageId}")
        return "<event version=\"2.0\" uid=\"$uid\" type=\"$GEOCHAT_TYPE\"" +
            " time=\"$t\" start=\"$t\" stale=\"${time(now.plus(CHAT_STALE))}\" how=\"h-g-i-g-o\">" +
            "<point lat=\"0\" lon=\"0\" hae=\"$UNKNOWN\" ce=\"$UNKNOWN\" le=\"$UNKNOWN\"/>" +
            "<detail>" +
            "<__chat parent=\"RootContactGroup\" groupOwner=\"false\" chatroom=\"$room\" id=\"$roomId\"" +
            " senderCallsign=\"${escapeXml(spec.senderCallsign)}\">" +
            "<chatgrp uid0=\"$sender\" uid1=\"$roomId\" id=\"$roomId\"/>" +
            "</__chat>" +
            "<link uid=\"$sender\" type=\"a-f-G-U-C\" relation=\"p-p\"/>" +
            "<remarks source=\"BAO.F.ATAK.$sender\" to=\"$roomId\" time=\"$sent\">" +
            escapeXml(spec.text) +
            "</remarks>" +
            "</detail>" +
            "</event>"
    }

    /** Tells ATAK to remove the marker with [targetUid] now, without waiting for it to go stale. */
    fun delete(targetUid: String, now: Instant): String {
        val t = time(now)
        val target = escapeXml(targetUid)
        return "<event version=\"2.0\" uid=\"$target-delete-${now.toEpochMilli()}\" type=\"$DELETE_TYPE\"" +
            " time=\"$t\" start=\"$t\" stale=\"${time(now.plus(MARKER_STALE))}\" how=\"h-g-i-g-o\">" +
            "<point lat=\"0\" lon=\"0\" hae=\"$UNKNOWN\" ce=\"$UNKNOWN\" le=\"$UNKNOWN\"/>" +
            "<detail><link uid=\"$target\" relation=\"none\" type=\"none\"/><__forcedelete/></detail>" +
            "</event>"
    }
}
