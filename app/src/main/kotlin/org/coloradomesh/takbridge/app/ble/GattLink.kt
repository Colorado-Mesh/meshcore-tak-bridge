package org.coloradomesh.takbridge.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.util.ArrayDeque
import java.util.UUID

/**
 * One Bluetooth connection to a MeshCore companion radio, from connect to close.
 *
 * Android's Bluetooth stack accepts one operation at a time and reports each result later, and
 * on some devices a result never comes. So every step here waits for the one before it, every
 * step has a time limit, and every step is logged. Everything runs on one private thread.
 *
 * The order is connect, discover services, ask for a larger packet size, turn on notifications.
 * The packet size request is allowed to go unanswered: the link carries on at the default size
 * rather than waiting forever, which is where some tablets were seen to stall.
 *
 * Connecting waits a moment first and is tried up to [CONNECT_TRIES] times. Android, Samsung
 * devices especially, often fails a first connection with status 133, and more so straight
 * after a scan has stopped; the next try usually works. Giving up after one try made it look as
 * if a radio had to be tapped several times.
 *
 * A link is used once. After [Events.onClosed] make a new one to reconnect.
 */
@SuppressLint("MissingPermission") // The caller checks Bluetooth permission before connecting.
class GattLink(private val context: Context, private val events: Events) {
    interface Events {
        fun onLog(message: String)

        /** Notifications are on and frames can be sent. [packetSize] is the largest frame. */
        fun onReady(packetSize: Int)

        fun onFrame(frame: ByteArray)

        fun onClosed(reason: String)
    }

    private enum class Step { IDLE, CONNECTING, DISCOVERING, SIZING, SUBSCRIBING, PAIRING, READY, CLOSED }

    private val thread = HandlerThread("ble-link").apply { start() }
    private val handler = Handler(thread.looper)
    private var step = Step.IDLE
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var mtu = DEFAULT_MTU
    private var stepTimeout: Runnable? = null
    private var writeTimeout: Runnable? = null
    private val outbox = ArrayDeque<ByteArray>()
    private var writing = false
    private var receiverRegistered = false
    private var attempts = 0

    fun connect(device: BluetoothDevice) = handler.post {
        if (step != Step.IDLE) return@post
        step = Step.CONNECTING
        registerBondReceiver()
        // A scan that stopped a moment ago can make the first connection fail; let it settle.
        handler.postDelayed({ attempt(device) }, SETTLE_MS)
    }

    private fun attempt(device: BluetoothDevice) {
        if (step != Step.CONNECTING) return
        attempts++
        val which = if (attempts > 1) " (try $attempts of $CONNECT_TRIES)" else ""
        events.onLog("Connecting to ${device.address}$which")
        limit(CONNECT_MS) { connectFailed(device, "no answer within ${CONNECT_MS / 1000} s") }
        gatt = device.connectGatt(
            context,
            false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            handler,
        )
        if (gatt == null) shutdown("Android refused to open the connection")
    }

    /** A connection try failed before the link came up: drop it and try again, up to [CONNECT_TRIES]. */
    private fun connectFailed(device: BluetoothDevice, why: String) {
        clearLimit()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        if (attempts < CONNECT_TRIES) {
            events.onLog("Connection try $attempts failed ($why); trying again")
            handler.postDelayed({ attempt(device) }, RETRY_PAUSE_MS)
        } else {
            val advice = if (why.startsWith("status 133")) {
                ". If this keeps happening, forget the radio in Android's Bluetooth settings and pair again"
            } else {
                ""
            }
            shutdown("Could not connect after $attempts tries ($why)$advice")
        }
    }

    /** Queue one frame for the radio. Dropped, with a log line, when it cannot be sent. */
    fun send(frame: ByteArray) = handler.post {
        if (step != Step.READY) {
            events.onLog("Not sent: the radio link is not ready")
            return@post
        }
        if (frame.size > mtu - ATT_HEADER_BYTES) {
            events.onLog("Not sent: ${frame.size} bytes is more than this link carries (${mtu - ATT_HEADER_BYTES})")
            return@post
        }
        outbox.add(frame)
        pump()
    }

    fun close() = handler.post { shutdown("Disconnected") }

    private fun limit(ms: Long, onTimeout: () -> Unit) {
        clearLimit()
        stepTimeout = Runnable { onTimeout() }.also { handler.postDelayed(it, ms) }
    }

