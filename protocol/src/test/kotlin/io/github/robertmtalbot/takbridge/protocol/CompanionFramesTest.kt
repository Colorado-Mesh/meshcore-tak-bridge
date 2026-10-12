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
}
