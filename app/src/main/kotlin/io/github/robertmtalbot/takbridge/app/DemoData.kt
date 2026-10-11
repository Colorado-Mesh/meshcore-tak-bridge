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
    private const val BRIDGE_UID = "TAKBRIDGE-DEMO"
    private const val BRIDGE_CALLSIGN = "Bridge"

    val markers: List<MarkerSpec> = listOf(
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

    fun message(text: String, now: Instant): ChatSpec = ChatSpec(
        room = ROOM,
        senderUid = BRIDGE_UID,
        senderCallsign = BRIDGE_CALLSIGN,
        text = text,
        messageId = UUID.randomUUID().toString(),
        sentAt = now,
    )
}
