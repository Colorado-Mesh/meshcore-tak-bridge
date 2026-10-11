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
        else -> null
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
        const val SCAN_MS = 12_000L
    }
}
