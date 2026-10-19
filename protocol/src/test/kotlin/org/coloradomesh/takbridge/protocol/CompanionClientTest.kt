package org.coloradomesh.takbridge.protocol

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
    private val directs = ArrayList<DirectMessage>()
    private val directResults = ArrayList<Pair<String, DirectOutcome>>()
    private var channels: List<Channel>? = null
    private val batteries = ArrayList<Int>()
    private val positions = ArrayList<Pair<String, TelemetryOutcome>>()
    private var now = 1_800_000_000_000L

    private val client = CompanionClient(
        transmit = { sent.add(it) },
        listener = object : CompanionClient.Listener {
            override fun onLog(message: String) { log.add(message) }
            override fun onContact(contact: Contact) { contacts.add(contact) }
            override fun onChannels(channels: List<Channel>) { this@CompanionClientTest.channels = channels }
            override fun onChannelMessage(message: ChannelMessage) { messages.add(message) }
            override fun onSendResult(channelIndex: Int, text: String, accepted: Boolean) { results.add(text to accepted) }
            override fun onDirectMessage(message: DirectMessage) { directs.add(message) }
            override fun onDirectResult(id: Long, publicKeyHex: String, text: String, outcome: DirectOutcome) {
                directResults.add(text to outcome)
            }
            override fun onBattery(millivolts: Int) { batteries.add(millivolts) }
            override fun onTelemetry(publicKeyHex: String, outcome: TelemetryOutcome) { positions.add(publicKeyHex.take(2) to outcome) }
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
        startUpAfterGreeting()
    }

    /** The rest of start-up, once the opening greeting has been sent. */
    private fun startUpAfterGreeting() {
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
        assertArrayEquals(byteArrayOf(0x14), lastSent())
        client.onFrame(battery(3_900))
    }

    private fun battery(millivolts: Int) =
        byteArrayOf(0x0C, millivolts.toByte(), (millivolts shr 8).toByte()) + le32(100) + le32(2_000)

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
    fun theBatteryIsReadAfterStartUpAndThenEveryFewMinutes() {
        startUp()
        assertEquals(listOf(3_900), batteries)
        val before = sent.size
        now += CompanionClient.BATTERY_INTERVAL_MS - 1_000
        client.tick()
        assertEquals(before, sent.size)
        now += 1_000
        client.tick()
        assertArrayEquals(byteArrayOf(0x14), lastSent())
        client.onFrame(battery(3_650))
        assertEquals(listOf(3_900, 3_650), batteries)
        assertTrue(log.isEmpty())
    }

    @Test
    fun firmwareThatRefusesTheBatteryRequestIsNotAskedAgain() {
        startUp()
        now += CompanionClient.BATTERY_INTERVAL_MS
        client.tick()
        client.onFrame(byteArrayOf(1, 1))
        val before = sent.size
        now += 2 * CompanionClient.BATTERY_INTERVAL_MS
        client.tick()
        assertEquals(before, sent.size)
        assertEquals(listOf("The radio refused the battery request (error 1)"), log)
    }

    @Test
    fun aBatteryReadingWaitsItsTurnBehindMessages() {
        startUp()
        now += CompanionClient.BATTERY_INTERVAL_MS - 1_000
        client.sendChannelText(0, "first")
        now += 1_000
        client.tick()
        assertEquals(3, lastSent()[0].toInt())
        client.onFrame(byteArrayOf(0))
        assertArrayEquals(byteArrayOf(0x14), lastSent())
    }

    private val tracker1 = "aa".repeat(32)
    private val tracker2 = "bb".repeat(32)

    private fun telemetryFrom(prefix: Byte, withGps: Boolean = true): ByteArray {
        val gps = byteArrayOf(1, 136.toByte(), 0x06, 0x1B, 0x16, 0xF0.toByte(), 0x0F, 0xDF.toByte(), 0x02, 0x86.toByte(), 0x95.toByte())
        return byteArrayOf(0x8B.toByte(), 0) + ByteArray(6) { prefix } + byteArrayOf(1, 116, 0x01, 0x87.toByte()) + if (withGps) gps else ByteArray(0)
    }

    private fun isTelemetryRequestFor(key: String, frame: ByteArray) =
        frame.size == 36 && frame[0].toInt() == 0x27 && frame.copyOfRange(4, 36).contentEquals(CompanionFrames.telemetryRequest(key).copyOfRange(4, 36))

    @Test
    fun trackedNodesAreAskedInTurnOneAtATime() {
        startUp()
        client.setTracking(linkedSetOf(tracker1, tracker2), 300_000)
        client.tick()
        assertTrue(isTelemetryRequestFor(tracker1, lastSent()))
        client.onFrame(sentFrame(ack = 77, timeoutMs = 4_000))
        val before = sent.size
        client.tick()
        assertEquals("the next waits for the answer", before, sent.size)
        client.onFrame(telemetryFrom(0xAA.toByte()))
        val answer = positions.single().second as TelemetryOutcome.Answered
        assertEquals(40.015, answer.telemetry.position!!.latitude, 1e-4)
        client.tick()
        assertTrue(isTelemetryRequestFor(tracker2, lastSent()))
    }

    @Test
    fun aNodeThatStaysSilentIsReportedAndTheTurnMovesOn() {
        startUp()
        client.setTracking(linkedSetOf(tracker1, tracker2), 300_000)
        client.tick()
        client.onFrame(sentFrame(ack = 77, timeoutMs = 4_000))
        now += 4_000 + 2_000
        client.tick()
        assertEquals(listOf("aa" to TelemetryOutcome.NoAnswer), positions)
        assertTrue(isTelemetryRequestFor(tracker2, lastSent()))
    }

    @Test
    fun eachNodeIsAskedAgainOnlyAfterTheInterval() {
        startUp()
        client.setTracking(setOf(tracker1), 120_000)
        client.tick()
        client.onFrame(sentFrame(ack = 77))
        client.onFrame(telemetryFrom(0xAA.toByte(), withGps = false))
        assertEquals(null, (positions.single().second as TelemetryOutcome.Answered).telemetry.position)
        val before = sent.size
        now += 119_000
        client.tick()
        assertEquals(before, sent.size)
        now += 1_000
        client.tick()
        assertTrue(isTelemetryRequestFor(tracker1, lastSent()))
    }

    @Test
    fun pausingTrackingDoesNotResetTheInterval() {
        startUp()
        client.setTracking(setOf(tracker1), 120_000)
        client.tick()
        client.onFrame(sentFrame(ack = 77))
        client.onFrame(telemetryFrom(0xAA.toByte()))
        client.setTracking(emptySet(), 120_000) // ATAK disconnects
        now += 10_000
        client.setTracking(setOf(tracker1), 120_000) // and comes back
        val before = sent.size
        client.tick()
        assertEquals("not asked again so soon", before, sent.size)
        now += 110_000
        client.tick()
        assertTrue(isTelemetryRequestFor(tracker1, lastSent()))
    }

    @Test
    fun aRequestTheRadioRefusesIsReportedAsNotSent() {
        startUp()
        client.setTracking(setOf(tracker1), 300_000)
        client.tick()
        client.onFrame(byteArrayOf(1, 2))
        assertEquals(listOf("aa" to TelemetryOutcome.NotSent), positions)
    }

    @Test
    fun anUnaskedOrLateAnswerIsIgnored() {
        startUp()
        client.onFrame(telemetryFrom(0xCC.toByte()))
        assertTrue(positions.isEmpty())
        assertEquals(1, log.size)
    }

    @Test
    fun trackingWaitsForStartUpAndStopsWhenCleared() {
        client.setTracking(setOf(tracker1), 300_000)
        client.start()
        client.tick()
        assertEquals(1, sent.size)
        startUpAfterGreeting()
        client.setTracking(emptySet(), 300_000)
        val before = sent.size
        client.tick()
        assertEquals(before, sent.size)
    }

    @Test
    fun chattyLogFramesFromTheRadioAreIgnored() {
        startUp()
        client.onFrame(byteArrayOf(0x88.toByte(), 1, 2, 3))
        assertTrue(log.isEmpty())
    }

    private val ann = "a1b2c3d4e5f6" + "77".repeat(26)

    private fun sentFrame(ack: Long, timeoutMs: Long = 5_000) = byteArrayOf(6, 1) + le32(ack) + le32(timeoutMs)

    private fun confirmed(ack: Long) = byteArrayOf(0x82.toByte()) + le32(ack) + le32(800)

    private fun directText(prefix: Byte, timestamp: Long, text: String) =
        byteArrayOf(0x10, 0, 0, 0) + ByteArray(6) { prefix } + byteArrayOf(1, 0) + le32(timestamp) + text.toByteArray()

    @Test
    fun aDirectMessageIsSentAndItsAcknowledgementReported() {
        startUp()
        client.sendDirectText(ann, "where are you")
        val frame = lastSent()
        assertEquals(2, frame[0].toInt())
        assertEquals(0, frame[2].toInt())
        assertArrayEquals(le32(1_800_000_000), frame.copyOfRange(3, 7))
        client.onFrame(sentFrame(0x1234))
        assertTrue(directResults.isEmpty())
        client.onFrame(confirmed(0x1234))
        assertEquals(listOf("where are you" to DirectOutcome.DELIVERED), directResults)
        // A repeated acknowledgement is not reported again.
        client.onFrame(confirmed(0x1234))
        assertEquals(1, directResults.size)
    }

    @Test
    fun anUnacknowledgedDirectMessageIsRetriedWithItsFirstTimestampThenReported() {
        startUp()
        client.sendDirectText(ann, "ping")
        client.onFrame(sentFrame(1))
        now += 10_000
        client.tick()
        val retry = lastSent()
        assertEquals(2, retry[0].toInt())
        assertEquals(1, retry[2].toInt())
        assertArrayEquals(le32(1_800_000_000), retry.copyOfRange(3, 7))
        client.onFrame(sentFrame(2))
        now += 10_000
        client.tick()
        assertEquals(2, lastSent()[2].toInt())
        client.onFrame(sentFrame(3))
        now += 10_000
        client.tick()
        assertEquals(listOf("ping" to DirectOutcome.UNCONFIRMED), directResults)
    }

    @Test
    fun anAcknowledgementOfAnEarlierTryStillCounts() {
        startUp()
        client.sendDirectText(ann, "late")
        client.onFrame(sentFrame(1))
        now += 10_000
        client.tick()
        client.onFrame(sentFrame(2))
        client.onFrame(confirmed(1))
        assertEquals(listOf("late" to DirectOutcome.DELIVERED), directResults)
    }

    @Test
    fun aDirectMessageToANodeTheRadioDoesNotKnowIsRefused() {
        startUp()
        client.sendDirectText(ann, "hello")
        client.onFrame(byteArrayOf(1, 2))
        assertEquals(listOf("hello" to DirectOutcome.REFUSED), directResults)
    }

    @Test
    fun aDirectMessageBeforeStartUpIsRefused() {
        assertEquals(null, client.sendDirectText(ann, "too soon"))
        assertTrue(directResults.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun receivedDirectMessagesAreReportedAndARetriedCopyIsDropped() {
        startUp()
        client.onFrame(byteArrayOf(0x83.toByte()))
        client.onFrame(directText(0x11, 1_700_000_100, "on my way"))
        client.onFrame(directText(0x11, 1_700_000_100, "on my way"))
        client.onFrame(directText(0x11, 1_700_000_200, "on my way"))
        client.onFrame(byteArrayOf(0x0A))
        assertEquals(listOf(1_700_000_100L, 1_700_000_200L), directs.map { it.timestamp })
        assertEquals("111111111111", directs.first().senderPrefixHex)
        assertTrue(log.single().contains("arrived again"))
    }

    @Test
    fun aChannelMessageSentFromAtakThatComesBackIsLoggedAndDropped() {
        startUp()
        client.sendChannelText(0, "on scene")
        client.onFrame(byteArrayOf(0))
        client.onFrame(byteArrayOf(0x83.toByte()))
        client.onFrame(channelText(0, "Radio: on scene"))
        client.onFrame(channelText(0, "Ann: on scene"))
        client.onFrame(channelText(1, "Radio: on scene"))
        client.onFrame(byteArrayOf(0x0A))
        assertEquals(listOf("Ann: on scene", "Radio: on scene"), messages.map { it.text })
        assertEquals(1, messages.last().channelIndex)
        assertTrue(log.single().startsWith("Echo check"))
    }
}
