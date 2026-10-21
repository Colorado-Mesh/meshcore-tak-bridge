package org.coloradomesh.takbridge.protocol

import java.util.Locale

/**
 * MECP, the Mesh Emergency Communication Protocol (mecp.radio): emergency and everyday messages
 * as short codes, so they can be read in any language. A message is plain text,
 * `MECP/<severity>/<codes> [free text]`, for example `MECP/0/M01 M07 2pax P05 48.6520,20.1305`:
 * MAYDAY, injury, fracture, 2 people, at those coordinates.
 *
 * [parse] follows the MECP project's reference decoder (`engine/src/decoder.ts`, protocol 1.1)
 * step by step, so this app reads a message exactly as other MECP apps do. [describe] turns it
 * into English with the project's code table ([MecpCodes]).
 */
object Mecp {
    /** A decoded MECP message. */
    data class Message(
        /** 0 MAYDAY, 1 URGENT, 2 SAFETY, 3 ROUTINE. */
        val severity: Int,
        /** Codes in the order sent, such as `M01`; ones this table does not know are kept too. */
        val codes: List<String>,
        /**
         * Known codes that come after the free text has begun, as in MECP's own example
         * `M01 M07 2pax P05 …`. The reference decoder counts them as free text; they are shown
         * with their meaning, but take no part in deciding what the message is (a drill, say).
         */
        val laterCodes: List<String> = emptyList(),
        /** Everything after the last code, or null. */
        val freeText: String?,
        /** The `Npax` head count. */
        val people: Int?,
        val latitude: Double?,
        val longitude: Double?,
        /** Minutes, from a number straight after the codes when `R03` (ETA) is among them. */
        val etaMinutes: Int?,
        /** An incident tag, from `#tag`. */
        val reference: String?,
        /** The sender's language, from `@xx`. */
        val language: String?,
        /** When it was written, as `HHMM` UTC, from `@HHMM`. */
        val timeUtc: String?,
        /** The sender's callsign, from `~CALL`. */
        val callsign: String?,
        /** The message as it arrived, from `MECP/` on. */
        val raw: String,
    ) {
        /** `D01` (drill) or `D02` (test): practice, not a real emergency. `D03` and `D04` are not. */
        val isDrill: Boolean
            get() = "D01" in codes || "D02" in codes

        val hasPosition: Boolean
            get() = latitude != null && longitude != null
    }

    /** A received chat text that is MECP: who sent it, when the text names them, and the message. */
    data class Received(val sender: String?, val message: Message)

