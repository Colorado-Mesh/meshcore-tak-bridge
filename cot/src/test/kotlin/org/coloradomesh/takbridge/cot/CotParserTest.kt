package org.coloradomesh.takbridge.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CotParserTest {
    /** A GeoChat event as ATAK sends it; each part can be overridden. */
    private fun geochat(
        uid: String = "GeoChat.ANDROID-1.Mesh Ops.6f9d2c1e-0a4b-4c58-9e11-2b7a5f0c9d3e",
        type: String = "b-t-f",
        chat: String = "chatroom=\"Mesh Ops\" id=\"Mesh Ops\" senderCallsign=\"VIPER\"",
        remarks: String = "on scene",
    ) = "<event version=\"2.0\" uid=\"$uid\" type=\"$type\" time=\"2026-10-11T12:00:00Z\" " +
        "start=\"2026-10-11T12:00:00Z\" stale=\"2026-10-12T12:00:00Z\" how=\"h-g-i-g-o\">" +
        "<point lat=\"39.7\" lon=\"-105\" hae=\"9999999\" ce=\"9999999\" le=\"9999999\"/><detail>" +
        "<__chat parent=\"RootContactGroup\" groupOwner=\"false\" $chat>" +
        "<chatgrp uid0=\"ANDROID-1\" uid1=\"Mesh Ops\" id=\"Mesh Ops\"/></__chat>" +
        "<link uid=\"SOMEONE-ELSE\" type=\"a-f-G-U-C\" relation=\"p-p\"/>" +
        "<remarks source=\"BAO.F.ATAK.ANDROID-1\" to=\"Mesh Ops\" time=\"2026-10-11T12:00:00Z\">" +
        "$remarks</remarks></detail></event>"

    private val ping = "<event version=\"2.0\" uid=\"ANDROID-1-ping\" type=\"t-x-c-t\" " +
        "time=\"2026-10-11T12:00:00Z\" start=\"2026-10-11T12:00:00Z\" stale=\"2026-10-11T12:00:10Z\" " +
        "how=\"m-g\"><point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"9999999\" le=\"9999999\"/></event>"

    @Test
    fun recognisesAPingAndNothingElse() {
        assertTrue(CotParser.isPing(ping))
        assertFalse(CotParser.isPing(ping.replace("t-x-c-t", "t-x-c-t-r")))
        assertFalse(CotParser.isPing(geochat()))
        assertFalse(CotParser.isPing("not xml"))
        assertNull(CotParser.eventType("not xml"))
        assertEquals("b-t-f", CotParser.eventType(geochat()))
    }

    @Test
    fun readsRoomSenderTextAndMessageId() {
        assertEquals(
            InboundChat(
                room = "Mesh Ops",
                senderUid = "ANDROID-1",
                senderCallsign = "VIPER",
                text = "on scene",
                messageId = "6f9d2c1e-0a4b-4c58-9e11-2b7a5f0c9d3e",
                roomId = "Mesh Ops",
            ),
            CotParser.parseGeoChat(geochat()),
        )
    }

    @Test
    fun takesTheSenderOnlyFromTheEventUid() {
        assertEquals("ANDROID-1", CotParser.parseGeoChat(geochat())?.senderUid)
        assertNull(CotParser.parseGeoChat(geochat(uid = "custom-uid-1")))
    }

    @Test
    fun unescapesTextAndCollapsesLineBreaks() {
        val chat = CotParser.parseGeoChat(geochat(remarks = "a &amp; b\nline two"))
        assertEquals("a & b line two", chat?.text)
    }

    @Test
    fun cutsOverlongFields() {
        val chat = CotParser.parseGeoChat(geochat(remarks = "x".repeat(5000)))
        assertEquals(CotParser.MAX_TEXT_LENGTH, chat?.text?.length)
    }

    @Test
    fun leavesTheCallsignEmptyAndTheMessageIdNullWhenUnusable() {
        val noCallsign = CotParser.parseGeoChat(geochat(chat = "chatroom=\"Mesh Ops\" id=\"Mesh Ops\""))
        assertEquals("", noCallsign?.senderCallsign)
        val badId = CotParser.parseGeoChat(geochat(uid = "GeoChat.ANDROID-1.Mesh Ops.bad id!"))
        assertEquals("ANDROID-1", badId?.senderUid)
        assertNull(badId?.messageId)
    }

    @Test
    fun acceptsSingleQuotedAttributes() {
        val chat = CotParser.parseGeoChat(geochat(chat = "chatroom='Mesh Ops' senderCallsign='VIPER'"))
        assertEquals("Mesh Ops", chat?.room)
        assertEquals("VIPER", chat?.senderCallsign)
    }

    @Test
    fun ignoresEverythingThatIsNotAUsableMessage() {
        val rejected = mapOf(
            "a delivery receipt" to geochat(type = "b-t-f-d"),
            "a read receipt" to geochat(type = "b-t-f-r"),
            "a ping" to ping,
            "a message without a room" to geochat(chat = "id=\"x\" senderCallsign=\"VIPER\""),
            "a message without text" to geochat(remarks = "   "),
            "a sender uid with markup" to geochat(uid = "GeoChat.a&quot;b.Mesh Ops.m1"),
            "an event uid that names no sender" to geochat(uid = "x"),
            "text that is not xml" to "hello",
        )
        for ((label, xml) in rejected) assertNull(label, CotParser.parseGeoChat(xml))
    }

    @Test
    fun readsBackWhatTheBuilderWrites() {
        val now = Instant.parse("2026-10-11T12:00:00Z")
        val spec = ChatSpec("R&D \"lab\"", "ANDROID-9", "A<B", "5 > 3 & 2 < 4", "m-77", now)
        val parsed = CotParser.parseGeoChat(CotEvents.geoChat(spec, now))
        assertEquals(InboundChat("R&D \"lab\"", "ANDROID-9", "A<B", "5 > 3 & 2 < 4", "m-77", "R&D \"lab\""), parsed)
    }

    @Test
    fun readsTheClientIdentityFromItsOwnPositionReport() {
        val xml = "<event version=\"2.0\" uid=\"ANDROID-1a2b\" type=\"a-f-G-U-C\" how=\"m-g\">" +
            "<point lat=\"1\" lon=\"2\" hae=\"3\" ce=\"4\" le=\"5\"/><detail>" +
            "<takv os=\"34\" version=\"5.4\" device=\"PIXEL\" platform=\"ATAK-CIV\"/>" +
            "<contact endpoint=\"*:-1:stcp\" callsign=\"BUD\"/></detail></event>"
        assertEquals(ClientIdentity("ANDROID-1a2b", "BUD"), CotParser.parseClientIdentity(xml))
    }

    @Test
    fun doesNotTakeAnotherMarkerForTheClient() {
        val xml = "<event version=\"2.0\" uid=\"X\" type=\"a-f-G-U-C\"><detail>" +
            "<contact callsign=\"Other\"/></detail></event>"
        assertEquals(null, CotParser.parseClientIdentity(xml))
    }

    @Test
    fun aDirectMessageRoundTripsWithItsConversationId() {
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val xml = CotEvents.geoChat(ChatSpec("BUD", "SENDER-1", "Bridge", "hi", "m1", now, roomId = "ANDROID-1a2b"), now)
        val chat = CotParser.parseGeoChat(xml)!!
        assertEquals("BUD", chat.room)
        assertEquals("ANDROID-1a2b", chat.roomId)
        assertEquals("SENDER-1", chat.senderUid)
    }
}
