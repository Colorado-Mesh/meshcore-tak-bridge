package io.github.robertmtalbot.takbridge.cot

import java.io.ByteArrayOutputStream

/** A client sent more than [CotFramer.maxFrameBytes] without completing an event. */
class CotFrameTooLargeException(limit: Int) : Exception("CoT event exceeds $limit bytes")

/**
 * Splits a TAK streaming connection into complete `<event>…</event>` strings.
 *
 * Events arrive back to back or separated by whitespace, sometimes each with its own XML
 * declaration, and a read can end anywhere, including in the middle of a multi-byte character.
 * Bytes are buffered until an event closes, and only a whole event is decoded as UTF-8.
 */
class CotFramer(val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES) {
    private val buffer = ByteArrayOutputStream()

    /**
     * Add [length] bytes from [chunk] and return the events they completed, in order.
     *
     * @throws CotFrameTooLargeException when the buffered partial event passes the limit. The
     * framer is reset, and the caller should drop the connection.
     */
    fun push(chunk: ByteArray, length: Int = chunk.size): List<String> {
        buffer.write(chunk, 0, length)
        val bytes = buffer.toByteArray()
        val frames = ArrayList<String>()
        var start = 0
        while (true) {
            val end = indexOf(bytes, EVENT_END, start)
            if (end < 0) break
            val frameEnd = end + EVENT_END.size
            val text = String(bytes, start, frameEnd - start, Charsets.UTF_8)
            val open = lastEventStart(text)
            if (open >= 0) frames.add(text.substring(open))
            start = frameEnd
        }
        buffer.reset()
        val remaining = bytes.size - start
        if (remaining > maxFrameBytes) throw CotFrameTooLargeException(maxFrameBytes)
        buffer.write(bytes, start, remaining)
        return frames
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from
        val last = haystack.size - needle.size
        while (i <= last) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    /** Start of the last `<event` opening tag, so leading junk and declarations are dropped. */
    private fun lastEventStart(text: String): Int {
        var found = -1
        var i = text.indexOf(EVENT_OPEN)
        while (i >= 0) {
            val next = text.getOrNull(i + EVENT_OPEN.length)
            if (next == '>' || next == '/' || (next != null && next.isWhitespace())) found = i
            i = text.indexOf(EVENT_OPEN, i + 1)
        }
        return found
    }

    companion object {
        /** A sane CoT event is a few kilobytes. */
        const val DEFAULT_MAX_FRAME_BYTES = 64 * 1024
        private const val EVENT_OPEN = "<event"
        private val EVENT_END = "</event>".toByteArray(Charsets.US_ASCII)
    }
}