    private const val PREFIX = "MECP/"
    private val CODE = Regex("^[A-Z]\\d{2}$")
    private val PEOPLE = Regex("(\\d+)pax")
    private val GPS = Regex("(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val REFERENCE = Regex("#([A-Za-z0-9]{1,4})")
    private val LANGUAGE = Regex("@([a-z]{2})(?:\\s|$)")
    private val TIME = Regex("@(\\d{4})(?:\\s|$)")
    private val CALLSIGN = Regex("~([A-Za-z0-9]{1,9})(?:\\s|$)")
    private val ETA = Regex("^(\\d+)(?:\\s|$)")

    /**
     * Decodes [text] when it is an MECP message, or returns null. As in the reference decoder,
     * the prefix is case-sensitive, and a message needs a severity from 0 to 3 to count.
     */
    fun parse(text: String): Message? {
        if (!text.startsWith(PREFIX) || text.length < 6) return null
        val severity = text[5].digitToIntOrNull()?.takeIf { it in 0..3 } ?: return null
        val body = if (text.length > 7) text.substring(7) else ""
        val tokens = body.split(' ').filter { it.isNotEmpty() }
        val codes = tokens.takeWhile { CODE.matches(it) }
        val rest = tokens.drop(codes.size)
        val freeText = rest.takeIf { it.isNotEmpty() }?.joinToString(" ")
        val laterCodes = rest.filter { CODE.matches(it) && it in MecpCodes.CODES }
        val search = freeText.orEmpty()
        val gps = GPS.find(search)?.let { match ->
            val lat = match.groupValues[1].toDouble()
            val lon = match.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
        }
        val time = TIME.find(search)?.groupValues?.get(1)?.takeIf {
            it.substring(0, 2).toInt() in 0..23 && it.substring(2, 4).toInt() in 0..59
        }
        val eta = if ("R03" in codes && freeText != null) ETA.find(freeText)?.groupValues?.get(1)?.toIntOrNull() else null
        return Message(
            severity = severity,
            codes = codes,
            laterCodes = laterCodes,
            freeText = freeText,
            people = PEOPLE.find(search)?.groupValues?.get(1)?.toIntOrNull(),
            latitude = gps?.first,
            longitude = gps?.second,
            etaMinutes = eta,
            reference = REFERENCE.find(search)?.groupValues?.get(1),
            language = LANGUAGE.find(search)?.groupValues?.get(1),
            timeUtc = time,
            callsign = CALLSIGN.find(search)?.groupValues?.get(1),
            raw = text,
        )
    }

    /**
     * Finds MECP in a received chat text: a channel message in MeshCore's `Name: text` form, or a
     * direct message that is MECP from its first character.
     */
    fun find(text: String): Received? {
        parse(text)?.let { return Received(null, it) }
        val sender = ChannelReplies.senderOf(text) ?: return null
        val rest = text.substring(text.indexOf(": ") + 2)
        return parse(rest)?.let { Received(sender, it) }
    }

    /** "🆘 MAYDAY", "DRILL 🆘 MAYDAY", and so on. */
    fun headline(message: Message): String {
        val label = "${SEVERITY_SIGNS[message.severity]} ${MecpCodes.SEVERITIES[message.severity]}"
        return if (message.isDrill) "DRILL $label" else label
    }

    /** What each code means, in order, with `[N]` and `[minutes]` filled in when the message gives them. */
    fun codeMeanings(message: Message): List<String> = (message.codes + message.laterCodes).map { code ->
        val meaning = MecpCodes.CODES[code] ?: return@map "unknown code $code"
        var text = meaning
        message.people?.let { text = text.replace("[N]pax", "$it people") }
        message.etaMinutes?.let { text = text.replace("[minutes]", "$it min") }
        text
    }

    /**
     * The message in English, on one line: the headline, then what each code means, then the
     * details from the free text, then whatever free text is left, in quotes.
     * `🆘 MAYDAY: Injury · Fracture / immobile · At GPS coordinates · 2 people · 48.6520, 20.1305`
     */
    fun describe(message: Message): String {
        val parts = ArrayList(codeMeanings(message))
        val filled = parts.joinToString(" ")
        message.people?.let { if ("$it people" !in filled) parts.add("$it people") }
        if (message.latitude != null && message.longitude != null) parts.add(coordinates(message.latitude, message.longitude))
        message.timeUtc?.let { parts.add("${it.substring(0, 2)}:${it.substring(2)} UTC") }
        message.etaMinutes?.let { if ("$it min" !in filled) parts.add("ETA $it min") }
        message.reference?.let { parts.add("incident #$it") }
        message.callsign?.let { parts.add("callsign $it") }
        leftover(message)?.let { parts.add("“$it”") }
        val head = headline(message)
        return if (parts.isEmpty()) head else "$head: ${parts.joinToString(" · ")}"
    }

    /** Coordinates as MECP writes them, with up to 4 decimals (about 11 m). */
    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude)

    /** The free text with the parts already shown taken out, or null when nothing is left. */
    private fun leftover(message: Message): String? {
        var text = message.freeText ?: return null
        if (message.etaMinutes != null) text = ETA.replaceFirst(text, "")
        if (message.people != null) text = PEOPLE.replaceFirst(text, "")
        if (message.latitude != null) text = GPS.replaceFirst(text, "")
        if (message.reference != null) text = REFERENCE.replaceFirst(text, "")
        if (message.language != null) text = LANGUAGE.replaceFirst(text, " ")
        if (message.timeUtc != null) text = TIME.replaceFirst(text, " ")
        if (message.callsign != null) text = CALLSIGN.replaceFirst(text, " ")
        return text.split(' ').filter { it.isNotBlank() && it !in message.laterCodes }.joinToString(" ").ifEmpty { null }
    }

    private val SEVERITY_SIGNS = listOf("🆘", "🚨", "⚠️", "ℹ️")
}
