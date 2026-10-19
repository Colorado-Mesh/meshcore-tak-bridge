package org.coloradomesh.takbridge.protocol

import java.util.ArrayDeque

/** How a direct message handed to [CompanionClient.sendDirectText] ended. */
enum class DirectOutcome {
    /** The recipient acknowledged it. */
    DELIVERED,

    /** The radio sent it, but no acknowledgement came back after every try. It may still have arrived. */
    UNCONFIRMED,

    /** The radio would not send it: not connected yet, too many waiting, or no such contact on the radio. */
    REFUSED,
}

/**
 * Runs the conversation with a companion radio over an open link.
 *
 * The radio answers one command at a time, so commands wait in a queue and each has a time
 * limit. After [start] the client introduces itself, reads the radio's capacity, its contacts
 * and every channel slot it reports, then collects queued messages; from then on it reacts to
 * the radio's own notices of new messages and adverts. It reads the battery once start-up is done
 * and every [BATTERY_INTERVAL_MS] after that.
 *
 * A direct message is sent, then waited on for the recipient's acknowledgement, and sent again
 * up to [MAX_DIRECT_ATTEMPTS] times in all if none comes, as the MeshCore apps do.
 *
 * Two guards keep ATAK from showing a message twice, and each logs what it drops:
 * - a direct message the sender retried after its acknowledgement was lost arrives again with
 *   the same timestamp and text;
 * - a channel message this client sent could in principle come back from the radio. The
 *   firmware marks its own flooded packets as seen, so this is not expected, but if it happens
 *   the log says so.
 *
 * It only reads from the radio and sends text. It never changes the radio's settings,
 * channels, contacts, paths or clock.
 *
 * Thread-safe. Listener calls are made while the client is locked, so they must not block.
 */
