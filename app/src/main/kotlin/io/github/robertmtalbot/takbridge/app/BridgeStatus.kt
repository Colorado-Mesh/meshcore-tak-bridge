package io.github.robertmtalbot.takbridge.app

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet

/** What the status screen shows. */
data class BridgeState(
    val running: Boolean = false,
    val port: Int = 0,
    val clientConnected: Boolean = false,
    /** One line on the radio link, for the status screen. */
    val radio: String = "No radio",
    /** True while the bridge has a radio it is connected to or is trying to reach. */
    val radioBusy: Boolean = false,
    val log: List<String> = emptyList(),
)

/**
 * The bridge's current state and a short activity log, shared in-process between the service
 * that owns the bridge and the screen that shows it. Listeners are called on whichever thread
 * made the change, so a listener that touches views must hop to the main thread itself.
 */
object BridgeStatus {
    private const val MAX_LOG_LINES = 200
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val listeners = CopyOnWriteArraySet<(BridgeState) -> Unit>()
    private val lock = Any()
    private var state = BridgeState()

    fun current(): BridgeState = synchronized(lock) { state }

    fun addListener(listener: (BridgeState) -> Unit) {
        listeners.add(listener)
        listener(current())
    }

    fun removeListener(listener: (BridgeState) -> Unit) {
        listeners.remove(listener)
    }

    fun update(change: (BridgeState) -> BridgeState) {
        val next = synchronized(lock) {
            state = change(state)
            state
        }
        for (listener in listeners) listener(next)
    }

    fun log(message: String) {
        val line = "${LocalTime.now().format(clockFormat)}  $message"
        update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }
}
