package io.github.robertmtalbot.takbridge.app

import io.github.robertmtalbot.takbridge.cot.ChatSpec
import io.github.robertmtalbot.takbridge.cot.MarkerSpec
import java.time.Instant
import java.util.UUID

/**
 * Fixed stand-ins for a mesh, used until the radio link exists. They let the ATAK side of the
 * bridge be proven on its own: three markers in central Denver, one per node kind, and one chat
 * room that answers back.
 */
object DemoData {
    const val ROOM = "Bridge Test"
    const val ALL_CHAT = "All Chat Rooms"
    const val BRIDGE_UID = "TAKBRIDGE-DEMO"
    private const val BRIDGE_CALLSIGN = "Bridge"

    val markers: List<MarkerSpec> = listOf(
        // The chat sender as a contact ATAK can message back through this connection.
        MarkerSpec(
            uid = BRIDGE_UID,
            cotType = "a-f-G-U-C",
            callsign = BRIDGE_CALLSIGN,
            latitude = 39.7450,
            longitude = -104.9800,
            team = "Cyan",
            role = "Team Member",
            remarks = "MeshCore TAK Bridge demo contact",
            endpoint = "*:-1:stcp",
        ),
        MarkerSpec(
            uid = "TAKBRIDGE-DEMO-COMPANION",
            cotType = "a-f-G-U-C",
            callsign = "Demo Companion",
            latitude = 39.7392,
            longitude = -104.9903,
            team = "Cyan",
            role = "Team Member",
            remarks = "MeshCore TAK Bridge demo marker",
        ),
        MarkerSpec(
            uid = "TAKBRIDGE-DEMO-REPEATER",
            cotType = "a-f-G-I",
            callsign = "Demo Repeater",
            latitude = 39.7500,
            longitude = -105.0000,
            remarks = "MeshCore TAK Bridge demo marker",
        ),
        MarkerSpec(
            uid = "TAKBRIDGE-DEMO-SENSOR",
            cotType = "a-f-G-E-S",
            callsign = "Demo Sensor",
            latitude = 39.7300,
            longitude = -104.9700,
            remarks = "MeshCore TAK Bridge demo marker",
        ),
    )

    /** A message in [room]; pass [roomId] as the recipient's uid to make it a direct message. */
    fun message(text: String, now: Instant, room: String = ROOM, roomId: String? = null): ChatSpec = ChatSpec(
        roomId = roomId,
        room = room,
        senderUid = BRIDGE_UID,
        senderCallsign = BRIDGE_CALLSIGN,
        text = text,
        messageId = UUID.randomUUID().toString(),
        sentAt = now,
    )
}
