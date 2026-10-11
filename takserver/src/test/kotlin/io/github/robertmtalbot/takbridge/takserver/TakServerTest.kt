package io.github.robertmtalbot.takbridge.takserver

import io.github.robertmtalbot.takbridge.cot.ChatSpec
import io.github.robertmtalbot.takbridge.cot.CotEvents
import io.github.robertmtalbot.takbridge.cot.InboundChat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TakServerTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")
    private var server: TakServer? = null
    private val sockets = ArrayList<Socket>()

    /** Records what the server reports, with latches the tests wait on. */
    private class Recorder : TakServerListener {
        val chats = LinkedBlockingQueue<InboundChat>()
        val notices = CopyOnWriteArrayList<String>()
        val connected = LinkedBlockingQueue<Unit>()
        val disconnected = CountDownLatch(1)
        var onConnect: () -> Unit = {}

        override fun onClientConnected() {
            onConnect()
            connected.add(Unit)
        }

        override fun onClientDisconnected() = disconnected.countDown()

        override fun onChat(chat: InboundChat) {
            chats.add(chat)
        }

        override fun onNotice(message: String) {
            notices.add(message)
        }
    }

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        server?.stop()
    }

    private fun start(recorder: Recorder, maxFrameBytes: Int = 64 * 1024, chatLimit: Int = 10): Int {
        val created = TakServer(recorder, port = 0, clock = { now }, maxFrameBytes = maxFrameBytes, chatLimit = chatLimit)
        server = created
        return created.start()
    }

    private fun connect(port: Int): Socket {
        val socket = Socket(InetAddress.getLoopbackAddress(), port)
        socket.soTimeout = 3000
        sockets.add(socket)
        return socket
    }

    private fun Socket.send(text: String) {
        getOutputStream().write(text.toByteArray(Charsets.UTF_8))
        getOutputStream().flush()
    }

    private fun Socket.lines() = BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))

    private fun ping() = "<event version=\"2.0\" uid=\"ANDROID-1-ping\" type=\"t-x-c-t\" " +
        "time=\"2026-10-11T12:00:00Z\" start=\"2026-10-11T12:00:00Z\" stale=\"2026-10-11T12:00:10Z\" " +
        "how=\"m-g\"><point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"9999999\" le=\"9999999\"/></event>"

    private fun chat(text: String) =
        CotEvents.geoChat(ChatSpec("Mesh Ops", "ANDROID-1", "VIPER", text, "m-${text.length}", now), now)

    private fun awaitConnected(recorder: Recorder) {
        assertNotNull("client was not accepted", recorder.connected.poll(3, TimeUnit.SECONDS))
    }

    /**
     * Wait for a notice containing [text]. The server reports on its own threads, so a notice
     * can arrive a moment after the socket activity that the test observed.
     */
    private fun awaitNotice(recorder: Recorder, text: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (recorder.notices.any { it.contains(text) }) return
            Thread.sleep(10)
        }
        throw AssertionError("no notice containing \"$text\"; got ${recorder.notices}")
    }

    /**
     * Read until the server closes the connection. A close while the server still holds unread
     * bytes from the client reaches the client as a reset, not a clean end of stream.
     */
    private fun Socket.awaitClosedByServer() {
        try {
            while (getInputStream().read() >= 0) {
                // Discard anything sent before the close.
            }
        } catch (expected: SocketException) {
            // Connection reset: the server closed it.
        }
    }

    @Test
    fun listensOnLoopbackAndReportsItsPort() {
        val port = start(Recorder())
        assertTrue(port > 0)
        assertTrue(server!!.isRunning)
        assertFalse(server!!.hasClient)
        assertEquals(port, server!!.start())
    }

    @Test
    fun sendsTheSnapshotWrittenFromTheConnectCallbackOneEventPerLine() {
        val recorder = Recorder()
        recorder.onConnect = {
            server!!.send("<event uid=\"A\"></event>")
            server!!.send("<event uid=\"B\"></event>")
        }
        val client = connect(start(recorder))
        val reader = client.lines()
        assertEquals("<event uid=\"A\"></event>", reader.readLine())
        assertEquals("<event uid=\"B\"></event>", reader.readLine())
        assertTrue(server!!.hasClient)
    }

    @Test
    fun answersAPingWithAPong() {
        val recorder = Recorder()
        val client = connect(start(recorder))
        awaitConnected(recorder)
        client.send(ping())
        val line = client.lines().readLine()
        assertTrue(line, line.startsWith("<event ") && line.contains("type=\"t-x-c-t-r\""))
        assertTrue(recorder.chats.isEmpty())
    }

    @Test
    fun handsAChatMessageToTheListenerEvenWhenSplitAcrossWrites() {
        val recorder = Recorder()
        val client = connect(start(recorder))
        awaitConnected(recorder)
        val event = chat("on scene")
        client.send(event.substring(0, 40))
        Thread.sleep(50)
        client.send(event.substring(40) + "\n" + ping())
        val received = recorder.chats.poll(3, TimeUnit.SECONDS)
        assertEquals(InboundChat("Mesh Ops", "ANDROID-1", "VIPER", "on scene", "m-8"), received)
        assertTrue(client.lines().readLine().contains("t-x-c-t-r"))
    }

    @Test
    fun ignoresEventsItHasNoUseFor() {
        val recorder = Recorder()
        val client = connect(start(recorder))
        awaitConnected(recorder)
        client.send("<event version=\"2.0\" uid=\"ANDROID-1\" type=\"a-f-G-U-C\"><point lat=\"1\" lon=\"1\"/></event>")
        client.send(ping())
        assertTrue(client.lines().readLine().contains("t-x-c-t-r"))
        assertTrue(recorder.chats.isEmpty())
        assertTrue(recorder.notices.isEmpty())
    }

    @Test
    fun refusesASecondClientWhileOneIsConnected() {
        val recorder = Recorder()
        val port = start(recorder)
        val first = connect(port)
        awaitConnected(recorder)
        val second = connect(port)
        second.awaitClosedByServer()
        awaitNotice(recorder, "second TAK connection")
        first.send(ping())
        assertTrue(first.lines().readLine().contains("t-x-c-t-r"))
    }

    @Test
    fun acceptsANewClientAfterTheFirstLeaves() {
        val recorder = Recorder()
        val port = start(recorder)
        val first = connect(port)
        awaitConnected(recorder)
        first.close()
        assertTrue(recorder.disconnected.await(3, TimeUnit.SECONDS))
        assertFalse(server!!.hasClient)
        val second = connect(port)
        awaitConnected(recorder)
        second.send(ping())
        assertTrue(second.lines().readLine().contains("t-x-c-t-r"))
    }

    @Test
    fun dropsAClientThatSendsAnOversizedEvent() {
        val recorder = Recorder()
        val client = connect(start(recorder, maxFrameBytes = 512))
        awaitConnected(recorder)
        client.send("<event uid=\"A\">" + "x".repeat(2000))
        client.awaitClosedByServer()
        assertTrue(recorder.disconnected.await(3, TimeUnit.SECONDS))
        awaitNotice(recorder, "oversized")
    }

    @Test
    fun dropsChatPastTheRateLimit() {
        val recorder = Recorder()
        val client = connect(start(recorder, chatLimit = 2))
        awaitConnected(recorder)
        client.send(chat("a") + chat("bb") + chat("ccc") + ping())
        assertTrue(client.lines().readLine().contains("t-x-c-t-r"))
        assertEquals(listOf("a", "bb"), listOf(recorder.chats.poll()?.text, recorder.chats.poll()?.text))
        assertNull(recorder.chats.poll())
        awaitNotice(recorder, "rate limit")
    }

    @Test
    fun sendReportsFalseWithNoClientAndStopDisconnectsTheClient() {
        val recorder = Recorder()
        val port = start(recorder)
        assertFalse(server!!.send("<event></event>"))
        val client = connect(port)
        awaitConnected(recorder)
        assertTrue(server!!.send("<event></event>"))
        server!!.stop()
        assertFalse(server!!.isRunning)
        assertTrue(recorder.disconnected.await(3, TimeUnit.SECONDS))
        val reader = client.lines()
        assertEquals("<event></event>", reader.readLine())
        assertNull(reader.readLine())
        server!!.stop()
    }

    @Test
    fun staysQuietWhenTheClientIsIdle() {
        val recorder = Recorder()
        val client = connect(start(recorder))
        awaitConnected(recorder)
        client.soTimeout = 300
        try {
            client.getInputStream().read()
            throw AssertionError("the server sent data to an idle client")
        } catch (expected: SocketTimeoutException) {
            assertTrue(server!!.hasClient)
        }
    }
}
