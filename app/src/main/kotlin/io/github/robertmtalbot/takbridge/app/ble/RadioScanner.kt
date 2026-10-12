package io.github.robertmtalbot.takbridge.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock

/** A radio seen while scanning. */
data class FoundRadio(val address: String, val name: String, val rssi: Int)

/**
 * Looks for MeshCore companion radios for a few seconds. A device counts when it advertises the
 * companion service or its name starts with "MeshCore"; radios already paired are listed first
 * because a connected or sleeping radio may not be advertising.
 */
@SuppressLint("MissingPermission") // The caller checks Bluetooth permission before scanning.
class RadioScanner(context: Context) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private var active: ScanCallback? = null

    /** Null when a scan can start, otherwise why not, in words for the user. */
    fun blocker(): String? = when {
        adapter == null -> "This device has no Bluetooth"
        !adapter.isEnabled -> "Bluetooth is off"
        adapter.bluetoothLeScanner == null -> "Bluetooth scanning is unavailable"
        else -> throttled()
    }

    /**
     * Android lets an app start only a few scans in 30 seconds. A start past that limit is not
     * refused: the scan simply never reports anything. So the limit is counted here, and the
     * user is told how long to wait instead.
     */
    private fun throttled(): String? {
        val now = SystemClock.elapsedRealtime()
        while (starts.isNotEmpty() && now - starts.first() >= THROTTLE_WINDOW_MS) starts.removeFirst()
        if (starts.size < MAX_STARTS) return null
        val seconds = ((starts.first() + THROTTLE_WINDOW_MS - now) / 1000 + 1).coerceAtLeast(1)
        return "Android allows only a few scans in 30 seconds. Try again in $seconds s"
    }

    /** Both callbacks run on the main thread. [onFound] may repeat a radio with a new signal level. */
    fun start(onFound: (FoundRadio) -> Unit, onDone: (String?) -> Unit) {
        stop()
        val scanner = adapter?.bluetoothLeScanner ?: return onDone(blocker())
        for (device in adapter.bondedDevices.orEmpty()) {
            val name = device.name ?: continue
            if (looksLikeMeshCore(name)) onFound(FoundRadio(device.address, "$name (paired)", 0))
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val offersService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(GattLink.SERVICE_UUID)) == true
                if (!offersService && !looksLikeMeshCore(name)) return
                val found = FoundRadio(result.device.address, name.ifEmpty { "Unnamed radio" }, result.rssi)
                main.post { onFound(found) }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    active = null
                    onDone("Scan failed (error $errorCode)")
                }
            }
        }
        active = callback
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        starts.addLast(SystemClock.elapsedRealtime())
        scanner.startScan(null, settings, callback)
        main.postDelayed({
            if (active === callback) {
                stop()
                onDone(null)
            }
        }, SCAN_MS)
    }

    fun stop() {
        val callback = active ?: return
        active = null
        if (adapter?.isEnabled == true) adapter.bluetoothLeScanner?.stopScan(callback)
    }

    private fun looksLikeMeshCore(name: String) = name.startsWith("MeshCore", ignoreCase = true)

    private companion object {
        /**
         * When recent scans started, by the uptime clock. Android's limit is per app, so this is
         * shared by every scanner the screen creates, for example after a rotation. Main thread only.
         */
        val starts = ArrayDeque<Long>()

        const val SCAN_MS = 12_000L

        /** Android blocks the sixth scan start within 30 seconds; stay one under to be safe. */
        const val MAX_STARTS = 4
        const val THROTTLE_WINDOW_MS = 30_000L
    }
}