class CompanionClient(
    private val transmit: (ByteArray) -> Unit,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onLog(message: String) {}

        fun onSelf(info: SelfInfo) {}

        fun onDevice(info: DeviceInfo) {}

        fun onContact(contact: Contact) {}

        /** Every programmed channel, once all slots have been read. */
        fun onChannels(channels: List<Channel>) {}

        fun onChannelMessage(message: ChannelMessage) {}

        fun onDirectMessage(message: DirectMessage) {}

        /** The radio accepted, or did not accept, a message handed to [sendChannelText]. */
        fun onSendResult(channelIndex: Int, text: String, accepted: Boolean) {}

        /** How the direct message [id] from [sendDirectText] ended. Called once per message. */
        fun onDirectResult(id: Long, publicKeyHex: String, text: String, outcome: DirectOutcome) {}

        /** The radio's battery voltage, at start-up and then every [BATTERY_INTERVAL_MS]; zero if it cannot measure it. */
        fun onBattery(millivolts: Int) {}
    }

    private sealed interface Job {
        data object AppStart : Job
        data object DeviceQuery : Job
        data object Contacts : Job
        data class GetChannel(val index: Int) : Job
        data object Sync : Job
        data class SendText(val index: Int, val text: String) : Job
        data class SendDirect(val id: Long) : Job
        data object Battery : Job
    }

    /** A direct message from the first try until it is acknowledged or given up on. */
    private class Outbound(val publicKeyHex: String, val text: String, val timestamp: Long) {
        var attempt = 0

        /** Acknowledgement codes of every try so far; any of them confirms the message. */
        val acks = HashSet<Long>()

        /** When to give up waiting for this try's acknowledgement; 0 while the try is not yet sent. */
        var ackDeadline = 0L
    }

    private val queue = ArrayDeque<Job>()
    private var current: Job? = null
    private var deadline = 0L
    private var contactsSince = 0L
    private var slotsLeft = 0
    private val channels = ArrayList<Channel>()
    private var ready = false
    private var warnedAboutOtherMessages = false
    private var selfName = ""
    private var nextDirectId = 1L

    /** When to read the battery next; firmware that refuses the request is not asked again. */
    private var nextBatteryAt = Long.MAX_VALUE
    private var batteryRefused = false
    private val outbound = LinkedHashMap<Long, Outbound>()
    private val sentOnChannels = RecentKeys(ECHO_MEMORY, ECHO_WINDOW_MS, clock)
    private val directsHeard = RecentKeys(ECHO_MEMORY, ECHO_WINDOW_MS, clock)

    @Synchronized
    fun start() {
        enqueue(Job.AppStart)
    }

    /** Queue a channel message. The result arrives at [Listener.onSendResult]. */
    @Synchronized
    fun sendChannelText(channelIndex: Int, text: String) {
        if (!ready || queue.count { it is Job.SendText } >= MAX_QUEUED_SENDS) {
            listener.onSendResult(channelIndex, text, false)
            return
        }
        enqueue(Job.SendText(channelIndex, text))
    }

    /**
     * Queue a direct message to the contact with [publicKeyHex] (at least its first 12 hex
     * characters). Returns the id that [Listener.onDirectResult] will report it under, or null,
     * with no listener call, when it cannot be queued: start-up is not finished or too many wait.
     */
    @Synchronized
    fun sendDirectText(publicKeyHex: String, text: String): Long? {
        if (!ready || outbound.size >= MAX_OUTSTANDING_DIRECTS || publicKeyHex.length < CompanionFrames.KEY_PREFIX_BYTES * 2) {
            return null
        }
        val id = nextDirectId++
        outbound[id] = Outbound(publicKeyHex, text, clock() / 1000)
        enqueue(Job.SendDirect(id))
        return id
    }

    /**
     * Call about once a second. A command the radio never answers is given up on, and a direct
     * message whose acknowledgement is overdue is tried again or reported unconfirmed.
     */
    @Synchronized
    fun tick() {
        val now = clock()
        current?.let { job ->
            if (now >= deadline) {
                listener.onLog("No answer from the radio to ${describe(job)}")
                finish(job, answered = false)
            }
        }
        for ((id, message) in outbound.entries.toList()) {
            if (message.ackDeadline == 0L || now < message.ackDeadline) continue
            if (message.attempt + 1 < MAX_DIRECT_ATTEMPTS) {
                message.attempt++
                message.ackDeadline = 0L
                listener.onLog("No acknowledgement yet for a direct message; sending it again (try ${message.attempt + 1} of $MAX_DIRECT_ATTEMPTS)")
                enqueue(Job.SendDirect(id))
            } else {
                outbound.remove(id)
                listener.onDirectResult(id, message.publicKeyHex, message.text, DirectOutcome.UNCONFIRMED)
            }
        }
        if (ready && !batteryRefused && now >= nextBatteryAt) requestBattery()
    }

    private fun requestBattery() {
        nextBatteryAt = clock() + BATTERY_INTERVAL_MS
        enqueueOnce(Job.Battery)
    }

    @Synchronized
    fun onFrame(bytes: ByteArray) {
        val frame = CompanionFrames.parse(bytes) ?: return
        when (frame) {
            CompanionFrame.MessagesWaiting -> if (ready) enqueueOnce(Job.Sync)
            CompanionFrame.AdvertHeard -> if (ready) enqueueOnce(Job.Contacts)
            is CompanionFrame.SendConfirmed -> confirm(frame)
            is CompanionFrame.ContactEntry -> {
                listener.onContact(frame.contact)
                if (!frame.pushed && current == Job.Contacts) extend()
            }
            // Codes from 0x80 up are notices the radio sends unasked; the rest of them are not needed.
            is CompanionFrame.Other -> if (frame.code < PUSH_CODES_START) answer(frame)
            else -> answer(frame)
        }
    }

    /** An acknowledgement can arrive at any time, and more than once; only the first counts. */
    private fun confirm(frame: CompanionFrame.SendConfirmed) {
        val entry = outbound.entries.firstOrNull { frame.ackCode in it.value.acks } ?: return
        outbound.remove(entry.key)
        queue.removeAll { it == Job.SendDirect(entry.key) }
        listener.onLog("Direct message acknowledged after ${frame.tripMs} ms")
        listener.onDirectResult(entry.key, entry.value.publicKeyHex, entry.value.text, DirectOutcome.DELIVERED)
    }

    private fun answer(frame: CompanionFrame) {
        val job = current
        if (job == null) {
            listener.onLog("Unexpected frame from the radio: ${frame::class.simpleName}")
            return
        }
        if (frame is CompanionFrame.Error) {
            // An empty or missing channel slot is reported as an error, which is not worth a log line.
            if (job !is Job.GetChannel) listener.onLog("The radio refused ${describe(job)} (error ${frame.code})")
            if (job == Job.Battery) batteryRefused = true
            finish(job, answered = false)
            return
        }
        when (job) {
            Job.AppStart -> if (frame is CompanionFrame.Self) {
                selfName = frame.info.name
                listener.onSelf(frame.info)
                finish(job)
                enqueue(Job.DeviceQuery)
            }
            Job.DeviceQuery -> if (frame is CompanionFrame.Device) {
                listener.onDevice(frame.info)
                finish(job)
                slotsLeft = frame.info.maxChannels.takeIf { it > 0 } ?: LEGACY_CHANNEL_SLOTS
                channels.clear()
                enqueue(Job.Contacts)
                for (index in 0 until slotsLeft) enqueue(Job.GetChannel(index))
                enqueue(Job.Sync)
            }
            Job.Contacts -> when (frame) {
                CompanionFrame.ContactsStart -> extend()
                is CompanionFrame.ContactsEnd -> {
                    if (frame.lastModified > contactsSince) contactsSince = frame.lastModified
                    finish(job)
                }
                else -> Unit
            }
            is Job.GetChannel -> if (frame is CompanionFrame.ChannelSlot) {
                frame.channel?.let { channels.add(it) }
                finish(job)
            }
            Job.Sync -> when (frame) {
                is CompanionFrame.ChannelText -> {
                    receiveChannel(frame.message)
                    finish(job)
                    enqueueOnce(Job.Sync)
                }
                is CompanionFrame.DirectText -> {
                    receiveDirect(frame.message)
                    finish(job)
                    enqueueOnce(Job.Sync)
                }
                is CompanionFrame.OtherMessage -> {
                    if (!warnedAboutOtherMessages) {
                        warnedAboutOtherMessages = true
                        listener.onLog("The radio has room server posts or data; the bridge carries plain text only")
                    }
                    finish(job)
                    enqueueOnce(Job.Sync)
                }
                CompanionFrame.NoMoreMessages -> finish(job)
                else -> Unit
            }
            is Job.SendText -> if (frame == CompanionFrame.Ok) {
                sentOnChannels.add(channelKey(job.index, "$selfName: ${job.text}"))
                finish(job)
            }
            is Job.SendDirect -> if (frame is CompanionFrame.Sent) {
                outbound[job.id]?.let { message ->
                    message.acks.add(frame.ackCode)
                    message.ackDeadline = clock() + frame.timeoutMs.coerceIn(MIN_ACK_WAIT_MS, MAX_ACK_WAIT_MS) + ACK_MARGIN_MS
                    val route = if (frame.flood) "flooded to the mesh" else "sent along a known path"
                    listener.onLog("Direct message $route (try ${message.attempt + 1}); waiting for acknowledgement")
                }
                finish(job)
            }
            Job.Battery -> if (frame is CompanionFrame.Battery) {
                listener.onBattery(frame.millivolts)
                finish(job)
            }
        }
    }

    private fun receiveChannel(message: ChannelMessage) {
        if (sentOnChannels.contains(channelKey(message.channelIndex, message.text))) {
            listener.onLog("Echo check: a message sent from ATAK came back on channel ${message.channelIndex}; not shown again")
            return
        }
        listener.onChannelMessage(message)
    }

    private fun receiveDirect(message: DirectMessage) {
        val key = "${message.senderPrefixHex}|${message.timestamp}|${message.text}"
        if (directsHeard.contains(key)) {
            listener.onLog("Direct message from ${message.senderPrefixHex} arrived again (a retry); not shown twice")
            return
        }
        directsHeard.add(key)
        listener.onDirectMessage(message)
    }

    private fun channelKey(index: Int, text: String) = "$index|$text"

    /** Ends [job], whether the radio answered it or not, and starts the next one. */
    private fun finish(job: Job, answered: Boolean = true) {
        current = null
        when (job) {
            is Job.SendText -> listener.onSendResult(job.index, job.text, answered)
            is Job.SendDirect -> if (!answered) {
                outbound.remove(job.id)?.let { listener.onDirectResult(job.id, it.publicKeyHex, it.text, DirectOutcome.REFUSED) }
            }
            is Job.GetChannel -> {
                slotsLeft--
                if (slotsLeft == 0) {
                    ready = true
                    listener.onChannels(channels.sortedBy { it.index })
                    requestBattery()
                }
            }
            else -> Unit
        }
        pump()
    }

    private fun enqueue(job: Job) {
        queue.add(job)
        pump()
    }

    private fun enqueueOnce(job: Job) {
        if (current != job && job !in queue) enqueue(job)
    }

    private fun extend() {
        deadline = clock() + TIMEOUT_MS
    }

    private fun pump() {
        while (current == null) {
            val job = queue.poll() ?: return
            // A message acknowledged while its retry was waiting needs no retry.
            if (job is Job.SendDirect && job.id !in outbound) continue
            current = job
            extend()
            transmit(frameFor(job))
        }
    }

    private fun frameFor(job: Job): ByteArray = when (job) {
        Job.AppStart -> CompanionFrames.appStart(APP_NAME)
        Job.DeviceQuery -> CompanionFrames.deviceQuery()
        Job.Contacts -> CompanionFrames.getContacts(contactsSince)
        is Job.GetChannel -> CompanionFrames.getChannel(job.index)
        Job.Sync -> CompanionFrames.syncNextMessage()
        is Job.SendText -> CompanionFrames.sendChannelText(job.index, clock() / 1000, job.text)
        is Job.SendDirect -> {
            val message = outbound.getValue(job.id)
            CompanionFrames.sendDirectText(message.publicKeyHex, message.attempt, message.timestamp, message.text)
        }
        Job.Battery -> CompanionFrames.getBattery()
    }

    private fun describe(job: Job): String = when (job) {
        Job.AppStart -> "the opening greeting"
        Job.DeviceQuery -> "the device query"
        Job.Contacts -> "the contact list request"
        is Job.GetChannel -> "the request for channel ${job.index}"
        Job.Sync -> "the request for waiting messages"
        is Job.SendText -> "a message for channel ${job.index}"
        is Job.SendDirect -> "a direct message"
        Job.Battery -> "the battery request"
    }

    companion object {
        /** Tries in all for a direct message, the first included. */
        const val MAX_DIRECT_ATTEMPTS = 3

        /** How often the battery is read after the first time. */
        const val BATTERY_INTERVAL_MS = 5 * 60_000L

        private const val APP_NAME = "TAKBridge"
        private const val TIMEOUT_MS = 6_000L
        private const val MAX_QUEUED_SENDS = 5
        private const val MAX_OUTSTANDING_DIRECTS = 5
        private const val PUSH_CODES_START = 0x80

        /** Bounds on the radio's own estimate of how long an acknowledgement takes, plus slack. */
        private const val MIN_ACK_WAIT_MS = 2_000L
        private const val MAX_ACK_WAIT_MS = 60_000L
        private const val ACK_MARGIN_MS = 2_000L

        /** How many recent messages, and for how long, the duplicate and echo guards remember. */
        private const val ECHO_MEMORY = 64
        private const val ECHO_WINDOW_MS = 15 * 60_000L

        /** Slots to try on firmware too old to say how many it has. */
        private const val LEGACY_CHANNEL_SLOTS = 8
    }
}

/** Keys seen recently: at most [capacity] of them, each forgotten after [maxAgeMs]. Not thread-safe. */
internal class RecentKeys(private val capacity: Int, private val maxAgeMs: Long, private val clock: () -> Long) {
    private val seen = LinkedHashMap<String, Long>()

    fun add(key: String) {
        seen.remove(key)
        seen[key] = clock()
        trim()
    }

    operator fun contains(key: String): Boolean {
        trim()
        return key in seen
    }

    private fun trim() {
        val oldest = clock() - maxAgeMs
        val entries = seen.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (seen.size > capacity || entry.value < oldest) entries.remove() else break
        }
    }
}
