package org.coloradomesh.takbridge.protocol

/**
 * Reads the parts of a MeshCore telemetry block the bridge uses. MeshCore sends telemetry in
 * Cayenne LPP form: entries of a channel byte, a type byte and a value of a size fixed by the type,
 * big-endian. Channel 1 is the node itself. Sizes are those in MeshCore's `LPPDataHelpers.h`.
 *
 * Reading stops at a type whose size is not known, since nothing after it can be found; the node's
 * own voltage and position come first, so they are read before that can happen.
 */
internal object CayenneLpp {
    data class Reading(val position: Position?, val millivolts: Int?)

    private const val SELF_CHANNEL = 1
    private const val TYPE_VOLTAGE = 116
    private const val TYPE_GPS = 136

    private val SIZES: Map<Int, Int> = buildMap {
        for (type in listOf(0, 1, 102, 104, 120, 142)) put(type, 1)
        for (type in listOf(2, 3, 101, 103, 115, 116, 117, 121, 125, 128, 132)) put(type, 2)
        put(135, 3)
        for (type in listOf(100, 118, 130, 131, 133)) put(type, 4)
        put(113, 6)
        put(134, 6)
        put(TYPE_GPS, 9)
    }

    fun read(data: ByteArray): Reading {
        var position: Position? = null
        var millivolts: Int? = null
        var i = 0
        while (i + 2 <= data.size) {
            val channel = data[i].toInt() and 0xFF
            val type = data[i + 1].toInt() and 0xFF
            val size = SIZES[type] ?: break
            val start = i + 2
            if (start + size > data.size) break
            if (channel == SELF_CHANNEL) {
                when (type) {
                    TYPE_VOLTAGE -> if (millivolts == null) millivolts = unsigned(data, start, 2) * 10
                    TYPE_GPS -> if (position == null) position = gps(data, start)
                }
            }
            i = start + size
        }
        return Reading(position, millivolts)
    }

    /** Latitude and longitude in units of 0.0001°, altitude in centimetres, 3 signed bytes each. */
    private fun gps(data: ByteArray, start: Int): Position? {
        val latitude = signed24(data, start) / 10_000.0
        val longitude = signed24(data, start + 3) / 10_000.0
        val altitude = signed24(data, start + 6) / 100.0
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        // Exactly 0, 0 is how MeshCore marks "no position", as in adverts.
        if (latitude == 0.0 && longitude == 0.0) return null
        return Position(latitude, longitude, altitude)
    }

    private fun unsigned(data: ByteArray, start: Int, size: Int): Int =
        (0 until size).fold(0) { value, k -> (value shl 8) or (data[start + k].toInt() and 0xFF) }

    private fun signed24(data: ByteArray, start: Int): Int {
        val value = unsigned(data, start, 3)
        return if (value and 0x800000 != 0) value - 0x1000000 else value
    }
}
