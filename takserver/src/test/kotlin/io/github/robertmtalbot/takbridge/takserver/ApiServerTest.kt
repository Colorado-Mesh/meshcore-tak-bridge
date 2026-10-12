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
    fun claimsNoApiVersion() {
        val (_, body) = ApiServer.respond("GET", "/Marti/api/clientEndPoints")
        assertTrue(!body.contains("version"))
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
