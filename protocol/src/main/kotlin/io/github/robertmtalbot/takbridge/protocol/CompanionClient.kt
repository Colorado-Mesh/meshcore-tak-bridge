package io.github.robertmtalbot.takbridge.protocol

import java.util.ArrayDeque

/**
 * Runs the conversation with a companion radio over an open link.
 *
 * The radio answers one command at a time, so commands wait in a queue and each has a time
 * limit. After [start] the client introduces itself, reads the radio's capacity, its contacts
 * and every channel slot it reports, then collects queued messages; from then on it reacts to
 * the radio's own notices of new messages and adverts.
 *
 * It only reads from the radio and sends channel text. It never changes the radio's settings,
 * channels, contacts or clock.
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

        /** The radio accepted, or did not accept, a message handed to [sendChannelText]. */
        fun onSendResult(channelIndex: Int, text: String, accepted: Boolean) {}
    }

    private sealed interface Job {
        data object AppStart : Job
        data object DeviceQuery : Job
        data object Contacts : Job
        data class GetChannel(val index: Int) : Job
        data object Sync : Job
        data class SendText(val index: Int, val text: String) : Job
    }

    private val queue = ArrayDeque<Job>()
    private var current: Job? = null
    private var deadline = 0L
    private var contactsSince = 0L
    private var slotsLeft = 0
    private val channels = ArrayList<Channel>()
    private var ready = false
    private var warnedAboutOtherMessages = false

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

    /** Call about once a second so that a command the radio never answers is given up on. */
    @Synchronized
    fun tick() {
        val job = current ?: return
        if (clock() < deadline) return
        listener.onLog("No answer from the radio to ${describe(job)}")
        finish(job, answered = false)
    }

    @Synchronized
    fun onFrame(bytes: ByteArray) {
        val frame = CompanionFrames.parse(bytes) ?: return
        when (frame) {
            CompanionFrame.MessagesWaiting -> if (ready) enqueueOnce(Job.Sync)
            CompanionFrame.AdvertHeard -> if (ready) enqueueOnce(Job.Contacts)
            is CompanionFrame.ContactEntry -> {
                listener.onContact(frame.contact)
                if (!frame.pushed && current == Job.Contacts) extend()
            }
            // Codes from 0x80 up are notices the radio sends unasked; the rest of them are not needed.
            is CompanionFrame.Other -> if (frame.code < PUSH_CODES_START) answer(frame)
            else -> answer(frame)
        }
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
            finish(job, answered = false)
            return
        }
        when (job) {
            Job.AppStart -> if (frame is CompanionFrame.Self) {
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
                    listener.onChannelMessage(frame.message)
                    finish(job)
                    enqueueOnce(Job.Sync)
                }
                is CompanionFrame.OtherMessage -> {
                    if (!warnedAboutOtherMessages) {
                        warnedAboutOtherMessages = true
                        listener.onLog("The radio has direct messages or data; the bridge carries channel text only")
                    }
                    finish(job)
                    enqueueOnce(Job.Sync)
                }
                CompanionFrame.NoMoreMessages -> finish(job)
                else -> Unit
            }
            is Job.SendText -> if (frame == CompanionFrame.Ok) finish(job)
        }
    }

    /** Ends [job], whether the radio answered it or not, and starts the next one. */
    private fun finish(job: Job, answered: Boolean = true) {
        current = null
        when (job) {
            is Job.SendText -> listener.onSendResult(job.index, job.text, answered)
            is Job.GetChannel -> {
                slotsLeft--
                if (slotsLeft == 0) {
                    ready = true
                    listener.onChannels(channels.sortedBy { it.index })
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
        if (current != null) return
        val job = queue.poll() ?: return
        current = job
        extend()
        transmit(
            when (job) {
                Job.AppStart -> CompanionFrames.appStart(APP_NAME)
                Job.DeviceQuery -> CompanionFrames.deviceQuery()
                Job.Contacts -> CompanionFrames.getContacts(contactsSince)
                is Job.GetChannel -> CompanionFrames.getChannel(job.index)
                Job.Sync -> CompanionFrames.syncNextMessage()
                is Job.SendText -> CompanionFrames.sendChannelText(job.index, clock() / 1000, job.text)
            },
        )
    }

    private fun describe(job: Job): String = when (job) {
        Job.AppStart -> "the opening greeting"
        Job.DeviceQuery -> "the device query"
        Job.Contacts -> "the contact list request"
        is Job.GetChannel -> "the request for channel ${job.index}"
        Job.Sync -> "the request for waiting messages"
        is Job.SendText -> "a message for channel ${job.index}"
    }

    private companion object {
        const val APP_NAME = "TAKBridge"
        const val TIMEOUT_MS = 6_000L
        const val MAX_QUEUED_SENDS = 5
        const val PUSH_CODES_START = 0x80

        /** Slots to try on firmware too old to say how many it has. */
        const val LEGACY_CHANNEL_SLOTS = 8
    }
}
