package org.coloradomesh.takbridge.takserver

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The smallest part of a TAK server's web API that ATAK asks for, so it stops reporting errors.
 *
 * Besides the CoT stream, ATAK asks a TAK server's web API on port 8080 for a few things each
 * time it connects, and shows errors such as "Failed to get server contacts" or "Failed to get
 * server groups" when they fail. [respond] lists what is answered; every answer is empty. The
 * contacts and channels themselves reach ATAK through the CoT stream.
 *
 * The contact-list reply has no `version` field. ATAK reads the server's API version from it,
 * and the bridge should not claim to support an API it does not provide. Any other request gets 404, and its
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
                val type = if (bytes.isEmpty()) "" else "Content-Type: application/json; charset=utf-8\r\n"
                val header = "HTTP/1.1 $status\r\n" +
                    type +
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

        /** The JSON type ATAK checks for in a group-list reply. */
        const val GROUP_LIST_TYPE = "com.bbn.marti.remote.groups.Group"

        /**
         * An empty list of Data Sync missions, which newer ATAK shows as "feeds". This is TAK
         * Server's own wrapper for the list. Its `version` is the wrapper's format version, which
         * TAK Server includes on every mission reply. It is not the server's API version, which
         * ATAK reads only from the contact list.
         */
        internal const val MISSION_LIST =
            "{\"version\":\"3\",\"type\":\"Mission\",\"data\":[],\"nodeId\":\"meshcore-tak-bridge\"}"

        internal const val OK = "200 OK"
        internal const val NO_CONTENT = "204 No Content"
        internal const val NOT_FOUND = "404 Not Found"
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_HEAD_BYTES = 8_192
        private const val MAX_REPORTED = 20
        private val SLASHES = Regex("/+")

        /**
         * What to answer for [method] and [path], as an HTTP status line and a body, for the
         * requests ATAK makes on its own whenever it connects to a server:
         * - the server's contacts: an empty list;
         * - the server's groups (ATAK's Channels feature): an empty list, which ATAK accepts;
         * - the server's Data Sync missions ("feeds" in newer ATAK): an empty list;
         * - a device or tool profile: 204, which ATAK reads as "nothing new", not as an error.
         *
         * The server version is not answered: ATAK only logs that failure, and an answer would
         * claim TAK Server features the bridge lacks.
         */
        internal fun respond(method: String, path: String): Pair<String, String> {
            if (method != "GET" && method != "HEAD") return NOT_FOUND to "{}"
            val clean = path.replace(SLASHES, "/").trimEnd('/')
            return when {
                clean == "/Marti/api/clientEndPoints" -> OK to "{\"type\":\"$CLIENT_LIST_TYPE\",\"data\":[]}"
                clean == "/Marti/api/groups/all" -> OK to "{\"type\":\"$GROUP_LIST_TYPE\",\"data\":[]}"
                clean == "/Marti/api/missions" -> OK to MISSION_LIST
                clean.startsWith("/Marti/api/device/profile/") -> NO_CONTENT to ""
                else -> NOT_FOUND to "{}"
            }
        }
    }
}
