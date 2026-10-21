package org.coloradomesh.takbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Examples from the MECP project's README, read the way its reference decoder reads them. */
class MecpTest {
    private fun describe(text: String) = Mecp.describe(Mecp.parse(text)!!)

    @Test
    fun aMaydayWithPeopleAndAPosition() {
        val m = Mecp.parse("MECP/0/M01 M07 2pax P05 48.6520,20.1305")!!
        assertEquals(0, m.severity)
        assertEquals(listOf("M01", "M07"), m.codes)
        assertEquals(listOf("P05"), m.laterCodes)
        assertEquals(2, m.people)
        assertEquals(48.652, m.latitude!!, 1e-9)
        assertEquals(20.1305, m.longitude!!, 1e-9)
        assertFalse(m.isDrill)
        assertEquals(
            "🆘 MAYDAY: Injury · Fracture / immobile · At GPS coordinates · 2 people · 48.6520, 20.1305",
            describe(m.raw),
        )
    }

    @Test
    fun aSafetyWarningWithAPlaceName() {
        assertEquals("⚠️ SAFETY: Flooding · Road blocked · Evacuating toward · “Poprad”", describe("MECP/2/T04 T01 P02 Poprad"))
    }

    @Test
    fun aSocialMessage() {
        assertEquals("ℹ️ ROUTINE: Beer / drinks · Running late · “30min”", describe("MECP/3/L01 L06 30min"))
    }

    @Test
    fun aDrillSaysSoFirst() {
        val m = Mecp.parse("MECP/0/D01 M01 M07 2pax P05 48.65,20.13")!!
        assertTrue(m.isDrill)
        assertTrue(Mecp.describe(m).startsWith("DRILL 🆘 MAYDAY: This is a drill · Injury"))
        assertFalse(Mecp.parse("MECP/0/D03")!!.isDrill)
    }

    @Test
    fun countsAndEtasFillTheirPlaceholders() {
        assertEquals("⚠️ SAFETY: Have medical supplies · Have shelter / space for 5 people · At GPS coordinates · 48.6500, 20.1300",
            describe("MECP/2/H03 H07 5pax P05 48.65,20.13"))
        assertEquals("ℹ️ ROUTINE: Help coming · ETA 15 min", describe("MECP/3/R02 R03 15"))
    }

    @Test
    fun aBeaconWithTimeCallsignAndReference() {
        assertEquals(
            "🆘 MAYDAY: Automated distress beacon active — sender may be unresponsive · Injury · At GPS coordinates · " +
                "48.6500, 20.1300 · 14:30 UTC · incident #A1 · callsign W0RMT",
            describe("MECP/0/B01 M01 P05 48.65,20.13 @1430 #A1 ~W0RMT @en"),
        )
    }

    @Test
    fun unknownCodesAreNamedNotDropped() {
        assertEquals("🚨 URGENT: unknown code Q99 · Injury", describe("MECP/1/Q99 M01"))
    }

    @Test
    fun aSeverityAloneIsEnough() {
        assertEquals("⚠️ SAFETY", describe("MECP/2/"))
        assertEquals("⚠️ SAFETY", describe("MECP/2"))
    }

    @Test
    fun notMecpOrABadSeverityIsLeftAlone() {
        assertNull(Mecp.parse("mecp/0/M01"))
        assertNull(Mecp.parse("MECP/7/M01"))
        assertNull(Mecp.parse("MECP/"))
        assertNull(Mecp.parse("hello MECP/0/M01"))
    }

    @Test
    fun badDetailsAreNotTakenAsPositionsOrTimes() {
        val m = Mecp.parse("MECP/1/P05 95.0,20.0 @2599")!!
        assertNull(m.latitude)
        assertNull(m.timeUtc)
    }

    @Test
    fun foundInAChannelMessageWithItsSender() {
        val found = Mecp.find("Silver Eagle: MECP/0/M06 P05 39.7392,-104.9903")!!
        assertEquals("Silver Eagle", found.sender)
        assertEquals(-104.9903, found.message.longitude!!, 1e-9)
        assertNull(Mecp.find("Silver Eagle: hello MECP/0/M06"))
        assertNull(Mecp.find("Silver Eagle: good morning"))
    }

    @Test
    fun foundInADirectMessage() {
        val found = Mecp.find("MECP/3/L15")!!
        assertNull(found.sender)
        assertEquals("ℹ️ ROUTINE: Good morning / check-in", Mecp.describe(found.message))
    }

    @Test
    fun theTableHasEveryCode() {
        assertEquals(108, MecpCodes.CODES.size)
        assertEquals(12, MecpCodes.CATEGORIES.size)
        assertTrue(MecpCodes.CODES.keys.all { it[0] in MecpCodes.CATEGORIES })
    }
}