    private fun clearLimit() {
        stepTimeout?.let { handler.removeCallbacks(it) }
        stepTimeout = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // A callback from a try already given up on is ignored. Callbacks run on this link's
            // own thread, so [gatt] is always set before the first one arrives.
            if (step == Step.CLOSED || g !== gatt) return
            val connected = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
            if (step == Step.CONNECTING && !connected) {
                connectFailed(g.device, describeStatus(status))
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                events.onLog("Connected; discovering services")
                step = Step.DISCOVERING
                limit(DISCOVER_MS) { shutdown("Service discovery did not finish") }
                // A short pause lets the connection settle; some phones fail discovery without it.
                handler.postDelayed({
                    if (step == Step.DISCOVERING && !g.discoverServices()) {
                        shutdown("Android refused to start service discovery")
                    }
                }, SETTLE_MS)
            } else {
                shutdown("Connection lost (${describeStatus(status)})")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (step != Step.DISCOVERING) return
            val service = g.getService(SERVICE_UUID)
            toRadio = service?.getCharacteristic(TO_RADIO_UUID)
            fromRadio = service?.getCharacteristic(FROM_RADIO_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || toRadio == null || fromRadio == null) {
                val found = g.services.joinToString { it.uuid.toString().take(8) }
                shutdown("This device does not offer the MeshCore companion service (status $status; found: $found)")
                return
            }
            step = Step.SIZING
            events.onLog("Services found; asking for a larger packet size")
            limit(MTU_MS) {
                events.onLog("No answer to the packet size request; carrying on at $mtu")
                subscribe()
            }
            if (!g.requestMtu(WANTED_MTU)) {
                events.onLog("Packet size request refused; carrying on at $mtu")
                subscribe()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            events.onLog("Packet size is $mtu (status $status)")
            if (step == Step.SIZING) subscribe()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (step != Step.SUBSCRIBING) return
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    clearLimit()
                    step = Step.READY
                    events.onLog("Notifications on; link ready")
                    events.onReady(mtu - ATT_HEADER_BYTES)
                }
                in PAIRING_NEEDED -> awaitPairing(g.device)
                else -> shutdown("Could not turn on notifications (${describeStatus(status)})")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeTimeout?.let { handler.removeCallbacks(it) }
            writeTimeout = null
            writing = false
            if (status != BluetoothGatt.GATT_SUCCESS) events.onLog("A write failed (${describeStatus(status)})")
            pump()
        }

        @Deprecated("Android 12 and earlier deliver notifications here")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (c.uuid == FROM_RADIO_UUID) c.value?.let { deliver(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == FROM_RADIO_UUID) deliver(value)
        }
    }

    private fun deliver(frame: ByteArray) {
        if (step == Step.READY) events.onFrame(frame)
    }

    /** Turn on notifications. Reached from the packet size step and again after pairing. */
    private fun subscribe() {
        val g = gatt ?: return
        val source = fromRadio ?: return
        step = Step.SUBSCRIBING
        limit(SUBSCRIBE_MS) {
            // Android can start pairing by itself part-way through this write and say nothing.
            if (g.device.bondState == BluetoothDevice.BOND_BONDING) {
                awaitPairing(g.device)
            } else {
                shutdown("Turning on notifications did not finish")
            }
        }
        val descriptor = source.getDescriptor(NOTIFY_DESCRIPTOR_UUID)
        if (descriptor == null || !g.setCharacteristicNotification(source, true) || !writeEnable(g, descriptor)) {
            shutdown("Android refused to turn on notifications")
        }
    }

    @Suppress("DEPRECATION")
    private fun writeEnable(g: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, enable) == 0
        } else {
            descriptor.value = enable
            g.writeDescriptor(descriptor)
        }
    }

    private fun awaitPairing(device: BluetoothDevice) {
        if (step == Step.PAIRING) return
        step = Step.PAIRING
        events.onLog("The radio wants pairing; enter its PIN when Android asks")
        limit(PAIRING_MS) { shutdown("Pairing was not completed") }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            handler.postDelayed({ if (step == Step.PAIRING) subscribe() }, SETTLE_MS)
        } else if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond()) {
            shutdown("Android refused to start pairing")
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (step != Step.PAIRING) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            when {
                state == BluetoothDevice.BOND_BONDED -> {
                    events.onLog("Paired")
                    handler.postDelayed({ if (step == Step.PAIRING) subscribe() }, SETTLE_MS)
                }
                state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING ->
                    shutdown("Pairing failed or was cancelled")
            }
        }
    }

    private fun registerBondReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondReceiver, filter, null, handler)
        }
        receiverRegistered = true
    }

    private fun pump() {
        if (writing || step != Step.READY) return
        val g = gatt ?: return
        val target = toRadio ?: return
        val frame = outbox.poll() ?: return
        writing = true
        writeTimeout = Runnable { shutdown("The radio stopped acknowledging writes") }
            .also { handler.postDelayed(it, WRITE_MS) }
        if (!write(g, target, frame)) shutdown("Android refused a write")
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, target: BluetoothGattCharacteristic, frame: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(target, frame, type) == 0
        } else {
            target.writeType = type
            target.value = frame
            g.writeCharacteristic(target)
        }
    }

    private fun shutdown(reason: String) {
        if (step == Step.CLOSED) return
        step = Step.CLOSED
        clearLimit()
        handler.removeCallbacksAndMessages(null)
        outbox.clear()
        if (receiverRegistered) {
            context.unregisterReceiver(bondReceiver)
            receiverRegistered = false
        }
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        events.onLog("Radio link closed: $reason")
        events.onClosed(reason)
        thread.quitSafely()
    }

    private fun describeStatus(status: Int): String = when (status) {
        0 -> "status 0"
        5 -> "status 5, authentication needed"
        8 -> "status 8, the radio went out of range or stopped responding"
        15 -> "status 15, encryption needed"
        19 -> "status 19, the radio closed the connection"
        22 -> "status 22, this device closed the connection"
        133 -> "status 133, a general Bluetooth error"
        else -> "status $status"
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val TO_RADIO_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val FROM_RADIO_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val NOTIFY_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_MTU = 23
        private const val WANTED_MTU = 512
        private const val ATT_HEADER_BYTES = 3

        /** Statuses that mean the radio wants an encrypted, paired link first. */
        private val PAIRING_NEEDED = setOf(5, 8, 15, 137)

        private const val SETTLE_MS = 600L
        /** Per try. Android's own limit for a direct connection is about 30 s. */
        private const val CONNECT_MS = 12_000L
        private const val CONNECT_TRIES = 3
        private const val RETRY_PAUSE_MS = 1_500L
        private const val DISCOVER_MS = 15_000L
        private const val MTU_MS = 4_000L
        private const val SUBSCRIBE_MS = 10_000L
        private const val PAIRING_MS = 60_000L
        private const val WRITE_MS = 5_000L
    }
}
