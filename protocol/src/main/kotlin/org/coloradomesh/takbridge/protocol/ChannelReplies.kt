package org.coloradomesh.takbridge.protocol

/**
 * Replies on a channel, MeshCore style. MeshCore has no reply-to-message feature: the apps start a
 * reply with `@[Name]`, and the firmware sends that like any other text. ATAK's chat has no reply
 * action either, so a reply is typed:
 *
 * - `@silver are you on?` sends `@[Silver Eagle] are you on?`, when exactly one person heard on
 *   that channel recently has a name starting with "silver". Case, spaces, emoji and punctuation are
 *   ignored when matching, so `@w0rmt` finds `🔥 W0RMT 03`. A name that matches exactly wins over
 *   longer names it starts. `@"Two Words"` matches a whole name with a space in it.
 * - `@@ thanks` replies to whoever spoke last on that channel.
 *
 * When several people match, nothing is sent and the caller says who matched, so a reply never goes
 * to the wrong person. When nobody matches, the text is sent exactly as typed, so a message that
 * just starts with "@" is not changed by surprise. Text already in the `@[Name]` form is left alone.
 *
 * Senders are learned from channel messages as they arrive, in MeshCore's `Name: text` form, and
 * remembered per channel by the time this side heard them (not the sender's clock, which may be
 * wrong). Thread-safe.
 */
class ChannelReplies(
    private val maxNamesPerChannel: Int = 50,
    private val maxAgeSeconds: Long = 24 * 3600L,
) {
    sealed interface Outcome {
        /** Send [text]; [repliedTo] is the name it was addressed to, or null when it is unchanged. */
        data class Send(val text: String, val repliedTo: String? = null) : Outcome

        /** [typed] matches several [names], most recently heard first; send nothing. */
        data class Ambiguous(val typed: String, val names: List<String>) : Outcome

        /** `@@` was used, but nobody has been heard on the channel recently; send nothing. */
        data object NobodyYet : Outcome
    }

    /** Per channel, each sender's name and when it was last heard, least recent first. */
    private val heard = HashMap<Int, LinkedHashMap<String, Long>>()

    /** Remember the sender of a channel message. Messages from [ownName] are not replied to. */
    @Synchronized
    fun heard(channelIndex: Int, text: String, atSeconds: Long, ownName: String?) {
        val name = senderOf(text) ?: return
        if (name == ownName) return
        val names = heard.getOrPut(channelIndex) { LinkedHashMap() }
        names.remove(name)
        names[name] = atSeconds
        while (names.size > maxNamesPerChannel) names.remove(names.keys.first())
    }

    /** Forget everyone, for example when a different radio, with different channels, is chosen. */
    @Synchronized
    fun clear() = heard.clear()

    /** Names heard on [channelIndex] recently, most recent first. */
    @Synchronized
    fun recent(channelIndex: Int, nowSeconds: Long): List<String> =
        heard[channelIndex].orEmpty().entries
            .filter { nowSeconds - it.value <= maxAgeSeconds }
            .map { it.key }
            .reversed()

    /** What to send for [text], typed in ATAK for [channelIndex]. */
    fun resolve(channelIndex: Int, text: String, nowSeconds: Long): Outcome {
        if (!text.startsWith("@") || text.startsWith("@[")) return Outcome.Send(text)
        val recent = recent(channelIndex, nowSeconds)
        if (text == "@@" || (text.startsWith("@@") && text[2].isWhitespace())) {
            val last = recent.firstOrNull() ?: return Outcome.NobodyYet
            return Outcome.Send(mention(last, text.substring(2)), last)
        }
        val (typed, rest) = splitTyped(text.substring(1)) ?: return Outcome.Send(text)
        val key = normalize(typed)
        if (key.isEmpty()) return Outcome.Send(text)
        val exact = recent.filter { normalize(it) == key }
        val matches = if (exact.size == 1) exact else recent.filter { normalize(it).startsWith(key) }
        return when (matches.size) {
            0 -> Outcome.Send(text)
            1 -> Outcome.Send(mention(matches[0], rest), matches[0])
            else -> Outcome.Ambiguous(typed, matches)
        }
    }

    companion object {
        /** MeshCore names are at most 32 bytes; a little slack for multi-byte characters. */
        private const val MAX_NAME_CHARS = 40

        /** The sender's name from MeshCore's `Name: text` channel form, or null when there is none. */
        fun senderOf(text: String): String? {
            val end = text.indexOf(": ")
            if (end <= 0 || end > MAX_NAME_CHARS) return null
            return text.substring(0, end).trim().ifEmpty { null }
        }

        /** Lower case, letters and digits only, so emoji, spaces and punctuation do not matter. */
        fun normalize(name: String): String =
            name.lowercase().filter { it.isLetterOrDigit() }

        private fun mention(name: String, rest: String): String {
            val message = rest.trim()
            return if (message.isEmpty()) "@[$name]" else "@[$name] $message"
        }

        /** The typed name and the message after it, from the text after "@". */
        private fun splitTyped(afterAt: String): Pair<String, String>? {
            if (afterAt.startsWith("\"")) {
                val close = afterAt.indexOf('"', 1)
                if (close < 0) return null
                return afterAt.substring(1, close) to afterAt.substring(close + 1)
            }
            val end = afterAt.indexOfFirst { it.isWhitespace() }.let { if (it < 0) afterAt.length else it }
            return afterAt.substring(0, end) to afterAt.substring(end)
        }
    }
}
