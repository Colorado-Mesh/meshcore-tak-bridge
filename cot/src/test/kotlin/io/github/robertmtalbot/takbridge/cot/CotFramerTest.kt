package io.github.robertmtalbot.takbridge.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CotFramerTest {
    private fun event(uid: String, body: String = "") = "<event version=\"2.0\" uid=\"$uid\">$body</event>"

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun splitsEventsThatArriveBackToBackOrSeparatedByNewlines() {
        val framer = CotFramer()
        val frames = framer.push(bytes(event("A") + event("B") + "\n" + event("C") + "\n"))
        assertEquals(listOf(event("A"), event("B"), event("C")), frames)
    }

    @Test
    fun holdsAPartialEventUntilItCloses() {
        val framer = CotFramer()
        val whole = event("A", "<detail/>")
        assertEquals(emptyList<String>(), framer.push(bytes(whole.substring(0, 20))))
        assertEquals(emptyList<String>(), framer.push(bytes(whole.substring(20, 45))))
        assertEquals(listOf(whole), framer.push(bytes(whole.substring(45))))
    }

    @Test
    fun dropsAnXmlDeclarationAndJunkBeforeTheEvent() {
        val framer = CotFramer()
        val frames = framer.push(bytes("<?xml version=\"1.0\"?>\n" + event("A") + "  junk " + event("B")))
        assertEquals(listOf(event("A"), event("B")), frames)
    }

    @Test
    fun decodesAMultiByteCharacterSplitAcrossReads() {
        val framer = CotFramer()
        val whole = bytes(event("A", "<remarks>snow ☃ day</remarks>"))
        val cut = whole.indexOfFirst { it == 0xE2.toByte() } + 1
        assertEquals(emptyList<String>(), framer.push(whole.copyOfRange(0, cut)))
        val frames = framer.push(whole.copyOfRange(cut, whole.size))
        assertEquals(1, frames.size)
        assertTrue(frames[0].contains("snow ☃ day"))
    }

    @Test
    fun readsOnlyTheGivenLengthOfTheChunk() {
        val framer = CotFramer()
        val data = bytes(event("A") + "leftover-from-a-previous-read")
        val frames = framer.push(data, event("A").length)
        assertEquals(listOf(event("A")), frames)
        assertEquals(listOf(event("B")), framer.push(bytes(event("B"))))
    }

    @Test
    fun ignoresAClosingTagWithNoOpeningEvent() {
        val framer = CotFramer()
        assertEquals(emptyList<String>(), framer.push(bytes("garbage</event>")))
        assertEquals(listOf(event("A")), framer.push(bytes(event("A"))))
    }

    @Test
    fun doesNotTreatALongerTagNameAsAnEvent() {
        val framer = CotFramer()
        val frames = framer.push(bytes("<eventual>x</eventual>" + event("A")))
        assertEquals(listOf(event("A")), frames)
    }

    @Test
    fun rejectsAnEventThatNeverCloses() {
        val framer = CotFramer(maxFrameBytes = 256)
        framer.push(bytes("<event uid=\"A\">" + "x".repeat(200)))
        assertThrows(CotFrameTooLargeException::class.java) { framer.push(bytes("y".repeat(100))) }
        assertEquals(listOf(event("B")), framer.push(bytes(event("B"))))
    }

    @Test
    fun acceptsManyEventsInOneReadEvenWhenTogetherTheyPassTheLimit() {
        val framer = CotFramer(maxFrameBytes = 256)
        val many = (1..50).joinToString("") { event("E$it", "<detail/>") }
        assertEquals(50, framer.push(bytes(many)).size)
    }
}
