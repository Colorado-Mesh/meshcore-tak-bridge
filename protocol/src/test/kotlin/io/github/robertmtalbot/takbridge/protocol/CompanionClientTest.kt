package io.github.robertmtalbot.takbridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionClientTest {
    private val sent = ArrayList<ByteArray>()
    private val log = ArrayList<String>()
    private val contacts = ArrayList<Contact>()
    private val messages = ArrayList<ChannelMessage>()
    private val results = ArrayList<Pair<String, Boolean>>()
    private var channels: List<Channel>? = null
    private var now = 1_800_000_000_000L

    private val client = CompanionClient(
        transmit = { sent.add(it) },
        listener = object : CompanionClient.Listener {
            override fun onLog(message: String) { log.add(message) }
            override fun onContact(contact: Contact) { contacts.add(contact) }
            override fun onChannels(channels: List<Channel>) { this@CompanionClientTest.channels = channels }
            override fun onChannelMessage(message: ChannelMessage) { messages.add(message) }
            override fun onSendResult(channelIndex: Int, text: String, accepted: Boolean) { results.add(text to accepted) }
        },
        clock = { now },
    )

    private fun le32(value: Long) = ByteArray(4) { (value shr (8 * it)).toByte() }

    private fun selfInfo() = ByteArray(58).also { it[0] = 5 } + "Radio".toByteArray()

    private fun deviceInfo(slots: Int) = ByteArray(82).also { it[0] = 0x0D; it[1] = 9; it[3] = slots.toByte() }

    private fun contact(code: Int, name: String, type: Int, lat: Int, lon: Int) =
        byteArrayOf(code.toByte()) + ByteArray(32) { 0x11 } + byteArrayOf(type.toByte(), 0, 0) + ByteArray(64) +
            name.toByteArray().copyOf(32) + le32(1_700_000_000) + le32(lat.toLong()) + le32(lon.toLong()) + le32(1_700_000_500)

    private fun channelInfo(index: Int, name: String, programmed: Boolean = true) =
        byteArrayOf(0x12, index.toByte()) + name.toByteArray().copyOf(32) + ByteArray(16) { if (programmed) 7 else 0 }

    private fun channelText(index: Int, text: String) =
        byteArrayOf(0x11, 20, 0, 0, index.toByte(), 2, 0) + le32(1_700_000_900) + text.toByteArray()

    private fun lastSent() = sent.last()

    /** Walks the client through start-up with two channel slots and one contact. */
    private fun startUp() {
        client.start()
        assertEquals(1, lastSent()[0].toInt())
        client.onFrame(selfInfo())
        assertArrayEquals(byteArrayOf(0x16, 3), lastSent())
        client.onFrame(deviceInfo(slots = 2))
        assertArrayEquals(byteArrayOf(4), lastSent())
        client.onFrame(byteArrayOf(2) + le32(1))
        client.onFrame(contact(3, "Lookout Repeater", Contact.TYPE_REPEATER, 39_739_200, -104_990_300))
        client.onFrame(byteArrayOf(4) + le32(1_700_000_500))
        assertArrayEquals(byteArrayOf(0x1F, 0), lastSent())
        client.onFrame(channelInfo(0, "Public"))
        assertArrayEquals(byteArrayOf(0x1F, 1), lastSent())
        client.onFrame(byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(0x0A), lastSent())
        client.onFrame(byteArrayOf(0x0A))
    }

    @Test
    fun startUpReadsContactsAndChannelsInOrder() {
        startUp()
        assertEquals(listOf(Channel(0, "Public")), channels)
        val repeater = contacts.single()
        assertEquals("Lookout Repeater", repeater.name)
        assertEquals(Contact.TYPE_REPEATER, repeater.type)
        assertEquals(-104.9903, repeater.longitude, 1e-9)
        assertTrue(repeater.hasPosition)
        assertTrue(log.isEmpty())
    }

    @Test
    fun aNoticeOfWaitingMessagesCollectsThemAll() {
        startUp()
        val before = sent.size
        client.onFrame(byteArrayOf(0x83.toByte()))
        client.onFrame(channelText(0, "Ann: hello"))
        client.onFrame(channelText(0, "Bo: hi"))
        client.onFrame(byteArrayOf(0x0A))
        assertEquals(listOf("Ann: hello", "Bo: hi"), messages.map { it.text })
        assertEquals(3, sent.size - before)
    }

    @Test
    fun sendsChannelTextWithTheCurrentTime() {
        startUp()
        client.sendChannelText(0, "on scene")
        val expected = byteArrayOf(3, 0, 0) + le32(1_800_000_000) + "on scene".toByteArray()
        assertArrayEquals(expected, lastSent())
        client.onFrame(byteArrayOf(0))
        assertEquals(listOf("on scene" to true), results)
    }

    @Test
    fun aRefusedOrUnansweredSendIsReported() {
        startUp()
        client.sendChannelText(5, "one")
        client.onFrame(byteArrayOf(1, 2))
        client.sendChannelText(0, "two")
        now += 7_000
        client.tick()
        assertEquals(listOf("one" to false, "two" to false), results)
    }

    @Test
    fun aSendBeforeStartUpFinishesIsRefused() {
        client.start()
        client.sendChannelText(0, "early")
        assertEquals(listOf("early" to false), results)
    }

    @Test
    fun anAdvertFromAKnownNodeAsksOnlyForChanges() {
        startUp()
        client.onFrame(byteArrayOf(0x80.toByte()) + ByteArray(32))
        assertArrayEquals(byteArrayOf(4) + le32(1_700_000_500), lastSent())
    }

    @Test
    fun aNewlyHeardNodeIsReportedWithoutAsking() {
        startUp()
        val before = sent.size
        client.onFrame(contact(0x8A, "New Node", Contact.TYPE_COMPANION, 0, 0))
        assertEquals("New Node", contacts.last().name)
        assertEquals(false, contacts.last().hasPosition)
        assertEquals(before, sent.size)
    }

    @Test
    fun aSilentRadioDoesNotStallTheQueue() {
        client.start()
        client.onFrame(selfInfo())
        client.onFrame(deviceInfo(slots = 1))
        now += 7_000
        client.tick() // contacts never answered
        assertArrayEquals(byteArrayOf(0x1F, 0), lastSent())
        client.onFrame(channelInfo(0, "", programmed = false))
        assertEquals(emptyList<Channel>(), channels)
        assertEquals(1, log.size)
    }

    @Test
    fun chattyLogFramesFromTheRadioAreIgnored() {
        startUp()
        client.onFrame(byteArrayOf(0x88.toByte(), 1, 2, 3))
        assertTrue(log.isEmpty())
    }
}
