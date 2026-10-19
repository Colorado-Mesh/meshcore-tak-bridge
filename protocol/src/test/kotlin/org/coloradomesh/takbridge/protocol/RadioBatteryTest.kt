package org.coloradomesh.takbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBatteryTest {
    @Test
    fun theCurvesPointsGiveRoundNumbers() {
        assertEquals(100, RadioBattery.percent(4_190))
        assertEquals(50, RadioBattery.percent(3_720))
        assertEquals(20, RadioBattery.percent(3_500))
        assertEquals(0, RadioBattery.percent(3_270))
    }

    @Test
    fun betweenPointsItInterpolatesAndClamps() {
        assertEquals(82, RadioBattery.percent(4_000))
        assertEquals(100, RadioBattery.percent(4_350))
        assertEquals(0, RadioBattery.percent(3_000))
    }

    @Test
    fun itNeverRisesAsTheVoltageFalls() {
        val readings = (3_000..4_300 step 5).map { RadioBattery.percent(it)!! }
        assertTrue(readings.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun zeroMeansTheRadioCannotMeasureIt() {
        assertNull(RadioBattery.percent(0))
        assertEquals("not measured by this radio", RadioBattery.describe(0))
        assertEquals("about 82% (4.00 V)", RadioBattery.describe(4_000))
    }

    @Test
    fun aFallingBatteryAlertsOnceAtEachLevel() {
        val watch = LowBatteryWatch()
        assertEquals(listOf(null, null, 20, null, null, 10, null), listOf(60, 21, 20, 19, 12, 10, 4).map { watch.reading(it) })
    }

    @Test
    fun aWobbleAroundALevelDoesNotAlertAgain() {
        val watch = LowBatteryWatch()
        assertEquals(listOf(20, null, null, null), listOf(19, 21, 19, 23).map { watch.reading(it) })
        assertEquals(listOf(null, 20), listOf(25, 20).map { watch.reading(it) })
    }

    @Test
    fun aFirstReadingBelowBothLevelsAlertsOnceForTheLower() {
        val watch = LowBatteryWatch()
        assertEquals(listOf(10, null), listOf(8, 15).map { watch.reading(it) })
        watch.reset()
        assertEquals(20, watch.reading(15))
    }
}
