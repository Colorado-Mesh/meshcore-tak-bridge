package io.github.robertmtalbot.takbridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionFramesTest {
    private fun le32(value: Int) = ByteArray(4) { (value shr (8 * it)).toByte() }

    private fun padded(text: String, size: Int) = text.toByteArray().copyOf(size)

    @Test
    fun appStartMatchesTheDocumentedExample() {
        val expected = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0x6d, 0x63, 0x63, 0x6c, 0x69)
        assertArrayEquals(expected, CompanionFrames.appStart("mccli"))
    }

    @Test
    fun appStartFitsTheSmallestPacketSize() {
        assertTrue(CompanionFrames.appStart("A very long application name").size <= 20)
    }

    @Test
    fun deviceQueryMatchesTheDocumentedExample() {
        assertArrayEquals(byteArrayOf(0x16, 0x03), CompanionFrames.deviceQuery())
    }

    @Test
    fun readsSelfInfoIncludingAWesternLongitude() {
        val frame = byteArrayOf(5, 1, 20, 22) + ByteArray(32) { it.toByte() } +
            le32(39_739_200) + le32(-104_990_300) + byteArrayOf(0, 0, 0, 0) +
            le32(910_525) + le32(62_500) + byteArrayOf(7, 5) + "Bud's Radio".toByteArray()
        val info = (CompanionFrames.parse(frame) as CompanionFrame.Self).info
        assertEquals("Bud's Radio", info.name)
        assertEquals(39.7392, info.latitude, 1e-9)
        assertEquals(-104.9903, info.longitude, 1e-9)
        assertEquals(910.525, info.frequencyMhz, 1e-9)
        assertEquals(62.5, info.bandwidthKhz, 1e-9)
        assertEquals(7, info.spreadingFactor)
        assertEquals(5, info.codingRate)
        assertEquals(20, info.txPowerDbm)
        assertTrue(info.publicKeyHex.startsWith("000102") && info.publicKeyHex.length == 64)
    }

    @Test
    fun readsDeviceInfoAndIgnoresThePin() {
        val frame = byteArrayOf(0x0D, 9, 175.toByte(), 40) + le32(123456) +
            padded("Oct 2026", 12) + padded("Heltec T114", 40) + padded("v1.12.0", 20) + byteArrayOf(0, 0)
        val info = (CompanionFrames.parse(frame) as CompanionFrame.Device).info
        assertEquals(DeviceInfo(9, 350, 40, "Oct 2026", "Heltec T114", "v1.12.0"), info)
    }

    @Test
    fun anOldFirmwareDeviceInfoStillParses() {
        val info = (CompanionFrames.parse(byteArrayOf(0x0D, 2)) as CompanionFrame.Device).info
        assertEquals(0, info.maxChannels)
    }

    @Test
    fun aTruncatedSelfInfoIsNotMisread() {
        assertEquals(CompanionFrame.Other(5, 20), CompanionFrames.parse(ByteArray(20).also { it[0] = 5 }))
    }

    @Test
    fun readsOkErrorAndUnknownFrames() {
        assertEquals(CompanionFrame.Ok, CompanionFrames.parse(byteArrayOf(0)))
        assertEquals(CompanionFrame.Error(2), CompanionFrames.parse(byteArrayOf(1, 2)))
        assertEquals(CompanionFrame.Error(null), CompanionFrames.parse(byteArrayOf(1)))
        assertEquals(CompanionFrame.Other(0x88, 1), CompanionFrames.parse(byteArrayOf(0x88.toByte())))
        assertNull(CompanionFrames.parse(ByteArray(0)))
    }

    private fun channelFrame(name: String, secret: ByteArray) =
        byteArrayOf(0x12, 3) + name.toByteArray().copyOf(32) + secret

    private fun kindOf(name: String, secret: ByteArray): Channel.Kind? =
        ((CompanionFrames.parse(channelFrame(name, secret)) as CompanionFrame.ChannelSlot).channel)?.kind

    @Test
    fun recognisesThePublicChannelByItsKey() {
        val publicKey = java.util.Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==")
        assertEquals(Channel.Kind.PUBLIC, kindOf("Public", publicKey))
    }

    @Test
    fun recognisesAHashtagChannelByTheKeyItsNameGives() {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("#denver".toByteArray()).copyOf(16)
        assertEquals(Channel.Kind.HASHTAG, kindOf("#denver", key))
    }

    @Test
    fun aHashtagNameWithItsOwnKeyIsPrivate() {
        assertEquals(Channel.Kind.PRIVATE, kindOf("#denver", ByteArray(16) { 9 }))
        assertEquals(Channel.Kind.PRIVATE, kindOf("Team", ByteArray(16) { 9 }))
    }

    private val contactKey = "a1b2c3d4e5f6" + "00".repeat(26)
    private val prefix = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte())

    @Test
    fun sendDirectTextMatchesTheFirmwareLayout() {
        // Code, text type, attempt, timestamp, the recipient's 6-byte key prefix, then the text.
        val expected = byteArrayOf(2, 0, 1) + le32(1_700_000_000) + prefix + "hi".toByteArray()
        assertArrayEquals(expected, CompanionFrames.sendDirectText(contactKey, 1, 1_700_000_000, "hi"))
    }

    @Test
    fun readsADirectMessageInTheNewLayout() {
        val frame = byteArrayOf(0x10, 24, 0, 0) + prefix + byteArrayOf(2, 0) + le32(1_700_000_123) + "Meet at the trailhead".toByteArray()
        val message = (CompanionFrames.parse(frame) as CompanionFrame.DirectText).message
        assertEquals(DirectMessage("a1b2c3d4e5f6", "Meet at the trailhead", 1_700_000_123), message)
    }

    @Test
    fun readsADirectMessageInTheOldLayout() {
        val frame = byteArrayOf(0x07) + ByteArray(6) { 0x11 } + byteArrayOf(0xFF.toByte(), 0) + le32(5) + "ok".toByteArray()
        val message = (CompanionFrames.parse(frame) as CompanionFrame.DirectText).message
        assertEquals("111111111111", message.senderPrefixHex)
        assertEquals("ok", message.text)
    }

    @Test
    fun aSignedRoomServerPostIsNotReadAsADirectMessage() {
        val frame = byteArrayOf(0x10, 0, 0, 0) + ByteArray(6) + byteArrayOf(1, 2) + le32(5) + ByteArray(4) + "post".toByteArray()
        assertEquals(CompanionFrame.OtherMessage(0x10), CompanionFrames.parse(frame))
    }

    @Test
    fun readsSentAndConfirmedFrames() {
        val sent = byteArrayOf(6, 1) + le32(0x0A0B0C0D) + le32(9_000)
        assertEquals(CompanionFrame.Sent(flood = true, ackCode = 0x0A0B0C0D, timeoutMs = 9_000), CompanionFrames.parse(sent))
        val confirmed = byteArrayOf(0x82.toByte()) + le32(0x0A0B0C0D) + le32(1_234)
        assertEquals(CompanionFrame.SendConfirmed(0x0A0B0C0D, 1_234), CompanionFrames.parse(confirmed))
        assertEquals(CompanionFrame.Other(6, 2), CompanionFrames.parse(byteArrayOf(6, 0)))
    }
}
