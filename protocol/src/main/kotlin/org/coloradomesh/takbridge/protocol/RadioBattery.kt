package org.coloradomesh.takbridge.protocol

import java.util.Locale

/**
 * What a battery voltage from the radio means. MeshCore reports only the voltage, so the
 * percentage is an estimate from the discharge curve of a typical single lithium cell, which most
 * companion radios use. It reads high while the radio is charging and a little low while it
 * transmits, so it is shown as "about".
 */
object RadioBattery {
    /** Millivolts at 100%, 90%, … 0%, for a typical lithium-ion cell at rest. */
    private val CURVE = intArrayOf(4190, 4050, 3990, 3890, 3800, 3720, 3630, 3570, 3500, 3420, 3270)

    /** Estimated charge from 0 to 100, or null when the radio cannot measure its battery (it reports 0). */
    fun percent(millivolts: Int): Int? {
        if (millivolts <= 0) return null
        if (millivolts >= CURVE.first()) return 100
        if (millivolts <= CURVE.last()) return 0
        val step = 100 / (CURVE.size - 1)
        for (i in 1 until CURVE.size) {
            val low = CURVE[i]
            if (millivolts >= low) {
                val high = CURVE[i - 1]
                val top = 100 - (i - 1) * step
                return top - Math.round((high - millivolts) * step.toDouble() / (high - low)).toInt()
            }
        }
        return 0
    }

    /** For people: "about 72% (3.92 V)", or "not measured by this radio". */
    fun describe(millivolts: Int): String {
        val percent = percent(millivolts) ?: return "not measured by this radio"
        return "about $percent% (${String.format(Locale.ROOT, "%.2f", millivolts / 1000.0)} V)"
    }
}

/**
 * Decides when a falling battery deserves an alert: once as it drops to each of [thresholds]
 * percent. A level is warned about again only after the battery has climbed [rearmMargin] points
 * above it, so a reading that wobbles around a threshold does not alert over and over. Not
 * thread-safe.
 */
class LowBatteryWatch(
    private val thresholds: List<Int> = listOf(20, 10),
    private val rearmMargin: Int = 5,
) {
    private val warned = HashSet<Int>()

    /** The threshold just reached, the lowest if several were, or null when no alert is due. */
    fun reading(percent: Int): Int? {
        warned.removeAll { percent >= it + rearmMargin }
        val reached = thresholds.filter { percent <= it && it !in warned }
        if (reached.isEmpty()) return null
        warned.addAll(reached)
        return reached.min()
    }

    /** Forget earlier alerts, for example when a different radio is chosen. */
    fun reset() = warned.clear()
}
