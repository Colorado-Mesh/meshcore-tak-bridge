package io.github.robertmtalbot.takbridge.takserver

import io.github.robertmtalbot.takbridge.cot.CotEvents
import io.github.robertmtalbot.takbridge.cot.CotFrameTooLargeException
import io.github.robertmtalbot.takbridge.cot.CotFramer
import io.github.robertmtalbot.takbridge.cot.CotParser
import io.github.robertmtalbot.takbridge.cot.InboundChat
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.time.Instant

/** What the server reports. Callbacks run on the server's own threads and must not block. */
interface TakServerListener {
    /** A client connected. Send it the current picture from here with [TakServer.send]. */
    fun onClientConnected() {}

    fun onClientDisconnected() {}

    /** The client sent an event other than a keepalive ping. Chat is also reported to [onChat]. */
    fun onEvent(xml: String) {}

    /** The connected client sent a chat message. */
    fun onChat(chat: InboundChat) {}

    /** Something worth showing the user, such as a refused connection or a dropped message. */
    fun onNotice(message: String) {}
}

/**
 * A minimal TAK server for one ATAK instance on the same device.
 *
 * It listens on the loopback address only, accepts a single client at a time, answers the
 * client's keepalive pings, and hands chat messages to the listener. Everything it sends is a
 * Cursor on Target event the caller built; it adds only the newline between events.
 */
class TakServer(
    private val listener: TakServerListener,
    private val port: Int = DEFAULT_PORT,
    private val clock: () -> Instant = Instant::now,
    private val maxFrameBytes: Int = CotFramer.DEFAULT_MAX_FRAME_BYTES,
    private val chatLimit: Int = DEFAULT_CHAT_LIMIT,
    private val chatWindow: Duration = DEFAULT_CHAT_WINDOW,
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var client: Socket? = null
    private var clientOut: OutputStream? = null

    val isRunning: Boolean
        get() = synchronized(lock) { serverSocket != null }

    val hasClient: Boolean
        get() = synchronized(lock) { client != null }

    /**
     * Start listening and return the bound port, which differs from [port] only when that is 0.
     *
     * @throws IOException when the port cannot be bound, for example because it is in use.
     */
    fun start(): Int {
        synchronized(lock) {
            serverSocket?.let { return it.localPort }
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK_V4, port), 1)
            serverSocket = socket
            Thread({ acceptLoop(socket) }, "tak-server-accept").apply { isDaemon = true }.start()
            return socket.localPort
        }
    }

    /** Stop listening and drop the client, if any. Safe to call more than once. */
    fun stop() {
        val (server, current) = synchronized(lock) {
            val pair = Pair(serverSocket, client)
            serverSocket = null
            pair
        }
        closeQuietly(server)
        closeQuietly(current)
    }

    /**
     * Send one event to the connected client.
     *
     * @return false when no client is connected or the write failed.
     */
    fun send(event: String): Boolean {
        val out = synchronized(lock) { clientOut } ?: return false
        return try {
            synchronized(out) {
                out.write((event + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            if (!socket.inetAddress.isLoopbackAddress) {
                closeQuietly(socket)
                listener.onNotice("Refused a connection from outside this device")
                continue
            }
            val accepted = synchronized(lock) {
                if (serverSocket !== server || client != null) {
                    false
                } else {
                    client = socket
                    clientOut = socket.getOutputStream()
                    true
                }
            }
            if (!accepted) {
                closeQuietly(socket)
                listener.onNotice("Refused a second TAK connection; one is already active")
                continue
            }
            Thread({ serve(socket) }, "tak-server-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        val framer = CotFramer(maxFrameBytes)
        val limiter = RateLimiter(chatLimit, chatWindow)
        try {
            socket.tcpNoDelay = true
            listener.onClientConnected()
            val input = socket.getInputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (frame in framer.push(buffer, read)) handleFrame(frame, limiter)
            }
        } catch (e: CotFrameTooLargeException) {
            listener.onNotice("TAK client sent an oversized event; disconnected")
        } catch (e: IOException) {
            // The client went away or stop() closed the socket; the finally block reports it.
        } finally {
            synchronized(lock) {
                if (client === socket) {
                    client = null
                    clientOut = null
                }
            }
            closeQuietly(socket)
            listener.onClientDisconnected()
        }
    }

    private fun handleFrame(frame: String, limiter: RateLimiter) {
        val now = clock()
        if (CotParser.isPing(frame)) {
            send(CotEvents.pong(now))
            return
        }
        listener.onEvent(frame)
        val chat = CotParser.parseGeoChat(frame) ?: return
        if (!limiter.tryAcquire(now)) {
            listener.onNotice("Chat rate limit reached; a message from ATAK was dropped")
            return
        }
        listener.onChat(chat)
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (e: IOException) {
            // Nothing useful to do: the resource is being discarded.
        }
    }

    companion object {
        /** The port ATAK conventionally uses for a plain TCP streaming connection. */
        const val DEFAULT_PORT = 8087
        /** Chat messages the client may send per window; a mesh channel carries far less. */
        const val DEFAULT_CHAT_LIMIT = 10
        val DEFAULT_CHAT_WINDOW: Duration = Duration.ofMinutes(1)
        /**
         * The IPv4 loopback address, spelled out. `InetAddress.getLoopbackAddress()` is `::1` on
         * Android, and a server bound there refuses a client that dials 127.0.0.1.
         */
        val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val READ_BUFFER_BYTES = 8192
    }
}
