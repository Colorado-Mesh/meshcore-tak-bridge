package org.coloradomesh.takbridge.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CotEventsTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")

    private fun marker(
        uid: String = "MC-1a2b3c4d",
        callsign: String = "Ridge",
        team: String? = "Cyan",
        role: String? = "Team Member",
        iconsetPath: String? = null,
        remarks: String? = null,
        altitudeMeters: Double? = null,
    ) = MarkerSpec(
        uid = uid,
        cotType = "a-f-G-U-C",
        callsign = callsign,
        latitude = 39.7392,
        longitude = -104.9903,
        altitudeMeters = altitudeMeters,
        team = team,
        role = role,
        iconsetPath = iconsetPath,
        remarks = remarks,
    )

    @Test
    fun everyEventIsBareWithNoXmlDeclaration() {
        val chat = ChatSpec("Mesh Ops", "MESHCHAT-1", "Bob", "hi", "m1", now)
        val events = listOf(
            CotEvents.pong(now),
            CotEvents.marker(marker(), now),
            CotEvents.geoChat(chat, now),
            CotEvents.delete("MC-6", now),
        )
        for (event in events) {
            assertTrue(event, event.startsWith("<event "))
            assertTrue(event, event.endsWith("</event>"))
            assertFalse(event, event.contains("<?xml"))
            assertFalse(event, event.contains('\n'))
        }
    }

    @Test
    fun pongMatchesWhatTakServerSends() {
        val pong = CotEvents.pong(now)
        assertTrue(pong.contains("uid=\"takPong\""))
        assertTrue(pong.contains("type=\"t-x-c-t-r\""))
        assertTrue(pong.contains("time=\"2026-10-11T12:00:00Z\""))
        assertTrue(pong.contains("stale=\"2026-10-11T12:00:20Z\""))
    }

    @Test
    fun markerCarriesPositionTeamAndTenMinuteStale() {
        val cot = CotEvents.marker(marker(), now)
        assertTrue(cot.contains("uid=\"MC-1a2b3c4d\" type=\"a-f-G-U-C\""))
        assertTrue(cot.contains("stale=\"2026-10-11T12:10:00Z\""))
        assertTrue(cot.contains("<point lat=\"39.739200\" lon=\"-104.990300\" hae=\"9999999\""))
        assertTrue(cot.contains("<contact callsign=\"Ridge\"/>"))
        assertTrue(cot.contains("<__group name=\"Cyan\" role=\"Team Member\"/>"))
        assertFalse(cot.contains("<usericon"))
        assertFalse(cot.contains("<remarks"))
    }

    @Test
    fun markerOmitsTheGroupWhenNoTeamOrRoleIsSet() {
        val cot = CotEvents.marker(marker(team = null, role = null), now)
        assertFalse(cot.contains("<__group"))
        assertTrue(CotEvents.marker(marker(team = null), now).contains("<__group role=\"Team Member\"/>"))
    }

    @Test
    fun markerWritesAltitudeIconAndRemarks() {
        val cot = CotEvents.marker(
            marker(iconsetPath = "set-uid/WildFire/Repeater.png", remarks = "2 hops", altitudeMeters = 1609.34),
            now,
        )
        assertTrue(cot.contains("hae=\"1609.3\""))
        assertTrue(cot.contains("<usericon iconsetpath=\"set-uid/WildFire/Repeater.png\"/>"))
        assertTrue(cot.contains("<remarks>2 hops</remarks>"))
    }

    @Test
    fun markerEscapesEveryTextField() {
        val cot = CotEvents.marker(
            marker(uid = "a\"b", callsign = "<Ridge & Co>", iconsetPath = "u/A&B/x.png", remarks = "a < b"),
            now,
        )
        assertTrue(cot.contains("uid=\"a&quot;b\""))
        assertTrue(cot.contains("callsign=\"&lt;Ridge &amp; Co&gt;\""))
        assertTrue(cot.contains("iconsetpath=\"u/A&amp;B/x.png\""))
        assertTrue(cot.contains("<remarks>a &lt; b</remarks>"))
    }

    @Test
    fun markerRejectsAPositionOffTheGlobe() {
        val bad = marker().copy(latitude = 91.0)
        assertThrows(IllegalArgumentException::class.java) { CotEvents.marker(bad, now) }
    }

    @Test
    fun geoChatAddressesTheRoomAndNamesTheSender() {
        val spec = ChatSpec("Mesh Ops", "MESHCHAT-0a1b2c3d", "Bob", "on scene", "msg-1", now.minusSeconds(5))
        val cot = CotEvents.geoChat(spec, now)
        assertTrue(cot.contains("uid=\"GeoChat.MESHCHAT-0a1b2c3d.Mesh Ops.msg-1\" type=\"b-t-f\""))
        assertTrue(cot.contains("chatroom=\"Mesh Ops\" id=\"Mesh Ops\" senderCallsign=\"Bob\""))
        assertTrue(cot.contains("<chatgrp uid0=\"MESHCHAT-0a1b2c3d\" uid1=\"Mesh Ops\" id=\"Mesh Ops\"/>"))
        assertTrue(cot.contains("to=\"Mesh Ops\" time=\"2026-10-11T11:59:55Z\">on scene</remarks>"))
    }

    @Test
    fun geoChatEscapesTextAndNeverReportsASendTimeInTheFuture() {
        val spec = ChatSpec("R&D", "S-1", "A\"B", "<b>hi</b>", "m1", now.plusSeconds(600))
        val cot = CotEvents.geoChat(spec, now)
        assertTrue(cot.contains("chatroom=\"R&amp;D\""))
        assertTrue(cot.contains("senderCallsign=\"A&quot;B\""))
        assertTrue(cot.contains("&lt;b&gt;hi&lt;/b&gt;</remarks>"))
        assertFalse(cot.contains("<b>"))
        assertTrue(cot.contains("time=\"2026-10-11T12:00:00Z\">"))
    }

    @Test
    fun deleteTargetsTheMarkerUid() {
        val cot = CotEvents.delete("MC-6", now)
        assertTrue(cot.contains("type=\"t-x-d-d\""))
        assertTrue(cot.contains("<link uid=\"MC-6\" relation=\"none\" type=\"none\"/><__forcedelete/>"))
        assertFalse(cot.contains("uid=\"MC-6\" type="))
    }

    @Test
    fun xmlEscapeRoundTrips() {
        val text = "a < b & \"c\" > 'd'"
        assertEquals(text, unescapeXml(escapeXml(text)))
        assertEquals("A☃", unescapeXml("&#65;&#x2603;"))
        assertEquals("x&nbsp;y", unescapeXml("x&nbsp;y"))
    }
}
