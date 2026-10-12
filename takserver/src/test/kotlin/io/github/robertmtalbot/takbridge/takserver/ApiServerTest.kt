package io.github.robertmtalbot.takbridge.takserver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class ApiServerTest {
    private val unknown = CopyOnWriteArrayList<String>()
    private val server = ApiServer(port = 0) { unknown.add(it) }

    @After
    fun tearDown() = server.stop()

    /** Sends one raw request and returns the whole reply. */
    private fun request(port: Int, path: String, method: String = "GET"): String =
        Socket(TakServer.LOOPBACK_V4, port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(
                "$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAccept-Encoding: gzip\r\n\r\n".toByteArray(),
            )
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun answersAtaksClientListRequestWithAnEmptyList() {
        val port = server.start()
        val reply = request(port, "/Marti/api/clientEndPoints")
        assertTrue(reply, reply.startsWith("HTTP/1.1 200 OK"))
        val body = reply.substringAfter("\r\n\r\n")
        assertEquals("{\"type\":\"com.bbn.marti.remote.ClientEndpoint\",\"data\":[]}", body)
        assertTrue(reply.contains("Content-Length: ${body.toByteArray().size}"))
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun aQueryStringIsIgnored() {
        val port = server.start()
        assertTrue(request(port, "/Marti/api/clientEndPoints?secago=60").startsWith("HTTP/1.1 200 OK"))
    }

    @Test
    fun claimsNoApiVersionWhereAtakReadsOne() {
        for (path in listOf("/Marti/api/clientEndPoints", "/Marti/api/groups/all")) {
            assertTrue(!ApiServer.respond("GET", path).second.contains("version"))
        }
        assertEquals(ApiServer.NOT_FOUND, ApiServer.respond("GET", "/Marti/api/version/config").first)
        assertEquals(ApiServer.NOT_FOUND, ApiServer.respond("GET", "/Marti/api/version").first)
    }

    @Test
    fun answersAtaksGroupRequestWithAnEmptyList() {
        val port = server.start()
        val reply = request(port, "/Marti/api/groups/all?useCache=true")
        assertTrue(reply, reply.startsWith("HTTP/1.1 200 OK"))
        assertEquals("{\"type\":\"com.bbn.marti.remote.groups.Group\",\"data\":[]}", reply.substringAfter("\r\n\r\n"))
    }

    @Test
    fun answersTheFeedsRequestWithAnEmptyMissionList() {
        val port = server.start()
        val reply = request(port, "/Marti/api/missions")
        assertTrue(reply, reply.startsWith("HTTP/1.1 200 OK"))
        val body = reply.substringAfter("\r\n\r\n")
        assertTrue(body, body.contains("\"type\":\"Mission\"") && body.contains("\"data\":[]"))
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun aDeviceProfileRequestGetsNoContent() {
        val port = server.start()
        val reply = request(port, "/Marti/api/device/profile/connection?syncSecago=-1&clientUid=ANDROID-1")
        assertTrue(reply, reply.startsWith("HTTP/1.1 204 No Content"))
        assertTrue(reply.contains("Content-Length: 0"))
        assertTrue(!reply.contains("Content-Type"))
        assertEquals("", reply.substringAfter("\r\n\r\n"))
    }

    @Test
    fun doubledSlashesInAPathStillMatch() {
        assertEquals(ApiServer.OK, ApiServer.respond("GET", "/Marti//api/groups/all").first)
    }

    @Test
    fun anythingElseIsNotFoundAndReportedOnce() {
        val port = server.start()
        assertTrue(request(port, "/Marti/api/version/config").startsWith("HTTP/1.1 404"))
        request(port, "/Marti/api/version/config")
        assertTrue(request(port, "/Marti/api/clientEndPoints", method = "POST").startsWith("HTTP/1.1 404"))
        assertEquals(listOf("GET /Marti/api/version/config", "POST /Marti/api/clientEndPoints"), unknown.toList())
    }

    @Test
    fun startingTwiceKeepsOnePort() {
        val port = server.start()
        assertEquals(port, server.start())
    }
}
