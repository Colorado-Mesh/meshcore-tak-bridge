package io.github.robertmtalbot.takbridge.takserver

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The smallest part of a TAK server's web API that ATAK asks for, so it stops reporting errors.
 *
 * Besides the CoT stream, ATAK polls a TAK server's web API on port 8080 for the server's
 * contact list (`GET /Marti/api/clientEndPoints`) and shows "Failed to get server contacts" when
 * nothing answers. This answers that request with an empty list, in the form ATAK's
 * `GetClientListOperation` parses: a `type` naming the client-endpoint class and a `data`
 * array. The contacts themselves already reach ATAK through the CoT stream.
 *
 * The reply has no `version` field. ATAK reads the server's API version from it, and the bridge
 * should not claim to support an API it does not provide. Any other request gets 404, and its
 * path is reported once to [onUnknownPath] so that a device test shows what else ATAK wants.
 *
 * Loopback only, plain HTTP, one short request per connection.
 */
class ApiServer(
    private val port: Int = DEFAULT_PORT,
    private val onUnknownPath: (String) -> Unit = {},
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private val reported = HashSet<String>()

    /**
     * Start listening and return the bound port, which differs from [port] only when that is 0.
     *
     * @throws IOException when the port cannot be bound, for example because another app has it.
     */
    fun start(): Int {
        synchronized(lock) {
            serverSocket?.let { return it.localPort }
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(TakServer.LOOPBACK_V4, port), BACKLOG)
            serverSocket = socket
            Thread({ acceptLoop(socket) }, "tak-api-accept").apply { isDaemon = true }.start()
            return socket.localPort
        }
    }

    /** Stop listening. Safe to call more than once. */
    fun stop() {
        val socket = synchronized(lock) { serverSocket.also { serverSocket = null } }
        try {
            socket?.close()
        } catch (e: IOException) {
            // Nothing useful to do: the socket is being discarded.
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            Thread({ handle(socket) }, "tak-api-request").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                if (!it.inetAddress.isLoopbackAddress) return
                it.soTimeout = READ_TIMEOUT_MS
                val head = readHead(it.getInputStream()) ?: return
                val (method, target) = head.lineSequence().first().split(' ').let { parts ->
                    if (parts.size < 2) return else parts[0] to parts[1]
                }
                val path = target.substringBefore('?')
                val (status, body) = respond(method, path)
                if (status == NOT_FOUND) reportOnce("$method $path")
                val bytes = body.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 $status\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = it.getOutputStream()
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                // A client that hung up or stalled; there is nobody to tell.
            }
        }
    }

    private fun reportOnce(what: String) {
        val first = synchronized(reported) { reported.size < MAX_REPORTED && reported.add(what) }
        if (first) onUnknownPath(what)
    }

    /** The request line and headers, up to the blank line, or null when they are too long or cut off. */
    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        while (buffer.length < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buffer.append(b.toChar())
            if (buffer.endsWith("\r\n\r\n") || buffer.endsWith("\n\n")) return buffer.toString()
        }
        return null
    }

    companion object {
        /** The port ATAK uses for a TAK server's plain web API. */
        const val DEFAULT_PORT = 8080

        /** The JSON type ATAK checks for in a client-list reply. */
        const val CLIENT_LIST_TYPE = "com.bbn.marti.remote.ClientEndpoint"

        internal const val OK = "200 OK"
        internal const val NOT_FOUND = "404 Not Found"
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_HEAD_BYTES = 8_192
        private const val MAX_REPORTED = 20

        /** What to answer for [method] and [path], as an HTTP status line and a JSON body. */
        internal fun respond(method: String, path: String): Pair<String, String> =
            if ((method == "GET" || method == "HEAD") && path.trimEnd('/') == "/Marti/api/clientEndPoints") {
                OK to "{\"type\":\"$CLIENT_LIST_TYPE\",\"data\":[]}"
            } else {
                NOT_FOUND to "{}"
            }
    }
}
