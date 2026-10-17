package org.coloradomesh.takbridge.protocol

import org.coloradomesh.takbridge.protocol.ChannelReplies.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelRepliesTest {
    private val now = 1_800_000_000L
    private val replies = ChannelReplies().apply {
        heard(0, "Silver Eagle: 2 hops to Eaton.", now - 300, OWN)
        heard(0, "SCRAM-A1 (FOEHAMMER): Morning from the Great Boulder Fogbank", now - 200, OWN)
        heard(0, "M🐻β: smoke is the male equivalent of pumpkin spice", now - 100, OWN)
        heard(0, "🔥 W0RMT 03: test", now - 50, OWN)
        heard(4, "Hobo: This seems like a bad idea", now - 10, OWN)
    }

    private fun send(text: String, channel: Int = 0) = replies.resolve(channel, text, now)

    @Test
    fun theStartOfANameAddressesTheReply() {
        assertEquals(
            Outcome.Send("@[Silver Eagle] are you on the repeater?", "Silver Eagle"),
            send("@silver are you on the repeater?"),
        )
    }

    @Test
    fun caseSpacesEmojiAndPunctuationAreIgnored() {
        assertEquals("🔥 W0RMT 03", (send("@w0rmt hi") as Outcome.Send).repliedTo)
        assertEquals("🔥 W0RMT 03", (send("@W0RMT03, hi") as Outcome.Send).repliedTo)
        assertEquals("M🐻β", (send("@mβ ha") as Outcome.Send).repliedTo)
    }

    @Test
    fun aQuotedNameCanHaveSpaces() {
        assertEquals(Outcome.Send("@[Silver Eagle] ok", "Silver Eagle"), send("@\"silver eagle\" ok"))
    }

    @Test
    fun atAtRepliesToWhoeverSpokeLast() {
        assertEquals(Outcome.Send("@[🔥 W0RMT 03] thanks!", "🔥 W0RMT 03"), send("@@ thanks!"))
        assertEquals(Outcome.Send("@[Hobo] agreed", "Hobo"), send("@@ agreed", channel = 4))
        assertEquals(Outcome.Send("@[Hobo]", "Hobo"), send("@@", channel = 4))
    }

    @Test
    fun atAtOnAQuietChannelSendsNothing() {
        assertEquals(Outcome.NobodyYet, send("@@ anyone?", channel = 7))
    }

    @Test
    fun severalMatchesSendNothingAndSayWho() {
        val outcome = send("@s hello")
        assertEquals(Outcome.Ambiguous("s", listOf("SCRAM-A1 (FOEHAMMER)", "Silver Eagle")), outcome)
    }

    @Test
    fun anExactMatchWinsOverLongerNames() {
        val r = ChannelReplies().apply {
            heard(1, "Bob: hi", now - 20, null)
            heard(1, "Bobcat: hi", now - 10, null)
        }
        assertEquals(Outcome.Send("@[Bob] yes", "Bob"), r.resolve(1, "@bob yes", now))
        assertEquals(Outcome.Ambiguous("bo", listOf("Bobcat", "Bob")), r.resolve(1, "@bo yes", now))
    }

    @Test
    fun noMatchSendsTheTextAsTyped() {
        assertEquals(Outcome.Send("@home in 10"), send("@home in 10"))
        assertEquals(Outcome.Send("@ 5pm then"), send("@ 5pm then"))
        assertEquals(Outcome.Send("@\"unclosed quote"), send("@\"unclosed quote"))
    }

    @Test
    fun ordinaryAndAlreadyFormattedTextIsUnchanged() {
        assertEquals(Outcome.Send("good morning"), send("good morning"))
        assertEquals(Outcome.Send("@[Silver Eagle] hi"), send("@[Silver Eagle] hi"))
    }

    @Test
    fun namesAreKeptPerChannel() {
        assertEquals(Outcome.Send("@hobo hi"), send("@hobo hi", channel = 0))
        assertEquals(Outcome.Send("@[Hobo] hi", "Hobo"), send("@hobo hi", channel = 4))
    }

    @Test
    fun theRadiosOwnMessagesAreNotRepliedTo() {
        val r = ChannelReplies().apply {
            heard(2, "Ann: hello", now - 20, OWN)
            heard(2, "$OWN: hello Ann", now - 10, OWN)
        }
        assertEquals(Outcome.Send("@[Ann] hi", "Ann"), r.resolve(2, "@@ hi", now))
    }

    @Test
    fun namesHeardTooLongAgoAreForgotten() {
        val r = ChannelReplies(maxAgeSeconds = 3600).apply {
            heard(3, "Old Timer: hi", now - 7200, null)
        }
        assertEquals(Outcome.Send("@old hi"), r.resolve(3, "@old hi", now))
        assertEquals(Outcome.NobodyYet, r.resolve(3, "@@ hi", now))
    }

    @Test
    fun onlyTheMostRecentNamesAreKept() {
        val r = ChannelReplies(maxNamesPerChannel = 2).apply {
            heard(5, "A1: x", now - 30, null)
            heard(5, "B2: x", now - 20, null)
            heard(5, "C3: x", now - 10, null)
        }
        assertEquals(listOf("C3", "B2"), r.recent(5, now))
    }

    @Test
    fun hearingSomeoneAgainMakesThemTheLastSpeaker() {
        replies.heard(0, "Silver Eagle: back again", now - 1, OWN)
        assertEquals("Silver Eagle", (send("@@ welcome back") as Outcome.Send).repliedTo)
    }

    @Test
    fun aSenderIsReadFromMeshCoresChannelForm() {
        assertEquals("Silver Eagle", ChannelReplies.senderOf("Silver Eagle: hi: there"))
        assertNull(ChannelReplies.senderOf("no sender here"))
        assertNull(ChannelReplies.senderOf(": empty name"))
        assertNull(ChannelReplies.senderOf("x".repeat(60) + ": far too long to be a name"))
    }

    @Test
    fun clearForgetsEveryone() {
        replies.clear()
        assertEquals(Outcome.NobodyYet, send("@@ hi"))
    }

    private companion object {
        const val OWN = "W0RMT Base"
    }
}
